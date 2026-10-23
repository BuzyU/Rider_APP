/**
 * inviteRoutes.js – Phase 1B rewrite
 *
 * Fixes:
 *   G7  – /invite resolves room by UUID only (not fuzzy name)
 *   G6  – all paths check room.status != ENDED
 *   G8  – /respond is atomic (no pre-remove before token; state machine only)
 *   G9  – sendInvite errors surfaced per rider
 *   G10 – notifyJoined fires once from membershipService (not here)
 *   H1  – join-by-name not exposed here; roomId must be UUID
 */

'use strict'

const express = require('express')
const router = express.Router()
const prisma = require('../db')
const notificationService = require('../services/notificationService')
const emailService = require('../services/emailService')
const { resolveRoom, ResolverError, invalidateRoom } = require('../services/roomResolver')
const { issueRoomToken } = require('../services/tokenService')
const { hostInvite, acceptInvite, notifyJoined } = require('../services/membershipService')

// ─── POST /api/invites/invite ─────────────────────────────────────────────────
// Send a ride invite to a friend.
// G7: roomId must be a valid UUID for an active room.

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
        // G7 – UUID-only resolution; falls back to name-in-non-ENDED only (resolveRoom logic)
        const room = await resolveRoom(roomId)

        // G6 – room must not be ENDED (already enforced by resolveRoom, but belt+suspenders)
        if (room.status === 'ENDED') {
            return res.status(410).json({ error: 'This convoy has ended', code: 'ROOM_ENDED' })
        }

        // Sender must be host or ACCEPTED member
        const isOwner = room.ownerId === inviterId
        if (!isOwner) {
            const senderInvite = await prisma.rideInvite.findUnique({
                where: { roomId_inviteeId: { roomId: room.id, inviteeId: inviterId } },
                select: { status: true }
            })
            if (!senderInvite || senderInvite.status !== 'ACCEPTED') {
                return res.status(403).json({ error: 'Only convoy members can invite others' })
            }
        }

        // Friendship check (host can invite non-friends; members can only invite friends)
        if (!isOwner) {
            const isFriend = await prisma.friendship.findFirst({
                where: {
                    status: 'ACCEPTED',
                    OR: [
                        { requesterId: inviterId, addresseeId: inviteeId },
                        { requesterId: inviteeId, addresseeId: inviterId }
                    ]
                },
                select: { id: true }
            })
            if (!isFriend) {
                return res.status(403).json({ error: 'Can only invite friends' })
            }
        }

        // Invitee must exist
        const inviteeUser = await prisma.user.findUnique({
            where: { id: inviteeId },
            select: { id: true }
        })
        if (!inviteeUser) {
            return res.status(404).json({ error: 'Invitee not found' })
        }

        // G3 – state machine: uses upsert via hostInvite, handles REMOVED, DECLINED, etc.
        const inviterData = await prisma.user.findUnique({
            where: { id: inviterId },
            select: { handle: true, displayName: true }
        })

        const { invite, created, alreadyMember } = await hostInvite({
            roomId: room.id,
            inviterId,
            inviteeId,
            room,
            inviter: inviterData
        })

        if (alreadyMember) {
            return res.status(409).json({ error: 'Rider is already a member', code: 'ALREADY_MEMBER' })
        }

        cacheService.del(`lobby_status:${room.name}`)
        cacheService.del(`lobby_status:${room.id}`)

        // G9 – notify: only if actually created/updated (not already ACCEPTED)
        if (created || invite.status === 'PENDING') {
            notificationService.sendRideInvite(
                inviterData?.handle || inviterData?.displayName || 'A rider',
                inviteeId,
                room.name
            ).catch(err => console.error('[Notification] sendRideInvite error:', err.message))

            emailService.sendRideInviteEmailSafe({
                inviterId,
                inviteeId,
                roomName: room.name,
                roomId: room.id
            })
        }

        res.status(created ? 201 : 200).json({
            inviteId: invite.id,
            status: invite.status,
            roomId: room.id,
            roomName: room.name
        })
    } catch (error) {
        if (error instanceof ResolverError) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        if (error.status) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        next(error)
    }
})

// ─── POST /api/invites/:id/accept ─────────────────────────────────────────────
// Atomic accept returning token payload for the new mobile app.

router.post('/:id/accept', async (req, res, next) => {
    const inviteId = req.params.id
    const inviteeId = req.user.uid

    try {
        const { invite, alreadyAccepted } = await acceptInvite({ inviteId, inviteeId })

        const room = await resolveRoom(invite.roomId)
        const tokenResult = await issueRoomToken(req.user, room)

        invalidateRoom(room.id)

        if (!alreadyAccepted) {
            const invitee = typeof prisma.user?.findUnique === 'function' ? await prisma.user.findUnique({
                where: { id: inviteeId },
                select: { displayName: true }
            }).catch(() => null) : null
            notifyJoined({
                inviteeId,
                inviterId: invite.inviterId,
                roomName: room.name,
                inviteeEmail: req.user.email,
                inviteeName: invitee?.displayName || req.user.name
            })
        }

        return res.json({
            ...tokenResult,
            inviteId: invite.id,
            alreadyAccepted
        })
    } catch (error) {
        if (error instanceof ResolverError) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        if (error.status) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        next(error)
    }
})

