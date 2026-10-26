const { RoomServiceClient } = require('livekit-server-sdk')
const prisma = require('../db')
const { markRoomEnded, invalidateRoom, resolveLkRoomName } = require('./roomResolver')
const diagnosticService = require('./diagnosticService')

let cachedSweeperClient = null
let cachedSweeperKey = null

function getRoomServiceClient() {
    let host = process.env.LIVEKIT_URL || ''
    if (host.startsWith('wss://')) host = host.replace('wss://', 'https://')
    else if (host.startsWith('ws://')) host = host.replace('ws://', 'http://')
    if (!host) return null

    const apiKey = process.env.LIVEKIT_API_KEY || 'test_key'
    const apiSecret = process.env.LIVEKIT_API_SECRET || 'test_secret'
    const key = `${host}:${apiKey}:${apiSecret}`
    if (!cachedSweeperClient || cachedSweeperKey !== key) {
        cachedSweeperClient = new RoomServiceClient(host, apiKey, apiSecret)
        cachedSweeperKey = key
    }
    return cachedSweeperClient
}

// Track when ACTIVE rooms were first observed empty { [roomId]: firstEmptyEpochMs }
const activeRoomEmptySince = new Map()

// In-memory pending cleanups: Map of lkName -> { lkName, roomId, attempts, lastAttempt, nextRetry, error }
const pendingCleanups = new Map()

function isNotFoundError(err) {
    if (!err) return false
    if (err.status === 404 || err.statusCode === 404 || err.code === 404 || err.code === 'ROOM_NOT_FOUND') return true
    const msg = (err.message || '').toLowerCase()
    return msg.includes('not found') || msg.includes('404') || msg.includes('could not find room') || msg.includes('could not be found')
}

function recordPendingCleanup(lkName, opts = {}) {
    if (!lkName) return
    const existing = pendingCleanups.get(lkName)
    const attempts = existing ? existing.attempts + 1 : 1
    const nextRetry = Date.now() + Math.min(60000, 2000 * Math.pow(2, attempts - 1))
    pendingCleanups.set(lkName, {
        lkName,
        roomId: opts.roomId || existing?.roomId || null,
        attempts,
        lastAttempt: Date.now(),
        nextRetry,
        error: opts.error || existing?.error || 'Unknown deletion error'
    })
    console.warn(`[roomSweeper] Recorded pending cleanup for ${lkName} (attempt ${attempts}): ${opts.error || ''}`)
}

function getPendingCleanups() {
    return Array.from(pendingCleanups.values())
}

function clearPendingCleanups() {
    pendingCleanups.clear()
}

let hasLkRoomNameColumn = true
let hasLastEmptyAtColumn = true

async function countActiveClaimants(lkName) {
    if (typeof prisma.room?.count !== 'function') return 0
    if (hasLkRoomNameColumn) {
        try {
            return await prisma.room.count({
                where: {
                    status: { not: 'ENDED' },
                    OR: [
                        { lkRoomName: lkName },
                        { name: lkName }
                    ]
                }
            })
        } catch (err) {
            if (err.code === 'P2022' || (err.message && err.message.includes('lkRoomName'))) {
                hasLkRoomNameColumn = false
                return await prisma.room.count({
                    where: {
                        status: { not: 'ENDED' },
                        name: lkName
                    }
                }).catch(() => 0)
            }
            return 0
        }
    }
    return await prisma.room.count({
        where: {
            status: { not: 'ENDED' },
            name: lkName
        }
    }).catch(() => 0)
}

