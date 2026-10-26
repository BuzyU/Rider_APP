/**
 * lobbyRoutes.js - Phase 1 / 1B rewrite
 *
 * All bugs fixed in this file:
 *   A1  - roomCreate:false on every token (delegated to tokenService)
 *   A2  - single issueRoomToken() for all 4 token paths
 *   A3  - /end marks DB ENDED before calling deleteRoom
 *   A4  - /create never revives stale rows; always creates fresh
 *   G1  - join-via-token blocks REMOVED riders
 *   G2  - join-via-token blocks DECLINED? No - DECLINED can self-join
 *   G3  - @@unique([roomId,inviteeId]) in schema; handled by membership service
 *   G4  - join-via-token stores actual link-creator as inviterId (createdById)
 *   G5  - /create: no more stale-room revival
 *   G6  - all membership paths check room.status != ENDED
 *   G8  - accept is atomic (no pre-remove before token call)
 *   G9  - invite errors surfaced per-rider
 *   G10 - "joined" email fires once from membershipService.notifyJoined
 *   G11 - /status only shows owner + ACCEPTED members (not PENDING/DECLINED)
 *   H1  - join-by-name removed; join code or share-link token required
 *   H2  - /create persists origin/destination/meetupPoint/estimatedDurationMin
 *   H3  - 410 returned for ENDED rooms everywhere
 *   H4  - canStart computed from real state: host + >=1 ACCEPTED or allowSolo
 *   H5  - transfer-host: newHostId validated as ACCEPTED member
 *   H6  - share tokens revoked on /end
 *   B6  - grace-close uses persisted closeAt on DB row (not in-memory setTimeout only)
 */

'use strict'

const express = require('express')
const router = express.Router()
const prisma = require('../db')
const { RoomServiceClient } = require('livekit-server-sdk')
const diagnosticService = require('../services/diagnosticService')
const emailService = require('../services/emailService')
const notificationService = require('../services/notificationService')
const cacheService = require('../services/cacheService')
const { resolveRoom, markRoomEnded, generateUniqueJoinCode, isReservedName, ResolverError, invalidateRoom, lkRoomName: _lkRoomName } = require('../services/roomResolver')
const { issueRoomToken, lkRoomName } = require('../services/tokenService')
const { selfJoin, removeRider, leaveRoom, notifyJoined } = require('../services/membershipService')
const { joinLimiter, recordFailedJoinCode } = require('../middleware/rateLimiter')
const { joinWithLinkToken, joinWithCode, joinAsMember, extractCleanToken } = require('../services/joinService')
const { deleteLiveKitRoom } = require('../services/roomSweeper')
const { setSchemaHealth } = require('./healthRoute')

// --- Helpers -----------------------------------------------------------------

function getLiveKitHttpUrl() {
    const raw = process.env.LIVEKIT_URL || ''
    return raw.replace('wss://', 'https://').replace('ws://', 'http://')
}

async function sendLiveKitData(lkName, payload, topic = 'rv') {
    try {
        const httpUrl = getLiveKitHttpUrl()
        if (!httpUrl || !process.env.LIVEKIT_API_KEY || !process.env.LIVEKIT_API_SECRET) return
        const svc = new RoomServiceClient(httpUrl, process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET)
        const data = Buffer.from(JSON.stringify(payload))
        await svc.sendData(lkName, data, 0, { topic })
    } catch (err) {
        console.warn(`[LiveKit] sendData notice for ${lkName}:`, err.message)
    }
}

async function removeLiveKitParticipant(lkRoomName, userId) {
    try {
        const httpUrl = getLiveKitHttpUrl()
        if (!httpUrl || !process.env.LIVEKIT_API_KEY || !process.env.LIVEKIT_API_SECRET) return
        const svc = new RoomServiceClient(httpUrl, process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET)
        await svc.removeParticipant(lkRoomName, userId)
    } catch (err) {
        console.warn(`[LiveKit] removeParticipant notice for ${userId}:`, err.message)
    }
}

const crypto = require('crypto')

function computeCreatePayloadHash(payload) {
    const normalized = {
        allowSolo: payload.allowSolo === true,
        convoyName: (payload.convoyName || '').trim().toLowerCase(),
        destination: payload.destination ? payload.destination.trim() : null,
        diagnosticsEnabled: payload.diagnosticsEnabled === true,
        estimatedDurationMin: payload.estimatedDurationMin ? parseInt(payload.estimatedDurationMin, 10) : null,
        meetupPoint: payload.meetupPoint ? payload.meetupPoint.trim() : null,
        origin: payload.origin ? payload.origin.trim() : null
    }
    return crypto.createHash('sha256').update(JSON.stringify(normalized)).digest('hex')
}

// In-memory fallback only when database model is unavailable in tests
const inMemoryFallbackRequests = new Map()

// --- POST /lobby/create -------------------------------------------------------

