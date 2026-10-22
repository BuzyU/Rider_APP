-- Phase 1 & 1B Migration: Room Lifecycle + Membership State Machine
-- Applied by: npx prisma migrate deploy (in render.yaml startCommand)
-- 
-- IMPORTANT: This migration is ADDITIVE ONLY. No drops or renames.
-- It can be applied to a database with existing rooms/invites safely.
-- Partial unique index on Room(name) WHERE status != 'ENDED' replaces
-- the old unconditional unique constraint on name.

-- ============================================================
-- 1. Add RoomStatus enum and lifecycle columns to Room
-- ============================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'RoomStatus') THEN
        CREATE TYPE "RoomStatus" AS ENUM ('LOBBY', 'ACTIVE', 'ENDED');
    END IF;
END $$;

-- Add status column with default LOBBY
ALTER TABLE "Room"
    ADD COLUMN IF NOT EXISTS "status" "RoomStatus" NOT NULL DEFAULT 'LOBBY';

-- Add endedAt for ended rooms
ALTER TABLE "Room"
    ADD COLUMN IF NOT EXISTS "endedAt" TIMESTAMP(3);

-- Add joinCode (8-char unambiguous code)
ALTER TABLE "Room"
    ADD COLUMN IF NOT EXISTS "joinCode" TEXT;

-- Add ride metadata fields (Phase 1.5, H2)
ALTER TABLE "Room"
    ADD COLUMN IF NOT EXISTS "origin" TEXT;
ALTER TABLE "Room"
    ADD COLUMN IF NOT EXISTS "destination" TEXT;
ALTER TABLE "Room"
    ADD COLUMN IF NOT EXISTS "meetupPoint" TEXT;
ALTER TABLE "Room"
    ADD COLUMN IF NOT EXISTS "estimatedDurationMin" INTEGER;

-- ============================================================
-- 2. Backfill Room.status from existing data
-- ============================================================
-- Rooms with startedAt older than 12h -> ENDED
UPDATE "Room"
SET "status" = 'ENDED',
    "endedAt" = NOW()
WHERE "startedAt" IS NOT NULL
  AND "startedAt" < NOW() - INTERVAL '12 hours'
  AND "status" = 'LOBBY';

-- Rooms with startedAt in the last 12h -> ACTIVE
UPDATE "Room"
SET "status" = 'ACTIVE'
WHERE "startedAt" IS NOT NULL
  AND "startedAt" >= NOW() - INTERVAL '12 hours'
  AND "status" = 'LOBBY';

-- ============================================================
-- 3. Replace unconditional unique index on Room(name) with
--    conditional unique index WHERE status != 'ENDED'.
--    The old constraint may be a unique index or a unique
--    constraint depending on how Prisma created it.
-- ============================================================

-- Drop the old Prisma-generated unique index if it exists
DROP INDEX IF EXISTS "Room_name_key";

-- Some versions create it as a constraint
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'Room_name_key'
    ) THEN
        ALTER TABLE "Room" DROP CONSTRAINT "Room_name_key";
    END IF;
END $$;

-- Create partial unique index: name unique only among non-ENDED rooms
CREATE UNIQUE INDEX IF NOT EXISTS "Room_name_active_unique"
    ON "Room"(lower("name"))
    WHERE "status" != 'ENDED';

-- Also index joinCode (unique among non-null rows, normal unique is fine)
CREATE UNIQUE INDEX IF NOT EXISTS "Room_joinCode_key"
    ON "Room"("joinCode");

-- Additional useful indexes
CREATE INDEX IF NOT EXISTS "Room_status_idx" ON "Room"("status");
CREATE INDEX IF NOT EXISTS "Room_name_status_idx" ON "Room"("name", "status");

-- ============================================================
-- 4. InviteStatus: add LEFT value
-- ============================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_enum
        WHERE enumlabel = 'LEFT'
          AND enumtypid = (SELECT oid FROM pg_type WHERE typname = 'InviteStatus')
    ) THEN
        ALTER TYPE "InviteStatus" ADD VALUE 'LEFT';
    END IF;
END $$;

-- ============================================================
-- 5. InviteSource enum and column on RideInvite
-- ============================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'InviteSource') THEN
        CREATE TYPE "InviteSource" AS ENUM ('HOST_INVITE', 'SHARE_LINK', 'JOIN_CODE');
    END IF;
END $$;

ALTER TABLE "RideInvite"
    ADD COLUMN IF NOT EXISTS "source" "InviteSource" NOT NULL DEFAULT 'HOST_INVITE';

ALTER TABLE "RideInvite"
    ADD COLUMN IF NOT EXISTS "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT NOW();
ALTER TABLE "RideInvite" ALTER COLUMN "updatedAt" DROP DEFAULT;

-- ============================================================
-- 6. Deduplicate RideInvite rows and add unique constraint
--    Phase 1B / G3
-- ============================================================

