-- CreateTable
CREATE TABLE IF NOT EXISTS "RideCreateRequest" (
    "id" TEXT NOT NULL,
    "ownerId" TEXT NOT NULL,
    "clientRequestId" TEXT NOT NULL,
    "payloadHash" TEXT NOT NULL,
    "roomId" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "RideCreateRequest_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX IF NOT EXISTS "RideCreateRequest_ownerId_clientRequestId_key" ON "RideCreateRequest"("ownerId", "clientRequestId");

-- CreateIndex
CREATE INDEX IF NOT EXISTS "RideCreateRequest_ownerId_idx" ON "RideCreateRequest"("ownerId");

-- CreateIndex
CREATE INDEX IF NOT EXISTS "RideCreateRequest_roomId_idx" ON "RideCreateRequest"("roomId");

-- CreateIndex
CREATE INDEX IF NOT EXISTS "RideCreateRequest_createdAt_idx" ON "RideCreateRequest"("createdAt");

-- AddForeignKey
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'RideCreateRequest_ownerId_fkey'
    ) THEN
        ALTER TABLE "RideCreateRequest" ADD CONSTRAINT "RideCreateRequest_ownerId_fkey" FOREIGN KEY ("ownerId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
    END IF;
END $$;

-- AddForeignKey
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'RideCreateRequest_roomId_fkey'
    ) THEN
        ALTER TABLE "RideCreateRequest" ADD CONSTRAINT "RideCreateRequest_roomId_fkey" FOREIGN KEY ("roomId") REFERENCES "Room"("id") ON DELETE CASCADE ON UPDATE CASCADE;
    END IF;
END $$;
