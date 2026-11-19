const express = require('express')
const router = express.Router()
const prisma = require('../db')
const cacheService = require('../services/cacheService')

router.post('/profile', async (req, res, next) => {
    const id = req.user.uid
    const { handle, displayName, bikeModel, bio, phone } = req.body
    const cleanHandle = handle ? handle.replace(/^@/, '').trim() : undefined
    const isGuestEmail = req.user?.email && req.user.email.endsWith('@guest.ridervoice.local')
    const userEmail = isGuestEmail ? null : (req.user?.email || null)

    try {
        const user = await prisma.user.upsert({
            where: { id },
            update: {
                ...(cleanHandle !== undefined && { handle: cleanHandle }),
                ...(displayName !== undefined && { displayName }),
                ...(bikeModel !== undefined && { bikeModel }),
                ...(bio !== undefined && { bio }),
                ...(phone !== undefined && { phone }),
                ...(userEmail && { email: userEmail })
            },
            create: {
                id,
                email: userEmail,
                handle: cleanHandle || null,
                displayName: displayName || null,
                bikeModel: bikeModel || null,
                bio: bio || null,
                phone: phone || null
            },
            select: {
                id: true,
                handle: true,
                displayName: true,
                bikeModel: true,
                bio: true,
                email: true,
                phone: true,
                createdAt: true
            }
        })
        cacheService.del(`user_me:${id}`)
        cacheService.del(`user_name:${id}`)
        res.json({
            ...user,
            createdAt: user.createdAt ? user.createdAt.toISOString() : null
        })
    } catch (error) {
        if (error.code === 'P2002') {
            return res.status(409).json({ error: 'Handle already taken' })
        }
        next(error)
    }
})

router.get('/search', async (req, res, next) => {
    const { handle, query } = req.query
    const searchTerm = (handle || query || '').trim()
    if (!searchTerm) {
        return res.status(400).json({ error: 'handle or query parameter is required' })
    }

    const cleanTerm = searchTerm.replace(/^@/, '').trim()
    const isEmail = cleanTerm.includes('@')

    try {
        const user = await prisma.user.findFirst({
            where: isEmail
                ? { email: { equals: cleanTerm, mode: 'insensitive' } }
                : {
                    OR: [
                        { handle: { equals: cleanTerm, mode: 'insensitive' } },
                        { email: { equals: cleanTerm, mode: 'insensitive' } }
                    ]
                },
            select: {
                id: true,
                handle: true,
                displayName: true,
                bikeModel: true,
                bio: true,
                email: true,
                phone: true,
                createdAt: true
            }
        })
        if (!user) return res.status(404).json({ error: 'Rider not found' })
        res.json({
            ...user,
            createdAt: user.createdAt ? user.createdAt.toISOString() : null
        })
    } catch (error) {
        next(error)
    }
})

router.get('/me', async (req, res, next) => {
    try {
        const cacheKey = `user_me:${req.user.uid}`
        const cached = cacheService.get(cacheKey)
        if (cached) return res.json(cached)

        const user = await prisma.user.findUnique({
            where: { id: req.user.uid },
            select: {
                id: true,
                handle: true,
                displayName: true,
                bikeModel: true,
                bio: true,
                email: true,
                phone: true,
                createdAt: true
            }
        })
        if (!user) return res.status(404).json({ error: 'Profile not found. Call POST /profile first.' })
        const payload = {
            ...user,
            createdAt: user.createdAt ? user.createdAt.toISOString() : null
        }
        cacheService.set(cacheKey, payload, 30000)
        res.json(payload)
    } catch (error) {
        next(error)
    }
})

router.post('/fcm-token', async (req, res, next) => {
    const userId = req.user.uid
    const { token, platform = 'android' } = req.body
    if (!token) {
        return res.status(400).json({ error: 'token is required' })
    }

    try {
        const device = await prisma.deviceToken.upsert({
            where: { token },
            update: { userId, platform, updatedAt: new Date() },
            create: { userId, token, platform },
            select: { id: true, userId: true, token: true, platform: true }
        })
        res.json(device)
    } catch (error) {
        next(error)
    }
})

router.delete('/fcm-token', async (req, res, next) => {
    const userId = req.user.uid
    const token = req.body?.token || req.query?.token

    try {
        if (token) {
            await prisma.deviceToken.deleteMany({
                where: { userId, token }
            })
        } else {
            await prisma.deviceToken.deleteMany({
                where: { userId }
            })
        }
        res.json({ success: true })
    } catch (error) {
        next(error)
    }
})

router.delete('/account', async (req, res, next) => {
    const id = req.user.uid
    try {
        await prisma.$transaction(async (tx) => {
            const ownedRooms = await tx.room.findMany({
                where: { ownerId: id },
                select: { id: true }
            })
            const ownedRoomIds = ownedRooms.map(r => r.id)

            await tx.deviceToken.deleteMany({ where: { userId: id } })
            await tx.friendship.deleteMany({
                where: { OR: [{ requesterId: id }, { addresseeId: id }] }
            })
            await tx.rideInvite.deleteMany({
                where: {
                    OR: [
                        { inviterId: id },
                        { inviteeId: id },
                        ...(ownedRoomIds.length > 0 ? [{ roomId: { in: ownedRoomIds } }] : [])
                    ]
                }
            })
            await tx.emergencyAlert.deleteMany({
                where: {
                    OR: [
                        { senderId: id },
                        ...(ownedRoomIds.length > 0 ? [{ roomId: { in: ownedRoomIds } }] : [])
                    ]
                }
            })

            const sessions = await tx.rideSession.findMany({
                where: { riderId: id },
                select: { id: true }
            })
            const sessionIds = sessions.map(s => s.id)
            if (sessionIds.length > 0) {
                await tx.convoyEvent.deleteMany({ where: { rideId: { in: sessionIds } } })
            }
            await tx.rideSession.deleteMany({ where: { riderId: id } })
            await tx.rideCreateRequest.deleteMany({ where: { ownerId: id } })
            if (ownedRoomIds.length > 0) {
                await tx.room.deleteMany({ where: { id: { in: ownedRoomIds } } })
            }
            try {
                await tx.user.delete({ where: { id } })
            } catch (err) {
                if (err.code !== 'P2025') throw err
            }
        })

        cacheService.del(`user_me:${id}`)
        cacheService.del(`user_name:${id}`)
        res.json({ deleted: true })
    } catch (error) {
        next(error)
    }
})

module.exports = router
