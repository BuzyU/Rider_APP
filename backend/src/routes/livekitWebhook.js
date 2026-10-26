const express = require('express')
const router = express.Router()
const { WebhookReceiver, RoomServiceClient } = require('livekit-server-sdk')
const prisma = require('../db')
const { resolveRoom, markRoomEnded, invalidateRoom, ResolverError } = require('../services/roomResolver')
const diagnosticService = require('../services/diagnosticService')

function getRoomServiceClient() {
    let host = process.env.LIVEKIT_URL || ''
    if (host.startsWith('wss://')) host = host.replace('wss://', 'https://')
    else if (host.startsWith('ws://')) host = host.replace('ws://', 'http://')
    return new RoomServiceClient(host, process.env.LIVEKIT_API_KEY || 'test_key', process.env.LIVEKIT_API_SECRET || 'test_secret')
}

async function handleLiveKitEvent(event, deps = {}) {
    if (!event || !event.event) return

    const client = deps.roomServiceClient || getRoomServiceClient()
    const eventType = event.event
    const lkRoom = event.room || {}
    const lkRoomName = lkRoom.name

    if (!lkRoomName) return

    if (eventType === 'participant_joined') {
        const participant = event.participant || {}
        const identity = participant.identity
        if (!identity) return

        let metadataObj = null
        if (participant.metadata) {
            try {
                metadataObj = typeof participant.metadata === 'string'
                    ? JSON.parse(participant.metadata)
                    : participant.metadata
            } catch (_) {
                metadataObj = null
            }
        }

        try {
            const room = await resolveRoom(lkRoomName, { allowEnded: true, select: { lastEmptyAt: true } })

            // 1. If DB room is ENDED -> kick only if matched by exact LiveKit room identity, never on display-name fallback
            if (room.status === 'ENDED') {
                const matchedByExactLkIdentity = (room.lkRoomName && room.lkRoomName === lkRoomName) || room.id === lkRoomName
                if (!matchedByExactLkIdentity) {
                    console.warn(`[webhook] Ignoring display-name fallback match to ENDED room ${room.id} (${room.name}) for live LiveKit room ${lkRoomName}`)
                    return
                }
                console.warn(`[webhook] Kicking ${identity}: room ${room.id} is ENDED`)
                await client.removeParticipant(lkRoomName, identity).catch(err => console.warn(`[webhook] removeParticipant warning: ${err.message}`))
                return
            }

            // 2. If metadata roomId differs from room.id -> kick
            if (metadataObj && metadataObj.roomId && metadataObj.roomId !== room.id) {
                console.warn(`[webhook] Kicking ${identity}: metadata roomId ${metadataObj.roomId} != room.id ${room.id}`)
                await client.removeParticipant(lkRoomName, identity).catch(err => console.warn(`[webhook] removeParticipant warning: ${err.message}`))
                return
            }

            // 3. Identity must be owner or ACCEPTED member
            const isOwner = room.ownerId === identity
            let isAcceptedMember = false
            if (!isOwner) {
                const invite = await prisma.rideInvite.findFirst({
                    where: {
                        roomId: room.id,
                        inviteeId: identity,
                        status: 'ACCEPTED'
                    }
                })
                isAcceptedMember = !!invite
            }

            if (!isOwner && !isAcceptedMember) {
                console.warn(`[webhook] Kicking ${identity}: not host and not ACCEPTED member of room ${room.id}`)
                await client.removeParticipant(lkRoomName, identity).catch(err => console.warn(`[webhook] removeParticipant warning: ${err.message}`))
                return
            }

            // R1: If participant rejoins an active room that had lastEmptyAt set, clear it
            if (room.lastEmptyAt && typeof prisma.room?.update === 'function') {
                await prisma.room.update({
                    where: { id: room.id },
                    data: { lastEmptyAt: null }
                }).catch(() => null)
            }
        } catch (err) {
            // R2 fix: Only kick on ResolverError 404/410. On DB errors/timeouts, log and DO NOT kick.
            if (err instanceof ResolverError && (err.status === 404 || err.status === 410 || err.code === 'ROOM_NOT_FOUND' || err.code === 'ROOM_ENDED')) {
                console.warn(`[webhook] Kicking ${identity}: room resolution confirmed invalid/ended (${err.code})`)
                await client.removeParticipant(lkRoomName, identity).catch(e => console.warn(`[webhook] removeParticipant warning: ${e.message}`))
            } else {
                console.warn(`[webhook] Transient error during room resolution for ${identity}: ${err.message}. Retaining participant.`)
            }
        }
    } else if (eventType === 'room_finished') {
        try {
            const room = await resolveRoom(lkRoomName, { allowEnded: true, select: { lastEmptyAt: true } })
            if (!room || room.status === 'ENDED') {
                return
            }
            if (room.status === 'ACTIVE' || room.status === 'LOBBY') {
                // R1 fix: Tunnel / temporary loss protection: DO NOT end room immediately.
                // Record lastEmptyAt in DB. The background room sweeper will end after 15 min empty.
                console.log(`[webhook] Room finished: recording lastEmptyAt for ${room.status} room ${room.id}`)
                if (typeof prisma.room?.update === 'function') {
                    await prisma.room.update({
                        where: { id: room.id },
                        data: { lastEmptyAt: new Date() }
                    }).catch(err => console.warn(`[webhook] failed to set lastEmptyAt: ${err.message}`))
                }
            }
        } catch (err) {
            console.warn(`[webhook] room_finished error for ${lkRoomName}: ${err.message}`)
        }
    }
}

const webhookStatus = {
    lastSuccessfulEvent: null,
    lastProcessingError: null
}

function getWebhookStatus() {
    return { ...webhookStatus }
}

function createWebhookHandler(deps = {}) {
    return async (req, res) => {
        if (process.env.LIVEKIT_WEBHOOK_ENABLED !== 'true') {
            return res.sendStatus(204)
        }

        let event
        try {
            const receiver = deps.receiver || new WebhookReceiver(
                process.env.LIVEKIT_API_KEY || '',
                process.env.LIVEKIT_API_SECRET || ''
            )
            const rawBody = Buffer.isBuffer(req.body)
                ? req.body.toString('utf8')
                : (typeof req.body === 'string' ? req.body : JSON.stringify(req.body))
            const authHeader = req.get('Authorization')
            event = await receiver.receive(rawBody, authHeader)
        } catch (e) {
            console.warn('[webhook] rejected - invalid signature:', e.message)
            return res.status(401).json({ error: 'Invalid webhook signature' })
        }

        try {
            await handleLiveKitEvent(event, deps)
            webhookStatus.lastSuccessfulEvent = new Date().toISOString()
            res.sendStatus(200)
        } catch (err) {
            console.error('[webhook] processing error:', err.message)
            webhookStatus.lastProcessingError = {
                timestamp: new Date().toISOString(),
                error: err.message
            }
            res.status(500).json({ error: 'Webhook processing error', retryable: true })
        }
    }
}

router.post('/webhook', createWebhookHandler())

module.exports = {
    router,
    createWebhookHandler,
    handleLiveKitEvent,
    getWebhookStatus
}
