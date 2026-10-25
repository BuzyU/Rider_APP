-- AlterTable
ALTER TABLE "Room" ADD COLUMN "lkRoomName" TEXT;

-- CreateIndex
CREATE INDEX "Room_lkRoomName_idx" ON "Room"("lkRoomName");
