const express = require('express')
const router = express.Router()
const prisma = require('../db')

router.post('/request', async (req, res, next) => {
    const requesterId = req.user.uid
    const { addresseeId, handle } = req.body

    if (!addresseeId && !handle) {
        return res.status(400).json({ error: 'addresseeId or handle is required' })
    }

    try {
        let targetId = addresseeId
        if (handle) {
            const cleanHandle = handle.startsWith('@') ? handle.substring(1).trim() : handle.trim()
            const userByHandle = await prisma.user.findFirst({
                where: { handle: { equals: cleanHandle, mode: 'insensitive' } },
                select: { id: true }
            })
            if (!userByHandle) {
                return res.status(404).json({ error: `Rider @${cleanHandle} not found` })
            }
            targetId = userByHandle.id
        }

        if (requesterId === targetId) {
            return res.status(400).json({ error: 'Cannot add yourself to squad' })
        }

        const addressee = await prisma.user.findUnique({
            where: { id: targetId },
            select: { id: true }
        })
        if (!addressee) {
            return res.status(404).json({ error: 'Rider not found' })
        }

        const existing = await prisma.friendship.findFirst({
            where: {
                OR: [
                    { requesterId, addresseeId: targetId },
                    { requesterId: targetId, addresseeId: requesterId }
                ]
            },
            select: { id: true, status: true, requesterId: true, addresseeId: true, createdAt: true }
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

router.post('/accept', async (req, res, next) => {
    const addresseeId = req.user.uid
    const { requesterId } = req.body

    if (!requesterId) {
        return res.status(400).json({ error: 'requesterId is required' })
    }

    try {
        const friendship = await prisma.friendship.findUnique({
            where: { requesterId_addresseeId: { requesterId, addresseeId } },
            select: { id: true, status: true }
        })

        if (!friendship) {
            return res.status(404).json({ error: 'Friend request not found' })
        }
        if (friendship.status !== 'PENDING') {
            return res.status(409).json({ error: `Request is already ${friendship.status}` })
        }

        const updated = await prisma.friendship.update({
            where: { requesterId_addresseeId: { requesterId, addresseeId } },
            data: { status: 'ACCEPTED' }
        })
        res.json(updated)
    } catch (error) {
        next(error)
    }
})

router.delete('/:friendId', async (req, res, next) => {
    const userId = req.user.uid
    const friendId = req.params.friendId

    try {
        await prisma.friendship.deleteMany({
            where: {
                OR: [
                    { requesterId: userId, addresseeId: friendId },
                    { requesterId: friendId, addresseeId: userId }
                ],
                status: 'ACCEPTED'
            }
        })
        res.json({ success: true })
    } catch (error) {
        next(error)
    }
})

router.get('/list/:userId', async (req, res, next) => {
    const { userId } = req.params

    if (userId !== req.user.uid) {
        return res.status(403).json({ error: 'Forbidden' })
    }

    try {
        const friends = await prisma.friendship.findMany({
            where: {
                status: 'ACCEPTED',
                OR: [{ requesterId: userId }, { addresseeId: userId }]
            },
            select: {
                requesterId: true,
                requester: { select: { id: true, handle: true, displayName: true, bikeModel: true } },
                addressee: { select: { id: true, handle: true, displayName: true, bikeModel: true } }
            }
        })

        const friendList = new Array(friends.length)
        for (let i = 0; i < friends.length; i++) {
            const f = friends[i]
            const u = f.requesterId === userId ? f.addressee : f.requester
            friendList[i] = {
                id: u.id,
                handle: u.handle || u.displayName || 'Rider',
                displayName: u.displayName || null,
                bikeModel: u.bikeModel || null
            }
        }
        res.json(friendList)
    } catch (error) {
        next(error)
    }
})

router.get('/pending', async (req, res, next) => {
    try {
        const requests = await prisma.friendship.findMany({
            where: { addresseeId: req.user.uid, status: 'PENDING' },
            select: {
                id: true,
                requesterId: true,
                addresseeId: true,
                status: true,
                createdAt: true,
                requester: { select: { id: true, handle: true, displayName: true } }
            }
        })
        res.json(requests)
    } catch (error) {
        next(error)
    }
})

module.exports = router
