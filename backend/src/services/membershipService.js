/**
 * membershipService.js – Phase 1B.2
 *
 * Single source of truth for all membership state transitions.
 * Enforces the strict state machine:
 *
 *   (none)   → PENDING   host invite (friends only, room not ENDED)
 *   PENDING  → ACCEPTED  invitee accepts
 *   PENDING  → DECLINED  invitee declines
 *   DECLINED → PENDING   ONLY via a new host invite
 *   (none)   → ACCEPTED  valid link token or valid join code (not REMOVED)
 *   ACCEPTED → LEFT      rider leaves voluntarily
 *   ACCEPTED → REMOVED   host removes (terminal for this room)
 *   REMOVED  → PENDING   ONLY via explicit host re-invite
 *   LEFT     → ACCEPTED  rider rejoins with link/code/invite
 *
 * Any other transition returns { ok: false, code: 'INVALID_TRANSITION', current }
 *
 * Emits exactly ONE "joined" notification per ACCEPTED transition (G10).
 */

'use strict'

const prisma = require('../db')
const notificationService = require('./notificationService')
const emailService = require('./emailService')

// ─── State machine ────────────────────────────────────────────────────────────

/**
 * All valid (from → to) transitions by trigger type.
 */
const VALID_TRANSITIONS = {
    HOST_INVITE: {
        null: 'PENDING',
        DECLINED: 'PENDING',
        LEFT: 'PENDING',
        REMOVED: 'PENDING'       // owner-only re-invite
    },
    ACCEPT: {
        PENDING: 'ACCEPTED'
    },
    DECLINE: {
        PENDING: 'DECLINED'
    },
    SELF_JOIN: {
        null: 'ACCEPTED',
        LEFT: 'ACCEPTED',
        DECLINED: 'ACCEPTED'     // self-join always accepted if link/code valid
    },
    LEAVE: {
        ACCEPTED: 'LEFT'
    },
    REMOVE: {
        PENDING: 'REMOVED',
        ACCEPTED: 'REMOVED',
        LEFT: 'REMOVED'
    }
}

function allowedTo(trigger, currentStatus) {
    const map = VALID_TRANSITIONS[trigger]
    if (!map) return null
    const key = currentStatus || null
    return map[key] ?? null
}

// ─── Notification helper ──────────────────────────────────────────────────────

/**
 * Fire the "user joined" notification exactly once (G10).
 * Should be called ONLY from this service.
 */
async function notifyJoined({ inviteeId, inviterId, roomName, inviteeEmail, inviteeName }) {
    // Push notification to host/inviter
    if (inviterId) {
        notificationService.sendInviteAccepted(
            inviteeName || 'A rider',
            inviterId,
            roomName
        ).catch(err => console.error('[MembershipService] sendInviteAccepted error:', err.message))
    }

    // Email notification (replaces all sendUserJoinedEmailSafe calls in routes)
    emailService.sendUserJoinedEmailSafe({
        userId: inviteeId,
        userEmail: inviteeEmail,
        userName: inviteeName,
        roomName
    })
}

// ─── Core state machine operations ───────────────────────────────────────────

/**
 * Apply a HOST_INVITE transition.
 * @returns {{ invite, created }}
 */
async function hostInvite({ roomId, inviterId, inviteeId, room, inviter }) {
    const existing = await prisma.rideInvite.findUnique({
        where: { roomId_inviteeId: { roomId, inviteeId } },
        select: { id: true, status: true }
    })

    const currentStatus = existing?.status || null

    // REMOVED can only be re-invited if caller is room owner
    if (currentStatus === 'REMOVED' && inviterId !== room.ownerId) {
        const err = new Error('Only the room owner can re-invite a removed rider')
        err.status = 403
        err.code = 'ONLY_OWNER_CAN_REINVITE'
        throw err
    }

    const nextStatus = allowedTo('HOST_INVITE', currentStatus)
    if (!nextStatus) {
        if (currentStatus === 'ACCEPTED') {
            // Already a member, not an error
            return { invite: existing, created: false, alreadyMember: true }
        }
        const err = new Error(`Cannot invite: current membership status is ${currentStatus || 'none'}`)
        err.status = 409
        err.code = 'INVALID_TRANSITION'
        err.current = currentStatus
        throw err
    }

    let invite
    let created = false

    if (existing) {
        invite = await prisma.rideInvite.update({
            where: { id: existing.id },
            data: { status: nextStatus, inviterId, source: 'HOST_INVITE', updatedAt: new Date() }
        })
    } else {
        invite = await prisma.rideInvite.create({
            data: {
                roomId,
                inviterId,
                inviteeId,
                status: nextStatus,
                source: 'HOST_INVITE'
            }
        })
        created = true
    }

    return { invite, created }
}

/**
 * Apply an ACCEPT transition.
 * Idempotent: second call on ACCEPTED returns the existing row.
 */
