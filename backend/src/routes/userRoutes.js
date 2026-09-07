const express = require('express')
const router  = express.Router()
const prisma  = require('../db')

/**
 * POST /api/users/profile
 * Creates or updates the authenticated rider's profile.
 * id always comes from req.user.uid — never trusted from body.
 */
router.post('/profile', async (req, res, next) => {
    const id = req.user.uid  // ← always from auth token

    const { handle, displayName, bikeModel, bio, phone } = req.body

    // Sanitise handle — strip @ prefix if user included it
    const cleanHandle = handle ? handle.replace(/^@/, '').trim() : undefined

    try {
        const user = await prisma.user.upsert({
            where:  { id },
            update: {
                ...(cleanHandle   !== undefined && { handle:      cleanHandle }),
                ...(displayName   !== undefined && { displayName }),
                ...(bikeModel     !== undefined && { bikeModel }),
                ...(bio           !== undefined && { bio }),
                ...(phone         !== undefined && { phone })
            },
            create: {
                id,
                email:       req.user.email || null,
                handle:      cleanHandle    || null,
                displayName: displayName    || null,
                bikeModel:   bikeModel      || null,
                bio:         bio            || null,
                phone:       phone          || null
            }
        })
        res.json(user)
    } catch (error) {
        // Unique constraint on handle
        if (error.code === 'P2002') {
            return res.status(409).json({ error: 'Handle already taken' })
        }
        next(error)
    }
})

/**
 * GET /api/users/search?handle=...
 * Public rider search by handle. Never returns email or phone.
 */
router.get('/search', async (req, res, next) => {
    const { handle } = req.query
    if (!handle) {
        return res.status(400).json({ error: 'handle query parameter is required' })
    }

    const cleanHandle = handle.replace(/^@/, '').trim()

    try {
        const user = await prisma.user.findFirst({
            where:  { handle: { equals: cleanHandle, mode: 'insensitive' } },
            select: { id: true, handle: true, displayName: true, bikeModel: true, bio: true }
        })
        if (!user) return res.status(404).json({ error: 'Rider not found' })
        res.json(user)
    } catch (error) {
        next(error)
    }
})

/**
 * GET /api/users/me
 * Returns the full profile for the authenticated user.
 */
router.get('/me', async (req, res, next) => {
    try {
        const user = await prisma.user.findUnique({
            where:  { id: req.user.uid },
            select: {
                id: true, handle: true, displayName: true,
                bikeModel: true, bio: true, email: true, createdAt: true
            }
        })
        if (!user) return res.status(404).json({ error: 'Profile not found. Call POST /profile first.' })
        res.json(user)
    } catch (error) {
        next(error)
    }
})

/**
 * POST /api/users/fcm-token
 * Registers an FCM push token for the authenticated user.
 *
 * BUG FIX: userId previously came from req.body — any caller could register a
 * token for any user. Now always uses req.user.uid.
 */
router.post('/fcm-token', async (req, res, next) => {
    const userId = req.user.uid   // ← always from auth, never from body

    const { token, platform = 'android' } = req.body
    if (!token) {
        return res.status(400).json({ error: 'token is required' })
    }

    try {
        const device = await prisma.deviceToken.upsert({
            where:  { token },
            update: { userId, platform, updatedAt: new Date() },
            create: { userId, token, platform }
        })
        res.json(device)
    } catch (error) {
        next(error)
    }
})

module.exports = router
