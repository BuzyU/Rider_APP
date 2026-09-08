const express = require('express')
const router = express.Router()
const crypto = require('crypto')
const prisma = require('../db')
const { AccessToken, RoomServiceClient } = require('livekit-server-sdk')

function getLiveKitHttpUrl() {
    const rawUrl = process.env.LIVEKIT_URL || ''
    return rawUrl
        .replace('wss://', 'https://')
        .replace('ws://', 'http://')
}

/**
 * POST /api/lobby/create
 * Host creates a named convoy with trip details.
 */
router.post('/create', async (req, res, next) => {
    const hostId = req.user.uid
    const { convoyName, origin, destination, estimatedDurationMin, meetupPoint } = req.body

    if (!convoyName) {
        return res.status(400).json({ error: 'convoyName is required' })
    }

    try {
        const existing = await prisma.room.findUnique({ where: { name: convoyName } })
        if (existing) {
            return res.status(409).json({ error: 'A convoy with that name already exists' })
        }

        const room = await prisma.room.create({
            data: {
                name: convoyName,
                ownerId: hostId,
            }
        })

        res.status(201).json({
            roomId: room.id,
            convoyName: room.name,
            hostId,
        })
    } catch (error) {
        next(error)
    }
})

/**
 * GET /api/lobby/:roomName/status
 * Host polls this to monitor member acceptances.
 * Host can always start (solo start allowed).
 */
router.get('/:roomName/status', async (req, res, next) => {
    const { roomName } = req.params
    const requesterId = req.user.uid

    try {
        const room = await prisma.room.findUnique({
            where: { name: roomName },
            include: {
                invites: {
                    where: { status: { not: 'REMOVED' } },
                    include: {
                        invitee: {
                            select: { id: true, handle: true, displayName: true, bikeModel: true }
                        }
                    }
                }
            }
        })

        if (!room) return res.status(404).json({ error: 'Room not found' })

        if (room.ownerId !== requesterId) {
            return res.status(403).json({ error: 'Only the host can view lobby status' })
        }

        const summary = {
            roomId: room.id,
            convoyName: room.name,
            invites: room.invites.map(inv => ({
                inviteId: inv.id,
                status: inv.status,
                invitee: inv.invitee,
            })),
            acceptedCount: room.invites.filter(i => i.status === 'ACCEPTED').length,
            pendingCount:  room.invites.filter(i => i.status === 'PENDING').length,
            declinedCount: room.invites.filter(i => i.status === 'DECLINED').length,
            canStart: true, // Host is always permitted to start (including solo rides)
        }

        res.json(summary)
    } catch (error) {
        next(error)
    }
})

/**
 * POST /api/lobby/:roomName/start
 * Host taps "Start Ride". Generates LiveKit token for the host.
 * Solo start is permitted.
 */
router.post('/:roomName/start', async (req, res, next) => {
    const { roomName } = req.params
    const user = req.user

    try {
        const room = await prisma.room.findUnique({
            where: { name: roomName }
        })

        if (!room) return res.status(404).json({ error: 'Room not found' })
        if (room.ownerId !== user.uid) return res.status(403).json({ error: 'Only the host can start the ride' })

        // Generate LiveKit token for host
        const at = new AccessToken(
            process.env.LIVEKIT_API_KEY,
            process.env.LIVEKIT_API_SECRET,
            { identity: user.uid, name: user.name || 'Host', ttl: '6h' }
        )
        at.addGrant({ roomJoin: true, room: roomName, canPublish: true, canSubscribe: true })
        const token = await at.toJwt()

        res.json({ token, roomName, livekitUrl: process.env.LIVEKIT_URL })
    } catch (error) {
        next(error)
    }
})

/**
 * POST /api/lobby/join-token
 * Joiner calls this AFTER accepting an invite.
 */