async function acceptInvite({ inviteId, inviteeId }) {
    const invite = await prisma.rideInvite.findUnique({
        where: { id: inviteId },
        select: {
            id: true,
            status: true,
            inviteeId: true,
            inviterId: true,
            roomId: true,
            room: { select: { name: true, status: true, ownerId: true } }
        }
    })

    if (!invite || invite.inviteeId !== inviteeId) {
        const err = new Error('Not your invite')
        err.status = 403
        err.code = 'NOT_YOUR_INVITE'
        throw err
    }
    if (invite.room?.status === 'ENDED') {
        const err = new Error('This convoy has ended')
        err.status = 410
        err.code = 'ROOM_ENDED'
        throw err
    }

    if (invite.status === 'ACCEPTED') {
        // Idempotent
        return { invite, alreadyAccepted: true }
    }

    const nextStatus = allowedTo('ACCEPT', invite.status)
    if (!nextStatus) {
        const err = new Error(`Cannot accept: current status is ${invite.status}`)
        err.status = 409
        err.code = 'INVALID_TRANSITION'
        err.current = invite.status
        throw err
    }

    const updated = await prisma.rideInvite.update({
        where: { id: inviteId },
        data: { status: nextStatus, updatedAt: new Date() }
    })

    return { invite: updated, alreadyAccepted: false }
}

/**
 * Apply a SELF_JOIN transition (via share link or join code).
 * Never overwrites REMOVED.
 */
async function selfJoin({ roomId, inviteeId, inviterId, source, room }) {
    const existing = await prisma.rideInvite.findUnique({
        where: { roomId_inviteeId: { roomId, inviteeId } },
        select: { id: true, status: true }
    })

    const currentStatus = existing?.status || null

    if (currentStatus === 'REMOVED') {
        const err = new Error('You have been removed from this convoy by the host')
        err.status = 403
        err.code = 'REMOVED_BY_HOST'
        throw err
    }

    const nextStatus = allowedTo('SELF_JOIN', currentStatus)
    if (!nextStatus) {
        // Already ACCEPTED → idempotent
        if (currentStatus === 'ACCEPTED') {
            return { invite: existing, alreadyMember: true, isNew: false }
        }
        const err = new Error(`Cannot join: current status is ${currentStatus}`)
        err.status = 409
        err.code = 'INVALID_TRANSITION'
        err.current = currentStatus
        throw err
    }

    let invite
    let isNew = false

    if (existing) {
        invite = await prisma.rideInvite.update({
            where: { id: existing.id },
            data: {
                status: nextStatus,
                inviterId: inviterId || room?.ownerId,
                source: source || 'SHARE_LINK',
                updatedAt: new Date()
            }
        })
    } else {
        invite = await prisma.rideInvite.create({
            data: {
                roomId,
                inviterId: inviterId || room?.ownerId,
                inviteeId,
                status: nextStatus,
                source: source || 'SHARE_LINK'
            }
        })
        isNew = true
    }

    return { invite, alreadyMember: false, isNew }
}

/**
 * Apply a LEAVE transition.
 */
async function leaveRoom({ roomId, userId }) {
    const existing = await prisma.rideInvite.findUnique({
        where: { roomId_inviteeId: { roomId, inviteeId: userId } },
        select: { id: true, status: true }
    })

    const currentStatus = existing?.status || null
    const nextStatus = allowedTo('LEAVE', currentStatus)

    if (!nextStatus) {
        const err = new Error(`Cannot leave: current status is ${currentStatus || 'none'}`)
        err.status = 409
        err.code = 'INVALID_TRANSITION'
        err.current = currentStatus
        throw err
    }

    const updated = await prisma.rideInvite.update({
        where: { id: existing.id },
        data: { status: nextStatus, updatedAt: new Date() }
    })

    return { invite: updated }
}

/**
 * Apply a REMOVE transition (host removes a rider).
 */
async function removeRider({ roomId, userId, hostId, room }) {
    if (room.ownerId !== hostId) {
        const err = new Error('Only the host can remove riders')
        err.status = 403
        err.code = 'NOT_HOST'
        throw err
    }

    const existing = await prisma.rideInvite.findUnique({
        where: { roomId_inviteeId: { roomId, inviteeId: userId } },
        select: { id: true, status: true }
    })

    const currentStatus = existing?.status || null
    const nextStatus = allowedTo('REMOVE', currentStatus)

    if (!nextStatus) {
        const err = new Error(`Cannot remove: current status is ${currentStatus || 'none'}`)
        err.status = 409
        err.code = 'INVALID_TRANSITION'
        err.current = currentStatus
        throw err
    }

    let updated
    if (existing) {
        updated = await prisma.rideInvite.update({
            where: { id: existing.id },
            data: { status: nextStatus, updatedAt: new Date() }
        })
    } else {
        // Not found: create a REMOVED row so they can't rejoin via link/code
        updated = await prisma.rideInvite.create({
            data: {
                roomId,
                inviterId: hostId,
                inviteeId: userId,
                status: 'REMOVED',
                source: 'HOST_INVITE'
            }
        })
    }

    return { invite: updated }
}

module.exports = {
    hostInvite,
    acceptInvite,
    selfJoin,
    leaveRoom,
    removeRider,
    notifyJoined
}