async function safeFindRooms(whereClause, extraSelect = {}) {
    const query = async (withLk, withLastEmpty) => {
        const sel = { id: true, name: true, ...extraSelect }
        if (withLk && hasLkRoomNameColumn) sel.lkRoomName = true
        else delete sel.lkRoomName
        if (withLastEmpty && hasLastEmptyAtColumn && extraSelect.lastEmptyAt) sel.lastEmptyAt = true
        else delete sel.lastEmptyAt
        return await prisma.room.findMany({
            where: whereClause,
            select: sel
        })
    }

    try {
        return await query(hasLkRoomNameColumn, hasLastEmptyAtColumn)
    } catch (err) {
        const isP2022 = err.code === 'P2022'
        const msg = err.message || ''
        if (isP2022 || msg.includes('lkRoomName') || msg.includes('lastEmptyAt')) {
            if (hasLkRoomNameColumn && (isP2022 || msg.includes('lkRoomName'))) {
                hasLkRoomNameColumn = false
                console.warn('[roomSweeper] lkRoomName column missing in DB; querying without lkRoomName')
            }
            if (hasLastEmptyAtColumn && (isP2022 || msg.includes('lastEmptyAt'))) {
                hasLastEmptyAtColumn = false
                console.warn('[roomSweeper] lastEmptyAt column missing in DB; querying without lastEmptyAt')
            }
            return await query(hasLkRoomNameColumn, hasLastEmptyAtColumn).catch(async () => {
                hasLkRoomNameColumn = false
                hasLastEmptyAtColumn = false
                return await query(false, false)
            })
        }
        throw err
    }
}

async function deleteLiveKitRoom(lkName, roomId = null, client = null) {
    if (!lkName) return
    const livekitClient = client || getRoomServiceClient()
    if (!livekitClient || typeof livekitClient.deleteRoom !== 'function') return

    // Ambiguous mapping defense: do not delete external room if multiple non-ended DB rides claim this identity
    if (typeof prisma.room?.count === 'function') {
        const activeCount = await countActiveClaimants(lkName)

        if (activeCount > 0) {
            console.warn(`[LiveKit] Blocked deletion of ${lkName}: ${activeCount} active ride(s) still claim this identity`)
            return
        }
    }

    try {
        if (typeof livekitClient.listParticipants === 'function' && typeof livekitClient.removeParticipant === 'function') {
            try {
                const participants = await livekitClient.listParticipants(lkName)
                if (Array.isArray(participants)) {
                    for (const p of participants) {
                        const id = p.identity || p.sid
                        if (id) {
                            await livekitClient.removeParticipant(lkName, id).catch(() => null)
                        }
                    }
                }
            } catch (_) {}
        }
        await livekitClient.deleteRoom(lkName)
        pendingCleanups.delete(lkName)
    } catch (err) {
        if (isNotFoundError(err)) {
            // Room already deleted or absent -> treat as success
            pendingCleanups.delete(lkName)
            return
        }
        recordPendingCleanup(lkName, { roomId, error: err.message })
    }
}

async function retryPendingCleanups(client = null) {
    const livekitClient = client || getRoomServiceClient()
    if (!livekitClient || typeof livekitClient.deleteRoom !== 'function') return []

    const now = Date.now()
    const retried = []

    for (const [lkName, entry] of pendingCleanups.entries()) {
        if (now < entry.nextRetry) continue

        // Ambiguous mapping check
        if (typeof prisma.room?.count === 'function') {
            const activeCount = await countActiveClaimants(lkName)

            if (activeCount > 0) {
                console.warn(`[roomSweeper] Blocked pending retry for ${lkName}: ${activeCount} active ride(s) still claim identity`)
                continue
            }
        }

        try {
            await livekitClient.deleteRoom(lkName)
            pendingCleanups.delete(lkName)
            retried.push({ lkName, status: 'CLEARED' })
        } catch (err) {
            if (isNotFoundError(err)) {
                pendingCleanups.delete(lkName)
                retried.push({ lkName, status: 'CLEARED' })
            } else {
                entry.attempts += 1
                entry.lastAttempt = now
                entry.error = err.message
                entry.nextRetry = now + Math.min(60000, 2000 * Math.pow(2, entry.attempts - 1))
                retried.push({ lkName, status: 'FAILED', attempts: entry.attempts, error: err.message })
            }
        }
    }

    return retried
}