router.post('/join-token', async (req, res, next) => {
    const { roomName } = req.body
    const user = req.user

    if (!roomName) return res.status(400).json({ error: 'roomName is required' })

    try {
        const room = await prisma.room.findUnique({ where: { name: roomName } })
        if (!room) return res.status(404).json({ error: 'Room not found' })

        const invite = await prisma.rideInvite.findFirst({
            where: {
                roomId: room.id,
                inviteeId: user.uid,
                status: 'ACCEPTED'
            }
        })

        const isHost = room.ownerId === user.uid

        if (!invite && !isHost) {
            return res.status(403).json({ error: 'No accepted invite found for this room' })
        }

        const at = new AccessToken(
            process.env.LIVEKIT_API_KEY,
            process.env.LIVEKIT_API_SECRET,
            { identity: user.uid, name: user.name || 'Rider', ttl: '6h' }
        )
        at.addGrant({ roomJoin: true, room: roomName, canPublish: true, canSubscribe: true })
        const token = await at.toJwt()

        res.json({ token, roomName, livekitUrl: process.env.LIVEKIT_URL })
    } catch (error) {
        next(error)
    }
})

/**
 * POST /api/lobby/:roomName/share-link
 * Generates a shareable join token valid for 24 hours.
 */
router.post('/:roomName/share-link', async (req, res, next) => {
    const { roomName } = req.params
    const user = req.user

    try {
        const room = await prisma.room.findUnique({ where: { name: roomName } })
        if (!room) return res.status(404).json({ error: 'Room not found' })

        // Caller must be room owner or accepted participant
        const isHost = room.ownerId === user.uid
        const isMember = await prisma.rideInvite.findFirst({
            where: { roomId: room.id, inviteeId: user.uid, status: 'ACCEPTED' }
        })

        if (!isHost && !isMember) {
            return res.status(403).json({ error: 'Only convoy participants can share join links' })
        }

        // Generate 16-character hex token
        const token = crypto.randomBytes(8).toString('hex')
        const expiresAt = new Date(Date.now() + 24 * 60 * 60 * 1000)

        await prisma.roomJoinToken.create({
            data: {
                roomId: room.id,
                token,
                expiresAt
            }
        })

        res.json({
            token,
            shareUrl: `ridervoice://join/${token}`,
            expiresAt: expiresAt.toISOString()
        })
    } catch (error) {
        next(error)
    }
})

/**
 * POST /api/lobby/join-via-token
 * Validates a share link token, automatically records user as ACCEPTED,
 * defaulting inviterId to room.ownerId (preserving referential integrity).
 * Returns LiveKit credentials in identical shape to join-token.
 */
router.post('/join-via-token', async (req, res, next) => {
    const { token } = req.body
    const user = req.user

    if (!token) return res.status(400).json({ error: 'token is required' })

    try {
        const joinTokenRecord = await prisma.roomJoinToken.findUnique({
            where: { token },
            include: { room: true }
        })

        if (!joinTokenRecord) {
            return res.status(404).json({ error: 'Invalid or expired convoy link' })
        }

        if (new Date() > joinTokenRecord.expiresAt) {
            return res.status(410).json({ error: 'Convoy join link has expired' })
        }

        const room = joinTokenRecord.room

        // Record or update user's invite record with status = ACCEPTED.
        // inviterId defaults explicitly to room.ownerId (the convoy host).
        const existingInvite = await prisma.rideInvite.findFirst({
            where: { roomId: room.id, inviteeId: user.uid }
        })

        if (existingInvite) {
            await prisma.rideInvite.update({
                where: { id: existingInvite.id },
                data: { status: 'ACCEPTED' }
            })
        } else {
            await prisma.rideInvite.create({
                data: {
                    roomId: room.id,
                    inviterId: room.ownerId, // Explicit default preserves User FK
                    inviteeId: user.uid,
                    status: 'ACCEPTED'
                }
            })
        }

        // Mint LiveKit token
        const at = new AccessToken(
            process.env.LIVEKIT_API_KEY,
            process.env.LIVEKIT_API_SECRET,
            { identity: user.uid, name: user.name || 'Rider', ttl: '6h' }
        )
        at.addGrant({ roomJoin: true, room: room.name, canPublish: true, canSubscribe: true })
        const livekitToken = await at.toJwt()

        res.json({
            token: livekitToken,
            roomName: room.name,
            livekitUrl: process.env.LIVEKIT_URL
        })
    } catch (error) {
        next(error)
    }
})

