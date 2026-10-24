-- ==============================================================================
-- RIDER VOICE DATABASE UPDATE SCRIPT FOR SUPABASE (IDEMPOTENT / SAFE TO RE-RUN)
-- ==============================================================================
-- Run this entire script in the Supabase SQL Editor:
-- (Supabase Dashboard -> Project -> SQL Editor -> New Query -> Paste & Run)
--
-- This script safely updates your PostgreSQL database to match the current
-- Prisma schema across all Phases (Room lifecycle, invites, tokens, diagnostics):
-- 1. Adds 'REMOVED' and 'LEFT' values to InviteStatus enum
-- 2. Creates user_role, RoomStatus, InviteSource enums
-- 3. Adds lifecycle columns to Room (status, endedAt, joinCode, metadata, lastEmptyAt)
-- 4. Replaces unconditional Room(name) unique index with conditional non-ENDED index
-- 5. Adds source, updatedAt, and unique constraint on RideInvite
-- 6. Adds RoomJoinToken Phase 1B fields (createdById, revokedAt, maxUses, useCount)
-- 7. Adds RideSession clientRideId and diagnosticsEnabled
-- 8. Creates / updates DiagnosticRun, DiagnosticParticipant, DiagnosticBatch tables
-- 9. Creates waitlist and tester_applications tables
-- 10. Creates all performance and relation indexes
-- ==============================================================================

-- 1. Enums
ALTER TYPE "InviteStatus" ADD VALUE IF NOT EXISTS 'REMOVED';
ALTER TYPE "InviteStatus" ADD VALUE IF NOT EXISTS 'LEFT';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'user_role') THEN
        CREATE TYPE "user_role" AS ENUM ('CUSTOMER', 'ADMIN');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'RoomStatus') THEN
        CREATE TYPE "RoomStatus" AS ENUM ('LOBBY', 'ACTIVE', 'ENDED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'InviteSource') THEN
        CREATE TYPE "InviteSource" AS ENUM ('HOST_INVITE', 'SHARE_LINK', 'JOIN_CODE');
    END IF;
END $$;

-- 2. User table
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "role" "user_role" NOT NULL DEFAULT 'CUSTOMER';

-- 3. Room table columns
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "diagnosticsEnabled" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "startedAt" TIMESTAMP(3);
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "status" "RoomStatus" NOT NULL DEFAULT 'LOBBY';
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "endedAt" TIMESTAMP(3);
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "joinCode" TEXT;
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "origin" TEXT;
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "destination" TEXT;
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "meetupPoint" TEXT;
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "estimatedDurationMin" INTEGER;
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "lastEmptyAt" TIMESTAMP(3);

-- 4. Backfill Room.status from existing data & deduplicate active room names
UPDATE "Room"
SET "status" = 'ENDED', "endedAt" = COALESCE("endedAt", NOW())
WHERE ("startedAt" IS NOT NULL AND "startedAt" < NOW() - INTERVAL '12 hours')
   OR ("createdAt" < NOW() - INTERVAL '12 hours' AND "status" != 'ENDED');

UPDATE "Room"
SET "status" = 'ACTIVE'
WHERE "startedAt" IS NOT NULL
  AND "startedAt" >= NOW() - INTERVAL '12 hours'
  AND "status" = 'LOBBY';

-- Deduplicate any remaining active rooms with the same lower(name).
-- Keeps the newest room active and marks all older duplicates as ENDED.
WITH duplicate_rooms AS (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY lower("name")
        ORDER BY "createdAt" DESC
    ) AS rn
    FROM "Room"
    WHERE "status" != 'ENDED'
)
UPDATE "Room"
SET "status" = 'ENDED',
    "endedAt" = COALESCE("endedAt", NOW())
WHERE id IN (
    SELECT id FROM duplicate_rooms WHERE rn > 1
);

-- 5. Room unique indexes
DROP INDEX IF EXISTS "Room_name_key";

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'Room_name_key') THEN
        ALTER TABLE "Room" DROP CONSTRAINT "Room_name_key";
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS "Room_name_active_unique"
    ON "Room"(lower("name"))
    WHERE "status" != 'ENDED';

DROP INDEX IF EXISTS "Room_joinCode_key";

CREATE UNIQUE INDEX IF NOT EXISTS "Room_joinCode_active_unique"
    ON "Room"("joinCode")
    WHERE "status" != 'ENDED' AND "joinCode" IS NOT NULL;

