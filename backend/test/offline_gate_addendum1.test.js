const { describe, it, before, after, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const prisma = require('../src/db')
const cacheService = require('../src/services/cacheService')

process.env.LIVEKIT_API_KEY = 'test_livekit_key'
process.env.LIVEKIT_API_SECRET = 'test_livekit_secret_1234567890123456'
process.env.LIVEKIT_URL = '' // Empty so livekit-server-sdk does not attempt real HTTP connection
process.env.NODE_ENV = 'test'

describe('Addendum 1: A1, A2, A3, A4 Offline Credential Timing & StartRideGate', () => {
    let app
    let server
    let baseUrl
    let currentUser = { uid: 'host_u1', name: 'Host Rider', email: 'host@test.com' }

    const originals = {}
    let mockRoom = null
    let mockInvites = []

    before(async () => {
        originals.roomFindUnique = prisma.room.findUnique
        originals.roomCreate = prisma.room.create
        originals.roomUpdate = prisma.room.update
        originals.roomUpdateMany = prisma.room.updateMany
        originals.roomFindFirst = prisma.room.findFirst
        originals.userFindUnique = prisma.user.findUnique
        originals.rideInviteFindFirst = prisma.rideInvite.findFirst
        originals.rideInviteFindMany = prisma.rideInvite.findMany
        originals.rideInviteFindUnique = prisma.rideInvite.findUnique
        originals.rideInviteCreate = prisma.rideInvite.create
        originals.rideInviteUpdate = prisma.rideInvite.update
        originals.rideInviteUpdateMany = prisma.rideInvite.updateMany
        originals.rideInviteCount = prisma.rideInvite.count
        originals.$transaction = prisma.$transaction
        originals.diagnosticRunFindFirst = prisma.diagnosticRun?.findFirst

        prisma.$transaction = async (fn) => (typeof fn === 'function' ? fn(prisma) : fn)
        if (!prisma.rideCreateRequest) prisma.rideCreateRequest = {}
        originals.rideCreateRequestFindUnique = prisma.rideCreateRequest?.findUnique
        originals.rideCreateRequestCreate = prisma.rideCreateRequest?.create
        prisma.rideCreateRequest.findUnique = async () => null
        prisma.rideCreateRequest.create = async ({ data }) => ({ id: 'req_1', ...data })

        prisma.user.findUnique = async () => null
        if (prisma.diagnosticRun) {
            prisma.diagnosticRun.findFirst = async () => null
        }

        app = express()
        app.use(express.json())
        app.use((req, res, next) => {
            req.user = currentUser
            next()
        })

        const lobbyRoutes = require('../src/routes/lobbyRoutes')
        app.use('/api/lobby', lobbyRoutes)

        server = app.listen(0)
        await new Promise(resolve => server.once('listening', resolve))
        baseUrl = `http://127.0.0.1:${server.address().port}`
    })

    after(async () => {
        prisma.room.findUnique = originals.roomFindUnique
        prisma.room.create = originals.roomCreate
        prisma.room.update = originals.roomUpdate
        prisma.room.updateMany = originals.roomUpdateMany
        prisma.room.findFirst = originals.roomFindFirst
        prisma.user.findUnique = originals.userFindUnique
        prisma.rideInvite.findFirst = originals.rideInviteFindFirst
        prisma.rideInvite.findMany = originals.rideInviteFindMany
        prisma.rideInvite.findUnique = originals.rideInviteFindUnique
        prisma.rideInvite.create = originals.rideInviteCreate
        prisma.rideInvite.update = originals.rideInviteUpdate
        prisma.rideInvite.updateMany = originals.rideInviteUpdateMany
        prisma.rideInvite.count = originals.rideInviteCount
        prisma.$transaction = originals.$transaction
        if (prisma.rideCreateRequest) {
            prisma.rideCreateRequest.findUnique = originals.rideCreateRequestFindUnique
            prisma.rideCreateRequest.create = originals.rideCreateRequestCreate
        }
        if (server) await new Promise(resolve => server.close(resolve))
    })

    beforeEach(() => {
        cacheService.clear()
        mockRoom = {
            id: '11111111-1111-4111-8111-111111111111',
            name: 'Convoy-Alpha',
            lkRoomName: 'rv_11111111-1111-4111-8111-111111111111',
            ownerId: 'host_u1',
            status: 'LOBBY',
            diagnosticsEnabled: false,
            offlineMeshEnabled: true,
            joinCode: 'ABC12345',
            offlineBackupCode: 'K7QX-M2PA-9TDE-4WZH',
            startedAt: null,
            invites: []
        }
        mockInvites = []

        prisma.room.findFirst = async (args) => {
            const w = args?.where
            if (!w) return { ...mockRoom, invites: mockInvites }
            if (w.id === mockRoom.id || w.id?.equals === mockRoom.id) return { ...mockRoom, invites: mockInvites }
            if (w.name === mockRoom.name || w.name?.equals === mockRoom.name || w.name?.equals?.toLowerCase() === mockRoom.name.toLowerCase()) return { ...mockRoom, invites: mockInvites }
            if (w.lkRoomName === mockRoom.lkRoomName) return { ...mockRoom, invites: mockInvites }
            if (w.joinCode === mockRoom.joinCode) return { ...mockRoom, invites: mockInvites }
            if (w.OR) return { ...mockRoom, invites: mockInvites }
            return null
        }
        prisma.room.findUnique = async (args) => {
            const w = args?.where
            if (w?.id === mockRoom.id || w?.name === mockRoom.name) {
                return { ...mockRoom, invites: mockInvites }
            }
            return null
        }
        prisma.room.update = async (args) => {
            Object.assign(mockRoom, args.data)
            return mockRoom
        }
        prisma.room.updateMany = async (args) => {
            Object.assign(mockRoom, args.data)
            return { count: 1 }
        }
        prisma.user.findUnique = async () => null
        prisma.rideInvite.findUnique = async (args) => {
            const uid = args?.where?.roomId_inviteeId?.inviteeId
            return mockInvites.find(i => i.invitee?.id === uid) || null
        }
        prisma.rideInvite.findMany = async () => mockInvites
        prisma.rideInvite.count = async (args) => {
            const st = args?.where?.status
            return mockInvites.filter(i => !st || i.status === st).length
        }
        prisma.rideInvite.updateMany = async () => ({ count: 1 })
    })

    describe('A1: Credential timing', () => {
        it('POST /create generates offlineBackupCode and returns it immediately in 201', async () => {
            currentUser = { uid: 'host_u1', name: 'Host Rider' }
            prisma.room.findFirst = async () => null // no name conflict
            prisma.room.create = async (args) => {
                assert.ok(args.data.offlineBackupCode, 'roomData must include offlineBackupCode')
                assert.strictEqual(args.data.offlineBackupCode.length, 16, 'Code must be 16 chars Crockford')
                return { ...args.data, ownerId: 'host_u1' }
            }

            const res = await fetch(`${baseUrl}/api/lobby/create`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ convoyName: 'NewRideTest' })
            })

            assert.strictEqual(res.status, 201)
            const json = await res.json()
            assert.ok(json.offlineBackupCode, '201 response must contain offlineBackupCode')
            assert.strictEqual(json.offlineBackupCode.length, 16)
        })

        it('GET /status returns offlineBackupCode to accepted member before start', async () => {
            currentUser = { uid: 'rider_accepted', name: 'Accepted Rider' }
            mockInvites = [{
                id: 'inv_1',
                status: 'ACCEPTED',
                source: 'HOST_INVITE',
                createdAt: new Date(),
                invitee: { id: 'rider_accepted', displayName: 'Accepted Rider' }
            }]

            const res = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/status`)
            assert.strictEqual(res.status, 200)
            const json = await res.json()
            assert.strictEqual(json.offlineBackupCode, 'K7QX-M2PA-9TDE-4WZH')
            assert.ok(json.serverTime, 'Response must carry serverTime')
        })

        it('GET /status does not deliver offlineBackupCode to unaccepted / non-member user (403)', async () => {
            currentUser = { uid: 'stranger_user', name: 'Stranger' }
            mockInvites = []

            const res = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/status`)
            assert.strictEqual(res.status, 403)
        })

        it('GET /status lazily backfills offlineBackupCode if room was created without one', async () => {
            currentUser = { uid: 'host_u1', name: 'Host Rider' }
            mockRoom.offlineBackupCode = null
            mockInvites = []

            const res = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/status`)
            assert.strictEqual(res.status, 200)
            const json = await res.json()
            assert.ok(json.offlineBackupCode, 'Must backfill offlineBackupCode')
            assert.strictEqual(json.offlineBackupCode.length, 16)
            assert.strictEqual(mockRoom.offlineBackupCode, json.offlineBackupCode)
        })
    })

    describe('A2: Server-side staleness and StartRideGate', () => {
        it('rider who reports CONFIGURING and is present blocks start for the host', async () => {
            currentUser = { uid: 'host_u1', name: 'Host' }
            const now = Date.now()
            mockInvites = [{
                id: 'inv_1',
                roomId: mockRoom.id,
                inviteeId: 'rider_active',
                status: 'ACCEPTED',
                source: 'HOST_INVITE',
                createdAt: new Date(now),
                invitee: { id: 'rider_active', displayName: 'Active Rider' }
            }]
            // Set rider presence active (last seen 5s ago)
            cacheService.set(`lobby_presence:${mockRoom.id}:rider_active`, now - 5000, 60)
            cacheService.set(`offline_config:${mockRoom.id}:rider_active`, {
                status: 'CONFIGURING',
                updatedAt: new Date(now).toISOString(),
                updatedAtMs: now
            }, 60)

            const statusRes = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/status`)
            const statusJson = await statusRes.json()
            assert.strictEqual(statusJson.canStart, false, 'canStart must be false while rider is CONFIGURING')
            assert.strictEqual(statusJson.blockingRidersCount, 1)

            const startRes = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/start`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            })
            assert.strictEqual(startRes.status, 400)
            const startJson = await startRes.json()
            assert.strictEqual(startJson.code, 'WAITING_ROOM_CONFIGURING')
        })

        it('rider whose last activity is > 90s server time is TIMED_OUT and does not block start', async () => {
            currentUser = { uid: 'host_u1', name: 'Host' }
            const now = Date.now()
            // Joined 120s ago, last config updated 100s ago
            const activityTime = now - 100_000
            mockInvites = [{
                id: 'inv_1',
                status: 'ACCEPTED',
                source: 'HOST_INVITE',
                createdAt: new Date(now - 120_000),
                invitee: { id: 'rider_stale', displayName: 'Stale Rider' }
            }]
            // Even if presence heartbeat was sent, >90s config activity triggers server TIMED_OUT
            cacheService.set(`lobby_presence:${mockRoom.id}:rider_stale`, now - 10000, 60)
            cacheService.set(`offline_config:${mockRoom.id}:rider_stale`, {
                status: 'CONFIGURING',
                updatedAt: new Date(activityTime).toISOString(),
                updatedAtMs: activityTime
            }, 300)

            const statusRes = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/status`)
            const statusJson = await statusRes.json()
            assert.strictEqual(statusJson.canStart, true, 'canStart must be true after 90s server timeout')
            assert.strictEqual(statusJson.blockingRidersCount, 0)
            assert.strictEqual(statusJson.invites[0].offlineConfigStatus, 'TIMED_OUT')

            const startRes = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/start`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            })
            assert.strictEqual(startRes.status, 200, 'Host can start ride after 90s timeout')
        })

        it('rider who vanished (>30s no lobby poll) is absent and does not block start', async () => {
            currentUser = { uid: 'host_u1', name: 'Host' }
            const now = Date.now()
            mockInvites = [{
                id: 'inv_1',
                status: 'ACCEPTED',
                source: 'HOST_INVITE',
                createdAt: new Date(now - 10000),
                invitee: { id: 'rider_ghost', displayName: 'Ghost Rider' }
            }]
            // No presence in cache or > 30s ago
            cacheService.set(`lobby_presence:${mockRoom.id}:rider_ghost`, now - 45000, 60)
            cacheService.set(`offline_config:${mockRoom.id}:rider_ghost`, {
                status: 'CONFIGURING',
                updatedAt: new Date(now - 10000).toISOString(),
                updatedAtMs: now - 10000
            }, 60)

            const statusRes = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/status`)
            const statusJson = await statusRes.json()
            assert.strictEqual(statusJson.canStart, true, 'Absent rider does not block start')
            assert.strictEqual(statusJson.invites[0].isPresent, false)

            const startRes = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/start`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            })
            assert.strictEqual(startRes.status, 200)
        })

        it('rider reporting terminal status (READY, OPTED_OUT, UNAVAILABLE) allows start immediately', async () => {
            currentUser = { uid: 'host_u1', name: 'Host' }
            const now = Date.now()
            mockInvites = [{
                id: 'inv_1',
                status: 'ACCEPTED',
                source: 'HOST_INVITE',
                createdAt: new Date(now),
                invitee: { id: 'rider_ready', displayName: 'Ready Rider' }
            }]
            cacheService.set(`lobby_presence:${mockRoom.id}:rider_ready`, now, 60)
            cacheService.set(`offline_config:${mockRoom.id}:rider_ready`, {
                status: 'READY',
                updatedAt: new Date(now).toISOString(),
                updatedAtMs: now
            }, 60)

            const statusRes = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/status`)
            const statusJson = await statusRes.json()
            assert.strictEqual(statusJson.canStart, true)
            assert.strictEqual(statusJson.blockingRidersCount, 0)
        })

        it('rider presence detected from DB column lastLobbyPollAt even when in-memory cache is empty', async () => {
            currentUser = { uid: 'host_u1', name: 'Host' }
            const now = Date.now()
            mockInvites = [{
                id: 'inv_1',
                status: 'ACCEPTED',
                source: 'HOST_INVITE',
                createdAt: new Date(now),
                offlineConfigStatus: 'CONFIGURING',
                offlineConfigUpdatedAt: new Date(now),
                lastLobbyPollAt: new Date(now - 5000), // polled 5s ago in DB
                invitee: { id: 'rider_db_present', displayName: 'DB Present Rider' }
            }]
            // Cache is empty (simulating server restart or separate instance)
            cacheService.clear()

            const startRes = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/start`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            })
            assert.strictEqual(startRes.status, 400)
            const json = await startRes.json()
            assert.strictEqual(json.code, 'WAITING_ROOM_CONFIGURING')
            assert.strictEqual(json.blockingRiderId, 'rider_db_present')
        })
    })

    describe('A3: Host changes and room-level offline switch', () => {
        it('PATCH /offline-mesh to false marks all riders OPTED_OUT and unblocks start', async () => {
            currentUser = { uid: 'host_u1', name: 'Host' }
            const now = Date.now()
            mockInvites = [{
                id: 'inv_1',
                status: 'ACCEPTED',
                source: 'HOST_INVITE',
                createdAt: new Date(now),
                invitee: { id: 'rider_cfg', displayName: 'Configuring Rider' }
            }]
            cacheService.set(`lobby_presence:${mockRoom.id}:rider_cfg`, now, 60)
            cacheService.set(`offline_config:${mockRoom.id}:rider_cfg`, {
                status: 'CONFIGURING',
                updatedAt: new Date(now).toISOString(),
                updatedAtMs: now
            }, 60)

            // Initially blocked
            let statusRes = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/status`)
            let statusJson = await statusRes.json()
            assert.strictEqual(statusJson.canStart, false)

            // Host toggles offline backup OFF for the room
            const patchRes = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/offline-mesh`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled: false })
            })
            assert.strictEqual(patchRes.status, 200)
            mockRoom.offlineMeshEnabled = false

            // Now start is unblocked, rider status reflects OPTED_OUT
            statusRes = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/status`)
            statusJson = await statusRes.json()
            assert.strictEqual(statusJson.canStart, true)
            assert.strictEqual(statusJson.invites[0].offlineConfigStatus, 'OPTED_OUT')
        })

        it('POST /offline-config updates rider status and server records updatedAt', async () => {
            currentUser = { uid: 'rider_1', name: 'Rider 1' }
            const res = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/offline-config`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ status: 'READY' })
            })
            assert.strictEqual(res.status, 200)
            const json = await res.json()
            assert.strictEqual(json.status, 'READY')
            assert.ok(json.offlineConfigUpdatedAt)
            assert.ok(json.serverTime)
        })

        it('turning offline switch back ON re-runs config gate and blocks start if rider is CONFIGURING', async () => {
            currentUser = { uid: 'host_u1', name: 'Host' }
            const now = Date.now()
            mockInvites = [{
                id: 'inv_1',
                status: 'ACCEPTED',
                source: 'HOST_INVITE',
                createdAt: new Date(now),
                invitee: { id: 'rider_cfg2', displayName: 'Configuring Rider 2' }
            }]
            cacheService.set(`lobby_presence:${mockRoom.id}:rider_cfg2`, now, 60)
            cacheService.set(`offline_config:${mockRoom.id}:rider_cfg2`, {
                status: 'CONFIGURING',
                updatedAtMs: now
            }, 60)

            // Turn ON
            const patchRes = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/offline-mesh`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled: true })
            })
            assert.strictEqual(patchRes.status, 200)
            mockRoom.offlineMeshEnabled = true

            // Now start is blocked again
            const statusRes = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/status`)
            const statusJson = await statusRes.json()
            assert.strictEqual(statusJson.canStart, false)
            assert.strictEqual(statusJson.blockingRidersCount, 1)
        })

        it('host transfer preserves rider configs and allows new host to see gate and start', async () => {
            currentUser = { uid: 'host_u1', name: 'Host' }
            const now = Date.now()
            mockInvites = [
                {
                    id: 'inv_2',
                    status: 'ACCEPTED',
                    source: 'HOST_INVITE',
                    createdAt: new Date(now),
                    inviteeId: 'rider_new_host',
                    invitee: { id: 'rider_new_host', displayName: 'New Host Rider' }
                },
                {
                    id: 'inv_3',
                    status: 'ACCEPTED',
                    source: 'HOST_INVITE',
                    createdAt: new Date(now),
                    inviteeId: 'rider_3',
                    invitee: { id: 'rider_3', displayName: 'Rider Three' }
                }
            ]
            cacheService.set(`lobby_presence:${mockRoom.id}:rider_3`, now, 60)
            cacheService.set(`offline_config:${mockRoom.id}:rider_3`, {
                status: 'READY',
                updatedAtMs: now
            }, 60)

            // Mock findUnique for newHostInvite check in transfer-host
            const origInviteFindUnique = prisma.rideInvite.findUnique
            prisma.rideInvite.findUnique = async () => ({ status: 'ACCEPTED' })
            prisma.rideInvite.upsert = async () => ({})
            prisma.roomJoinToken = { updateMany: async () => ({ count: 0 }) }

            // Host transfers to rider_new_host
            const transferRes = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/transfer-host`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ newHostId: 'rider_new_host' })
            })
            const transferText = await transferRes.text()
            assert.strictEqual(transferRes.status, 200, `transfer failed: ${transferText}`)
            mockRoom.ownerId = 'rider_new_host'

            // As new host, check status and start
            currentUser = { uid: 'rider_new_host', name: 'New Host Rider' }
            const statusRes = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/status`)
            const statusJson = await statusRes.json()
            assert.strictEqual(statusJson.isHost, true)
            assert.strictEqual(statusJson.canStart, true)

            const startRes = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/start`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            })
            assert.strictEqual(startRes.status, 200)

            prisma.rideInvite.findUnique = origInviteFindUnique
        })

        it('host leave is rejected with HOST_CANNOT_LEAVE and POST /end cleanly ends the convoy', async () => {
            currentUser = { uid: 'host_u1', name: 'Host' }
            mockRoom.ownerId = 'host_u1'
            mockRoom.status = 'LOBBY'

            // Host tries to leave
            const leaveRes = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/leave`, {
                method: 'POST'
            })
            assert.strictEqual(leaveRes.status, 400)
            const leaveJson = await leaveRes.json()
            assert.strictEqual(leaveJson.code, 'HOST_CANNOT_LEAVE')

            // Host ends the convoy
            const endRes = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/end`, {
                method: 'POST'
            })
            assert.strictEqual(endRes.status, 200)
            assert.strictEqual(mockRoom.status, 'ENDED')
        })
    })

    describe('A4: Backward compatibility of start call with NO body', () => {
        it('POST /start with no body succeeds and defaults allowSolo to false', async () => {
            currentUser = { uid: 'host_u1', name: 'Host' }
            mockInvites = [{
                id: 'inv_1',
                status: 'ACCEPTED',
                source: 'HOST_INVITE',
                createdAt: new Date(),
                invitee: { id: 'rider_accepted', displayName: 'Accepted Rider' }
            }]
            // Rider is READY
            cacheService.set(`offline_config:${mockRoom.id}:rider_accepted`, {
                status: 'READY',
                updatedAtMs: Date.now()
            }, 60)

            // Call POST with completely empty body (simulate legacy client)
            const res = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/start`, {
                method: 'POST'
            })
            assert.strictEqual(res.status, 200, 'Must accept start call with NO body')
            const json = await res.json()
            assert.ok(json.token, 'Must return token')
        })

        it('POST /start with no body and 0 accepted riders fails with NO_ACCEPTED_RIDERS (allowSolo=false)', async () => {
            currentUser = { uid: 'host_u1', name: 'Host' }
            mockInvites = [] // 0 accepted riders

            const res = await fetch(`${baseUrl}/api/lobby/Convoy-Alpha/start`, {
                method: 'POST'
            })
            assert.strictEqual(res.status, 400)
            const json = await res.json()
            assert.strictEqual(json.code, 'NO_ACCEPTED_RIDERS')
        })

        it('concurrent POST /start returns matching token payload for both callers without error', async () => {
            currentUser = { uid: 'host_u1', name: 'Host' }
            mockRoom.status = 'LOBBY'
            mockInvites = [{
                id: 'inv_1',
                status: 'ACCEPTED',
                source: 'HOST_INVITE',
                createdAt: new Date(),
                invitee: { id: 'rider_ready', displayName: 'Ready' }
            }]
            cacheService.set(`offline_config:${mockRoom.id}:rider_ready`, {
                status: 'READY',
                updatedAtMs: Date.now()
            }, 60)

            // Simulate 2 parallel calls
            const [res1, res2] = await Promise.all([
                fetch(`${baseUrl}/api/lobby/Convoy-Alpha/start`, { method: 'POST' }),
                fetch(`${baseUrl}/api/lobby/Convoy-Alpha/start`, { method: 'POST' })
            ])

            assert.strictEqual(res1.status, 200)
            assert.strictEqual(res2.status, 200)
            const json1 = await res1.json()
            const json2 = await res2.json()
            assert.ok(json1.token)
            assert.ok(json2.token)
            assert.strictEqual(mockRoom.status, 'ACTIVE')
        })
    })
})