async function sweepStaleRooms(deps = {}) {
    const livekitClient = deps.roomServiceClient || getRoomServiceClient()
    const now = new Date()
    const sixHoursAgo = new Date(now.getTime() - 6 * 60 * 60 * 1000)
    const twelveHoursAgo = new Date(now.getTime() - 12 * 60 * 60 * 1000)
    const fifteenMinMs = 15 * 60 * 1000

    const swept = []

    try {
        // Retry any pending cleanups from transient LiveKit failures
        await retryPendingCleanups(livekitClient)
        if (typeof prisma.room?.findMany === 'function') {
            const staleLobbies = await safeFindRooms({
                status: 'LOBBY',
                createdAt: { lte: sixHoursAgo },
                invites: {
                    none: { status: 'ACCEPTED' }
                }
            })

            for (const lobby of staleLobbies) {
                const updated = await prisma.room.updateMany({
                    where: { id: lobby.id, status: 'LOBBY' },
                    data: { status: 'ENDED', endedAt: now, joinCode: null }
                })
                if (updated.count > 0) {
                    await finalizeSweptRoom(lobby, 'EXPIRED_LOBBY', livekitClient)
                    swept.push({ id: lobby.id, reason: 'EXPIRED_LOBBY' })
                }
            }

            // 1b. R15: LOBBY older than 24h regardless of accepted riders -> ENDED
            const twentyFourHoursAgo = new Date(now.getTime() - 24 * 60 * 60 * 1000)
            const ancientLobbies = await safeFindRooms({
                status: 'LOBBY',
                createdAt: { lte: twentyFourHoursAgo }
            })

            for (const lobby of ancientLobbies) {
                if (swept.some(s => s.id === lobby.id)) continue
                const updated = await prisma.room.updateMany({
                    where: { id: lobby.id, status: 'LOBBY' },
                    data: { status: 'ENDED', endedAt: now, joinCode: null }
                })
                if (updated.count > 0) {
                    await finalizeSweptRoom(lobby, 'EXPIRED_LOBBY', livekitClient)
                    swept.push({ id: lobby.id, reason: 'EXPIRED_LOBBY' })
                }
            }

            // 2. ACTIVE older than 12h -> ENDED (reason ABANDONED) unless riders are connected (F-18)
            const ancientActive = await safeFindRooms({
                status: 'ACTIVE',
                OR: [
                    { startedAt: { lte: twelveHoursAgo } },
                    { startedAt: null, createdAt: { lte: twelveHoursAgo } }
                ]
            })

            for (const room of ancientActive) {
                const targetName = resolveLkRoomName(room)
                let connectedCount = null
                try {
                    if (livekitClient && typeof livekitClient.listParticipants === 'function') {
                        const participants = await livekitClient.listParticipants(targetName)
                        if (Array.isArray(participants)) {
                            connectedCount = participants.length
                        }
                    }
                } catch (e) {
                    connectedCount = null
                }

                // F-18: If riders are currently connected in LiveKit, skip ending this active room
                if (connectedCount !== null && connectedCount > 0) {
                    console.log(`[roomSweeper] Active room ${room.id} (${room.name}) is older than 12h but has ${connectedCount} connected participants; skipping sweep.`)
                    continue
                }

                const updated = await prisma.room.updateMany({
                    where: { id: room.id, status: 'ACTIVE' },
                    data: { status: 'ENDED', endedAt: now, joinCode: null }
                })
                if (updated.count > 0) {
                    await finalizeSweptRoom(room, 'ABANDONED', livekitClient)
                    swept.push({ id: room.id, reason: 'ABANDONED' })
                }
            }

            // 3. ACTIVE with listParticipants empty or lastEmptyAt older than 15 min (R1 + R15)
            const activeRooms = await safeFindRooms(
                { status: 'ACTIVE' },
                { lastEmptyAt: true }
            )

            for (const room of activeRooms) {
                const targetName = resolveLkRoomName(room)
                let participants = null
                try {
                    if (livekitClient && typeof livekitClient.listParticipants === 'function') {
                        participants = await livekitClient.listParticipants(targetName)
                    }
                } catch (e) {
                    // If LiveKit is unreachable, do nothing
                    participants = null
                }

                if (Array.isArray(participants)) {
                    if (participants.length === 0) {
                        const emptySince = room.lastEmptyAt
                            ? new Date(room.lastEmptyAt).getTime()
                            : (activeRoomEmptySince.get(room.id) || Date.now())
                        activeRoomEmptySince.set(room.id, emptySince)

                        if (hasLastEmptyAtColumn && !room.lastEmptyAt && typeof prisma.room?.update === 'function') {
                            await prisma.room.update({
                                where: { id: room.id },
                                data: { lastEmptyAt: new Date(emptySince) }
                            }).catch(err => {
                                if (err.code === 'P2022' || (err.message && err.message.includes('lastEmptyAt'))) {
                                    hasLastEmptyAtColumn = false
                                }
                            })
                        }

                        if (Date.now() - emptySince >= fifteenMinMs) {
                            const updated = await prisma.room.updateMany({
                                where: { id: room.id, status: 'ACTIVE' },
                                data: { status: 'ENDED', endedAt: now, joinCode: null }
                            })
                            if (updated.count > 0) {
                                await finalizeSweptRoom(room, 'ABANDONED', livekitClient)
                                activeRoomEmptySince.delete(room.id)
                                swept.push({ id: room.id, reason: 'ABANDONED' })
                            }
                        }
                    } else {
                        activeRoomEmptySince.delete(room.id)
                        if (hasLastEmptyAtColumn && room.lastEmptyAt && typeof prisma.room?.update === 'function') {
                            await prisma.room.update({
                                where: { id: room.id },
                                data: { lastEmptyAt: null }
                            }).catch(err => {
                                if (err.code === 'P2022' || (err.message && err.message.includes('lastEmptyAt'))) {
                                    hasLastEmptyAtColumn = false
                                }
                            })
                        }
                    }
                }
            }
        }
    } catch (err) {
        console.warn('[roomSweeper] sweep cycle error:', err.message)
    }

    return swept
}