CREATE INDEX IF NOT EXISTS "Room_joinCode_idx" ON "Room"("joinCode");
CREATE INDEX IF NOT EXISTS "Room_status_idx" ON "Room"("status");
CREATE INDEX IF NOT EXISTS "Room_name_status_idx" ON "Room"("name", "status");
CREATE INDEX IF NOT EXISTS "Room_lastEmptyAt_idx" ON "Room"("lastEmptyAt");
CREATE INDEX IF NOT EXISTS "Room_ownerId_idx" ON "Room"("ownerId");

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'Room_ownerId_fkey') THEN
        ALTER TABLE "Room" ADD CONSTRAINT "Room_ownerId_fkey"
            FOREIGN KEY ("ownerId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
    END IF;
END $$;

-- 6. RideInvite columns & constraint
ALTER TABLE "RideInvite" ADD COLUMN IF NOT EXISTS "source" "InviteSource" NOT NULL DEFAULT 'HOST_INVITE';
ALTER TABLE "RideInvite" ADD COLUMN IF NOT EXISTS "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT NOW();

WITH ranked AS (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY "roomId", "inviteeId"
        ORDER BY
            CASE status::text
                WHEN 'REMOVED'  THEN 0
                WHEN 'ACCEPTED' THEN 1
                WHEN 'LEFT'     THEN 2
                WHEN 'PENDING'  THEN 3
                WHEN 'DECLINED' THEN 4
                ELSE 5
            END,
            "createdAt" DESC
    ) AS rn
    FROM "RideInvite"
)
DELETE FROM "RideInvite" WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'RideInvite_roomId_inviteeId_key') THEN
        ALTER TABLE "RideInvite" ADD CONSTRAINT "RideInvite_roomId_inviteeId_key" UNIQUE ("roomId", "inviteeId");
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS "RideInvite_roomId_status_idx" ON "RideInvite"("roomId", "status");
CREATE INDEX IF NOT EXISTS "RideInvite_inviteeId_status_idx" ON "RideInvite"("inviteeId", "status");
CREATE INDEX IF NOT EXISTS "RideInvite_inviterId_status_idx" ON "RideInvite"("inviterId", "status");

-- 7. RoomJoinToken table & columns
CREATE TABLE IF NOT EXISTS "RoomJoinToken" (
    "id" TEXT NOT NULL,
    "roomId" TEXT NOT NULL,
    "token" TEXT NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "RoomJoinToken_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "RoomJoinToken_token_key" ON "RoomJoinToken"("token");
CREATE INDEX IF NOT EXISTS "RoomJoinToken_roomId_idx" ON "RoomJoinToken"("roomId");
CREATE INDEX IF NOT EXISTS "RoomJoinToken_expiresAt_idx" ON "RoomJoinToken"("expiresAt");

ALTER TABLE "RoomJoinToken" ADD COLUMN IF NOT EXISTS "createdById" TEXT;
ALTER TABLE "RoomJoinToken" ADD COLUMN IF NOT EXISTS "revokedAt" TIMESTAMP(3);
ALTER TABLE "RoomJoinToken" ADD COLUMN IF NOT EXISTS "maxUses" INTEGER;
ALTER TABLE "RoomJoinToken" ADD COLUMN IF NOT EXISTS "useCount" INTEGER NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'RoomJoinToken_roomId_fkey') THEN
        ALTER TABLE "RoomJoinToken"
            ADD CONSTRAINT "RoomJoinToken_roomId_fkey"
            FOREIGN KEY ("roomId") REFERENCES "Room"("id") ON DELETE CASCADE ON UPDATE CASCADE;
    END IF;
END $$;

-- 8. RideSession columns
ALTER TABLE "RideSession" ADD COLUMN IF NOT EXISTS "roomName" TEXT;
ALTER TABLE "RideSession" ADD COLUMN IF NOT EXISTS "clientRideId" TEXT;
ALTER TABLE "RideSession" ADD COLUMN IF NOT EXISTS "diagnosticsEnabled" BOOLEAN NOT NULL DEFAULT false;

CREATE UNIQUE INDEX IF NOT EXISTS "RideSession_riderId_clientRideId_key" ON "RideSession"("riderId", "clientRideId");
CREATE INDEX IF NOT EXISTS "RideSession_riderId_startTime_idx" ON "RideSession"("riderId", "startTime" DESC);
CREATE INDEX IF NOT EXISTS "ConvoyEvent_rideId_idx" ON "ConvoyEvent"("rideId");

