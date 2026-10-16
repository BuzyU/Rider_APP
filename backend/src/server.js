require('dotenv').config()

const express = require('express')
const cors = require('cors')

const healthRoute = require('./routes/healthRoute')
const authMiddleware = require('./middleware/authMiddleware')
const rateLimiter = require('./middleware/rateLimiter')
const errorMiddleware = require('./middleware/errorMiddleware')
const requestLogger = require('./middleware/requestLogger')
const profileRoutes = require('./routes/userRoutes')
const friendRoutes = require('./routes/friendRoutes')
const inviteRoutes = require('./routes/inviteRoutes')
const rideRoutes = require('./routes/rideRoutes')
const roomRoutes = require('./routes/roomRoutes')
const lobbyRoutes = require('./routes/lobbyRoutes')
const emergencyRoutes = require('./routes/emergencyRoutes')
const diagnosticRoutes = require('./routes/diagnosticRoutes')
const testerRoutes = require('./routes/testerRoutes')

const app = express()
app.set('trust proxy', 1)

app.use(cors())
app.use(requestLogger)
app.use(rateLimiter)

app.use('/api/diagnostics', diagnosticRoutes)

app.use(express.json({ limit: '10mb' }))

app.use('/api/health', healthRoute)
app.use('/health', healthRoute)
app.use('/', healthRoute)

app.use('/api/tester-application', testerRoutes)
app.use('/api/testers', testerRoutes)

app.use('/api/rooms', authMiddleware, roomRoutes)
app.use('/api/users', authMiddleware, profileRoutes)
app.use('/api/friends', authMiddleware, friendRoutes)
app.use('/api/rides', authMiddleware, rideRoutes)
app.use('/api/invites', authMiddleware, inviteRoutes)
app.use('/api/lobby', authMiddleware, lobbyRoutes)
app.use('/api/emergency', authMiddleware, emergencyRoutes)

app.use(errorMiddleware)

const prisma = require('./db')
const diagnosticService = require('./services/diagnosticService')

