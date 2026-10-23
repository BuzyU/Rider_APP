const { describe, it, before, after } = require('node:test')
const assert = require('node:assert')
const express = require('express')
const { createWebhookHandler, handleLiveKitEvent } = require('../src/routes/livekitWebhook')
const landingRouter = require('../src/routes/landingRoutes')
const { sweepStaleRooms, _activeRoomEmptySince } = require('../src/services/roomSweeper')
const prisma = require('../src/db')

describe('Part D: LiveKit Webhook, Landing Page, and Stale Room Sweeper', () => {
    let server
    let baseUrl

    const originals = {
        roomFindUnique: prisma.room.findUnique,
        roomFindFirst: prisma.room.findFirst,
        roomFindMany: prisma.room.findMany,
        roomUpdate: prisma.room.update,
        roomUpdateMany: prisma.room.updateMany,
        roomJoinTokenFindUnique: prisma.roomJoinToken.findUnique,
        roomJoinTokenUpdateMany: prisma.roomJoinToken.updateMany,
        rideInviteFindFirst: prisma.rideInvite.findFirst
    }

    before(async () => {
        const app = express()
        app.use('/j', landingRouter)

        // Webhook handler mounted with mock receiver injection
        const fakeReceiver = {
            receive: async (rawBody, auth) => {
                if (auth !== 'Bearer valid_sig') {
                    throw new Error('invalid signature')
                }
                return JSON.parse(rawBody)
            }
        }
        app.post('/api/livekit/webhook', express.raw({ type: '*/*' }), createWebhookHandler({ receiver: fakeReceiver }))

        await new Promise(resolve => {
            server = app.listen(0, () => {
                const port = server.address().port
                baseUrl = `http://127.0.0.1:${port}`
                resolve()
            })
        })
    })

    after(async () => {
        prisma.room.findUnique = originals.roomFindUnique
        prisma.room.findFirst = originals.roomFindFirst
        prisma.room.findMany = originals.roomFindMany
        prisma.room.update = originals.roomUpdate
        prisma.room.updateMany = originals.roomUpdateMany
        prisma.roomJoinToken.findUnique = originals.roomJoinTokenFindUnique
        prisma.roomJoinToken.updateMany = originals.roomJoinTokenUpdateMany
        prisma.rideInvite.findFirst = originals.rideInviteFindFirst

        if (server) {
            await new Promise(r => server.close(r))
        }
    })

    describe('D1: LiveKit Webhook Defense', () => {
        it('returns 204 if LIVEKIT_WEBHOOK_ENABLED is not true', async () => {
            const oldVal = process.env.LIVEKIT_WEBHOOK_ENABLED
            process.env.LIVEKIT_WEBHOOK_ENABLED = 'false'
            try {
                const res = await fetch(`${baseUrl}/api/livekit/webhook`, {
                    method: 'POST',
                    headers: { 'Authorization': 'Bearer valid_sig', 'Content-Type': 'application/json' },
                    body: JSON.stringify({ event: 'participant_joined' })
                })
                assert.strictEqual(res.status, 204)
            } finally {
                process.env.LIVEKIT_WEBHOOK_ENABLED = oldVal
            }
        })

        it('returns 401 when signature verification fails', async () => {
            const oldVal = process.env.LIVEKIT_WEBHOOK_ENABLED
            process.env.LIVEKIT_WEBHOOK_ENABLED = 'true'
            try {
                const res = await fetch(`${baseUrl}/api/livekit/webhook`, {
                    method: 'POST',
                    headers: { 'Authorization': 'Bearer bad_sig', 'Content-Type': 'application/json' },
                    body: JSON.stringify({ event: 'participant_joined' })
                })
                assert.strictEqual(res.status, 401)
            } finally {
                process.env.LIVEKIT_WEBHOOK_ENABLED = oldVal
            }
        })

        it('kicks participant if identity is not host and not accepted member', async () => {
            let kickedIdentity = null
            const mockClient = {
                removeParticipant: async (roomName, identity) => {
                    kickedIdentity = identity
                }
            }

            prisma.room.findFirst = async () => ({
                id: 'room-101',
                name: 'Convoy-101',
                ownerId: 'host-user',
                status: 'ACTIVE'
            })
            prisma.rideInvite.findFirst = async () => null // not accepted

            const event = {
                event: 'participant_joined',
                room: { name: 'Convoy-101' },
                participant: { identity: 'unauthorized_rider', metadata: JSON.stringify({ roomId: 'room-101' }) }
            }

            await handleLiveKitEvent(event, { roomServiceClient: mockClient })
            assert.strictEqual(kickedIdentity, 'unauthorized_rider')
        })

        it('does NOT kick participant if identity is host or accepted member', async () => {
            let kickedIdentity = null
            const mockClient = {
                removeParticipant: async (roomName, identity) => {
                    kickedIdentity = identity
                }
            }

            prisma.room.findFirst = async () => ({
                id: 'room-101',
                name: 'Convoy-101',
                ownerId: 'host-user',
                status: 'ACTIVE'
            })
            prisma.rideInvite.findFirst = async () => ({ id: 'inv-1', status: 'ACCEPTED' })

            const event = {
                event: 'participant_joined',
                room: { name: 'Convoy-101' },
                participant: { identity: 'valid_member', metadata: JSON.stringify({ roomId: 'room-101' }) }
            }

            await handleLiveKitEvent(event, { roomServiceClient: mockClient })
            assert.strictEqual(kickedIdentity, null)
        })

        it('kicks participant if metadata roomId does not match DB room.id', async () => {
            let kickedIdentity = null
            const mockClient = {
                removeParticipant: async (roomName, identity) => {
                    kickedIdentity = identity
                }
            }

            prisma.room.findFirst = async () => ({
                id: 'room-101',
                name: 'Convoy-101',
                ownerId: 'host-user',
                status: 'ACTIVE'
            })

            const event = {
                event: 'participant_joined',
                room: { name: 'Convoy-101' },
                participant: { identity: 'host-user', metadata: JSON.stringify({ roomId: 'wrong-room-id' }) }
            }

            await handleLiveKitEvent(event, { roomServiceClient: mockClient })
            assert.strictEqual(kickedIdentity, 'host-user')
        })
    })

    describe('D2: HTTPS Share Landing Page GET /j/:token', () => {
        it('renders valid landing page with convoy name for active token', async () => {
            prisma.roomJoinToken.findUnique = async () => ({
                id: 'tok-1',
                token: 'validtoken123',
                expiresAt: new Date(Date.now() + 86400000),
                revokedAt: null,
                maxUses: 10,
                useCount: 2,
                room: { id: 'room-1', name: 'Sunday Ghat Run', status: 'LOBBY' }
            })

            const res = await fetch(`${baseUrl}/j/validtoken123`)
            assert.strictEqual(res.status, 200)
            const html = await res.text()
            assert.ok(html.includes('Sunday Ghat Run'))
            assert.ok(html.includes('intent://join/validtoken123'))
            assert.ok(html.includes('Open in RiderVoice'))
            assert.strictEqual(res.headers.get('cache-control'), 'no-store, no-cache, must-revalidate, proxy-revalidate')
            assert.strictEqual(res.headers.get('x-robots-tag'), 'noindex, nofollow')
        })

        it('returns 410 for expired, revoked, or max-used token with no convoy name leakage', async () => {
            prisma.roomJoinToken.findUnique = async () => ({
                id: 'tok-2',
                token: 'expiredtoken',
                expiresAt: new Date(Date.now() - 3600000), // expired 1h ago
                revokedAt: null,
                maxUses: 10,
                useCount: 2,
                room: { id: 'room-1', name: 'Secret Secret Ride', status: 'LOBBY' }
            })

            const res = await fetch(`${baseUrl}/j/expiredtoken`)
            assert.strictEqual(res.status, 410)
            const html = await res.text()
            assert.ok(html.includes('Invite Unavailable'))
            assert.ok(!html.includes('Secret Secret Ride'))
        })

        it('returns 404 for malformed token format', async () => {
            const res = await fetch(`${baseUrl}/j/!@#invalid`)
            assert.strictEqual(res.status, 404)
        })
    })

    describe('D3: Stale Room Sweeper', () => {
        it('sweeps lobbies older than 6h without accepted riders', async () => {
            let sweptEndedId = null
            prisma.room.findMany = async (args) => {
                if (args.where?.status === 'LOBBY') {
                    return [{ id: 'stale-lobby-1', name: 'Empty Lobby' }]
                }
                return []
            }
            prisma.room.updateMany = async (args) => {
                sweptEndedId = args.where.id
                return { count: 1 }
            }
            prisma.roomJoinToken.updateMany = async () => ({ count: 1 })

            const mockClient = { deleteRoom: async () => {} }
            const swept = await sweepStaleRooms({ roomServiceClient: mockClient })

            assert.strictEqual(swept.length, 1)
            assert.strictEqual(swept[0].id, 'stale-lobby-1')
            assert.strictEqual(swept[0].reason, 'EXPIRED_LOBBY')
        })

        it('sweeps active rooms empty for > 15 minutes', async () => {
            prisma.room.findMany = async (args) => {
                if (args.where?.status === 'ACTIVE' && !args.where?.OR) {
                    return [{ id: 'empty-active-1', name: 'Deserted Run' }]
                }
                return []
            }
            prisma.room.updateMany = async () => ({ count: 1 })
            prisma.roomJoinToken.updateMany = async () => ({ count: 0 })

            const mockClient = {
                listParticipants: async () => [],
                deleteRoom: async () => {}
            }

            // Simulate first observation 20 minutes ago
            _activeRoomEmptySince.set('empty-active-1', Date.now() - 20 * 60 * 1000)

            const swept = await sweepStaleRooms({ roomServiceClient: mockClient })
            assert.strictEqual(swept.length, 1)
            assert.strictEqual(swept[0].id, 'empty-active-1')
            assert.strictEqual(swept[0].reason, 'ABANDONED')
        })
    })
})
