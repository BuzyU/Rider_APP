-- CreateTable
CREATE TABLE IF NOT EXISTS "DiagnosticRun" (
    "id" TEXT NOT NULL,
    "convoyId" TEXT NOT NULL,
    "testNumber" INTEGER NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'ACTIVE',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "closedAt" TIMESTAMP(3),
    "emailedAt" TIMESTAMP(3),
    "summary" JSONB,

    CONSTRAINT "DiagnosticRun_pkey" PRIMARY KEY ("id")
);

-- CreateTable
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

-- CreateTable
CREATE TABLE IF NOT EXISTS "DiagnosticBatch" (
    "id" TEXT NOT NULL,
    "runId" TEXT NOT NULL,
    "riderId" TEXT NOT NULL,
    "sequenceNumber" INTEGER NOT NULL,
    "payload" JSONB NOT NULL,
    "receivedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "DiagnosticBatch_pkey" PRIMARY KEY ("id")
);

-- Indices
CREATE UNIQUE INDEX IF NOT EXISTS "DiagnosticRun_convoyId_testNumber_key" ON "DiagnosticRun"("convoyId", "testNumber");
CREATE INDEX IF NOT EXISTS "DiagnosticRun_convoyId_status_idx" ON "DiagnosticRun"("convoyId", "status");

CREATE UNIQUE INDEX IF NOT EXISTS "DiagnosticParticipant_runId_riderId_key" ON "DiagnosticParticipant"("runId", "riderId");
CREATE INDEX IF NOT EXISTS "DiagnosticParticipant_runId_idx" ON "DiagnosticParticipant"("runId");
CREATE INDEX IF NOT EXISTS "DiagnosticParticipant_riderId_idx" ON "DiagnosticParticipant"("riderId");

CREATE UNIQUE INDEX IF NOT EXISTS "DiagnosticBatch_runId_riderId_sequenceNumber_key" ON "DiagnosticBatch"("runId", "riderId", "sequenceNumber");
CREATE INDEX IF NOT EXISTS "DiagnosticBatch_runId_riderId_idx" ON "DiagnosticBatch"("runId", "riderId");

-- Foreign Keys
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