/**
 * DELETE /api/lobby/:roomName/riders/:userId
 * Host removes a rider from the convoy.
 * Sets RideInvite to REMOVED and disconnects audio via LiveKit RoomServiceClient.
 */
router.delete('/:roomName/riders/:userId', async (req, res, next) => {
    const { roomName, userId } = req.params
    const hostId = req.user.uid

    try {
        const room = await prisma.room.findUnique({ where: { name: roomName } })
        if (!room) return res.status(404).json({ error: 'Room not found' })

        if (room.ownerId !== hostId) {
            return res.status(403).json({ error: 'Only the host can remove riders' })
        }

        // 1. Mark invite as REMOVED in database
        await prisma.rideInvite.updateMany({
            where: { roomId: room.id, inviteeId: userId },
            data: { status: 'REMOVED' }
        })

        // 2. Disconnect rider from LiveKit session if active
        try {
            const httpUrl = getLiveKitHttpUrl()
            if (httpUrl && process.env.LIVEKIT_API_KEY && process.env.LIVEKIT_API_SECRET) {
                const svc = new RoomServiceClient(httpUrl, process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET)
                await svc.removeParticipant(roomName, userId)
            }
        } catch (lkError) {
            console.warn(`[LiveKit] removeParticipant non-fatal notice for ${userId}:`, lkError.message)
        }

        res.json({ success: true, removedUserId: userId })
    } catch (error) {
        next(error)
    }
})

/**
 * POST /api/lobby/:roomName/end
 * Host closes the ride for everyone.
 * Disconnects all active participants via LiveKit deleteRoom.
 */
router.post('/:roomName/end', async (req, res, next) => {
    const { roomName } = req.params
    const hostId = req.user.uid

    try {
        const room = await prisma.room.findUnique({ where: { name: roomName } })
        if (!room) return res.status(404).json({ error: 'Room not found' })

        if (room.ownerId !== hostId) {
            return res.status(403).json({ error: 'Only the host can end the convoy ride' })
        }

        try {
            const httpUrl = getLiveKitHttpUrl()
            if (httpUrl && process.env.LIVEKIT_API_KEY && process.env.LIVEKIT_API_SECRET) {
                const svc = new RoomServiceClient(httpUrl, process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET)
                await svc.deleteRoom(roomName)
            }
        } catch (lkError) {
            console.warn(`[LiveKit] deleteRoom notice for ${roomName}:`, lkError.message)
        }

        res.json({ success: true, message: 'Convoy ended' })
    } catch (error) {
        next(error)
    }
})

/**
 * POST /api/lobby/:roomName/transfer-host
 * Host transfers ownership to another accepted rider.
 */
router.post('/:roomName/transfer-host', async (req, res, next) => {
    const { roomName } = req.params
    const hostId = req.user.uid
    const { newHostId } = req.body

    if (!newHostId) return res.status(400).json({ error: 'newHostId is required' })

    try {
        const room = await prisma.room.findUnique({ where: { name: roomName } })
        if (!room) return res.status(404).json({ error: 'Room not found' })

        if (room.ownerId !== hostId) {
            return res.status(403).json({ error: 'Only the current host can transfer convoy ownership' })
        }

        await prisma.room.update({
            where: { id: room.id },
            data: { ownerId: newHostId }
        })

        res.json({ success: true, newHostId })
    } catch (error) {
        next(error)
    }
})

module.exports = router