-- De-duplicate: keep the most advanced state per (roomId, inviteeId).
-- State advancement order: REMOVED > ACCEPTED > LEFT > PENDING > DECLINED
-- (higher number = more advanced)
WITH ranked AS (
    SELECT
        id,
        "roomId",
        "inviteeId",
        ROW_NUMBER() OVER (
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
DELETE FROM "RideInvite"
WHERE id IN (
    SELECT id FROM ranked WHERE rn > 1
);

-- Now safe to add the unique constraint
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'RideInvite_roomId_inviteeId_key'
    ) THEN
        ALTER TABLE "RideInvite"
            ADD CONSTRAINT "RideInvite_roomId_inviteeId_key"
            UNIQUE ("roomId", "inviteeId");
    END IF;
END $$;

-- ============================================================
-- 7. RoomJoinToken: add Phase 1B fields
-- ============================================================

ALTER TABLE "RoomJoinToken"
    ADD COLUMN IF NOT EXISTS "createdById"  TEXT;
ALTER TABLE "RoomJoinToken"
    ADD COLUMN IF NOT EXISTS "revokedAt"    TIMESTAMP(3);
ALTER TABLE "RoomJoinToken"
    ADD COLUMN IF NOT EXISTS "maxUses"      INTEGER;
ALTER TABLE "RoomJoinToken"
    ADD COLUMN IF NOT EXISTS "useCount"     INTEGER NOT NULL DEFAULT 0;

-- ============================================================
-- 8. RideSession: clientRideId for idempotent sync (H10/Phase 8.5)
-- ============================================================

ALTER TABLE "RideSession"
    ADD COLUMN IF NOT EXISTS "clientRideId" TEXT;

ALTER TABLE "RideSession"
    ADD COLUMN IF NOT EXISTS "diagnosticsEnabled" BOOLEAN NOT NULL DEFAULT false;

-- Add unique constraint (riderId, clientRideId) -> partial, only when not null
CREATE UNIQUE INDEX IF NOT EXISTS "RideSession_riderId_clientRideId_key"
    ON "RideSession"("riderId", "clientRideId");

-- ============================================================
-- 9. DiagnosticRun: closeAt for persisted grace close (B6/Phase 6.9)
-- ============================================================

ALTER TABLE "DiagnosticRun"
    ADD COLUMN IF NOT EXISTS "closeAt" TIMESTAMP(3);

-- ============================================================
-- 10. Move ensureDbExtensions raw DDL here so server.js doesn't
--     need to run DDL at startup. These are idempotent.
-- ============================================================

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

-- Diagnostic tables (already exist in prod via ensureDbExtensions, but now declared here too)
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
CREATE UNIQUE INDEX IF NOT EXISTS "DiagnosticRun_convoyId_testNumber_key" ON "DiagnosticRun"("convoyId", "testNumber");
CREATE INDEX IF NOT EXISTS "DiagnosticRun_convoyId_status_idx" ON "DiagnosticRun"("convoyId", "status");

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'DiagnosticRun_convoyId_fkey') THEN
        ALTER TABLE "DiagnosticRun"
            ADD CONSTRAINT "DiagnosticRun_convoyId_fkey"
            FOREIGN KEY ("convoyId") REFERENCES "Room"("id") ON DELETE CASCADE ON UPDATE CASCADE;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS "DeviceToken_userId_idx" ON "DeviceToken"("userId");
CREATE INDEX IF NOT EXISTS "Friendship_requesterId_status_idx" ON "Friendship"("requesterId", "status");
CREATE INDEX IF NOT EXISTS "Friendship_addresseeId_status_idx" ON "Friendship"("addresseeId", "status");
CREATE INDEX IF NOT EXISTS "RideInvite_roomId_status_idx" ON "RideInvite"("roomId", "status");
CREATE INDEX IF NOT EXISTS "RideInvite_inviteeId_status_idx" ON "RideInvite"("inviteeId", "status");
CREATE INDEX IF NOT EXISTS "RideInvite_inviterId_status_idx" ON "RideInvite"("inviterId", "status");
CREATE INDEX IF NOT EXISTS "RoomJoinToken_expiresAt_idx" ON "RoomJoinToken"("expiresAt");
CREATE INDEX IF NOT EXISTS "RideSession_riderId_startTime_idx" ON "RideSession"("riderId", "startTime" DESC);
CREATE INDEX IF NOT EXISTS "EmergencyAlert_roomName_senderId_idx" ON "EmergencyAlert"("roomName", "senderId");
CREATE INDEX IF NOT EXISTS "EmergencyAlert_senderId_idx" ON "EmergencyAlert"("senderId");
CREATE INDEX IF NOT EXISTS "Room_ownerId_idx" ON "Room"("ownerId");

-- Room.ownerId foreign key (matches schema.prisma relation RoomOwner)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'Room_ownerId_fkey') THEN
        ALTER TABLE "Room" ADD CONSTRAINT "Room_ownerId_fkey"
            FOREIGN KEY ("ownerId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
    END IF;
END $$;