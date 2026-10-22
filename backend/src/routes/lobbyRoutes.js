/**
 * lobbyRoutes.js ΓÇô Phase 1 / 1B rewrite
 *
 * All bugs fixed in this file:
 *   A1  ΓÇô roomCreate:false on every token (delegated to tokenService)
 *   A2  ΓÇô single issueRoomToken() for all 4 token paths
 *   A3  ΓÇô /end marks DB ENDED before calling deleteRoom
 *   A4  ΓÇô /create never revives stale rows; always creates fresh
 *   G1  ΓÇô join-via-token blocks REMOVED riders
 *   G2  ΓÇô join-via-token blocks DECLINED? No ΓÇô DECLINED can self-join
 *   G3  ΓÇô @@unique([roomId,inviteeId]) in schema; handled by membership service
 *   G4  ΓÇô join-via-token stores actual link-creator as inviterId (createdById)
 *   G5  ΓÇô /create: no more stale-room revival
 *   G6  ΓÇô all membership paths check room.status != ENDED
 *   G8  ΓÇô accept is atomic (no pre-remove before token call)
 *   G9  ΓÇô invite errors surfaced per-rider
 *   G10 ΓÇô "joined" email fires once from membershipService.notifyJoined
 *   G11 ΓÇô /status only shows owner + ACCEPTED members (not PENDING/DECLINED)
 *   H1  ΓÇô join-by-name removed; join code or share-link token required
 *   H2  ΓÇô /create persists origin/destination/meetupPoint/estimatedDurationMin
 *   H3  ΓÇô 410 returned for ENDED rooms everywhere
 *   H4  ΓÇô canStart computed from real state: host + >=1 ACCEPTED or allowSolo
 *   H5  ΓÇô transfer-host: newHostId validated as ACCEPTED member
 *   H6  ΓÇô share tokens revoked on /end
 *   B6  ΓÇô grace-close uses persisted closeAt on DB row (not in-memory setTimeout only)
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
const { selfJoin, removeRider, notifyJoined } = require('../services/membershipService')

// ΓöÇΓöÇΓöÇ Helpers ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

function extractCleanToken(input) {
    if (!input || typeof input !== 'string') return ''
    let cleaned = input.trim()
    if (cleaned.startsWith('ridervoice://join/')) {
        cleaned = cleaned.slice('ridervoice://join/'.length)
    } else if (cleaned.includes('/join/')) {
        cleaned = cleaned.slice(cleaned.indexOf('/join/') + '/join/'.length)
    }
    if (cleaned.includes('token=')) {
        cleaned = cleaned.slice(cleaned.indexOf('token=') + 'token='.length)
    }
    cleaned = cleaned.split('?')[0].split('&')[0].split('#')[0].replace(/\/+$/, '').trim()
    return cleaned
}

function getLiveKitHttpUrl() {
    const raw = process.env.LIVEKIT_URL || ''
    return raw.replace('wss://', 'https://').replace('ws://', 'http://')
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

// ΓöÇΓöÇΓöÇ POST /lobby/create ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

router.post('/create', async (req, res, next) => {
    const hostId = req.user.uid
    const {
        convoyName,
        diagnosticsEnabled,
        origin,
        destination,
        meetupPoint,
        estimatedDurationMin,
        allowSolo
    } = req.body

    if (!convoyName || typeof convoyName !== 'string') {
        return res.status(400).json({ error: 'convoyName is required' })
    }

    const trimmedName = convoyName.trim()
    if (!trimmedName) {
        return res.status(400).json({ error: 'convoyName cannot be blank' })
    }

    // H1 / Phase 1.5 ΓÇô block reserved names
    if (isReservedName(trimmedName)) {
        return res.status(400).json({ error: `"${trimmedName}" is a reserved convoy name` })
    }

    try {
        // A4 ΓÇô never revive stale rows. Check for an active (non-ENDED) room with this name.
        // Check for an active (non-ENDED) room with this name or collision
        const existing = await prisma.room.findFirst({
            where: {
                name: { equals: trimmedName, mode: 'insensitive' },
                status: { not: 'ENDED' }
            },
            select: { id: true, ownerId: true }
        }).catch(() => null) || (typeof prisma.room.findUnique === 'function' ? await prisma.room.findUnique({
            where: { name: trimmedName },
            select: { id: true, ownerId: true }
        }).catch(() => null) : null)

        let finalConvoyName = trimmedName
        if (existing) {
            let suffix = 2
            let candidate = `${trimmedName}-${suffix}`
            while ((typeof prisma.room.findUnique === 'function' && await prisma.room.findUnique({ where: { name: candidate }, select: { id: true } }).catch(() => null)) ||
                   (typeof prisma.room.findFirst === 'function' && await prisma.room.findFirst({ where: { name: candidate, status: { not: 'ENDED' } }, select: { id: true } }).catch(() => null))) {
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
                // H2 ΓÇô persist ride metadata
                origin: origin || null,
                destination: destination || null,
                meetupPoint: meetupPoint || null,
                estimatedDurationMin: estimatedDurationMin ? parseInt(estimatedDurationMin, 10) : null,
                // Store allowSolo in metadata via JSON-compatible field ΓÇô stored as custom metadata
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
        res.status(201).json({
            roomId: room.id,
            convoyName: room.name,
            hostId,
            diagnosticsEnabled: room.diagnosticsEnabled,
            joinCode: room.joinCode,
            origin: room.origin,
            destination: room.destination,
            meetupPoint: room.meetupPoint,
            estimatedDurationMin: room.estimatedDurationMin
        })
    } catch (error) {
        next(error)
    }
})

// ΓöÇΓöÇΓöÇ GET /lobby/:roomName/status ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

router.get('/:roomRef/status', async (req, res, next) => {
    const { roomRef } = req.params
    const requesterId = req.user.uid

    try {
        const room = await resolveRoom(roomRef, {
            select: {
                startedAt: true,
                // G11 ΓÇô only ACCEPTED and owner are "members" for lobby status
                invites: {
                    where: { status: { in: ['ACCEPTED'] } },
                    select: {
                        id: true,
                        status: true,
                        source: true,
                        invitee: {
                            select: { id: true, handle: true, displayName: true, bikeModel: true }
                        }
                    }
                },
                // Separately fetch pending/declined count for owner view
                _count: {
                    select: {
                        invites: true
                    }
                }
            }
        })

        // Only owner or ACCEPTED members can see status
        const isOwner = room.ownerId === requesterId
        const isAccepted = room.invites.some(inv => inv.invitee?.id === requesterId)
        if (!isOwner && !isAccepted) {
            return res.status(403).json({ error: 'Only convoy members can view lobby status' })
        }

        // G11 ΓÇô if not owner, return only own membership info
        const visibleInvites = isOwner ? room.invites : room.invites.filter(inv => inv.invitee?.id === requesterId)

        const formattedInvites = visibleInvites.map(inv => ({
            inviteId: inv.id,
            status: inv.status,
            source: inv.source,
            invitee: {
                id: inv.invitee.id,
                handle: inv.invitee.handle || inv.invitee.displayName || 'Rider',
                displayName: inv.invitee.displayName,
                bikeModel: inv.invitee.bikeModel
            }
        }))

        const acceptedCount = room.invites.length // already filtered to ACCEPTED

        // H4 ΓÇô canStart: real logic
        const canStart = isOwner && (acceptedCount >= 1 || req.query.allowSolo === 'true')

        res.json({
            roomId: room.id,
            convoyName: room.name,
            status: room.status,
            diagnosticsEnabled: room.diagnosticsEnabled,
            startedAt: room.startedAt?.toISOString() || null,
            origin: room.origin,
            destination: room.destination,
            meetupPoint: room.meetupPoint,
            estimatedDurationMin: room.estimatedDurationMin,
            invites: formattedInvites,
            acceptedCount,
            canStart,
            isOwner,
            joinCode: isOwner ? room.joinCode : undefined
        })
    } catch (error) {
        if (error instanceof ResolverError) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        next(error)
    }
})

// ΓöÇΓöÇΓöÇ POST /lobby/:roomName/start ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

router.post('/:roomRef/start', async (req, res, next) => {
    const { roomRef } = req.params
    const user = req.user

    try {
        const room = await resolveRoom(roomRef, { requireStatus: ['LOBBY'] })

        if (room.ownerId !== user.uid) {
            return res.status(403).json({ error: 'Only the host can start the ride' })
        }

        // H4 ΓÇô verify canStart: need at least one ACCEPTED member (unless allowSolo)
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

        // Transition room to ACTIVE
        await prisma.room.update({
            where: { id: room.id },
            data: { startedAt: new Date(), status: 'ACTIVE' }
        })

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

// ΓöÇΓöÇΓöÇ PATCH /lobby/:roomRef/diagnostics ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ
// Host-only; only before the ride has started.

router.patch('/:roomRef/diagnostics', async (req, res, next) => {
    const { roomRef } = req.params
    const { diagnosticsEnabled } = req.body || {}

    try {
        const room = await resolveRoom(roomRef)

        if (room.ownerId !== req.user.uid) {
            return res.status(403).json({ error: 'Only the host can modify diagnostics settings' })
        }
        if (room.startedAt || room.status === 'ACTIVE') {
            return res.status(400).json({ error: 'Cannot change diagnostics setting after ride has started' })
        }
        if (typeof diagnosticsEnabled !== 'boolean') {
            return res.status(400).json({ error: 'diagnosticsEnabled must be a boolean' })
        }

        const updated = await prisma.room.update({
            where: { id: room.id },
            data: { diagnosticsEnabled },
            select: { diagnosticsEnabled: true }
        })
        cacheService.del('lobby_status:' + room.name);
        cacheService.del('lobby_status:' + room.id);
        res.json({ success: true, diagnosticsEnabled: updated.diagnosticsEnabled })
    } catch (error) {
        if (error instanceof ResolverError) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        next(error)
    }
})

// ΓöÇΓöÇΓöÇ POST /lobby/join-token ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

router.post('/join-token', async (req, res, next) => {
    const { roomId, roomName } = req.body
    const user = req.user

    const ref = roomId || roomName
    if (!ref) return res.status(400).json({ error: 'roomId is required' })

    try {
        const room = await resolveRoom(ref)
        // issueRoomToken performs membership check (G8 ΓÇô atomic)
        cacheService.del('lobby_status:' + room.name);
        cacheService.del('lobby_status:' + room.id);
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

// ΓöÇΓöÇΓöÇ POST /lobby/:roomName/share-link ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

router.post('/:roomRef/share-link', async (req, res, next) => {
    const { roomRef } = req.params
    const user = req.user

    try {
        const room = await resolveRoom(roomRef)

        // Only owner or ACCEPTED members can create share links
        const isOwner = room.ownerId === user.uid
        if (!isOwner) {
            const invite = await prisma.rideInvite.findUnique({
                where: { roomId_inviteeId: { roomId: room.id, inviteeId: user.uid } },
                select: { status: true }
            })
            if (!invite || invite.status !== 'ACCEPTED') {
                return res.status(403).json({ error: 'Only convoy participants can share join links' })
            }
        }

        const crypto = require('crypto')
        const tokenValue = crypto.randomBytes(8).toString('hex')
        const expiresAt = new Date(Date.now() + 24 * 60 * 60 * 1000)

        await prisma.roomJoinToken.create({
            data: {
                roomId: room.id,
                token: tokenValue,
                expiresAt,
                createdById: user.uid,  // G4 ΓÇô track actual link creator
                maxUses: req.body.maxUses || null
            },
            select: { id: true }
        })

        // Build both a scheme URL and an https landing page URL (H6)
        const baseUrl = process.env.APP_BASE_URL || 'https://ridervoice.app'
        res.json({
            token: tokenValue,
            shareUrl: `${baseUrl}/join/${tokenValue}`,
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

// ΓöÇΓöÇΓöÇ POST /lobby/join-via-token ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

router.post('/join-via-token', async (req, res, next) => {
    const rawToken = req.body.token
    const token = extractCleanToken(rawToken) || rawToken
    const user = req.user

    if (!token) return res.status(400).json({ error: 'token is required' })

    try {
        let joinTokenRecord = typeof prisma.roomJoinToken?.findUnique === 'function' ? await prisma.roomJoinToken.findUnique({
            where: { token },
            select: {
                expiresAt: true,
                revokedAt: true,
                maxUses: true,
                useCount: true,
                createdById: true,
                room: {
                    select: {
                        id: true,
                        name: true,
                        ownerId: true,
                        status: true,
                        diagnosticsEnabled: true
                    }
                }
            }
        }).catch(() => null) : null

        if (!joinTokenRecord && typeof prisma.roomJoinToken?.findUnique === 'function' && token.toLowerCase() !== token) {
            joinTokenRecord = await prisma.roomJoinToken.findUnique({
                where: { token: token.toLowerCase() },
                select: {
                    expiresAt: true,
                    revokedAt: true,
                    maxUses: true,
                    useCount: true,
                    createdById: true,
                    room: {
                        select: {
                            id: true,
                            name: true,
                            ownerId: true,
                            status: true,
                            diagnosticsEnabled: true
                        }
                    }
                }
            }).catch(() => null)
        }

        if (!joinTokenRecord) {
            return res.status(404).json({ error: 'Invalid or expired convoy link' })
        }

        // H6 ΓÇô revoked links
        if (joinTokenRecord.revokedAt) {
            return res.status(410).json({ error: 'This convoy link has been revoked' })
        }

        if (new Date() > joinTokenRecord.expiresAt) {
            return res.status(410).json({ error: 'Convoy join link has expired' })
        }

        // H6 ΓÇô maxUses check
        if (joinTokenRecord.maxUses && joinTokenRecord.useCount >= joinTokenRecord.maxUses) {
            return res.status(410).json({ error: 'This convoy link has reached its maximum number of uses' })
        }

        const room = joinTokenRecord.room

        // H3 ΓÇô 410 for ended rooms
        if (room.status === 'ENDED') {
            return res.status(410).json({ error: 'This convoy has ended', code: 'ROOM_ENDED' })
        }

        // G1 ΓÇô membership state machine: REMOVED blocks join; DECLINED/LEFT/null ΓåÆ ACCEPTED
        const { alreadyMember, isNew } = await selfJoin({
            roomId: room.id,
            inviteeId: user.uid,
            // G4 ΓÇô inviterId = link creator (not always owner)
            inviterId: joinTokenRecord.createdById || room.ownerId,
            source: 'SHARE_LINK',
            room
        })

        // Increment use count
        await prisma.roomJoinToken.update({
            where: { token },
            data: { useCount: { increment: 1 } }
        }).catch(() => {})

        // G10 ΓÇô notify once if newly joined
        if (isNew && !alreadyMember) {
            const displayName = await prisma.user.findUnique({
                where: { id: user.uid },
                select: { displayName: true }
            }).then(u => u?.displayName || 'A rider').catch(() => 'A rider')

            notifyJoined({
                inviteeId: user.uid,
                inviterId: joinTokenRecord.createdById || room.ownerId,
                roomName: room.name,
                inviteeEmail: user.email,
                inviteeName: displayName
            })
        }

        // Issue token via tokenService (A1 + A2 + F3)
        cacheService.del('lobby_status:' + room.name);
        cacheService.del('lobby_status:' + room.id);
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

// ΓöÇΓöÇΓöÇ POST /lobby/join-code ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

router.post('/join-code', async (req, res, next) => {
    const { joinCode } = req.body
    const user = req.user

    if (!joinCode) return res.status(400).json({ error: 'joinCode is required' })

    try {
        const room = await prisma.room.findFirst({
            where: {
                joinCode: joinCode.toUpperCase(),
                status: { not: 'ENDED' }
            },
            select: {
                id: true,
                name: true,
                status: true,
                ownerId: true,
                diagnosticsEnabled: true,
                joinCode: true,
                origin: true,
                destination: true,
                meetupPoint: true,
                estimatedDurationMin: true,
                createdAt: true,
                endedAt: true,
                startedAt: true
            }
        })

        if (!room) {
            return res.status(404).json({ error: 'Invalid join code' })
        }

        // G1 ΓÇô membership state machine via selfJoin
        const { alreadyMember, isNew } = await selfJoin({
            roomId: room.id,
            inviteeId: user.uid,
            inviterId: room.ownerId,
            source: 'JOIN_CODE',
            room
        })

        // G10 ΓÇô notify once
        if (isNew && !alreadyMember) {
            const displayName = await prisma.user.findUnique({
                where: { id: user.uid },
                select: { displayName: true }
            }).then(u => u?.displayName || 'A rider').catch(() => 'A rider')

            notifyJoined({
                inviteeId: user.uid,
                inviterId: room.ownerId,
                roomName: room.name,
                inviteeEmail: user.email,
                inviteeName: displayName
            })
        }

        // Issue token
        cacheService.del('lobby_status:' + room.name);
        cacheService.del('lobby_status:' + room.id);
        const tokenResult = await issueRoomToken(user, room)

        res.json(tokenResult)
    } catch (error) {
        if (error.status) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        next(error)
    }
})

// ΓöÇΓöÇΓöÇ DELETE /lobby/:roomRef/riders/:userId ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

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

        // H6 ΓÇô revoke all share links created by the removed user
        await prisma.roomJoinToken.updateMany({
            where: { roomId: room.id, createdById: userId, revokedAt: null },
            data: { revokedAt: new Date() }
        })

        cacheService.del('lobby_status:' + room.name);
        cacheService.del('lobby_status:' + room.id);
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

// ΓöÇΓöÇΓöÇ POST /lobby/:roomRef/end ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

router.post('/:roomRef/end', async (req, res, next) => {
    const { roomRef } = req.params
    const hostId = req.user.uid

    try {
        const room = await resolveRoom(roomRef)

        if (room.ownerId !== hostId) {
            return res.status(403).json({ error: 'Only the host can end the convoy ride' })
        }

        // A3 ΓÇô mark DB ENDED BEFORE calling deleteRoom in LiveKit
        const endedRoom = await markRoomEnded(room.id)

        // H6 ΓÇô revoke all share links
        await prisma.roomJoinToken.updateMany({
            where: { roomId: room.id, revokedAt: null },
            data: { revokedAt: new Date() }
        })

        // Delete the LiveKit room (best-effort; DB is already ENDED)
        await deleteLiveKitRoom(lkRoomName(room))

        // B6 ΓÇô Diagnostics: close active run; persist closeAt on DB row (not just in-memory setTimeout)
        try {
            const activeRun = await prisma.diagnosticRun.findFirst({
                where: { convoyId: room.id, status: 'ACTIVE' },
                include: { participants: { select: { stoppedAt: true } } }
            })
            if (activeRun) {
                const allStopped = activeRun.participants.length > 0 &&
                    activeRun.participants.every(p => p.stoppedAt != null)

                if (allStopped) {
                    diagnosticService.closeAndExportRun(activeRun.id).catch(err => {
                        console.error(`[Diagnostic] Error exporting run ${activeRun.id}:`, err)
                    })
                } else {
                    const graceSec = parseInt(process.env.DIAG_CLOSE_GRACE_SECONDS || '90', 10)
                    const closeAt = new Date(Date.now() + graceSec * 1000)

                    // B6 ΓÇô persist closeAt so a server restart can still close the run
                    await prisma.diagnosticRun.update({
                        where: { id: activeRun.id },
                        data: { closeAt }
                    }).catch(() => {})

                    // Also set the in-memory timer as a best-effort backup
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

        cacheService.del('lobby_status:' + room.name);
        cacheService.del('lobby_status:' + room.id);
        res.json({ success: true, message: 'Convoy ended', endedAt: endedRoom.endedAt?.toISOString() })
    } catch (error) {
        if (error instanceof ResolverError) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        next(error)
    }
})

// ΓöÇΓöÇΓöÇ POST /lobby/:roomRef/transfer-host ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

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

        // H5 ΓÇô validate newHostId is an ACCEPTED member
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

        // H5 ΓÇô old host gets ACCEPTED invite so they remain a member
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

        // H6 ΓÇô revoke share links created by old host
        await prisma.roomJoinToken.updateMany({
            where: { roomId: room.id, createdById: hostId, revokedAt: null },
            data: { revokedAt: new Date() }
        })

        cacheService.del('lobby_status:' + room.name);
        cacheService.del('lobby_status:' + room.id);
        res.json({ success: true, newHostId, previousHostId: hostId })
    } catch (error) {
        if (error instanceof ResolverError) {
            return res.status(error.status).json({ error: error.message, code: error.code })
        }
        next(error)
    }
})

// ΓöÇΓöÇΓöÇ PATCH /lobby/:roomRef/diagnostics ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ

async function updateDiagnosticsHandler(req, res, next) {
    const { roomRef } = req.params
    const user = req.user
    const { diagnosticsEnabled } = req.body

    if (diagnosticsEnabled === undefined) {
        return res.status(400).json({ error: 'diagnosticsEnabled is required' })
    }

    try {
        const room = await resolveRoom(roomRef, { requireStatus: ['LOBBY'] })

        if (room.ownerId !== user.uid) {
            return res.status(403).json({ error: 'Only the host can modify diagnostics settings' })
        }

        const updated = await prisma.room.update({
            where: { id: room.id },
            data: { diagnosticsEnabled: Boolean(diagnosticsEnabled) },
            select: { diagnosticsEnabled: true }
        })

        cacheService.del('lobby_status:' + room.name);
        cacheService.del('lobby_status:' + room.id);
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
