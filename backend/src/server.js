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

const app = express()

app.use(cors())
app.use(express.json({ limit: '10mb' }))
app.use(requestLogger)
app.use(rateLimiter)

app.use('/api/health', healthRoute)
app.use('/health', healthRoute)
app.use('/', healthRoute)

app.use('/api/rooms', authMiddleware, roomRoutes)
app.use('/api/users', authMiddleware, profileRoutes)
app.use('/api/friends', authMiddleware, friendRoutes)
app.use('/api/rides', authMiddleware, rideRoutes)
app.use('/api/invites', authMiddleware, inviteRoutes)
app.use('/api/lobby', authMiddleware, lobbyRoutes)
app.use('/api/emergency', authMiddleware, emergencyRoutes)

app.use(errorMiddleware)

const prisma = require('./db')
async function ensureDbExtensions() {
    try {
        await prisma.$executeRawUnsafe(`ALTER TYPE "InviteStatus" ADD VALUE IF NOT EXISTS 'REMOVED';`)
    } catch (e) {
    }
    try {
        await prisma.$executeRawUnsafe(`
            CREATE TABLE IF NOT EXISTS "RoomJoinToken" (
                "id" TEXT NOT NULL,
                "token" TEXT NOT NULL,
                "roomId" TEXT NOT NULL,
                "expiresAt" TIMESTAMP(3) NOT NULL,
                "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT "RoomJoinToken_pkey" PRIMARY KEY ("id"),
                CONSTRAINT "RoomJoinToken_roomId_fkey" FOREIGN KEY ("roomId") REFERENCES "Room"("id") ON DELETE CASCADE ON UPDATE CASCADE
            );
            CREATE UNIQUE INDEX IF NOT EXISTS "RoomJoinToken_token_key" ON "RoomJoinToken"("token");
            CREATE INDEX IF NOT EXISTS "RoomJoinToken_token_idx" ON "RoomJoinToken"("token");
            CREATE INDEX IF NOT EXISTS "DeviceToken_userId_idx" ON "DeviceToken"("userId");
            CREATE INDEX IF NOT EXISTS "Friendship_requesterId_status_idx" ON "Friendship"("requesterId", "status");
            CREATE INDEX IF NOT EXISTS "Friendship_addresseeId_status_idx" ON "Friendship"("addresseeId", "status");
            CREATE INDEX IF NOT EXISTS "RideInvite_roomId_status_idx" ON "RideInvite"("roomId", "status");
            CREATE INDEX IF NOT EXISTS "RideInvite_inviteeId_status_idx" ON "RideInvite"("inviteeId", "status");
            CREATE INDEX IF NOT EXISTS "RideInvite_inviterId_status_idx" ON "RideInvite"("inviterId", "status");
            CREATE INDEX IF NOT EXISTS "RideSession_riderId_startTime_idx" ON "RideSession"("riderId", "startTime" DESC);
            CREATE INDEX IF NOT EXISTS "ConvoyEvent_rideId_idx" ON "ConvoyEvent"("rideId");
            CREATE INDEX IF NOT EXISTS "EmergencyAlert_roomName_senderId_idx" ON "EmergencyAlert"("roomName", "senderId");
            CREATE INDEX IF NOT EXISTS "EmergencyAlert_senderId_idx" ON "EmergencyAlert"("senderId");
            CREATE INDEX IF NOT EXISTS "Room_ownerId_idx" ON "Room"("ownerId");
            CREATE INDEX IF NOT EXISTS "RoomJoinToken_roomId_idx" ON "RoomJoinToken"("roomId");
        `)
    } catch (e) {
        console.warn('[DB] RoomJoinToken table initialization notice:', e.message)
    }
}
ensureDbExtensions()

const PORT = process.env.PORT || 3000
app.listen(PORT, () => console.log(`Server running on port ${PORT}`))