async function finalizeSweptRoom(room, reason, livekitClient) {
    console.log(`[roomSweeper] Ended room ${room.id} (${room.name}) - reason: ${reason}`)
    try {
        if (typeof prisma.roomJoinToken?.updateMany === 'function') {
            await prisma.roomJoinToken.updateMany({
                where: { roomId: room.id, revokedAt: null },
                data: { revokedAt: new Date() }
            })
        }
        invalidateRoom(room.id)

        const lkName = resolveLkRoomName(room)
        await deleteLiveKitRoom(lkName, room.id, livekitClient)

        if (diagnosticService && typeof diagnosticService.scheduleIdleRunClose === 'function') {
            await diagnosticService.scheduleIdleRunClose(room.name, room.id).catch(() => null)
        }
    } catch (err) {
        console.warn(`[roomSweeper] finalize error for ${room.id}:`, err.message)
    }
}

let sweeperInterval = null

function startRoomSweeper(deps = {}) {
    sweepStaleRooms(deps).catch(err => console.warn('[roomSweeper] initial sweep error:', err.message))

    sweeperInterval = setInterval(() => {
        sweepStaleRooms(deps).catch(err => console.warn('[roomSweeper] interval sweep error:', err.message))
    }, 5 * 60 * 1000)

    if (sweeperInterval.unref) {
        sweeperInterval.unref()
    }
}

function stopRoomSweeper() {
    if (sweeperInterval) {
        clearInterval(sweeperInterval)
        sweeperInterval = null
    }
}

module.exports = {
    sweepStaleRooms,
    startRoomSweeper,
    stopRoomSweeper,
    deleteLiveKitRoom,
    recordPendingCleanup,
    getPendingCleanups,
    clearPendingCleanups,
    retryPendingCleanups,
    _activeRoomEmptySince: activeRoomEmptySince
}
