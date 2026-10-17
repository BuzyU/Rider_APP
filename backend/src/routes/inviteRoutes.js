const express = require('express')
const router = express.Router()
const prisma = require('../db')
const notificationService = require('../services/notificationService')
const emailService = require('../services/emailService')
const cacheService = require('../services/cacheService')

router.post('/invite', async (req, res, next) => {
    const inviterId = req.user.uid
    const { roomId, inviteeId } = req.body

    if (!roomId || !inviteeId) {
        return res.status(400).json({ error: 'roomId and inviteeId are required' })
    }
    if (inviterId === inviteeId) {
        return res.status(400).json({ error: 'Cannot invite yourself' })
    }

    try {
        const room = await prisma.room.findFirst({
            where: {
                OR: [
                    { id: roomId },
                    { name: roomId },
                    { name: { equals: roomId, mode: 'insensitive' } }
                ]
            },
            select: { id: true, name: true }
        })
        if (!room) {
            return res.status(404).json({ error: 'Room not found' })
        }

        const [isFriend, existingInvite, inviter] = await Promise.all([
            prisma.friendship.findFirst({
                where: {
                    status: 'ACCEPTED',
                    OR: [
                        { requesterId: inviterId, addresseeId: inviteeId },
                        { requesterId: inviteeId, addresseeId: inviterId }
                    ]
                },
                select: { id: true }
            }),
            prisma.rideInvite.findFirst({
                where: { roomId: room.id, inviterId, inviteeId, status: 'PENDING' },
                select: { id: true }
            }),
            prisma.user.findUnique({
                where: { id: inviterId },
                select: { handle: true, displayName: true }
            })
        ])

        if (!isFriend) {
            return res.status(403).json({ error: 'Can only invite friends' })
        }

        if (existingInvite) {
            return res.status(409).json({ error: 'Invite already pending' })
        }

        const invite = await prisma.rideInvite.create({
            data: { roomId: room.id, inviterId, inviteeId, status: 'PENDING' }
        })
        cacheService.del(`lobby_status:${room.name}`)
        cacheService.del(`lobby_status:${room.id}`)

        if (inviter) {
            notificationService.sendRideInvite(
                inviter.handle || inviter.displayName || 'A rider',
                inviteeId,
                room.name
            ).catch(err => console.error('[Notification] sendRideInvite non-fatal error:', err.message))
        }

        emailService.sendRideInviteEmailSafe({
            inviterId,
            inviteeId,
            roomName: room.name,
            roomId: room.id
        })

        res.status(201).json(invite)
    } catch (error) {
        next(error)
    }
})

router.post('/respond', async (req, res, next) => {
    const inviteeId = req.user.uid
    const { inviteId, response } = req.body

    if (!inviteId || !['ACCEPTED', 'DECLINED'].includes(response)) {
        return res.status(400).json({ error: 'inviteId and response (ACCEPTED|DECLINED) required' })
    }

    try {
        const invite = await prisma.rideInvite.findUnique({
            where: { id: inviteId },
            select: {
                id: true,
                inviteeId: true,
                inviterId: true,
                roomId: true,
                status: true,
                room: {
                    select: {
                        id: true,
                        name: true,
                        ownerId: true
                    }
                }
            }
        })
        if (!invite || invite.inviteeId !== inviteeId) {
            return res.status(403).json({ error: 'Not your invite' })
        }
        if (invite.status !== 'PENDING') {
            return res.status(409).json({ error: `Invite already ${invite.status}` })
        }

        const updated = await prisma.rideInvite.update({
            where: { id: inviteId },
            data: { status: response }
        })
        if (invite.room?.name) {
            cacheService.del(`lobby_status:${invite.room.name}`)
        }
        if (invite.roomId || invite.room?.id) {
            cacheService.del(`lobby_status:${invite.roomId || invite.room?.id}`)
        }

        if (response === 'ACCEPTED') {
            let hostName = null
            if (invite.room?.ownerId) {
                try {
                    const hostUser = await prisma.user.findUnique({
                        where: { id: invite.room.ownerId },
                        select: { displayName: true, handle: true }
                    })
                    hostName = hostUser?.displayName || (hostUser?.handle ? `@${hostUser.handle}` : null)
                } catch {}
            }

            const riderName = req.user.name || (req.user.email ? req.user.email.split('@')[0] : 'A rider')
            try {
                notificationService.sendInviteAccepted(
                    riderName,
                    invite.inviterId,
                    invite.room?.name
                ).catch(err => console.error('[Notification] sendInviteAccepted error:', err.message))
            } catch {}

            emailService.sendUserJoinedEmailSafe({
                userId: inviteeId,
                userEmail: req.user.email,
                userName: req.user.name,
                roomName: invite.room?.name,
                hostName,
                joinedAt: new Date()
            })
        }

        res.json(updated)
    } catch (error) {
        next(error)
    }
})

router.get('/invites/:userId', async (req, res, next) => {
    const { userId } = req.params

    if (userId !== req.user.uid) {
        return res.status(403).json({ error: 'Forbidden' })
    }

    try {
        const invites = await prisma.rideInvite.findMany({
            where: { inviteeId: userId, status: 'PENDING' },
            select: {
                id: true,
                roomId: true,
                inviterId: true,
                inviteeId: true,
                status: true,
                createdAt: true,
                inviter: { select: { handle: true, displayName: true } },
                room: { select: { name: true } }
            },
            orderBy: { createdAt: 'desc' }
        })

        const formatted = new Array(invites.length)
        for (let i = 0; i < invites.length; i++) {
            const inv = invites[i]
            formatted[i] = {
                id: inv.id,
                roomId: inv.roomId,
                inviterId: inv.inviterId,
                inviteeId: inv.inviteeId,
                status: inv.status,
                createdAt: inv.createdAt,
                inviter: {
                    handle: inv.inviter?.handle || inv.inviter?.displayName || 'Rider',
                    displayName: inv.inviter?.displayName || null
                },
                room: {
                    name: inv.room?.name || 'Convoy'
                }
            }
        }

        res.json(formatted)
    } catch (error) {
        next(error)
    }
})

module.exports = router