-- 9. EmergencyAlert table (SOS distress alerts)
CREATE TABLE IF NOT EXISTS "EmergencyAlert" (
    "id" TEXT NOT NULL,
    "senderId" TEXT NOT NULL,
    "roomId" TEXT NOT NULL,
    "roomName" TEXT NOT NULL,
    "lat" DOUBLE PRECISION,
    "lng" DOUBLE PRECISION,
    "recipients" TEXT[],
    "status" TEXT NOT NULL DEFAULT 'SENT',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "EmergencyAlert_pkey" PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "EmergencyAlert_roomName_senderId_idx" ON "EmergencyAlert"("roomName", "senderId");
CREATE INDEX IF NOT EXISTS "EmergencyAlert_senderId_idx" ON "EmergencyAlert"("senderId");

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'EmergencyAlert_senderId_fkey') THEN
        ALTER TABLE "EmergencyAlert"
            ADD CONSTRAINT "EmergencyAlert_senderId_fkey"
            FOREIGN KEY ("senderId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'EmergencyAlert_roomId_fkey') THEN
        ALTER TABLE "EmergencyAlert"
            ADD CONSTRAINT "EmergencyAlert_roomId_fkey"
            FOREIGN KEY ("roomId") REFERENCES "Room"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
    END IF;
END $$;

-- 10. Diagnostic tables & closeAt
CREATE TABLE IF NOT EXISTS "DiagnosticRun" (
    "id" TEXT NOT NULL,
    "convoyId" TEXT NOT NULL,
    "testNumber" INTEGER NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'ACTIVE',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "closedAt" TIMESTAMP(3),
    "emailedAt" TIMESTAMP(3),
    "closeAt" TIMESTAMP(3),
    "summary" JSONB,
    CONSTRAINT "DiagnosticRun_pkey" PRIMARY KEY ("id")
);

ALTER TABLE "DiagnosticRun" ADD COLUMN IF NOT EXISTS "closeAt" TIMESTAMP(3);
CREATE UNIQUE INDEX IF NOT EXISTS "DiagnosticRun_convoyId_testNumber_key" ON "DiagnosticRun"("convoyId", "testNumber");
CREATE INDEX IF NOT EXISTS "DiagnosticRun_convoyId_status_idx" ON "DiagnosticRun"("convoyId", "status");

CREATE TABLE IF NOT EXISTS "DiagnosticParticipant" (
    "id" TEXT NOT NULL,
    "runId" TEXT NOT NULL,
    "riderId" TEXT NOT NULL,
    "handle" TEXT NOT NULL,
    "joinedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "stoppedAt" TIMESTAMP(3),
    "lastSeq" INTEGER,
    "lastBatchAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "DiagnosticParticipant_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "DiagnosticParticipant_runId_riderId_key" ON "DiagnosticParticipant"("runId", "riderId");
CREATE INDEX IF NOT EXISTS "DiagnosticParticipant_runId_idx" ON "DiagnosticParticipant"("runId");
CREATE INDEX IF NOT EXISTS "DiagnosticParticipant_riderId_idx" ON "DiagnosticParticipant"("riderId");

CREATE TABLE IF NOT EXISTS "DiagnosticBatch" (
    "id" TEXT NOT NULL,
    "runId" TEXT NOT NULL,
    "riderId" TEXT NOT NULL,
    "sequenceNumber" INTEGER NOT NULL,
    "payload" JSONB NOT NULL,
    "receivedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "DiagnosticBatch_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "DiagnosticBatch_runId_riderId_sequenceNumber_key" ON "DiagnosticBatch"("runId", "riderId", "sequenceNumber");
CREATE INDEX IF NOT EXISTS "DiagnosticBatch_runId_riderId_idx" ON "DiagnosticBatch"("runId", "riderId");

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'DiagnosticRun_convoyId_fkey') THEN
        ALTER TABLE "DiagnosticRun"
            ADD CONSTRAINT "DiagnosticRun_convoyId_fkey"
            FOREIGN KEY ("convoyId") REFERENCES "Room"("id") ON DELETE CASCADE ON UPDATE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'DiagnosticParticipant_runId_fkey') THEN
        ALTER TABLE "DiagnosticParticipant"
            ADD CONSTRAINT "DiagnosticParticipant_runId_fkey"
            FOREIGN KEY ("runId") REFERENCES "DiagnosticRun"("id") ON DELETE CASCADE ON UPDATE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'DiagnosticBatch_runId_fkey') THEN
        ALTER TABLE "DiagnosticBatch"
            ADD CONSTRAINT "DiagnosticBatch_runId_fkey"
            FOREIGN KEY ("runId") REFERENCES "DiagnosticRun"("id") ON DELETE CASCADE ON UPDATE CASCADE;
    END IF;
END $$;

-- 11. Support / auxiliary tables
CREATE TABLE IF NOT EXISTS "waitlist" (
    "id" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "waitlist_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX IF NOT EXISTS "waitlist_email_key" ON "waitlist"("email");
CREATE INDEX IF NOT EXISTS "waitlist_email_idx" ON "waitlist"("email");

CREATE TABLE IF NOT EXISTS "tester_applications" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "phone" TEXT,
    "device_model" TEXT,
    "motorcycle_model" TEXT,
    "experience_notes" TEXT,
    "apk_version" TEXT DEFAULT 'v0.0.3.8',
    "status" TEXT NOT NULL DEFAULT 'PENDING',
    "download_count" INTEGER DEFAULT 1,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "tester_applications_pkey" PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "tester_applications_email_idx" ON "tester_applications"("email");
CREATE INDEX IF NOT EXISTS "tester_applications_status_idx" ON "tester_applications"("status");
CREATE INDEX IF NOT EXISTS "tester_applications_created_at_idx" ON "tester_applications"("created_at" DESC);

CREATE TABLE IF NOT EXISTS "admin_settings" (
    "id" TEXT NOT NULL,
    "settings" JSONB NOT NULL,
    "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "admin_settings_pkey" PRIMARY KEY ("id")
);

-- 12. Miscellaneous indexes
CREATE INDEX IF NOT EXISTS "DeviceToken_userId_idx" ON "DeviceToken"("userId");
CREATE INDEX IF NOT EXISTS "Friendship_requesterId_status_idx" ON "Friendship"("requesterId", "status");
CREATE INDEX IF NOT EXISTS "Friendship_addresseeId_status_idx" ON "Friendship"("addresseeId", "status");