router.post('/create', async (req, res, next) => {
    const hostId = req.user.uid
    const {
        convoyName,
        diagnosticsEnabled,
        origin,
        destination,
        meetupPoint,
        estimatedDurationMin,
        allowSolo,
        clientRequestId
    } = req.body

    if (!convoyName || typeof convoyName !== 'string') {
        return res.status(400).json({ error: 'convoyName is required' })
    }

    const trimmedName = convoyName.trim()
    if (!trimmedName) {
        return res.status(400).json({ error: 'convoyName cannot be blank' })
    }

    // H1 / Phase 1.5 - block reserved names
    if (isReservedName(trimmedName)) {
        return res.status(400).json({ error: `"${trimmedName}" is a reserved convoy name` })
    }

    const payloadHash = computeCreatePayloadHash({
        convoyName,
        diagnosticsEnabled,
        origin,
        destination,
        meetupPoint,
        estimatedDurationMin,
        allowSolo
    })

    let rideCreateRequestTableUnavailable = false
    // 1. Check durable idempotency if clientRequestId is provided
    if (clientRequestId && typeof clientRequestId === 'string') {
        try {
            if (typeof prisma.rideCreateRequest?.findUnique === 'function') {
                const existingReq = await prisma.rideCreateRequest.findUnique({
                    where: {
                        ownerId_clientRequestId: {
                            ownerId: hostId,
                            clientRequestId
                        }
                    },
                    include: {
                        room: {
                            select: {
                                id: true,
                                name: true,
                                ownerId: true,
                                diagnosticsEnabled: true,
                                joinCode: true,
                                origin: true,
                                destination: true,
                                meetupPoint: true,
                                estimatedDurationMin: true,
                                status: true
                            }
                        }
                    }
                })

                if (existingReq) {
                    if (existingReq.payloadHash !== payloadHash) {
                        return res.status(409).json({
                            error: 'Idempotency key reused with different request payload',
                            code: 'IDEMPOTENCY_KEY_REUSED'
                        })
                    }
                    if (existingReq.room) {
                        return res.status(200).json({
                            roomId: existingReq.room.id,
                            convoyName: existingReq.room.name,
                            hostId: existingReq.room.ownerId,
                            diagnosticsEnabled: existingReq.room.diagnosticsEnabled,
                            joinCode: existingReq.room.joinCode,
                            origin: existingReq.room.origin,
                            destination: existingReq.room.destination,
                            meetupPoint: existingReq.room.meetupPoint,
                            estimatedDurationMin: existingReq.room.estimatedDurationMin
                        })
                    }
                }
            }
        } catch (err) {
            if (err.code === 'P2021' || (err.message && err.message.includes('RideCreateRequest'))) {
                rideCreateRequestTableUnavailable = true
            }
            console.warn('[LobbyCreate] Idempotency lookup notice:', err.message)
        }

        // Fallback to in-memory idempotency cache if DB lookup didn't match or failed
        if (inMemoryFallbackRequests.has(`${hostId}:${clientRequestId}`)) {
            const cached = inMemoryFallbackRequests.get(`${hostId}:${clientRequestId}`)
            if (cached.payloadHash !== payloadHash) {
                return res.status(409).json({
                    error: 'Idempotency key reused with different request payload',
                    code: 'IDEMPOTENCY_KEY_REUSED'
                })
            }
            return res.status(200).json(cached.response)
        }
    }

    try {
        const findRoomByName = async (name) => {
            let found = null
            if (typeof prisma.room.findFirst === 'function') {
                found = await prisma.room.findFirst({
                    where: {
                        name: { equals: name, mode: 'insensitive' },
                        status: { not: 'ENDED' }
                    },
                    select: { id: true, ownerId: true, name: true }
                }).catch(() => null)
            }
            if (!found && typeof prisma.room.findUnique === 'function') {
                found = await prisma.room.findUnique({ where: { name } }).catch(() => null)
            }
            return found
        }

        let finalConvoyName = trimmedName
        const existing = await findRoomByName(trimmedName)
        if (existing) {
            if (existing.ownerId && existing.ownerId !== hostId) {
                return res.status(409).json({
                    error: 'A ride with this name already exists',
                    code: 'ROOM_NAME_TAKEN'
                })
            }
            if (existing.ownerId === hostId && clientRequestId) {
                return res.status(409).json({
                    error: 'You already have an active ride with this name',
                    code: 'ROOM_NAME_TAKEN'
                })
            }
            // Suffixing only for requests without clientRequestId (legacy / backwards compatibility)
            let suffix = 2
            let candidate = `${trimmedName}-${suffix}`
            while (await findRoomByName(candidate)) {
                suffix++
                candidate = `${trimmedName}-${suffix}`
            }
            finalConvoyName = candidate
        }

        const diagBool = diagnosticsEnabled === true
        const joinCode = await generateUniqueJoinCode()

        const newRoomId = crypto.randomUUID()
        // P0-A (F-01): Canonical, unique, immutable LiveKit room identity for all newly created rooms
        const initialLkRoomName = `rv_${newRoomId}`

        const createRoomRecord = async (client, withLkRoomName) => {
            const data = {
                id: newRoomId,
                name: finalConvoyName,
                ownerId: hostId,
                diagnosticsEnabled: diagBool,
                joinCode,
                origin: origin ? origin.trim() : null,
                destination: destination ? destination.trim() : null,
                meetupPoint: meetupPoint ? meetupPoint.trim() : null,
                estimatedDurationMin: estimatedDurationMin ? parseInt(estimatedDurationMin, 10) : null
            }
            const select = {
                id: true,
                name: true,
                ownerId: true,
                diagnosticsEnabled: true,
                joinCode: true,
                origin: true,
                destination: true,
                meetupPoint: true,
                estimatedDurationMin: true
            }
            if (withLkRoomName) {
                data.lkRoomName = initialLkRoomName
                select.lkRoomName = true
            }
            const created = await client.room.create({ data, select })
            if (!created.lkRoomName) {
                created.lkRoomName = initialLkRoomName
            }
            return created
        }

        let room
        try {
            if (clientRequestId && !rideCreateRequestTableUnavailable && typeof prisma.$transaction === 'function') {
                try {
                    room = await prisma.$transaction(async (tx) => {
                        let createdRoom
                        try {
                            createdRoom = await createRoomRecord(tx, true)
                        } catch (createErr) {
                            if (createErr.code === 'P2022' || (createErr.message && createErr.message.includes('lkRoomName'))) {
                                console.error('[LobbyCreate] Schema degraded: lkRoomName column missing in DB; creating without lkRoomName')
                                setSchemaHealth(false, { error: 'Missing lkRoomName column in database' })
                                createdRoom = await createRoomRecord(tx, false)
                            } else {
                                throw createErr
                            }
                        }

                        if (typeof tx.rideCreateRequest?.create === 'function') {
                            await tx.rideCreateRequest.create({
                                data: {
                                    ownerId: hostId,
                                    clientRequestId,
                                    payloadHash,
                                    roomId: createdRoom.id
                                }
                            })
                        }

                        return createdRoom
                    })
                } catch (txErr) {
                    if (txErr.code === 'P2002') {
                        throw txErr // Let outer catch handle concurrent winner or unique name conflict
                    }
                    if (txErr.code === 'P2021' || txErr.code === 'P2022' || (txErr.message && (txErr.message.includes('RideCreateRequest') || txErr.message.includes('lkRoomName')))) {
                        console.warn('[LobbyCreate] DB schema fallback during transaction; creating room directly:', txErr.message)
                        rideCreateRequestTableUnavailable = true
                        try {
                            room = await createRoomRecord(prisma, true)
                        } catch (directErr) {
                            if (directErr.code === 'P2022' || (directErr.message && directErr.message.includes('lkRoomName'))) {
                                console.error('[LobbyCreate] Schema degraded: lkRoomName column missing in DB; creating without lkRoomName')
                                setSchemaHealth(false, { error: 'Missing lkRoomName column in database' })
                                room = await createRoomRecord(prisma, false)
                            } else {
                                throw directErr
                            }
                        }
                    } else {
                        throw txErr
                    }
                }
            } else {
                try {
                    room = await createRoomRecord(prisma, true)
                } catch (directErr) {
                    if (directErr.code === 'P2022' || (directErr.message && directErr.message.includes('lkRoomName'))) {
                        console.error('[LobbyCreate] Schema degraded: lkRoomName column missing in DB; creating without lkRoomName')
                        setSchemaHealth(false, { error: 'Missing lkRoomName column in database' })
                        room = await createRoomRecord(prisma, false)
                    } else {
                        throw directErr
                    }
                }
            }

            if (clientRequestId && !rideCreateRequestTableUnavailable && typeof prisma.rideCreateRequest?.create === 'function') {
                await prisma.rideCreateRequest.create({
                    data: {
                        ownerId: hostId,
                        clientRequestId,
                        payloadHash,
                        roomId: room.id
                    }
                }).catch(err => {
                    console.warn('[LobbyCreate] rideCreateRequest create notice:', err.message)
                })
            }
        } catch (dbErr) {
            if (dbErr.code === 'P2002') {
                const target = Array.isArray(dbErr.meta?.target) ? dbErr.meta.target.join(',') : (dbErr.meta?.target || '')
                if (clientRequestId && (target.includes('clientRequestId') || target.includes('ownerId'))) {
                    // Concurrent winner already inserted
                    if (typeof prisma.rideCreateRequest?.findUnique === 'function') {
                        const winnerReq = await prisma.rideCreateRequest.findUnique({
                            where: {
                                ownerId_clientRequestId: {
                                ownerId: hostId,
                                clientRequestId
                            }
                        },
                        include: { room: true }
                    }).catch(() => null)

                        if (winnerReq && winnerReq.payloadHash === payloadHash && winnerReq.room) {
                            return res.status(200).json({
                                roomId: winnerReq.room.id,
                                convoyName: winnerReq.room.name,
                                hostId: winnerReq.room.ownerId,
                                diagnosticsEnabled: winnerReq.room.diagnosticsEnabled,
                                joinCode: winnerReq.room.joinCode,
                                origin: winnerReq.room.origin,
                                destination: winnerReq.room.destination,
                                meetupPoint: winnerReq.room.meetupPoint,
                                estimatedDurationMin: winnerReq.room.estimatedDurationMin
                            })
                        } else if (winnerReq && winnerReq.payloadHash !== payloadHash) {
                            return res.status(409).json({
                                error: 'Idempotency key reused with different request payload',
                                code: 'IDEMPOTENCY_KEY_REUSED'
                            })
                        }
                    }
                }
                if (target.includes('name') || target.includes('lower')) {
                    return res.status(409).json({
                        error: 'A ride with this name already exists',
                        code: 'ROOM_NAME_TAKEN'
                    })
                }
            }
            throw dbErr
        }

        if (diagBool) {
            try {
                await diagnosticService.joinDiagnosticRun(room.id, req.user)
            } catch (err) {
                console.warn('[Diagnostic Auto-Start]:', err.message)
            }
        }

        emailService.sendRoomCreatedEmailSafe({
            hostId,
            hostEmail: req.user.email,
            hostName: req.user.name,
            convoyName: room.name,
            roomId: room.id,
            createdAt: new Date()
        })

        cacheService.del('lobby_status:' + room.name)
        cacheService.del('lobby_status:' + room.id)
        const respPayload = {
            roomId: room.id,
            convoyName: room.name,
            hostId,
            diagnosticsEnabled: room.diagnosticsEnabled,
            joinCode: room.joinCode,
            origin: room.origin,
            destination: room.destination,
            meetupPoint: room.meetupPoint,
            estimatedDurationMin: room.estimatedDurationMin
        }

        if (clientRequestId) {
            inMemoryFallbackRequests.set(`${hostId}:${clientRequestId}`, { response: respPayload, payloadHash })
        }

        res.status(201).json(respPayload)
    } catch (error) {
        next(error)
    }
})

