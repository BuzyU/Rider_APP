-- ==============================================================================
-- RIDER VOICE DATABASE UPDATE SCRIPT FOR SUPABASE (IDEMPOTENT / SAFE TO RE-RUN)
-- ==============================================================================
-- Run this entire script in the Supabase SQL Editor (Dashboard -> SQL Editor -> New Query -> Run)
-- It updates your Supabase PostgreSQL database to match the current Prisma schema:
-- 1. Updates InviteStatus enum to include 'REMOVED'
-- 2. Creates user_role enum ('CUSTOMER', 'ADMIN') and adds 'role' to "User"
-- 3. Adds 'roomName' to "RideSession"
-- 4. Creates "RoomJoinToken" table with CASCADE deletion and unique index
-- 5. Creates "EmergencyAlert" table with foreign keys and composite indexes
-- 6. Creates "admin_settings" table for dynamic platform configuration
-- 7. Creates all missing lookup and performance indexes
-- ==============================================================================

-- 1. Update InviteStatus enum
ALTER TYPE "InviteStatus" ADD VALUE IF NOT EXISTS 'REMOVED';

-- 2. Create user_role enum and add 'role' to "User"
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'user_role') THEN
        CREATE TYPE "user_role" AS ENUM ('CUSTOMER', 'ADMIN');
    END IF;
END $$;

ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "role" "user_role" NOT NULL DEFAULT 'CUSTOMER';

-- 3. Add 'roomName' column to "RideSession"
ALTER TABLE "RideSession" ADD COLUMN IF NOT EXISTS "roomName" TEXT;

-- 4. Create "RoomJoinToken" table (QR code / link-based invites)
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

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'RoomJoinToken_roomId_fkey'
    ) THEN
        ALTER TABLE "RoomJoinToken" 
        ADD CONSTRAINT "RoomJoinToken_roomId_fkey" 
        FOREIGN KEY ("roomId") REFERENCES "Room"("id") ON DELETE CASCADE ON UPDATE CASCADE;
    END IF;
END $$;

-- 5. Create "EmergencyAlert" table (SOS distress alerts & dispatch)
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
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'EmergencyAlert_senderId_fkey'
    ) THEN
        ALTER TABLE "EmergencyAlert" 
        ADD CONSTRAINT "EmergencyAlert_senderId_fkey" 
        FOREIGN KEY ("senderId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'EmergencyAlert_roomId_fkey'
    ) THEN
        ALTER TABLE "EmergencyAlert" 
        ADD CONSTRAINT "EmergencyAlert_roomId_fkey" 
        FOREIGN KEY ("roomId") REFERENCES "Room"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
    END IF;
END $$;

-- 6. Create "admin_settings" table
CREATE TABLE IF NOT EXISTS "admin_settings" (
    "id" TEXT NOT NULL,
    "settings" JSONB NOT NULL,
    "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "admin_settings_pkey" PRIMARY KEY ("id")
);

-- 7. Create Performance and Relation Indexes
CREATE INDEX IF NOT EXISTS "Friendship_requesterId_status_idx" ON "Friendship"("requesterId", "status");
CREATE INDEX IF NOT EXISTS "Friendship_addresseeId_status_idx" ON "Friendship"("addresseeId", "status");
CREATE INDEX IF NOT EXISTS "Room_ownerId_idx" ON "Room"("ownerId");
CREATE INDEX IF NOT EXISTS "RideInvite_roomId_status_idx" ON "RideInvite"("roomId", "status");
CREATE INDEX IF NOT EXISTS "RideInvite_inviteeId_status_idx" ON "RideInvite"("inviteeId", "status");
CREATE INDEX IF NOT EXISTS "RideInvite_inviterId_status_idx" ON "RideInvite"("inviterId", "status");
CREATE INDEX IF NOT EXISTS "RideSession_riderId_startTime_idx" ON "RideSession"("riderId", "startTime" DESC);
CREATE INDEX IF NOT EXISTS "ConvoyEvent_rideId_idx" ON "ConvoyEvent"("rideId");
CREATE INDEX IF NOT EXISTS "DeviceToken_userId_idx" ON "DeviceToken"("userId");
