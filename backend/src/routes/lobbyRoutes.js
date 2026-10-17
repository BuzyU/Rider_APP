const express = require('express')
const router = express.Router()
const crypto = require('crypto')
const prisma = require('../db')
const { AccessToken, RoomServiceClient } = require('livekit-server-sdk')
const diagnosticService = require('../services/diagnosticService')
const emailService = require('../services/emailService')
const cacheService = require('../services/cacheService')

let cachedHttpUrl = null
function getLiveKitHttpUrl() {
    if (cachedHttpUrl !== null) return cachedHttpUrl
    const rawUrl = process.env.LIVEKIT_URL || ''
    cachedHttpUrl = rawUrl
        .replace('wss://', 'https://')
        .replace('ws://', 'http://')
    return cachedHttpUrl
}

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

async function resolveRiderDisplayName(uid, fallback = 'Rider') {
    return cacheService.wrap(`user_name:${uid}`, 60000, async () => {
        try {
            const u = await prisma.user.findUnique({
                where: { id: uid },
                select: { displayName: true, handle: true }
            })
            return u?.displayName || (u?.handle ? `@${u.handle}` : null) || fallback
        } catch {
            return fallback
        }
    })
}

router.post('/create', async (req, res, next) => {
    const hostId = req.user.uid
    const { convoyName, diagnosticsEnabled } = req.body

    if (!convoyName) {
        return res.status(400).json({ error: 'convoyName is required' })
    }

    try {
        let finalConvoyName = String(convoyName).trim()
        let existing = await prisma.room.findUnique({
            where: { name: finalConvoyName },
            select: { id: true, ownerId: true, startedAt: true, createdAt: true, name: true }
        })
        if (!existing && typeof prisma.room.findFirst === 'function') {
            existing = await prisma.room.findFirst({
                where: { name: { equals: finalConvoyName, mode: 'insensitive' } },
                select: { id: true, ownerId: true, startedAt: true, createdAt: true, name: true }
            })
            if (existing) {
                finalConvoyName = existing.name
            }
        }
        const diagBool = diagnosticsEnabled === true
        let room
        if (existing) {
            const isOwner = Boolean(existing.ownerId && existing.ownerId === hostId)
            const isStale = Boolean(existing.startedAt != null || (existing.createdAt && (Date.now() - new Date(existing.createdAt).getTime() > 6 * 60 * 60 * 1000)))
            if (isOwner && isStale) {
                await prisma.rideInvite.deleteMany({ where: { roomId: existing.id } }).catch(() => {})
                room = await prisma.room.update({
                    where: { id: existing.id },
                    data: {
                        ownerId: hostId,
                        diagnosticsEnabled: diagBool,
                        startedAt: null
                    },
                    select: {
                        id: true,
                        name: true,
                        diagnosticsEnabled: true
                    }
                })
                cacheService.del(`lobby_status:${finalConvoyName}`)
            } else {
                let suffix = 2
                let candidate = `${finalConvoyName}-${suffix}`
                while (await prisma.room.findUnique({ where: { name: candidate }, select: { id: true } })) {
                    suffix++
                    candidate = `${finalConvoyName}-${suffix}`
                }
                finalConvoyName = candidate
                room = await prisma.room.create({
                    data: {
                        name: finalConvoyName,
                        ownerId: hostId,
                        diagnosticsEnabled: diagBool
                    },
                    select: {
                        id: true,
                        name: true,
                        diagnosticsEnabled: true
                    }
                })
            }
        } else {
            room = await prisma.room.create({
                data: {
                    name: finalConvoyName,
                    ownerId: hostId,
                    diagnosticsEnabled: diagBool
                },
                select: {
                    id: true,
                    name: true,
                    diagnosticsEnabled: true
                }
            })
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

        res.status(201).json({
            roomId: room.id,
            convoyName: room.name,
            hostId,
            diagnosticsEnabled: room.diagnosticsEnabled
        })
    } catch (error) {
        next(error)
    }
})

router.get('/:roomName/status', async (req, res, next) => {
    const { roomName } = req.params
    const requesterId = req.user.uid

    try {
        const cacheKey = `lobby_status:${roomName}`
        const cached = cacheService.get(cacheKey)
        if (cached) {
            const isAuthorized = cached.ownerId === requesterId || (cached.participantIds && cached.participantIds.includes(requesterId))
            if (isAuthorized) {
                return res.json({
                    ...cached.summary,
                    isHost: cached.ownerId === requesterId
                })
            }
        }

        let room = await prisma.room.findUnique({
            where: { name: roomName },
            select: {
                id: true,
                name: true,
                ownerId: true,
                diagnosticsEnabled: true,
                startedAt: true,
                invites: {
                    where: { status: { not: 'REMOVED' } },
                    select: {
                        id: true,
                        status: true,
                        inviteeId: true,
                        invitee: {
                            select: { id: true, handle: true, displayName: true, bikeModel: true }
                        }
                    }
                }
            }
        }).catch(() => null)

        if (!room && typeof prisma.room.findFirst === 'function') {
            room = await prisma.room.findFirst({
                where: {
                    OR: [
                        { id: roomName },
                        { name: roomName },
                        { name: { equals: roomName, mode: 'insensitive' } }
                    ]
                },
                select: {
                    id: true,
                    name: true,
                    ownerId: true,
                    diagnosticsEnabled: true,
                    startedAt: true,
                    invites: {
                        where: { status: { not: 'REMOVED' } },
                        select: {
                            id: true,
                            status: true,
                            inviteeId: true,
                            invitee: {
                                select: { id: true, handle: true, displayName: true, bikeModel: true }
                            }
                        }
                    }
                }
            }).catch(() => null)
        }

        if (!room) return res.status(404).json({ error: 'Room not found' })

        const isOwner = room.ownerId === requesterId
        const isInvited = room.invites.some(i => i.inviteeId === requesterId || i.invitee?.id === requesterId)

        if (!isOwner && !isInvited) {
            return res.status(403).json({ error: 'You are not a member of this convoy' })
        }

        let acceptedCount = 0
        let pendingCount = 0
        let declinedCount = 0

        const participantIds = [room.ownerId]
        const formattedInvites = new Array(room.invites.length)
        for (let i = 0; i < room.invites.length; i++) {
            const inv = room.invites[i]
            if (inv.invitee?.id) participantIds.push(inv.invitee.id)
            if (inv.inviteeId && !participantIds.includes(inv.inviteeId)) participantIds.push(inv.inviteeId)
            formattedInvites[i] = {
                inviteId: inv.id,
                status: inv.status,
                invitee: {
                    id: inv.invitee?.id || inv.inviteeId,
                    handle: inv.invitee?.handle || inv.invitee?.displayName || 'Rider',
                    displayName: inv.invitee?.displayName || inv.invitee?.handle || 'Rider',
                    bikeModel: inv.invitee?.bikeModel || null
                },
            }
            if (inv.status === 'ACCEPTED') acceptedCount++
            else if (inv.status === 'PENDING') pendingCount++
            else if (inv.status === 'DECLINED') declinedCount++
        }

        const isRideStarted = !!room.startedAt

        const summary = {
            roomId: room.id,
            convoyName: room.name,
            diagnosticsEnabled: room.diagnosticsEnabled,
            invites: formattedInvites,
            acceptedCount,
            pendingCount,
            declinedCount,
            canStart: true,
            isRideStarted,
            isHost: isOwner
        }

        cacheService.set(cacheKey, { ownerId: room.ownerId, participantIds, summary }, 1500)
        res.json(summary)
    } catch (error) {
        next(error)
    }
})

router.post('/:roomName/start', async (req, res, next) => {
    const { roomName } = req.params
    const user = req.user

    try {
        let room = await prisma.room.findUnique({
            where: { name: roomName },
            select: { id: true, name: true, ownerId: true, diagnosticsEnabled: true, startedAt: true }
        }).catch(() => null)
        if (!room && typeof prisma.room.findFirst === 'function') {
            room = await prisma.room.findFirst({
                where: {
                    OR: [
                        { id: roomName },
                        { name: roomName },
                        { name: { equals: roomName, mode: 'insensitive' } }
                    ]
                },
                select: { id: true, name: true, ownerId: true, diagnosticsEnabled: true, startedAt: true }
            }).catch(() => null)
        }

        if (!room) return res.status(404).json({ error: 'Room not found' })
        if (room.ownerId !== user.uid) return res.status(403).json({ error: 'Only the host can start the ride' })

        if (!room.startedAt) {
            await prisma.room.update({
                where: { id: room.id },
                data: { startedAt: new Date() }
            })
            cacheService.del(`lobby_status:${room.name}`)
            cacheService.del(`lobby_status:${room.id}`)
            cacheService.del(`lobby_status:${roomName}`)
        }

        const hostName = await resolveRiderDisplayName(user.uid, user.name || 'Host')

        const at = new AccessToken(
            process.env.LIVEKIT_API_KEY,
            process.env.LIVEKIT_API_SECRET,
            { identity: user.uid, name: hostName, ttl: '6h' }
        )
        at.addGrant({ roomJoin: true, room: room.name, canPublish: true, canSubscribe: true, canPublishData: true })
        const token = await at.toJwt()

        res.json({
            token,
            roomName: room.name,
            roomId: room.id,
            livekitUrl: process.env.LIVEKIT_URL || "wss://rider-com-app-u46cadz0.livekit.cloud",
            diagnosticsEnabled: room.diagnosticsEnabled
        })
    } catch (error) {
        next(error)
    }
})

router.post('/join-token', async (req, res, next) => {
    const { roomName, roomId } = req.body
    const user = req.user
    const target = String(roomId || roomName || '').trim()

    if (!target) return res.status(400).json({ error: 'roomName or roomId is required' })

    try {
        let room = await prisma.room.findUnique({
            where: { name: target },
            select: {
                id: true,
                name: true,
                ownerId: true,
                diagnosticsEnabled: true,
                invites: {
                    where: { inviteeId: user.uid, status: 'ACCEPTED' },
                    select: { id: true },
                    take: 1
                }
            }
        }).catch(() => null)
        if (!room && typeof prisma.room.findFirst === 'function') {
            room = await prisma.room.findFirst({
                where: {
                    OR: [
                        { id: target },
                        { name: target },
                        { name: { equals: target, mode: 'insensitive' } }
                    ]
                },
                select: {
                    id: true,
                    name: true,
                    ownerId: true,
                    diagnosticsEnabled: true,
                    invites: {
                        where: { inviteeId: user.uid, status: 'ACCEPTED' },
                        select: { id: true },
                        take: 1
                    }
                }
            }).catch(() => null)
        }
        if (!room) return res.status(404).json({ error: 'Room not found' })

        const isAuthorized = room.ownerId === user.uid || room.invites.length > 0
        if (!isAuthorized) {
            return res.status(403).json({ error: 'No accepted invite found for this room' })
        }

        cacheService.del(`lobby_status:${room.name}`)
        cacheService.del(`lobby_status:${room.id}`)
        cacheService.del(`lobby_status:${target}`)

        const riderName = await resolveRiderDisplayName(user.uid, user.name || 'Rider')

        const at = new AccessToken(
            process.env.LIVEKIT_API_KEY,
            process.env.LIVEKIT_API_SECRET,
            { identity: user.uid, name: riderName, ttl: '6h' }
        )
        at.addGrant({ roomJoin: true, room: room.name, canPublish: true, canSubscribe: true, canPublishData: true })
        const token = await at.toJwt()

        emailService.sendUserJoinedEmailSafe({
            userId: user.uid,
            userEmail: user.email,
            userName: user.name,
            roomName: room.name,
            joinedAt: new Date()
        })

        res.json({
            token,
            roomName: room.name,
            roomId: room.id,
            livekitUrl: process.env.LIVEKIT_URL || "wss://rider-com-app-u46cadz0.livekit.cloud",
            diagnosticsEnabled: room.diagnosticsEnabled
        })
    } catch (error) {
        next(error)
    }
})

router.post('/:roomName/share-link', async (req, res, next) => {
    const { roomName } = req.params
    const user = req.user

    try {
        let room = await prisma.room.findUnique({
            where: { name: roomName },
            select: {
                id: true,
                name: true,
                ownerId: true,
                invites: {
                    where: { inviteeId: user.uid, status: 'ACCEPTED' },
                    select: { id: true },
                    take: 1
                }
            }
        }).catch(() => null)
        if (!room && typeof prisma.room.findFirst === 'function') {
            room = await prisma.room.findFirst({
                where: {
                    OR: [
                        { id: roomName },
                        { name: roomName },
                        { name: { equals: roomName, mode: 'insensitive' } }
                    ]
                },
                select: {
                    id: true,
                    name: true,
                    ownerId: true,
                    invites: {
                        where: { inviteeId: user.uid, status: 'ACCEPTED' },
                        select: { id: true },
                        take: 1
                    }
                }
            }).catch(() => null)
        }
        if (!room) return res.status(404).json({ error: 'Room not found' })

        const isAuthorized = room.ownerId === user.uid || room.invites.length > 0
        if (!isAuthorized) {
            return res.status(403).json({ error: 'Only convoy participants can share join links' })
        }

        const token = crypto.randomBytes(8).toString('hex')
        const expiresAt = new Date(Date.now() + 24 * 60 * 60 * 1000)

        await prisma.roomJoinToken.create({
            data: {
                roomId: room.id,
                token,
                expiresAt
            },
            select: { id: true }
        })

        res.json({
            token,
            roomId: room.id,
            roomName: room.name,
            shareUrl: `ridervoice://join/${token}`,
            expiresAt: expiresAt.toISOString()
        })
    } catch (error) {
        next(error)
    }
})

router.post('/join-via-token', async (req, res, next) => {
    const { token } = req.body
    const user = req.user

    if (!token) return res.status(400).json({ error: 'token is required' })

    const cleanToken = extractCleanToken(token)
    if (!cleanToken) return res.status(400).json({ error: 'token is required' })

    try {
        let joinTokenRecord = await prisma.roomJoinToken.findUnique({
            where: { token: cleanToken },
            select: {
                expiresAt: true,
                room: {
                    select: {
                        id: true,
                        name: true,
                        ownerId: true,
                        diagnosticsEnabled: true
                    }
                }
            }
        })

        if (!joinTokenRecord && cleanToken.toLowerCase() !== cleanToken) {
            joinTokenRecord = await prisma.roomJoinToken.findUnique({
                where: { token: cleanToken.toLowerCase() },
                select: {
                    expiresAt: true,
                    room: {
                        select: {
                            id: true,
                            name: true,
                            ownerId: true,
                            diagnosticsEnabled: true
                        }
                    }
                }
            })
        }

        let room
        if (joinTokenRecord) {
            if (new Date() > joinTokenRecord.expiresAt) {
                return res.status(410).json({ error: 'Convoy join link has expired' })
            }
            room = joinTokenRecord.room
        } else {
            let roomByName = await prisma.room.findFirst({
                where: {
                    OR: [
                        { id: cleanToken },
                        { name: cleanToken },
                        { name: { equals: cleanToken, mode: 'insensitive' } }
                    ]
                },
                select: {
                    id: true,
                    name: true,
                    ownerId: true,
                    diagnosticsEnabled: true
                }
            })
            if (!roomByName) {
                return res.status(404).json({ error: 'Invalid or expired convoy link' })
            }
            room = roomByName
        }

        const existingInvite = await prisma.rideInvite.findFirst({
            where: { roomId: room.id, inviteeId: user.uid },
            select: { id: true }
        })

        if (existingInvite) {
            await prisma.rideInvite.update({
                where: { id: existingInvite.id },
                data: { status: 'ACCEPTED' },
                select: { id: true }
            })
        } else {
            await prisma.rideInvite.create({
                data: {
                    roomId: room.id,
                    inviterId: room.ownerId,
                    inviteeId: user.uid,
                    status: 'ACCEPTED'
                },
                select: { id: true }
            })
        }
        cacheService.del(`lobby_status:${room.name}`)
        cacheService.del(`lobby_status:${room.id}`)

        const riderName = await resolveRiderDisplayName(user.uid, user.name || 'Rider')

        const at = new AccessToken(
            process.env.LIVEKIT_API_KEY,
            process.env.LIVEKIT_API_SECRET,
            { identity: user.uid, name: riderName, ttl: '6h' }
        )
        at.addGrant({ roomJoin: true, room: room.name, canPublish: true, canSubscribe: true, canPublishData: true })
        const livekitToken = await at.toJwt()

        emailService.sendUserJoinedEmailSafe({
            userId: user.uid,
            userEmail: user.email,
            userName: user.name,
            roomName: room.name,
            joinedAt: new Date()
        })

        res.json({
            token: livekitToken,
            roomName: room.name,
            roomId: room.id,
            livekitUrl: process.env.LIVEKIT_URL || "wss://rider-com-app-u46cadz0.livekit.cloud",
            diagnosticsEnabled: room.diagnosticsEnabled
        })
    } catch (error) {
        next(error)
    }
})

router.delete('/:roomName/riders/:userId', async (req, res, next) => {
    const { roomName, userId } = req.params
    const hostId = req.user.uid

    try {
        let room = await prisma.room.findUnique({
            where: { name: roomName },
            select: { id: true, name: true, ownerId: true }
        }).catch(() => null)
        if (!room && typeof prisma.room.findFirst === 'function') {
            room = await prisma.room.findFirst({
                where: {
                    OR: [
                        { id: roomName },
                        { name: roomName },
                        { name: { equals: roomName, mode: 'insensitive' } }
                    ]
                },
                select: { id: true, name: true, ownerId: true }
            }).catch(() => null)
        }
        if (!room) return res.status(404).json({ error: 'Room not found' })

        if (room.ownerId !== hostId) {
            return res.status(403).json({ error: 'Only the host can remove riders' })
        }

        await prisma.rideInvite.updateMany({
            where: { roomId: room.id, inviteeId: userId },
            data: { status: 'REMOVED' }
        })
        cacheService.del(`lobby_status:${room.name}`)
        cacheService.del(`lobby_status:${room.id}`)
        cacheService.del(`lobby_status:${roomName}`)

        try {
            const httpUrl = getLiveKitHttpUrl()
            if (httpUrl && process.env.LIVEKIT_API_KEY && process.env.LIVEKIT_API_SECRET) {
                const svc = new RoomServiceClient(httpUrl, process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET)
                await svc.removeParticipant(room.name, userId)
            }
        } catch (lkError) {
            console.warn(`[LiveKit] removeParticipant notice for ${userId}:`, lkError.message)
        }

        res.json({ success: true, removedUserId: userId })
    } catch (error) {
        next(error)
    }
})

router.post('/:roomName/end', async (req, res, next) => {
    const { roomName } = req.params
    const hostId = req.user.uid

    try {
        let room = await prisma.room.findUnique({
            where: { name: roomName },
            select: { id: true, name: true, ownerId: true }
        }).catch(() => null)
        if (!room && typeof prisma.room.findFirst === 'function') {
            room = await prisma.room.findFirst({
                where: {
                    OR: [
                        { id: roomName },
                        { name: roomName },
                        { name: { equals: roomName, mode: 'insensitive' } }
                    ]
                },
                select: { id: true, name: true, ownerId: true }
            }).catch(() => null)
        }
        if (!room) return res.status(404).json({ error: 'Room not found' })

        if (room.ownerId !== hostId) {
            return res.status(403).json({ error: 'Only the host can end the convoy ride' })
        }

        try {
            const httpUrl = getLiveKitHttpUrl()
            if (httpUrl && process.env.LIVEKIT_API_KEY && process.env.LIVEKIT_API_SECRET) {
                const svc = new RoomServiceClient(httpUrl, process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET)
                try {
                    const dataPayload = Buffer.from(JSON.stringify({ type: 'CONVOY_ENDED', roomName: room.name }))
                    await svc.sendData(room.name, dataPayload, 0)
                } catch (sendErr) {
                    console.warn(`[LiveKit] sendData notice for ${room.name}:`, sendErr.message)
                }
                await svc.deleteRoom(room.name)
            }
        } catch (lkError) {
            console.warn(`[LiveKit] deleteRoom notice for ${room.name}:`, lkError.message)
        }

        try {
            const activeRun = await prisma.diagnosticRun.findFirst({
                where: {
                    room: { name: room.name },
                    status: 'ACTIVE'
                },
                include: { participants: true }
            })
            if (activeRun) {
                const allStopped = activeRun.participants.length > 0 && activeRun.participants.every(p => p.stoppedAt != null)
                if (allStopped) {
                    diagnosticService.closeAndExportRun(activeRun.id).catch(err => {
                        console.error(`[Diagnostic] Error exporting run ${activeRun.id} on ride end:`, err)
                    })
                } else {
                    const graceSec = parseInt(process.env.DIAG_CLOSE_GRACE_SECONDS || '90', 10)
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
            closedAt: new Date()
        })
        cacheService.del(`lobby_status:${room.name}`)
        cacheService.del(`lobby_status:${room.id}`)
        cacheService.del(`lobby_status:${roomName}`)

        res.json({ success: true, message: 'Convoy ended' })
    } catch (error) {
        next(error)
    }
})

router.post('/:roomName/transfer-host', async (req, res, next) => {
    const { roomName } = req.params
    const hostId = req.user.uid
    const { newHostId } = req.body

    if (!newHostId) return res.status(400).json({ error: 'newHostId is required' })

    try {
        let room = await prisma.room.findUnique({
            where: { name: roomName },
            select: { id: true, name: true, ownerId: true }
        }).catch(() => null)
        if (!room && typeof prisma.room.findFirst === 'function') {
            room = await prisma.room.findFirst({
                where: {
                    OR: [
                        { id: roomName },
                        { name: roomName },
                        { name: { equals: roomName, mode: 'insensitive' } }
                    ]
                },
                select: { id: true, name: true, ownerId: true }
            }).catch(() => null)
        }
        if (!room) return res.status(404).json({ error: 'Room not found' })

        if (room.ownerId !== hostId) {
            return res.status(403).json({ error: 'Only the current host can transfer convoy ownership' })
        }

        await prisma.room.update({
            where: { id: room.id },
            data: { ownerId: newHostId },
            select: { id: true }
        })

        cacheService.del(`lobby_status:${room.name}`)
        cacheService.del(`lobby_status:${room.id}`)
        cacheService.del(`lobby_status:${roomName}`)

        res.json({ success: true, newHostId })
    } catch (error) {
        next(error)
    }
})

async function updateDiagnosticsHandler(req, res, next) {
    const { roomName } = req.params
    const user = req.user
    const { diagnosticsEnabled } = req.body

    if (diagnosticsEnabled === undefined) {
        return res.status(400).json({ error: 'diagnosticsEnabled is required' })
    }

    try {
        let room = await prisma.room.findUnique({
            where: { name: roomName },
            select: { id: true, name: true, ownerId: true, startedAt: true }
        }).catch(() => null)
        if (!room && typeof prisma.room.findFirst === 'function') {
            room = await prisma.room.findFirst({
                where: {
                    OR: [
                        { id: roomName },
                        { name: roomName },
                        { name: { equals: roomName, mode: 'insensitive' } }
                    ]
                },
                select: { id: true, name: true, ownerId: true, startedAt: true }
            }).catch(() => null)
        }

        if (!room) return res.status(404).json({ error: 'Room not found' })

        if (room.ownerId !== user.uid) {
            return res.status(403).json({ error: 'Only the host can modify diagnostics settings' })
        }

        if (room.startedAt) {
            return res.status(400).json({ error: 'Cannot change diagnostics setting after ride has started' })
        }

        const updated = await prisma.room.update({
            where: { id: room.id },
            data: { diagnosticsEnabled: Boolean(diagnosticsEnabled) },
            select: { diagnosticsEnabled: true }
        })

        cacheService.del(`lobby_status:${room.name}`)
        cacheService.del(`lobby_status:${room.id}`)
        cacheService.del(`lobby_status:${roomName}`)

        res.json({ success: true, diagnosticsEnabled: updated.diagnosticsEnabled })
    } catch (error) {
        next(error)
    }
}

router.patch('/:roomName/diagnostics', updateDiagnosticsHandler)
router.put('/:roomName/diagnostics', updateDiagnosticsHandler)

module.exports = router