// --- GET /lobby/:roomName/status ----------------------------------------------

router.get('/:roomRef/status', async (req, res, next) => {
    const { roomRef } = req.params
    const requesterId = req.user.uid

    try {
        // Resolve room and include owner and non-REMOVED invites
        const room = await resolveRoom(roomRef, {
            select: {
                startedAt: true,
                owner: {
                    select: { id: true, handle: true, displayName: true, bikeModel: true }
                },
                invites: {
                    where: { status: { in: ['PENDING', 'ACCEPTED', 'DECLINED', 'LEFT'] } },
                    select: {
                        id: true,
                        status: true,
                        source: true,
                        invitee: {
                            select: { id: true, handle: true, displayName: true, bikeModel: true }
                        }
                    }
                }
            }
        })

        // Check cache (per-viewer 1.5s cache)
        const cacheKey = `lobby_status:${room.id}:${requesterId}`
        const cached = cacheService.get(cacheKey)
        if (cached) {
            return res.json(cached)
        }

        // Authorization: owner or ACCEPTED member only
        const isOwner = room.ownerId === requesterId
        const allInvites = Array.isArray(room.invites) ? room.invites : []
        const requesterInvite = allInvites.find(inv => inv.invitee?.id === requesterId)
        const isAccepted = requesterInvite && requesterInvite.status === 'ACCEPTED'

        if (!isOwner && !isAccepted) {
            // Check if requester was explicitly removed
            let removedInvite = null
            if (requesterInvite?.status === 'REMOVED') {
                removedInvite = requesterInvite
            } else if (typeof prisma.rideInvite?.findFirst === 'function') {
                removedInvite = await prisma.rideInvite.findFirst({
                    where: { roomId: room.id, inviteeId: requesterId, status: 'REMOVED' },
                    select: { id: true }
                }).catch(() => null)
            }
            if (removedInvite) {
                return res.status(403).json({ error: 'You were removed from this convoy by the host', code: 'REMOVED_BY_HOST' })
            }
            return res.status(403).json({ error: 'Only convoy members can view lobby status', code: 'NOT_A_MEMBER' })
        }

        // Visibility rule:
        // Owner sees every non-REMOVED invite;
        // Accepted rider sees the host plus all ACCEPTED riders, plus only their own row if not ACCEPTED.
        const visibleInvites = isOwner
            ? allInvites.filter(inv => inv.status !== 'REMOVED')
            : allInvites.filter(inv => inv.status === 'ACCEPTED' || inv.invitee?.id === requesterId)

        // Counts
        const acceptedCount = allInvites.filter(inv => inv.status === 'ACCEPTED').length
        const pendingCount = allInvites.filter(inv => inv.status === 'PENDING').length
        const declinedCount = allInvites.filter(inv => inv.status === 'DECLINED').length
        const leftCount = allInvites.filter(inv => inv.status === 'LEFT').length

        // R16: Separate left riders from active formatted invites
        const leftRiders = visibleInvites
            .filter(inv => inv.status === 'LEFT')
            .map(inv => ({
                inviteId: inv.id,
                status: inv.status,
                source: inv.source,
                invitee: {
                    id: inv.invitee?.id,
                    handle: inv.invitee?.handle || null,
                    displayName: inv.invitee?.displayName || null,
                    callsign: riderCallsign(inv.invitee),
                    bikeModel: inv.invitee?.bikeModel || null
                }
            }))

        const formattedInvites = visibleInvites
            .filter(inv => inv.status !== 'LEFT')
            .map(inv => ({
                inviteId: inv.id,
                status: inv.status,
                source: inv.source,
                invitee: {
                    id: inv.invitee?.id,
                    handle: inv.invitee?.handle || null,
                    displayName: inv.invitee?.displayName || null,
                    callsign: riderCallsign(inv.invitee),
                    bikeModel: inv.invitee?.bikeModel || null
                }
            }))

        const allowSolo = req.query.allowSolo === 'true' || req.query.allowSolo === true
        const canStart = isOwner && (acceptedCount >= 1 || allowSolo)
        const isRideStarted = room.status === 'ACTIVE' || Boolean(room.startedAt)

        const hostCallsign = room.owner ? riderCallsign(room.owner) : 'Host'
        const host = {
            id: room.owner?.id || room.ownerId,
            callsign: hostCallsign,
            handle: room.owner?.handle || null,
            displayName: room.owner?.displayName || null,
            bikeModel: room.owner?.bikeModel || null
        }

        const payload = {
            roomId: room.id,
            convoyName: room.name,
            lkRoomName: room.lkRoomName || null,
            status: room.status,                       // new: LOBBY | ACTIVE | ENDED
            isHost: isOwner,                           // legacy name, REQUIRED by current app
            isOwner,                                   // new alias
            isRideStarted,                             // legacy name, REQUIRED by current app
            startedAt: room.startedAt ? (room.startedAt instanceof Date ? room.startedAt.toISOString() : new Date(room.startedAt).toISOString()) : null,
            diagnosticsEnabled: Boolean(room.diagnosticsEnabled),
            origin: room.origin || null,
            destination: room.destination || null,
            meetupPoint: room.meetupPoint || null,
            estimatedDurationMin: room.estimatedDurationMin || null,
            host,
            invites: formattedInvites,
            leftRiders,
            leftCount,
            acceptedCount,
            pendingCount,
            declinedCount,
            canStart,
            joinCode: isOwner ? room.joinCode : undefined
        }

        cacheService.set(cacheKey, payload, 1.5)
        res.json(payload)
    } catch (error) {
        if (error instanceof ResolverError) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        next(error)
    }
})

