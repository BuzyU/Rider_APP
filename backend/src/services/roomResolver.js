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
const cacheService = require('./cacheService')

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
 * Retries up to 10 times on collision. Throws 503 CODE_GENERATION_FAILED if exhausted.
 */
async function generateUniqueJoinCode() {
    for (let attempt = 0; attempt < 10; attempt++) {
        let code = ''
        const bytes = crypto.randomBytes(JOIN_CODE_LENGTH)
        for (let i = 0; i < JOIN_CODE_LENGTH; i++) {
            code += JOIN_CODE_ALPHABET[bytes[i] % JOIN_CODE_ALPHABET.length]
        }
        let existing = null
        try {
            if (typeof prisma.room.findFirst === 'function') {
                existing = await prisma.room.findFirst({
                    where: { joinCode: code, status: { not: 'ENDED' } },
                    select: { id: true }
                })
            }
        } catch (_) {
            existing = null
        }
        if (!existing) return code
    }
    const err = new Error('Could not generate unique join code')
    err.status = 503
    err.code = 'CODE_GENERATION_FAILED'
    throw err
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
let hasLkRoomNameColumn = true

function buildBaseSelect(optsSelect = {}) {
    const base = {
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
        ...optsSelect
    }
    if (hasLkRoomNameColumn) {
        base.lkRoomName = true
    }
    return base
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

    const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

    const queryRoom = async (withLk) => {
        const select = buildBaseSelect(opts.select)
        if (!withLk) delete select.lkRoomName
        let room = null

        // 1. Try exact UUID match on id
        if (uuidPattern.test(ref)) {
            room = await prisma.room.findUnique({
                where: { id: ref },
                select
            })
        }

        // 2. Try matching by persisted LiveKit room identity among non-ENDED rooms (if column exists)
        if (!room && withLk) {
            room = await prisma.room.findFirst({
                where: {
                    lkRoomName: ref,
                    status: { not: 'ENDED' }
                },
                select
            })
        }

        // 3. Fall back to case-insensitive name among non-ENDED rooms
        if (!room) {
            room = await prisma.room.findFirst({
                where: {
                    name: { equals: ref, mode: 'insensitive' },
                    status: { not: 'ENDED' }
                },
                select
            })
        }

        // R10: If not found among active rooms and allowEnded is true, check for ended rooms
        if (!room && opts.allowEnded) {
            const orConditions = [{ name: { equals: ref, mode: 'insensitive' } }]
            if (withLk) {
                orConditions.unshift({ lkRoomName: ref })
            }
            room = await prisma.room.findFirst({
                where: { OR: orConditions },
                orderBy: { createdAt: 'desc' },
                select
            })
        }

        return room
    }

    let room = null
    try {
        room = await queryRoom(hasLkRoomNameColumn)
    } catch (err) {
        if (err.code === 'P2022' || (err.message && err.message.includes('lkRoomName'))) {
            hasLkRoomNameColumn = false
            room = await queryRoom(false)
        } else {
            throw err
        }
    }

    if (!room) {
        throw resolverError(404, 'Room not found', 'ROOM_NOT_FOUND')
    }

    // 3. If found but ENDED, return 410 unless allowEnded is explicitly set
    if (room.status === 'ENDED') {
        if (opts.allowEnded) {
            return room
        }
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
 * Mark a room as ENDED in the DB and clear joinCode.
 * Must be called BEFORE deleteRoom in LiveKit so no new token can be issued.
 * Uses conditional update to ensure only one concurrent caller transitions the room to ENDED.
 */
async function markRoomEnded(roomId) {
    const now = new Date()
    const updateResult = await prisma.room.updateMany({
        where: { id: roomId, status: { not: 'ENDED' } },
        data: { status: 'ENDED', endedAt: now, joinCode: null }
    })

    const select = { id: true, name: true, status: true, endedAt: true, joinCode: true }
    if (hasLkRoomNameColumn) select.lkRoomName = true

    let room
    try {
        room = await prisma.room.findUnique({
            where: { id: roomId },
            select
        })
    } catch (err) {
        if (err.code === 'P2022' || (err.message && err.message.includes('lkRoomName'))) {
            hasLkRoomNameColumn = false
            delete select.lkRoomName
            room = await prisma.room.findUnique({
                where: { id: roomId },
                select
            })
        } else {
            throw err
        }
    }

    if (!room) {
        throw resolverError(404, 'Room not found', 'ROOM_NOT_FOUND')
    }

    return {
        ...room,
        alreadyEnded: updateResult.count === 0
    }
}

/**
 * Check if a user is a member (owner or ACCEPTED invite) of a non-ENDED room.
 */
async function isMember(roomId, userId, options = {}) {
    const { allowEnded = false } = options
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
    if (!room) return false
    if (!allowEnded && room.status === 'ENDED') return false
    return room.ownerId === userId || (room.invites && room.invites.length > 0)
}

/**
 * Invalidate all cache keys for a room (lobby status cache).
 * Deletes keys matching lobby_status:<roomId>: prefix.
 * @param {string|object} roomOrId – room id string or room object
 */
function invalidateRoom(roomOrId) {
    const roomId = typeof roomOrId === 'string' ? roomOrId : roomOrId?.id
    if (!roomId) return
    cacheService.delPrefix(`lobby_status:${roomId}:`)
    cacheService.del(`lobby_status:${roomId}`)
    if (typeof roomOrId === 'object' && roomOrId.name) {
        cacheService.del(`lobby_status:${roomOrId.name}`)
    }
}

/**
 * Resolves the external LiveKit room name for a given room record.
 * Follows the staged room identity rollout:
 * 1. If room.lkRoomName is persisted and non-empty, use it directly (immutable per-ride identity).
 * 2. If room.lkRoomName is null/empty (legacy room), fallback to legacy configuration:
 *    process.env.ROOM_ID_MODE === 'true' ? room.id : room.name.
 *
 * @param {object} room – room record containing { id, name, lkRoomName? }
 * @returns {string} LiveKit room name
 */
function resolveLkRoomName(room) {
    if (!room) return null
    if (room.lkRoomName && typeof room.lkRoomName === 'string' && room.lkRoomName.trim()) {
        return room.lkRoomName.trim()
    }
    return process.env.ROOM_ID_MODE === 'true' ? room.id : room.name
}

module.exports = {
    resolveRoom,
    resolveLkRoomName,
    markRoomEnded,
    isMember,
    generateUniqueJoinCode,
    isReservedName,
    ResolverError,
    resolverError,
    invalidateRoom
}

