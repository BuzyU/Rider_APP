const express = require('express')
const router = express.Router()
const crypto = require('crypto')
const prisma = require('../db')
const { AccessToken, RoomServiceClient } = require('livekit-server-sdk')

let cachedHttpUrl = null
function getLiveKitHttpUrl() {
    if (cachedHttpUrl !== null) return cachedHttpUrl
    const rawUrl = process.env.LIVEKIT_URL || ''
    cachedHttpUrl = rawUrl
        .replace('wss://', 'https://')
        .replace('ws://', 'http://')
    return cachedHttpUrl
}

router.post('/create', async (req, res, next) => {
    const hostId = req.user.uid
    const { convoyName } = req.body

    if (!convoyName) {
        return res.status(400).json({ error: 'convoyName is required' })
    }

    try {
        const existing = await prisma.room.findUnique({
            where: { name: convoyName },
            select: { id: true }
        })
        if (existing) {
            return res.status(409).json({ error: 'A convoy with that name already exists' })
        }

        const room = await prisma.room.create({
            data: {
                name: convoyName,
                ownerId: hostId,
            },
            select: {
                id: true,
                name: true
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

router.get('/:roomName/status', async (req, res, next) => {
    const { roomName } = req.params
    const requesterId = req.user.uid

    try {
        const room = await prisma.room.findUnique({
            where: { name: roomName },
            select: {
                id: true,
                name: true,
                ownerId: true,
                invites: {
                    where: { status: { not: 'REMOVED' } },
                    select: {
                        id: true,
                        status: true,
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

        let acceptedCount = 0
        let pendingCount = 0
        let declinedCount = 0

        const formattedInvites = new Array(room.invites.length)
        for (let i = 0; i < room.invites.length; i++) {
            const inv = room.invites[i]
            formattedInvites[i] = {
                inviteId: inv.id,
                status: inv.status,
                invitee: {
                    id: inv.invitee.id,
                    handle: inv.invitee.handle || inv.invitee.displayName || 'Rider',
                    displayName: inv.invitee.displayName,
                    bikeModel: inv.invitee.bikeModel
                },
            }
            if (inv.status === 'ACCEPTED') acceptedCount++
            else if (inv.status === 'PENDING') pendingCount++
            else if (inv.status === 'DECLINED') declinedCount++
        }

        const summary = {
            roomId: room.id,
            convoyName: room.name,
            invites: formattedInvites,
            acceptedCount,
            pendingCount,
            declinedCount,
            canStart: true,
        }

        res.json(summary)
    } catch (error) {
        next(error)
    }
})

router.post('/:roomName/start', async (req, res, next) => {
    const { roomName } = req.params
    const user = req.user

    try {
        const room = await prisma.room.findUnique({
            where: { name: roomName },
            select: { ownerId: true }
        })

        if (!room) return res.status(404).json({ error: 'Room not found' })
        if (room.ownerId !== user.uid) return res.status(403).json({ error: 'Only the host can start the ride' })

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

router.post('/join-token', async (req, res, next) => {
    const { roomName } = req.body
    const user = req.user

    if (!roomName) return res.status(400).json({ error: 'roomName is required' })

    try {
        const room = await prisma.room.findUnique({
            where: { name: roomName },
            select: {
                id: true,
                ownerId: true,
                invites: {
                    where: { inviteeId: user.uid, status: 'ACCEPTED' },
                    select: { id: true },
                    take: 1
                }
            }
        })
        if (!room) return res.status(404).json({ error: 'Room not found' })

        const isAuthorized = room.ownerId === user.uid || room.invites.length > 0
        if (!isAuthorized) {
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

router.post('/:roomName/share-link', async (req, res, next) => {
    const { roomName } = req.params
    const user = req.user

    try {
        const room = await prisma.room.findUnique({
            where: { name: roomName },
            select: {
                id: true,
                ownerId: true,
                invites: {
                    where: { inviteeId: user.uid, status: 'ACCEPTED' },
                    select: { id: true },
                    take: 1
                }
            }
        })
        if (!room) return res.status(404).json({ error: 'Room not found' })

        const isAuthorized = room.ownerId === user.uid || room.invites.length > 0
        if (!isAuthorized) {
            return res.status(403).json({ error: 'Only convoy participants can share join links' })
        }

        const token = crypto.randomBytes(8).toString('hex')
        const expiresAt = new Date(Date.now() + 24 * 60 * 60 * 1000)

        await prisma.roomJoinToken.create({
            data: {
                roomId: room.id,
                token,
                expiresAt
            },
            select: { id: true }
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

router.post('/join-via-token', async (req, res, next) => {
    const { token } = req.body
    const user = req.user

    if (!token) return res.status(400).json({ error: 'token is required' })

    try {
        const joinTokenRecord = await prisma.roomJoinToken.findUnique({
            where: { token },
            select: {
                expiresAt: true,
                room: {
                    select: {
                        id: true,
                        name: true,
                        ownerId: true
                    }
                }
            }
        })

        if (!joinTokenRecord) {
            return res.status(404).json({ error: 'Invalid or expired convoy link' })
        }

        if (new Date() > joinTokenRecord.expiresAt) {
            return res.status(410).json({ error: 'Convoy join link has expired' })
        }

        const room = joinTokenRecord.room

        const existingInvite = await prisma.rideInvite.findFirst({
            where: { roomId: room.id, inviteeId: user.uid },
            select: { id: true }
        })

        if (existingInvite) {
            await prisma.rideInvite.update({
                where: { id: existingInvite.id },
                data: { status: 'ACCEPTED' },
                select: { id: true }
            })
        } else {
            await prisma.rideInvite.create({
                data: {
                    roomId: room.id,
                    inviterId: room.ownerId,
                    inviteeId: user.uid,
                    status: 'ACCEPTED'
                },
                select: { id: true }
            })
        }

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

router.delete('/:roomName/riders/:userId', async (req, res, next) => {
    const { roomName, userId } = req.params
    const hostId = req.user.uid

    try {
        const room = await prisma.room.findUnique({
            where: { name: roomName },
            select: { id: true, ownerId: true }
        })
        if (!room) return res.status(404).json({ error: 'Room not found' })

        if (room.ownerId !== hostId) {
            return res.status(403).json({ error: 'Only the host can remove riders' })
        }

        await prisma.rideInvite.updateMany({
            where: { roomId: room.id, inviteeId: userId },
            data: { status: 'REMOVED' }
        })

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

router.post('/:roomName/end', async (req, res, next) => {
    const { roomName } = req.params
    const hostId = req.user.uid

    try {
        const room = await prisma.room.findUnique({
            where: { name: roomName },
            select: { ownerId: true }
        })
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

router.post('/:roomName/transfer-host', async (req, res, next) => {
    const { roomName } = req.params
    const hostId = req.user.uid
    const { newHostId } = req.body

    if (!newHostId) return res.status(400).json({ error: 'newHostId is required' })

    try {
        const room = await prisma.room.findUnique({
            where: { name: roomName },
            select: { id: true, ownerId: true }
        })
        if (!room) return res.status(404).json({ error: 'Room not found' })

        if (room.ownerId !== hostId) {
            return res.status(403).json({ error: 'Only the current host can transfer convoy ownership' })
        }

        await prisma.room.update({
            where: { id: room.id },
            data: { ownerId: newHostId },
            select: { id: true }
        })

        res.json({ success: true, newHostId })
    } catch (error) {
        next(error)
    }
})

module.exports = router
