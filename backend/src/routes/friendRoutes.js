const express = require('express')
const router  = express.Router()
const prisma  = require('../db')

/**
 * POST /api/friends/request
 * Sends a friend request FROM the authenticated user TO addresseeId.
 * requesterId is always taken from req.user.uid — never from the body.
 */
router.post('/request', async (req, res, next) => {
    const requesterId = req.user.uid           // ← always the authenticated caller
    const { addresseeId, handle } = req.body

    if (!addresseeId && !handle) {
        return res.status(400).json({ error: 'addresseeId or handle is required' })
    }

    try {
        let targetId = addresseeId
        if (handle) {
            const cleanHandle = handle.startsWith('@') ? handle.substring(1).trim() : handle.trim()
            const userByHandle = await prisma.user.findFirst({
                where: { handle: { equals: cleanHandle, mode: 'insensitive' } }
            })
            if (!userByHandle) {
                return res.status(404).json({ error: `Rider @${cleanHandle} not found` })
            }
            targetId = userByHandle.id
        }

        if (requesterId === targetId) {
            return res.status(400).json({ error: 'Cannot add yourself to squad' })
        }

        // Check if addressee exists (in case addresseeId was passed directly)
        const addressee = await prisma.user.findUnique({ where: { id: targetId } })
        if (!addressee) {
            return res.status(404).json({ error: 'Rider not found' })
        }

        // Ensure requester user record exists in Supabase
        await prisma.user.upsert({
            where: { id: requesterId },
            update: {},
            create: {
                id: requesterId,
                email: req.user.email || null,
                displayName: req.user.name || (req.user.email ? req.user.email.split('@')[0] : 'Rider')
            }
        }).catch(() => {})

        // Check if friendship already exists
        const existing = await prisma.friendship.findFirst({
            where: {
                OR: [
                    { requesterId, addresseeId: targetId },
                    { requesterId: targetId, addresseeId: requesterId }
                ]
            }
        })
        if (existing) {
            if (existing.status !== 'ACCEPTED') {
                const updated = await prisma.friendship.update({
                    where: { id: existing.id },
                    data: { status: 'ACCEPTED' }
                })
                return res.status(200).json(updated)
            }
            return res.status(200).json(existing)
        }

        const request = await prisma.friendship.create({
            data: { requesterId, addresseeId: targetId, status: 'ACCEPTED' }
        })
        res.status(201).json(request)
    } catch (error) {
        next(error)
    }
})

/**
 * POST /api/friends/accept
 * Accepts a friend request sent TO the authenticated user.
 * Only the addressee can accept — prevents any user from accepting on behalf of others.
 */
router.post('/accept', async (req, res, next) => {
    const addresseeId = req.user.uid           // ← must be the person who received the request
    const { requesterId } = req.body

    if (!requesterId) {
        return res.status(400).json({ error: 'requesterId is required' })
    }

    try {
        const friendship = await prisma.friendship.findUnique({
            where: { requesterId_addresseeId: { requesterId, addresseeId } }
        })

        if (!friendship) {
            return res.status(404).json({ error: 'Friend request not found' })
        }
        if (friendship.status !== 'PENDING') {
            return res.status(409).json({ error: `Request is already ${friendship.status}` })
        }

        const updated = await prisma.friendship.update({
            where: { requesterId_addresseeId: { requesterId, addresseeId } },
            data:  { status: 'ACCEPTED' }
        })
        res.json(updated)
    } catch (error) {
        next(error)
    }
})

/**
 * DELETE /api/friends/:friendId
 * Removes a friendship. Either party can remove.
 */
router.delete('/:friendId', async (req, res, next) => {
    const userId   = req.user.uid
    const friendId = req.params.friendId

    try {
        await prisma.friendship.deleteMany({
            where: {
                OR: [
                    { requesterId: userId,   addresseeId: friendId },
                    { requesterId: friendId, addresseeId: userId   }
                ],
                status: 'ACCEPTED'
            }
        })
        res.json({ success: true })
    } catch (error) {
        next(error)
    }
})

/**
 * GET /api/friends/list/:userId
 * Only returns results if :userId matches the authenticated caller.
 */
router.get('/list/:userId', async (req, res, next) => {
    const { userId } = req.params

    // Users can only fetch their own friend list
    if (userId !== req.user.uid) {
        return res.status(403).json({ error: 'Forbidden' })
    }

    try {
        // Auto-upgrade any PENDING friendships for this user to ACCEPTED
        await prisma.friendship.updateMany({
            where: {
                status: 'PENDING',
                OR: [{ requesterId: userId }, { addresseeId: userId }]
            },
            data: { status: 'ACCEPTED' }
        }).catch(() => {})

        const friends = await prisma.friendship.findMany({
            where: {
                status: 'ACCEPTED',
                OR: [{ requesterId: userId }, { addresseeId: userId }]
            },
            include: {
                requester: { select: { id: true, handle: true, displayName: true, bikeModel: true } },
                addressee: { select: { id: true, handle: true, displayName: true, bikeModel: true } }
            }
        })

        const friendList = friends.map(f =>
            f.requesterId === userId ? f.addressee : f.requester
        )
        res.json(friendList)
    } catch (error) {
        next(error)
    }
})

/**
 * GET /api/friends/pending
 * Returns incoming friend requests for the authenticated user.
 */
router.get('/pending', async (req, res, next) => {
    try {
        const requests = await prisma.friendship.findMany({
            where: { addresseeId: req.user.uid, status: 'PENDING' },
            include: {
                requester: { select: { id: true, handle: true, displayName: true } }
            }
        })
        res.json(requests)
    } catch (error) {
        next(error)
    }
})

module.exports = router
