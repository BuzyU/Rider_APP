/**
 * tokenService.js – Phase 1.4
 *
 * Single function used by ALL token-issuing endpoints:
 *   /rooms/room/token, /lobby/join-token, /lobby/join-via-token,
 *   /lobby/:room/start, /api/rooms/token/refresh
 *
 * Authorization: caller must be owner or ACCEPTED member.
 * Room must be non-ENDED.
 * Token TTL: 2 hours. Metadata: { roomId, role }.
 * roomCreate: false — a token can NEVER create a LiveKit room.
 */

'use strict'

const { AccessToken, RoomServiceClient } = require('livekit-server-sdk')
const prisma = require('../db')

const LIVEKIT_TOKEN_TTL = '2h'
const TOKEN_TTL_MS = 2 * 60 * 60 * 1000

function getLiveKitHttpUrl() {
    const raw = process.env.LIVEKIT_URL || ''
    if (!raw) {
        console.error('[TokenService] LIVEKIT_URL is not set. Tokens will have no wss URL.')
    }
    return raw.replace('wss://', 'https://').replace('ws://', 'http://')
}

/**
 * Resolve the display name for a user.
 */
async function resolveDisplayName(uid, fallback = 'Rider') {
    try {
        const u = await prisma.user.findUnique({
            where: { id: uid },
            select: { displayName: true, handle: true }
        })
        return u?.displayName || (u?.handle ? `@${u.handle}` : null) || fallback
    } catch {
        return fallback
    }
}

/**
 * Ensure the LiveKit room exists for a non-ENDED DB room.
 * Creates it idempotently if it was dropped by the empty-timeout.
 * Never creates for ENDED rooms.
 *
 * @param {string} lkRoomName – LiveKit room name (room.id in ROOM_ID_MODE, room.name otherwise)
 * @param {string} roomId – DB room UUID (stored in LK room metadata)
 */
async function ensureLiveKitRoom(lkRoomName, roomId) {
    const httpUrl = getLiveKitHttpUrl()
    if (!httpUrl || !process.env.LIVEKIT_API_KEY || !process.env.LIVEKIT_API_SECRET) return

    try {
        const svc = new RoomServiceClient(httpUrl, process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET)
        await svc.createRoom({
            name: lkRoomName,
            emptyTimeout: 600,       // 10 min: re-creates on first connect if empty
            departureTimeout: 120,   // 2 min: allow brief disconnects
            maxParticipants: 50,
            metadata: JSON.stringify({ roomId })
        })
    } catch (err) {
        // Room already exists → not an error
        if (!err.message?.includes('already exists') && !err.message?.includes('409')) {
            console.warn('[TokenService] ensureLiveKitRoom warning:', err.message)
        }
    }
}

/**
 * Compute the LiveKit room name based on ROOM_ID_MODE.
 *   ROOM_ID_MODE=true  → room.id (UUID)
 *   ROOM_ID_MODE=false → room.name (legacy; also stored in token metadata so webhook can check)
 */
function lkRoomName(room) {
    return process.env.ROOM_ID_MODE === 'true' ? room.id : room.name
}

/**
 * Issue a LiveKit room token for a verified member.
 *
 * Authorization: caller must be the owner OR have an ACCEPTED invite.
 * Room must not be ENDED.
 *
 * @param {object} user – { uid, name?, email? } from authMiddleware
 * @param {object} room – resolved room row (must have id, name, status, ownerId, diagnosticsEnabled)
 * @returns {Promise<{token, livekitUrl, roomId, roomName, diagnosticsEnabled, testNumber, expiresAt}>}
 */
async function issueRoomToken(user, room) {
    // 1. Auth check
    const isOwner = room.ownerId === user.uid
    if (!isOwner) {
        const invite = await prisma.rideInvite.findUnique({
            where: { roomId_inviteeId: { roomId: room.id, inviteeId: user.uid } },
            select: { status: true }
        })
        // Denylist: a REMOVED rider can never get a token
        if (invite?.status === 'REMOVED') {
            const err = new Error('You have been removed from this convoy by the host')
            err.status = 403
            err.code = 'REMOVED_BY_HOST'
            throw err
        }
        if (!invite || invite.status !== 'ACCEPTED') {
            const err = new Error('Not a member of this convoy')
            err.status = 403
            err.code = 'NOT_A_MEMBER'
            throw err
        }
    }

    // 3. Resolve display name
    const displayName = await resolveDisplayName(user.uid, user.name || 'Rider')

    // 4. Compute LiveKit room name
    const roomName = lkRoomName(room)

    // 5. Ensure the LiveKit room exists (idempotent; creates if empty-timeout dropped it)
    await ensureLiveKitRoom(roomName, room.id)

    // 6. Build and sign token
    const role = isOwner ? 'host' : 'rider'
    const at = new AccessToken(
        process.env.LIVEKIT_API_KEY,
        process.env.LIVEKIT_API_SECRET,
        {
            identity: user.uid,
            name: displayName,
            ttl: LIVEKIT_TOKEN_TTL,
            metadata: JSON.stringify({ roomId: room.id, role })
        }
    )
    at.addGrant({
        roomJoin: true,
        room: roomName,
        roomCreate: false,          // A1: never allow implicit room creation
        canPublish: true,
        canSubscribe: true,
        canPublishData: true
    })
    const token = await at.toJwt()

    // 7. Find active diagnostic run
    const activeRun = await prisma.diagnosticRun.findFirst({
        where: { convoyId: room.id, status: 'ACTIVE' },
        select: { id: true, testNumber: true }
    }).catch(() => null)

    const expiresAt = new Date(Date.now() + TOKEN_TTL_MS).toISOString()

    return {
        token,
        livekitUrl: process.env.LIVEKIT_URL || '',
        roomId: room.id,
        roomName: room.name,        // always the display name, not the LK room name
        diagnosticsEnabled: room.diagnosticsEnabled ?? activeRun != null,
        testNumber: activeRun?.testNumber || null,
        expiresAt,
        role
    }
}

module.exports = { issueRoomToken, ensureLiveKitRoom, lkRoomName, resolveDisplayName }
