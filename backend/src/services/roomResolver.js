/**
 * roomResolver.js – Phase 1.2
 *
 * Single source of truth for room lookup across all routes.
 * Resolution order: exact UUID id → case-insensitive name among NON-ENDED rooms.
 * Never fuzzy-matches across ENDED rooms.
 *
 * Usage:
 *   const room = await resolveRoom(ref, { requireStatus: ['LOBBY', 'ACTIVE'] })
 *   // throws resolverError(404) if not found, resolverError(410) if ENDED
 */

'use strict'

const prisma = require('../db')
const crypto = require('crypto')

// ─── Unambiguous join-code alphabet (no 0/O, 1/I/l) ─────────────────────────
const JOIN_CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
const JOIN_CODE_LENGTH = 8

/**
 * Reserved convoy names (case-insensitive) — Phase 1.5
 */
const RESERVED_NAMES = new Set(['GLOBAL', 'ADMIN', 'SOS', 'SUPPORT', 'RIDERVOICE'])

function isReservedName(name) {
    return RESERVED_NAMES.has((name || '').trim().toUpperCase())
}

/**
 * Generate a unique join code for a room.
 * Retries up to 5 times on collision.
 */
async function generateUniqueJoinCode() {
    for (let attempt = 0; attempt < 5; attempt++) {
        let code = ''
        const bytes = crypto.randomBytes(JOIN_CODE_LENGTH)
        for (let i = 0; i < JOIN_CODE_LENGTH; i++) {
            code += JOIN_CODE_ALPHABET[bytes[i] % JOIN_CODE_ALPHABET.length]
        }
        const existing = await prisma.room.findFirst({
            where: { joinCode: code, status: { not: 'ENDED' } },
            select: { id: true }
        })
        if (!existing) return code
    }
    throw new Error('Failed to generate a unique join code after 5 attempts')
}

/**
 * Structured error thrown by resolveRoom.
 */
class ResolverError extends Error {
    constructor(status, message, code) {
        super(message)
        this.status = status
        this.code = code
    }
}

function resolverError(status, message, code) {
    return new ResolverError(status, message, code)
}

/**
 * Resolve a room by UUID id or (case-insensitive) name.
 *
 * @param {string} ref – room UUID or display name
 * @param {object} [opts]
 * @param {string[]} [opts.requireStatus] – if provided, room must be in one of these statuses
 * @param {object} [opts.select] – additional Prisma select fields (merged with defaults)
 * @returns {Promise<object>} room row
 * @throws {ResolverError} 404 not found, 410 ended, 403 status mismatch
 */
async function resolveRoom(ref, opts = {}) {
    if (!ref || typeof ref !== 'string') {
        throw resolverError(400, 'Room reference is required', 'INVALID_REF')
    }

    const baseSelect = {
        id: true,
        name: true,
        status: true,
        ownerId: true,
        diagnosticsEnabled: true,
        startedAt: true,
        endedAt: true,
        joinCode: true,
        origin: true,
        destination: true,
        meetupPoint: true,
        estimatedDurationMin: true,
        createdAt: true,
        ...(opts.select || {})
    }

    // 1. Try exact UUID match
    const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
    let room = null

    if (uuidPattern.test(ref)) {
        room = await prisma.room.findUnique({
            where: { id: ref },
            select: baseSelect
        })
    }

    // 2. Fall back to case-insensitive name among non-ENDED rooms
    if (!room) {
        room = await prisma.room.findFirst({
            where: {
                name: { equals: ref, mode: 'insensitive' },
                status: { not: 'ENDED' }
            },
            select: baseSelect
        })
    }

    if (!room) {
        throw resolverError(404, 'Room not found', 'ROOM_NOT_FOUND')
    }

    // 3. If found but ENDED, return 410
    if (room.status === 'ENDED') {
        throw resolverError(410, 'This convoy has ended', 'ROOM_ENDED')
    }

    // 4. Optional status requirement
    if (opts.requireStatus && !opts.requireStatus.includes(room.status)) {
        const msg = `Room is ${room.status}, expected one of: ${opts.requireStatus.join(', ')}`
        throw resolverError(403, msg, `WRONG_STATUS_${room.status}`)
    }

    return room
}

/**
 * Mark a room as ENDED in the DB.
 * Must be called BEFORE deleteRoom in LiveKit so no new token can be issued.
 */
async function markRoomEnded(roomId) {
    return prisma.room.update({
        where: { id: roomId },
        data: { status: 'ENDED', endedAt: new Date() },
        select: { id: true, name: true, status: true, endedAt: true }
    })
}

/**
 * Check if a user is a member (owner or ACCEPTED invite) of a non-ENDED room.
 */
async function isMember(roomId, userId) {
    const room = await prisma.room.findUnique({
        where: { id: roomId },
        select: {
            ownerId: true,
            status: true,
            invites: {
                where: { inviteeId: userId, status: 'ACCEPTED' },
                select: { id: true },
                take: 1
            }
        }
    })
    if (!room || room.status === 'ENDED') return false
    return room.ownerId === userId || room.invites.length > 0
}

/**
 * Invalidate all cache keys for a room (lobby status cache).
 * Cache is keyed by room.id only.
 * @param {object} room – must have id and name
 * @param {Map} lobbyStatusCache – the in-memory cache map
 */
function invalidateRoom(room, lobbyStatusCache) {
    if (!lobbyStatusCache) return
    lobbyStatusCache.delete(`lobby_status:${room.id}`)
    // Also evict any legacy name-keyed entries
    for (const key of lobbyStatusCache.keys()) {
        if (key.includes(room.name) || key.includes(room.id)) {
            lobbyStatusCache.delete(key)
        }
    }
}

module.exports = {
    resolveRoom,
    markRoomEnded,
    isMember,
    generateUniqueJoinCode,
    isReservedName,
    ResolverError,
    resolverError,
    invalidateRoom
}
