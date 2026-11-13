-- AlterTable
ALTER TABLE "Room" ADD COLUMN "offlineMeshEnabled" BOOLEAN NOT NULL DEFAULT true;

-- AlterTable
ALTER TABLE "RideInvite" ADD COLUMN "offlineConfigStatus" TEXT;
ALTER TABLE "RideInvite" ADD COLUMN "offlineConfigUpdatedAt" TIMESTAMP(3);

