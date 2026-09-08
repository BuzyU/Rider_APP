-- Step 1: Add REMOVED to InviteStatus enum (PostgreSQL non-transactional enum update)
ALTER TYPE "InviteStatus" ADD VALUE IF NOT EXISTS 'REMOVED';

-- Step 2: Create RoomJoinToken table
CREATE TABLE IF NOT EXISTS "RoomJoinToken" (
    "id" TEXT NOT NULL,
    "roomId" TEXT NOT NULL,
    "token" TEXT NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "RoomJoinToken_pkey" PRIMARY KEY ("id")
);

-- Indices and Constraints
CREATE UNIQUE INDEX IF NOT EXISTS "RoomJoinToken_token_key" ON "RoomJoinToken"("token");

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