// --- POST /lobby/:roomName/start ----------------------------------------------

router.post('/:roomRef/start', async (req, res, next) => {
    const { roomRef } = req.params
    const user = req.user

    try {
        // R3: allow ['LOBBY', 'ACTIVE'] so start is idempotent and token failure does not lock host out
        const room = await resolveRoom(roomRef, { requireStatus: ['LOBBY', 'ACTIVE'] })

        if (room.ownerId !== user.uid) {
            return res.status(403).json({ error: 'Only the host can start the ride' })
        }

        if (room.status === 'ACTIVE') {
            // Already ACTIVE: issue fresh token and return
            const tokenResult = await issueRoomToken(user, room)
            return res.json(tokenResult)
        }

        // H4 - verify canStart: need at least one ACCEPTED member (unless allowSolo)
        const acceptedCount = await prisma.rideInvite.count({
            where: { roomId: room.id, status: 'ACCEPTED' }
        })
        const allowSolo = req.body.allowSolo === true || req.query.allowSolo === 'true'
        if (acceptedCount < 1 && !allowSolo) {
            return res.status(400).json({
                error: 'No riders have accepted yet. Invite at least one rider or pass allowSolo=true.',
                code: 'NO_ACCEPTED_RIDERS'
            })
        }

        // Transition room to ACTIVE conditionally
        if (typeof prisma.room.updateMany === 'function') {
            await prisma.room.updateMany({
                where: { id: room.id, status: 'LOBBY' },
                data: { startedAt: new Date(), status: 'ACTIVE' }
            }).catch(() => null)
        } else if (typeof prisma.room.update === 'function') {
            await prisma.room.update({
                where: { id: room.id },
                data: { startedAt: new Date(), status: 'ACTIVE' }
            }).catch(() => null)
        }

        invalidateRoom(room.id)

        // Issue token via tokenService (A1 + A2 + F3)
        const tokenResult = await issueRoomToken(user, { ...room, status: 'ACTIVE' })

        res.json(tokenResult)
    } catch (error) {
        if (error instanceof ResolverError) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        next(error)
    }
})