async function ensureDbExtensions() {
    const fallbackStatements = [
        `ALTER TYPE "InviteStatus" ADD VALUE IF NOT EXISTS 'REMOVED'`,
        `ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "diagnosticsEnabled" BOOLEAN NOT NULL DEFAULT false`,
        `ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "startedAt" TIMESTAMP(3)`,
        `ALTER TABLE "RideSession" ADD COLUMN IF NOT EXISTS "roomName" TEXT`,
        `ALTER TABLE "RideSession" ADD COLUMN IF NOT EXISTS "diagnosticsEnabled" BOOLEAN NOT NULL DEFAULT false`,

        `CREATE TABLE IF NOT EXISTS "waitlist" (
            "id" TEXT NOT NULL,
            "email" TEXT NOT NULL,
            "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT "waitlist_pkey" PRIMARY KEY ("id")
        )`,
        `CREATE UNIQUE INDEX IF NOT EXISTS "waitlist_email_key" ON "waitlist"("email")`,
        `CREATE INDEX IF NOT EXISTS "waitlist_email_idx" ON "waitlist"("email")`,

        `CREATE TABLE IF NOT EXISTS "tester_applications" (
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
        )`,
        `CREATE INDEX IF NOT EXISTS "tester_applications_email_idx" ON "tester_applications"("email")`,
        `CREATE INDEX IF NOT EXISTS "tester_applications_status_idx" ON "tester_applications"("status")`,
        `CREATE INDEX IF NOT EXISTS "tester_applications_created_at_idx" ON "tester_applications"("created_at" DESC)`,

        `CREATE TABLE IF NOT EXISTS "RoomJoinToken" (
            "id" TEXT NOT NULL,
            "token" TEXT NOT NULL,
            "roomId" TEXT NOT NULL,
            "expiresAt" TIMESTAMP(3) NOT NULL,
            "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT "RoomJoinToken_pkey" PRIMARY KEY ("id")
        )`,
        `CREATE UNIQUE INDEX IF NOT EXISTS "RoomJoinToken_token_key" ON "RoomJoinToken"("token")`,
        `CREATE INDEX IF NOT EXISTS "RoomJoinToken_roomId_idx" ON "RoomJoinToken"("roomId")`,
        `DO $$
        BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'RoomJoinToken_roomId_fkey') THEN
                ALTER TABLE "RoomJoinToken"
                ADD CONSTRAINT "RoomJoinToken_roomId_fkey"
                FOREIGN KEY ("roomId") REFERENCES "Room"("id") ON DELETE CASCADE ON UPDATE CASCADE;
            END IF;
        END $$;`,

        `CREATE TABLE IF NOT EXISTS "DiagnosticRun" (
            "id" TEXT NOT NULL,
            "convoyId" TEXT NOT NULL,
            "testNumber" INTEGER NOT NULL,
            "status" TEXT NOT NULL DEFAULT 'ACTIVE',
            "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
            "closedAt" TIMESTAMP(3),
            "emailedAt" TIMESTAMP(3),
            "summary" JSONB,
            CONSTRAINT "DiagnosticRun_pkey" PRIMARY KEY ("id")
        )`,
        `CREATE UNIQUE INDEX IF NOT EXISTS "DiagnosticRun_convoyId_testNumber_key" ON "DiagnosticRun"("convoyId", "testNumber")`,
        `CREATE INDEX IF NOT EXISTS "DiagnosticRun_convoyId_status_idx" ON "DiagnosticRun"("convoyId", "status")`,

        `CREATE TABLE IF NOT EXISTS "DiagnosticParticipant" (
            "id" TEXT NOT NULL,
            "runId" TEXT NOT NULL,
            "riderId" TEXT NOT NULL,
            "handle" TEXT NOT NULL,
            "joinedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
            "stoppedAt" TIMESTAMP(3),
            "lastSeq" INTEGER,
            "lastBatchAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT "DiagnosticParticipant_pkey" PRIMARY KEY ("id")
        )`,
        `CREATE UNIQUE INDEX IF NOT EXISTS "DiagnosticParticipant_runId_riderId_key" ON "DiagnosticParticipant"("runId", "riderId")`,
        `CREATE INDEX IF NOT EXISTS "DiagnosticParticipant_runId_idx" ON "DiagnosticParticipant"("runId")`,
        `CREATE INDEX IF NOT EXISTS "DiagnosticParticipant_riderId_idx" ON "DiagnosticParticipant"("riderId")`,

        `CREATE TABLE IF NOT EXISTS "DiagnosticBatch" (
            "id" TEXT NOT NULL,
            "runId" TEXT NOT NULL,
            "riderId" TEXT NOT NULL,
            "sequenceNumber" INTEGER NOT NULL,
            "payload" JSONB NOT NULL,
            "receivedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
            CONSTRAINT "DiagnosticBatch_pkey" PRIMARY KEY ("id")
        )`,
        `CREATE UNIQUE INDEX IF NOT EXISTS "DiagnosticBatch_runId_riderId_sequenceNumber_key" ON "DiagnosticBatch"("runId", "riderId", "sequenceNumber")`,
        `CREATE INDEX IF NOT EXISTS "DiagnosticBatch_runId_riderId_idx" ON "DiagnosticBatch"("runId", "riderId")`,

        `DO $$
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
        END $$;`,

        `CREATE INDEX IF NOT EXISTS "DeviceToken_userId_idx" ON "DeviceToken"("userId")`,
        `CREATE INDEX IF NOT EXISTS "Friendship_requesterId_status_idx" ON "Friendship"("requesterId", "status")`,
        `CREATE INDEX IF NOT EXISTS "Friendship_addresseeId_status_idx" ON "Friendship"("addresseeId", "status")`,
        `CREATE INDEX IF NOT EXISTS "RideInvite_roomId_status_idx" ON "RideInvite"("roomId", "status")`,
        `CREATE INDEX IF NOT EXISTS "RideInvite_inviteeId_status_idx" ON "RideInvite"("inviteeId", "status")`,
        `CREATE INDEX IF NOT EXISTS "RideInvite_inviterId_status_idx" ON "RideInvite"("inviterId", "status")`,
        `CREATE INDEX IF NOT EXISTS "RideInvite_roomId_inviteeId_status_idx" ON "RideInvite"("roomId", "inviteeId", "status")`,
        `CREATE INDEX IF NOT EXISTS "RoomJoinToken_expiresAt_idx" ON "RoomJoinToken"("expiresAt")`,
        `CREATE INDEX IF NOT EXISTS "RideSession_riderId_startTime_idx" ON "RideSession"("riderId", "startTime" DESC)`,
        `CREATE INDEX IF NOT EXISTS "ConvoyEvent_rideId_idx" ON "ConvoyEvent"("rideId")`,
        `CREATE INDEX IF NOT EXISTS "EmergencyAlert_roomName_senderId_idx" ON "EmergencyAlert"("roomName", "senderId")`,
        `CREATE INDEX IF NOT EXISTS "EmergencyAlert_senderId_idx" ON "EmergencyAlert"("senderId")`,
        `CREATE INDEX IF NOT EXISTS "Room_ownerId_idx" ON "Room"("ownerId")`
    ]

    for (const statement of fallbackStatements) {
        try {
            await prisma.$executeRawUnsafe(statement)
        } catch (e) {
            console.warn('[DB] Fallback DDL notice:', e.message)
        }
    }
}

const PORT = process.env.PORT || 3000
app.listen(PORT, () => {
    console.log(`Server running on port ${PORT}`)
    ensureDbExtensions().then(() => {
        console.log('[DB] Database schema verified and initialized.')
        const idleInterval = setInterval(() => {
            diagnosticService.checkIdleDiagnosticRuns().catch(() => {})
        }, 60 * 1000)
        if (idleInterval.unref) idleInterval.unref()

        const retentionInterval = setInterval(() => {
            diagnosticService.cleanOldDiagnosticRuns().catch(() => {})
        }, 24 * 60 * 60 * 1000)
        if (retentionInterval.unref) retentionInterval.unref()
    }).catch((e) => {
        console.warn('[DB] ensureDbExtensions warning:', e.message)
    })
})

module.exports = { app, ensureDbExtensions }