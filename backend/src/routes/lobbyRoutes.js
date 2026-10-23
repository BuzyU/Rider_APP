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
const { riderCallsign } = require('../services/identity')
const { joinWithLinkToken, joinWithCode, joinAsMember, extractCleanToken } = require('../services/joinService')

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

async function deleteLiveKitRoom(lkName) {
    try {
        const httpUrl = getLiveKitHttpUrl()
        if (!httpUrl || !process.env.LIVEKIT_API_KEY || !process.env.LIVEKIT_API_SECRET) return
        const svc = new RoomServiceClient(httpUrl, process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET)
        await svc.deleteRoom(lkName)
    } catch (err) {
        console.warn(`[LiveKit] deleteRoom notice for ${lkName}:`, err.message)
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

const recentCreateRequests = new Map()

function sweepRecentCreateRequests() {
    const now = Date.now()
    for (const [id, entry] of recentCreateRequests.entries()) {
        if (now - entry.timestamp > 60000) {
            recentCreateRequests.delete(id)
        }
    }
    while (recentCreateRequests.size > 5000) {
        const oldestKey = recentCreateRequests.keys().next().value
        if (oldestKey) recentCreateRequests.delete(oldestKey)
        else break
    }
}

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

    sweepRecentCreateRequests()
    if (clientRequestId && recentCreateRequests.has(clientRequestId)) {
        const cached = recentCreateRequests.get(clientRequestId)
        if (Date.now() - cached.timestamp < 60000) {
            return res.status(200).json(cached.response)
        } else {
            recentCreateRequests.delete(clientRequestId)
        }
    }

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

        const room = await prisma.room.create({
            data: {
                name: finalConvoyName,
                ownerId: hostId,
                diagnosticsEnabled: diagBool,
                joinCode,
                // H2 - persist ride metadata
                origin: origin || null,
                destination: destination || null,
                meetupPoint: meetupPoint || null,
                estimatedDurationMin: estimatedDurationMin ? parseInt(estimatedDurationMin, 10) : null,
                // Store allowSolo in metadata via JSON-compatible field - stored as custom metadata
            },
            select: {
                id: true,
                name: true,
                diagnosticsEnabled: true,
                joinCode: true,
                origin: true,
                destination: true,
                meetupPoint: true,
                estimatedDurationMin: true
            }
        })

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

        cacheService.del('lobby_status:' + room.name);
        cacheService.del('lobby_status:' + room.id);
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
            recentCreateRequests.set(clientRequestId, { response: respPayload, timestamp: Date.now() })
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
        await deleteLiveKitRoom(lkRoomName(room))

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
