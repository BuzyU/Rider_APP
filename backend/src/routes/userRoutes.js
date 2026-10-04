const express = require('express')
const router = express.Router()
const prisma = require('../db')

router.post('/profile', async (req, res, next) => {
    const id = req.user.uid
    const { handle, displayName, bikeModel, bio, phone } = req.body
    const cleanHandle = handle ? handle.replace(/^@/, '').trim() : undefined

    try {
        const user = await prisma.user.upsert({
            where: { id },
            update: {
                ...(cleanHandle !== undefined && { handle: cleanHandle }),
                ...(displayName !== undefined && { displayName }),
                ...(bikeModel !== undefined && { bikeModel }),
                ...(bio !== undefined && { bio }),
                ...(phone !== undefined && { phone }),
                ...(req.user?.email && { email: req.user.email })
            },
            create: {
                id,
                email: req.user.email || null,
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
        res.json({
            ...user,
            createdAt: user.createdAt ? user.createdAt.toISOString() : null
        })
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

module.exports = router
