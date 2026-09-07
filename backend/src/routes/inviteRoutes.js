const express = require('express')
const router  = express.Router()
const prisma  = require('../db')
const notificationService = require('../services/notificationService')

/**
 * POST /api/invites/invite
 * Sends a ride invite FROM the authenticated user.
 * inviterId is always req.user.uid — never trusted from the request body.
 */
router.post('/invite', async (req, res, next) => {
    const inviterId = req.user.uid             // ← always from auth, never from body
    const { roomId, inviteeId } = req.body

    if (!roomId || !inviteeId) {
        return res.status(400).json({ error: 'roomId and inviteeId are required' })
    }
    if (inviterId === inviteeId) {
        return res.status(400).json({ error: 'Cannot invite yourself' })
    }

    try {
        // Ensure inviter user record exists in Supabase
        await prisma.user.upsert({
            where: { id: inviterId },
            update: {},
            create: {
                id: inviterId,
                email: req.user.email || null,
                displayName: req.user.name || (req.user.email ? req.user.email.split('@')[0] : 'Rider')
            }
        }).catch(() => {})

        // Check room exists — supports roomId (UUID) or room name (convoyName)
        const room = await prisma.room.findFirst({
            where: {
                OR: [
                    { id: roomId },
                    { name: roomId }
                ]
            }
        })
        if (!room) {
            return res.status(404).json({ error: 'Room not found' })
        }

        // Only friends can be invited — prevents invite spam
        const isFriend = await prisma.friendship.findFirst({
            where: {
                status: 'ACCEPTED',
                OR: [
                    { requesterId: inviterId, addresseeId: inviteeId },
                    { requesterId: inviteeId, addresseeId: inviterId }
                ]
            }
        })
        if (!isFriend) {
            return res.status(403).json({ error: 'Can only invite friends' })
        }

        // Prevent duplicate pending invites
        const existingInvite = await prisma.rideInvite.findFirst({
            where: { roomId: room.id, inviterId, inviteeId, status: 'PENDING' }
        })
        if (existingInvite) {
            return res.status(409).json({ error: 'Invite already pending' })
        }

        const invite = await prisma.rideInvite.create({
            data: { roomId: room.id, inviterId, inviteeId, status: 'PENDING' }
        })

        // Send push notification
        const inviter = await prisma.user.findUnique({ where: { id: inviterId } })
        if (inviter) {
            await notificationService.sendRideInvite(
                inviter.handle || inviter.displayName || 'A rider',
                inviteeId,
                room.name
            )
        }

        res.status(201).json(invite)
    } catch (error) {
        next(error)
    }
})

/**
 * POST /api/invites/respond
 * Accept or decline an invite. Only the invitee can respond.
 */
router.post('/respond', async (req, res, next) => {
    const inviteeId = req.user.uid
    const { inviteId, response } = req.body  // response: 'ACCEPTED' | 'DECLINED'

    if (!inviteId || !['ACCEPTED', 'DECLINED'].includes(response)) {
        return res.status(400).json({ error: 'inviteId and response (ACCEPTED|DECLINED) required' })
    }

    try {
        const invite = await prisma.rideInvite.findUnique({ where: { id: inviteId } })
        if (!invite || invite.inviteeId !== inviteeId) {
            return res.status(403).json({ error: 'Not your invite' })
        }
        if (invite.status !== 'PENDING') {
            return res.status(409).json({ error: `Invite already ${invite.status}` })
        }

        const updated = await prisma.rideInvite.update({
            where: { id: inviteId },
            data:  { status: response }
        })
        res.json(updated)
    } catch (error) {
        next(error)
    }
})

/**
 * GET /api/invites/invites/:userId
 * Returns pending invites for the authenticated user only.
 */
router.get('/invites/:userId', async (req, res, next) => {
    const { userId } = req.params

    if (userId !== req.user.uid) {
        return res.status(403).json({ error: 'Forbidden' })
    }

    try {
        const invites = await prisma.rideInvite.findMany({
            where: { inviteeId: userId, status: 'PENDING' },
            include: {
                inviter: { select: { handle: true, displayName: true } },
                room:    { select: { name: true } }
            }
        })

        const formatted = invites.map(i => ({
            ...i,
            inviter: {
                handle: i.inviter?.handle || i.inviter?.displayName || 'Rider',
                displayName: i.inviter?.displayName || null
            },
            room: {
                name: i.room?.name || 'Convoy'
            }
        }))

        res.json(formatted)
    } catch (error) {
        next(error)
    }
})

module.exports = router