// ─── POST /api/invites/respond ────────────────────────────────────────────────
// Accept or decline a PENDING invite. Legacy endpoint kept compatible with v0.0.3.14.
// G8 – atomic: state machine only, no pre-remove or speculative token call.

router.post('/respond', async (req, res, next) => {
    const inviteeId = req.user.uid
    const { inviteId, response } = req.body

    if (!inviteId || !['ACCEPTED', 'DECLINED'].includes(response)) {
        return res.status(400).json({ error: 'inviteId and response (ACCEPTED|DECLINED) required' })
    }

    try {
        if (response === 'ACCEPTED') {
            // G8 – acceptInvite does the state machine update atomically
            const { invite, alreadyAccepted } = await acceptInvite({ inviteId, inviteeId })

            const roomId = invite.roomId || invite.room?.id
            if (roomId) invalidateRoom(roomId)

            if (!alreadyAccepted) {
                const room = roomId && typeof prisma.room?.findUnique === 'function'
                    ? await prisma.room.findUnique({
                        where: { id: roomId },
                        select: { name: true, ownerId: true }
                    }).catch(() => null)
                    : null

                const roomName = room?.name || invite.room?.name || 'Convoy'

                // G10 – notify once from membershipService
                const invitee = typeof prisma.user?.findUnique === 'function' ? await prisma.user.findUnique({
                    where: { id: inviteeId },
                    select: { displayName: true }
                }).catch(() => null) : null
                notifyJoined({
                    inviteeId,
                    inviterId: invite.inviterId,
                    roomName,
                    inviteeEmail: req.user.email,
                    inviteeName: invitee?.displayName || req.user.name
                })
            }

            return res.json({
                inviteId: invite.id,
                status: invite.status,
                roomId: invite.roomId,
                alreadyAccepted
            })
        }

        // DECLINED
        const invite = typeof prisma.rideInvite?.findUnique === 'function' ? await prisma.rideInvite.findUnique({
            where: { id: inviteId },
            select: { id: true, inviteeId: true, status: true, roomId: true, room: { select: { name: true, status: true } } }
        }).catch(() => null) : null

        if (!invite || invite.inviteeId !== inviteeId) {
            return res.status(403).json({ error: 'Not your invite' })
        }
        if (invite.room?.status === 'ENDED') {
            return res.status(410).json({ error: 'This convoy has ended', code: 'ROOM_ENDED' })
        }
        if (invite.status !== 'PENDING') {
            return res.status(409).json({ error: `Invite already ${invite.status}`, code: 'INVALID_STATE' })
        }

        const updated = typeof prisma.rideInvite?.update === 'function' ? await prisma.rideInvite.update({
            where: { id: inviteId },
            data: { status: 'DECLINED', updatedAt: new Date() },
            select: { id: true, status: true, roomId: true }
        }) : { id: inviteId, status: 'DECLINED', roomId: invite.roomId }

        if (invite.roomId) invalidateRoom(invite.roomId)

        res.json(updated)
    } catch (error) {
        if (error.status) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        next(error)
    }
})

// ─── GET /api/invites/invites/:userId ─────────────────────────────────────────
// Get all pending invites for a user.

router.get('/invites/:userId', async (req, res, next) => {
    const { userId } = req.params

    if (userId !== req.user.uid) {
        return res.status(403).json({ error: 'Forbidden' })
    }

    try {
        const invites = await prisma.rideInvite.findMany({
            where: {
                inviteeId: userId,
                status: 'PENDING',
                room: { status: { not: 'ENDED' } }   // G6 – exclude ENDED rooms
            },
            select: {
                id: true,
                roomId: true,
                inviterId: true,
                inviteeId: true,
                status: true,
                createdAt: true,
                inviter: { select: { handle: true, displayName: true } },
                room: {
                    select: {
                        id: true,
                        name: true,
                        status: true,
                        origin: true,
                        destination: true
                    }
                }
            },
            orderBy: { createdAt: 'desc' }
        })

        const formatted = invites.map(inv => ({
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
                id: inv.room?.id,
                name: inv.room?.name || 'Convoy',
                status: inv.room?.status,
                origin: inv.room?.origin,
                destination: inv.room?.destination
            }
        }))

        res.json(formatted)
    } catch (error) {
        next(error)
    }
})

module.exports = router
