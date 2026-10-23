-- Migration: 20261024_post_phase1_fixups
-- Post Phase 1 fixups:
-- 1. Add Room.lastEmptyAt for tunnel and network loss protection (R1)
-- 2. Add index on Room.lastEmptyAt

ALTER TABLE "Room"
    ADD COLUMN IF NOT EXISTS "lastEmptyAt" TIMESTAMP(3);

CREATE INDEX IF NOT EXISTS "Room_lastEmptyAt_idx" ON "Room"("lastEmptyAt");
