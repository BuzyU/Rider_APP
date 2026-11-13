-- AlterTable
ALTER TABLE "EmergencyAlert" ADD COLUMN "clientAlertId" TEXT;

-- CreateIndex
CREATE UNIQUE INDEX "EmergencyAlert_senderId_clientAlertId_key" ON "EmergencyAlert"("senderId", "clientAlertId");

