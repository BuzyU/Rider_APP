const { RoomServiceClient } = require('livekit-server-sdk')
const prisma = require('../db')
const { markRoomEnded, invalidateRoom } = require('./roomResolver')
const diagnosticService = require('./diagnosticService')

function getRoomServiceClient() {
    let host = process.env.LIVEKIT_URL || ''
    if (host.startsWith('wss://')) host = host.replace('wss://', 'https://')
    else if (host.startsWith('ws://')) host = host.replace('ws://', 'http://')
    return new RoomServiceClient(host, process.env.LIVEKIT_API_KEY || 'test_key', process.env.LIVEKIT_API_SECRET || 'test_secret')
}

// Track when ACTIVE rooms were first observed empty { [roomId]: firstEmptyEpochMs }
const activeRoomEmptySince = new Map()

async function sweepStaleRooms(deps = {}) {
    const livekitClient = deps.roomServiceClient || getRoomServiceClient()
    const now = new Date()
    const sixHoursAgo = new Date(now.getTime() - 6 * 60 * 60 * 1000)
    const twelveHoursAgo = new Date(now.getTime() - 12 * 60 * 60 * 1000)
    const fifteenMinMs = 15 * 60 * 1000

    const swept = []

    try {
        // 1. LOBBY older than 6h with no accepted riders -> ENDED (reason EXPIRED_LOBBY)
        if (typeof prisma.room?.findMany === 'function') {
            const staleLobbies = await prisma.room.findMany({
                where: {
                    status: 'LOBBY',
                    createdAt: { lte: sixHoursAgo },
                    invites: {
                        none: { status: 'ACCEPTED' }
                    }
                },
                select: { id: true, name: true }
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
            const ancientLobbies = await prisma.room.findMany({
                where: {
                    status: 'LOBBY',
                    createdAt: { lte: twentyFourHoursAgo }
                },
                select: { id: true, name: true }
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

            // 2. ACTIVE older than 12h -> ENDED (reason ABANDONED)
            const ancientActive = await prisma.room.findMany({
                where: {
                    status: 'ACTIVE',
                    OR: [
                        { startedAt: { lte: twelveHoursAgo } },
                        { startedAt: null, createdAt: { lte: twelveHoursAgo } }
                    ]
                },
                select: { id: true, name: true }
            })

            for (const room of ancientActive) {
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
            const activeRooms = await prisma.room.findMany({
                where: { status: 'ACTIVE' },
                select: { id: true, name: true, lastEmptyAt: true }
            })

            for (const room of activeRooms) {
                const targetName = process.env.ROOM_ID_MODE === 'true' ? room.id : room.name
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

                        if (!room.lastEmptyAt && typeof prisma.room?.update === 'function') {
                            await prisma.room.update({
                                where: { id: room.id },
                                data: { lastEmptyAt: new Date(emptySince) }
                            }).catch(() => null)
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
                        if (room.lastEmptyAt && typeof prisma.room?.update === 'function') {
                            await prisma.room.update({
                                where: { id: room.id },
                                data: { lastEmptyAt: null }
                            }).catch(() => null)
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

        const lkName = process.env.ROOM_ID_MODE === 'true' ? room.id : room.name
        if (livekitClient && typeof livekitClient.deleteRoom === 'function') {
            await livekitClient.deleteRoom(lkName).catch(() => null)
        }

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
    _activeRoomEmptySince: activeRoomEmptySince
}