// --- POST /lobby/join-token ---------------------------------------------------

router.post('/join-token', async (req, res, next) => {
    const { roomId, roomName } = req.body
    const user = req.user

    const ref = roomId || roomName
    if (!ref) return res.status(400).json({ error: 'roomId is required' })

    try {
        const room = await resolveRoom(ref)
        invalidateRoom(room.id)
        const tokenResult = await issueRoomToken(user, room)
        res.json(tokenResult)
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

// --- POST /lobby/:roomName/share-link -----------------------------------------

router.post('/:roomRef/share-link', async (req, res, next) => {
    const { roomRef } = req.params
    const user = req.user

    try {
        const room = await resolveRoom(roomRef)

        // Only owner or ACCEPTED members can create share links
        const isOwner = room.ownerId === user.uid
        if (!isOwner) {
            const invite = typeof prisma.rideInvite?.findUnique === 'function' ? await prisma.rideInvite.findUnique({
                where: { roomId_inviteeId: { roomId: room.id, inviteeId: user.uid } },
                select: { status: true }
            }).catch(() => null) : null
            if (!invite || invite.status !== 'ACCEPTED') {
                return res.status(403).json({ error: 'Only convoy participants can share join links' })
            }
        }

        // C8 - Cap active share links to 5 per room
        const activeLinksCount = typeof prisma.roomJoinToken?.count === 'function' ? await prisma.roomJoinToken.count({
            where: { roomId: room.id, revokedAt: null, expiresAt: { gt: new Date() } }
        }).catch(() => 0) : 0
        if (activeLinksCount >= 5) {
            return res.status(409).json({ error: 'Maximum of 5 active share links reached for this convoy', code: 'TOO_MANY_LINKS' })
        }

        let maxUses = null
        if (req.body.maxUses !== undefined && req.body.maxUses !== null) {
            maxUses = parseInt(req.body.maxUses, 10)
            if (isNaN(maxUses) || maxUses < 1 || maxUses > 50) {
                return res.status(400).json({ error: 'maxUses must be an integer between 1 and 50', code: 'INVALID_MAX_USES' })
            }
        }

        const crypto = require('crypto')
        const tokenValue = crypto.randomBytes(8).toString('hex')
        const expiresAt = new Date(Date.now() + 24 * 60 * 60 * 1000)

        // R4: create join token without swallowing database error
        try {
            await prisma.roomJoinToken.create({
                data: {
                    roomId: room.id,
                    token: tokenValue,
                    expiresAt,
                    createdById: user.uid,
                    maxUses
                },
                select: { id: true }
            })
        } catch (dbErr) {
            console.error('[share-link] create token error:', dbErr.message)
            return res.status(503).json({ error: 'Failed to create share link in database', code: 'DATABASE_ERROR' })
        }

        const baseUrl = process.env.APP_BASE_URL || process.env.RENDER_EXTERNAL_URL
        if (!baseUrl) {
            console.warn('[share-link] APP_BASE_URL and RENDER_EXTERNAL_URL are unset. shareUrl omitted.')
        }
        res.json({
            token: tokenValue,
            ...(baseUrl ? { shareUrl: `${baseUrl.replace(/\/+$/, '')}/j/${tokenValue}` } : {}),
            deepLink: `ridervoice://join/${tokenValue}`,
            expiresAt: expiresAt.toISOString()
        })
    } catch (error) {
        if (error instanceof ResolverError) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        next(error)
    }
})

// --- POST /lobby/join-via-token -----------------------------------------------

router.post('/join-via-token', async (req, res, next) => {
    const rawToken = req.body.token
    if (!rawToken) return res.status(400).json({ error: 'token is required' })

    try {
        const tokenResult = await joinWithLinkToken(rawToken, req.user)
        res.json(tokenResult)
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

// --- POST /lobby/join-code ----------------------------------------------------

router.post('/join-code', joinLimiter, async (req, res, next) => {
    const { joinCode } = req.body
    if (!joinCode) return res.status(400).json({ error: 'joinCode is required' })

    const ip = req.ip || req.socket?.remoteAddress || 'unknown'
    const uid = req.user?.uid || 'anon'

    try {
        const tokenResult = await joinWithCode(joinCode, req.user)
        res.json(tokenResult)
    } catch (error) {
        recordFailedJoinCode(ip)
        recordFailedJoinCode(`${ip}:${uid}`)
        if (error instanceof ResolverError) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        if (error.status) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        next(error)
    }
})

// --- POST /lobby/join ---------------------------------------------------------
// Unified join endpoint: kind = "TOKEN" | "CODE" | "MEMBER"
router.post('/join', joinLimiter, async (req, res, next) => {
    const { kind, value } = req.body || {}
    if (!kind || !value) {
        return res.status(400).json({ error: 'kind (TOKEN|CODE|MEMBER) and value are required', code: 'MISSING_FIELDS' })
    }

    const ip = req.ip || req.socket?.remoteAddress || 'unknown'
    const uid = req.user?.uid || 'anon'

    try {
        let result
        switch (kind.toUpperCase()) {
            case 'TOKEN':
                result = await joinWithLinkToken(value, req.user)
                break
            case 'CODE':
                result = await joinWithCode(value, req.user)
                break
            case 'MEMBER':
                result = await joinAsMember(value, req.user)
                break
            default:
                return res.status(400).json({ error: 'Invalid join kind. Must be TOKEN, CODE, or MEMBER', code: 'INVALID_KIND' })
        }
        res.json(result)
    } catch (error) {
        recordFailedJoinCode(ip)
        recordFailedJoinCode(`${ip}:${uid}`)
        if (error instanceof ResolverError) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        if (error.status) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        next(error)
    }
})

// --- POST /lobby/:roomRef/leave -----------------------------------------------

router.post('/:roomRef/leave', async (req, res, next) => {
    const { roomRef } = req.params
    const user = req.user

    try {
        // R10: allowEnded: true so leaving an already ended room is idempotent
        const room = await resolveRoom(roomRef, { allowEnded: true })
        if (room.status === 'ENDED') {
            return res.json({ success: true, alreadyEnded: true, message: 'Convoy already ended' })
        }
        if (room.ownerId === user.uid) {
            return res.status(400).json({ error: 'The host cannot leave the convoy; transfer ownership or end the convoy', code: 'HOST_CANNOT_LEAVE' })
        }

        await leaveRoom({ roomId: room.id, userId: user.uid })
        await removeLiveKitParticipant(lkRoomName(room), user.uid)
        invalidateRoom(room.id)

        res.json({ success: true, message: 'Successfully left the convoy' })
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

// --- DELETE /lobby/:roomRef/riders/:userId ------------------------------------

router.delete('/:roomRef/riders/:userId', async (req, res, next) => {
    const { roomRef, userId } = req.params
    const hostId = req.user.uid

    try {
        const room = await resolveRoom(roomRef)

        const { invite } = await removeRider({
            roomId: room.id,
            userId,
            hostId,
            room
        })

        // Kick from LiveKit
        await removeLiveKitParticipant(lkRoomName(room), userId)

        // H6 - revoke all share links created by the removed user
        if (typeof prisma.roomJoinToken?.updateMany === 'function') {
            await prisma.roomJoinToken.updateMany({
                where: { roomId: room.id, createdById: userId, revokedAt: null },
                data: { revokedAt: new Date() }
            }).catch(() => {})
        }

        invalidateRoom(room.id)
        res.json({ success: true, removedUserId: userId })
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

// --- POST /lobby/:roomRef/end -------------------------------------------------

router.post('/:roomRef/end', async (req, res, next) => {
    const { roomRef } = req.params
    const hostId = req.user.uid

    try {
        // Allow ENDED rooms so ending an already-ended room is idempotent
        const room = await resolveRoom(roomRef, { allowEnded: true })

        if (room.ownerId !== hostId) {
            return res.status(403).json({ error: 'Only the host can end the convoy ride' })
        }

        if (room.status === 'ENDED') {
            return res.json({ alreadyEnded: true, message: 'Convoy already ended', endedAt: room.endedAt?.toISOString() })
        }

        // A3 - mark DB ENDED BEFORE calling deleteRoom in LiveKit
        const endedRoom = await markRoomEnded(room.id)
        if (endedRoom.alreadyEnded) {
            return res.json({ alreadyEnded: true, message: 'Convoy already ended', endedAt: endedRoom.endedAt?.toISOString() })
        }

        // H6 - revoke all share links
        if (typeof prisma.roomJoinToken?.updateMany === 'function') {
            await prisma.roomJoinToken.updateMany({
                where: { roomId: room.id, revokedAt: null },
                data: { revokedAt: new Date() }
            }).catch(() => {})
        }

        // Broadcast CONVOY_ENDED via LiveKit reliable data message, wait 400ms, then delete room
        await sendLiveKitData(lkRoomName(room), { type: 'CONVOY_ENDED', roomId: room.id, endedAt: endedRoom.endedAt })
        await new Promise(r => setTimeout(r, 400))
        await deleteLiveKitRoom(lkRoomName(room), room.id)

        // B6 - Diagnostics: close active run; persist closeAt on DB row
        try {
            const activeRun = typeof prisma.diagnosticRun?.findFirst === 'function' ? await prisma.diagnosticRun.findFirst({
                where: { convoyId: room.id, status: 'ACTIVE' },
                include: { participants: { select: { stoppedAt: true } } }
            }).catch(() => null) : null

            if (activeRun) {
                const allStopped = activeRun.participants?.length > 0 &&
                    activeRun.participants.every(p => p.stoppedAt != null)

                if (allStopped) {
                    diagnosticService.closeAndExportRun(activeRun.id).catch(err => {
                        console.error(`[Diagnostic] Error exporting run ${activeRun.id}:`, err)
                    })
                } else {
                    const graceSec = parseInt(process.env.DIAG_CLOSE_GRACE_SECONDS || '90', 10)
                    const closeAt = new Date(Date.now() + graceSec * 1000)

                    if (typeof prisma.diagnosticRun?.update === 'function') {
                        await prisma.diagnosticRun.update({
                            where: { id: activeRun.id },
                            data: { closeAt }
                        }).catch(() => {})
                    }

                    setTimeout(() => {
                        diagnosticService.closeAndExportRun(activeRun.id, { forceClose: true }).catch(err => {
                            console.error(`[Diagnostic Grace Close] Error closing run ${activeRun.id}:`, err)
                        })
                    }, graceSec * 1000).unref()
                }
            }
        } catch (diagErr) {
            console.warn('[Diagnostic] Could not check active run on ride end:', diagErr.message)
        }

        emailService.sendRoomClosedEmailSafe({
            roomName: room.name,
            hostId,
            hostEmail: req.user.email,
            hostName: req.user.name,
            closedAt: endedRoom.endedAt
        })

        invalidateRoom(room.id)
        res.json({ success: true, message: 'Convoy ended', endedAt: endedRoom.endedAt?.toISOString() })
    } catch (error) {
        if (error instanceof ResolverError) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        next(error)
    }
})

// --- POST /lobby/:roomRef/transfer-host ---------------------------------------

router.post('/:roomRef/transfer-host', async (req, res, next) => {
    const { roomRef } = req.params
    const hostId = req.user.uid
    const { newHostId } = req.body

    if (!newHostId) return res.status(400).json({ error: 'newHostId is required' })

    try {
        const room = await resolveRoom(roomRef)

        if (room.ownerId !== hostId) {
            return res.status(403).json({ error: 'Only the current host can transfer convoy ownership' })
        }

        // H5 - validate newHostId is an ACCEPTED member
        const newHostInvite = await prisma.rideInvite.findUnique({
            where: { roomId_inviteeId: { roomId: room.id, inviteeId: newHostId } },
            select: { status: true }
        })
        if (!newHostInvite || newHostInvite.status !== 'ACCEPTED') {
            return res.status(400).json({
                error: 'The new host must be an accepted member of the convoy',
                code: 'NEW_HOST_NOT_MEMBER'
            })
        }

        // H5 - old host gets ACCEPTED invite so they remain a member
        await prisma.rideInvite.upsert({
            where: { roomId_inviteeId: { roomId: room.id, inviteeId: hostId } },
            create: {
                roomId: room.id,
                inviterId: newHostId,
                inviteeId: hostId,
                status: 'ACCEPTED',
                source: 'HOST_INVITE'
            },
            update: { status: 'ACCEPTED', updatedAt: new Date() }
        })

        await prisma.room.update({
            where: { id: room.id },
            data: { ownerId: newHostId },
            select: { id: true }
        })

        // H6 - revoke share links created by old host
        await prisma.roomJoinToken.updateMany({
            where: { roomId: room.id, createdById: hostId, revokedAt: null },
            data: { revokedAt: new Date() }
        })

        // R12: Send HOST_TRANSFERRED event to LiveKit room data channel
        const newHostUser = await prisma.user.findUnique({
            where: { id: newHostId },
            select: { displayName: true, handle: true }
        }).catch(() => null)
        const newHostCallsign = riderCallsign(newHostUser || { id: newHostId })

        await sendLiveKitData(lkRoomName(room), {
            type: 'HOST_TRANSFERRED',
            roomId: room.id,
            newHostId,
            newHostCallsign
        }).catch(err => console.warn('[transfer-host] LiveKit broadcast warning:', err.message))

        cacheService.del('lobby_status:' + room.name);
        cacheService.del('lobby_status:' + room.id);
        res.json({ success: true, newHostId, newHostCallsign, previousHostId: hostId })
    } catch (error) {
        if (error instanceof ResolverError) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        next(error)
    }
})

// --- PATCH /lobby/:roomRef/diagnostics ----------------------------------------

async function updateDiagnosticsHandler(req, res, next) {
    const { roomRef } = req.params
    const user = req.user
    const { diagnosticsEnabled } = req.body

    if (diagnosticsEnabled === undefined) {
        return res.status(400).json({ error: 'diagnosticsEnabled is required' })
    }

    try {
        const room = await resolveRoom(roomRef)

        if (room.ownerId !== user.uid) {
            return res.status(403).json({ error: 'Only the host can modify diagnostics settings' })
        }

        if (room.status !== 'LOBBY' || room.startedAt) {
            return res.status(400).json({ error: 'Cannot change diagnostics setting after ride has started' })
        }

        const updated = await prisma.room.update({
            where: { id: room.id },
            data: { diagnosticsEnabled: Boolean(diagnosticsEnabled) },
            select: { diagnosticsEnabled: true }
        })

        invalidateRoom(room.id)
        res.json({ success: true, diagnosticsEnabled: updated.diagnosticsEnabled })
    } catch (error) {
        if (error instanceof ResolverError) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        next(error)
    }
}

router.patch('/:roomRef/diagnostics', updateDiagnosticsHandler)
router.put('/:roomRef/diagnostics', updateDiagnosticsHandler)

module.exports = router
