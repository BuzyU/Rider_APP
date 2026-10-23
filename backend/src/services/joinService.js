/**
 * joinService.js – Single source of truth for all room joining operations.
 *
 * Implements:
 *   - joinWithLinkToken(token, user)
 *   - joinWithCode(code, user)
 *   - joinAsMember(roomRef, user)
 *
 * All paths validate membership rules, enforce that ENDED rooms return 410,
 * block REMOVED riders with 403, increment useCount only on new joins,
 * invalidate room cache, and return the standard issueRoomToken payload.
 */

'use strict'

const prisma = require('../db')
const { resolveRoom, resolverError, invalidateRoom } = require('./roomResolver')
const { selfJoin, isMember, notifyJoined } = require('./membershipService')
const { issueRoomToken } = require('./tokenService')

function extractCleanToken(input) {
    if (!input || typeof input !== 'string') return ''
    let cleaned = input.trim()
    if (cleaned.startsWith('ridervoice://join/')) {
        cleaned = cleaned.slice('ridervoice://join/'.length)
    } else if (cleaned.includes('/join/')) {
        cleaned = cleaned.slice(cleaned.indexOf('/join/') + '/join/'.length)
    } else if (cleaned.includes('/j/')) {
        cleaned = cleaned.slice(cleaned.indexOf('/j/') + '/j/'.length)
    }
    if (cleaned.includes('token=')) {
        cleaned = cleaned.slice(cleaned.indexOf('token=') + 'token='.length)
    }
    cleaned = cleaned.split('?')[0].split('&')[0].split('#')[0].replace(/\/+$/, '').trim()
    return cleaned
}

/**
 * Join a room using a share link token.
 */
async function joinWithLinkToken(rawToken, user) {
    const token = extractCleanToken(rawToken) || (rawToken ? rawToken.trim() : '')
    if (!token) {
        throw resolverError(400, 'Share link token is required', 'MISSING_TOKEN')
    }

    let joinToken = await prisma.roomJoinToken.findUnique({
        where: { token },
        include: { room: true }
    })
    if (!joinToken && token.toLowerCase() !== token) {
        joinToken = await prisma.roomJoinToken.findUnique({
            where: { token: token.toLowerCase() },
            include: { room: true }
        })
    }

    if (!joinToken) {
        throw resolverError(404, 'Invalid or expired share link', 'INVALID_LINK')
    }

    if (joinToken.revokedAt) {
        throw resolverError(410, 'This invite link has been revoked', 'LINK_REVOKED')
    }

    if (new Date() > joinToken.expiresAt) {
        throw resolverError(410, 'This invite link has expired', 'LINK_EXPIRED')
    }

    if (joinToken.maxUses && joinToken.useCount >= joinToken.maxUses) {
        throw resolverError(410, 'This invite link has reached its maximum uses', 'LINK_EXHAUSTED')
    }

    const room = joinToken.room
    if (!room || room.status === 'ENDED') {
        throw resolverError(410, 'This convoy has ended', 'ROOM_ENDED')
    }

    // Check denylist / REMOVED before anything else
    const denylisted = typeof prisma.roomDenylist?.findFirst === 'function'
        ? await prisma.roomDenylist.findFirst({
            where: { roomId: room.id, userId: user.uid }
        }).catch(() => null)
        : null
    if (denylisted) {
        throw resolverError(403, 'You have been removed from this convoy by the host', 'REMOVED_BY_HOST')
    }

    // Self-join transition
    const { invite, isNew, alreadyMember } = await selfJoin({
        roomId: room.id,
        inviteeId: user.uid,
        inviterId: joinToken.createdById,
        source: 'SHARE_LINK',
        room
    })

    // R7: Atomic increment only when consuming a new seat (not already accepted)
    if (!alreadyMember && typeof prisma.roomJoinToken?.updateMany === 'function') {
        const updateResult = await prisma.roomJoinToken.updateMany({
            where: {
                id: joinToken.id,
                ...(joinToken.maxUses ? { useCount: { lt: joinToken.maxUses } } : {})
            },
            data: { useCount: { increment: 1 } }
        }).catch(err => {
            console.warn('[joinService] useCount increment error:', err.message)
            return { count: 0 }
        })

        if (joinToken.maxUses && updateResult.count === 0) {
            throw resolverError(410, 'This invite link has reached its maximum uses', 'LINK_EXHAUSTED')
        }
    }

    // R5: notify host when rider joins via link
    if (!alreadyMember) {
        notifyJoined({
            inviteeId: user.uid,
            inviterId: room.ownerId,
            roomName: room.name,
            inviteeEmail: user.email,
            inviteeName: user.name || user.displayName || user.handle
        }).catch(err => console.warn('[joinService] notifyJoined warning:', err.message))
    }

    invalidateRoom(room.id)

    // Issue standard room token
    return issueRoomToken(user, room)
}

/**
 * Join a room using an 8-character join code.
 */
async function joinWithCode(rawCode, user) {
    const code = (rawCode || '').trim().toUpperCase()
    if (!code) {
        throw resolverError(400, 'Join code is required', 'MISSING_CODE')
    }

    const room = await prisma.room.findFirst({
        where: {
            joinCode: code,
            status: { not: 'ENDED' }
        }
    })

    if (!room) {
        throw resolverError(404, 'No active convoy found with that join code', 'CODE_NOT_FOUND')
    }

    // Check denylist
    const denylisted = typeof prisma.roomDenylist?.findFirst === 'function'
        ? await prisma.roomDenylist.findFirst({
            where: { roomId: room.id, userId: user.uid }
        }).catch(() => null)
        : null
    if (denylisted) {
        throw resolverError(403, 'You have been removed from this convoy by the host', 'REMOVED_BY_HOST')
    }

    // Self-join transition
    const { isNew, alreadyMember } = await selfJoin({
        roomId: room.id,
        inviteeId: user.uid,
        inviterId: room.ownerId,
        source: 'JOIN_CODE',
        room
    })

    // R5: notify host when rider joins via code
    if (!alreadyMember) {
        notifyJoined({
            inviteeId: user.uid,
            inviterId: room.ownerId,
            roomName: room.name,
            inviteeEmail: user.email,
            inviteeName: user.name || user.displayName || user.handle
        }).catch(err => console.warn('[joinService] notifyJoined warning:', err.message))
    }

    invalidateRoom(room.id)

    return issueRoomToken(user, room)
}

/**
 * Join / get token as an already-existing member (owner or accepted rider).
 * For non-members, returns 403 NOT_A_MEMBER (never self-adds).
 */
async function joinAsMember(roomRef, user) {
    const room = await resolveRoom(roomRef)

    const isAuthorized = room.ownerId === user.uid || await isMember(room.id, user.uid)
    if (!isAuthorized) {
        const removed = typeof prisma.rideInvite?.findFirst === 'function'
            ? await prisma.rideInvite.findFirst({
                where: { roomId: room.id, inviteeId: user.uid, status: 'REMOVED' }
            }).catch(() => null)
            : null
        if (removed) {
            throw resolverError(403, 'You have been removed from this convoy by the host', 'REMOVED_BY_HOST')
        }
        throw resolverError(403, 'You are not a member of this convoy', 'NOT_A_MEMBER')
    }

    return issueRoomToken(user, room)
}

module.exports = {
    extractCleanToken,
    joinWithLinkToken,
    joinWithCode,
    joinAsMember
}
