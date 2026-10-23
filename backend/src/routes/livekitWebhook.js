const express = require('express')
const router = express.Router()
const { WebhookReceiver, RoomServiceClient } = require('livekit-server-sdk')
const prisma = require('../db')
const { resolveRoom, markRoomEnded, invalidateRoom } = require('../services/roomResolver')
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
            const room = await resolveRoom(lkRoomName, { allowEnded: true })

            // 1. If DB room is ENDED -> kick
            if (room.status === 'ENDED') {
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
        } catch (err) {
            console.warn(`[webhook] Kicking ${identity}: room resolution failed: ${err.message}`)
            await client.removeParticipant(lkRoomName, identity).catch(e => console.warn(`[webhook] removeParticipant warning: ${e.message}`))
        }
    } else if (eventType === 'room_finished') {
        try {
            const room = await resolveRoom(lkRoomName, { allowEnded: true })
            if (room && room.status !== 'ENDED') {
                console.log(`[webhook] Room finished: marking room ${room.id} ENDED`)
                await markRoomEnded(room.id)
                await prisma.roomJoinToken.updateMany({
                    where: { roomId: room.id, revokedAt: null },
                    data: { revokedAt: new Date() }
                })
                invalidateRoom(room.id)

                if (diagnosticService && typeof diagnosticService.scheduleIdleRunClose === 'function') {
                    await diagnosticService.scheduleIdleRunClose(room.name, room.id).catch(() => null)
                }
            }
        } catch (err) {
            console.warn(`[webhook] room_finished error for ${lkRoomName}: ${err.message}`)
        }
    }
}

function createWebhookHandler(deps = {}) {
    return async (req, res) => {
        if (process.env.LIVEKIT_WEBHOOK_ENABLED !== 'true') {
            return res.sendStatus(204)
        }
        try {
            const receiver = deps.receiver || new WebhookReceiver(
                process.env.LIVEKIT_API_KEY || '',
                process.env.LIVEKIT_API_SECRET || ''
            )
            const rawBody = Buffer.isBuffer(req.body)
                ? req.body.toString('utf8')
                : (typeof req.body === 'string' ? req.body : JSON.stringify(req.body))
            const authHeader = req.get('Authorization')
            const event = await receiver.receive(rawBody, authHeader)
            await handleLiveKitEvent(event, deps)
            res.sendStatus(200)
        } catch (e) {
            console.warn('[webhook] rejected:', e.message)
            res.sendStatus(401)
        }
    }
}

router.post('/webhook', createWebhookHandler())

module.exports = {
    router,
    createWebhookHandler,
    handleLiveKitEvent
}
