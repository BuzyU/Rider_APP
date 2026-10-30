'use strict'

const { describe, it, before, after } = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const prisma = require('../src/db')

process.env.LIVEKIT_API_KEY = 'test_livekit_key'
process.env.LIVEKIT_API_SECRET = 'test_livekit_secret_1234567890123456'
process.env.LIVEKIT_URL = 'http://localhost:7880'
process.env.NODE_ENV = 'test'

describe('Step 0: Route Handler Smoke Tests (Comprehensive Undefined Symbol Regression Prevention)', () => {
    let app
    let server
    let baseUrl
    let currentUser = { uid: 'host_001', id: 'host_001', name: 'Host Rider', email: 'host@ridervoice.test' }

    const originals = {}

    const mockRoom = {
        id: 'room_smoke_1',
        name: 'Smoke Test Convoy',
        lkRoomName: 'rv_room_smoke_1',
        ownerId: 'host_001',
        status: 'LOBBY',
        diagnosticsEnabled: false,
        joinCode: '778899',
        origin: 'Mumbai',
        destination: 'Pune',
        meetupPoint: 'Gateway',
        estimatedDurationMin: 120,
        createdAt: new Date(),
        updatedAt: new Date(),
        owner: { id: 'host_001', displayName: 'Host Rider', handle: 'host', bikeModel: 'Ducati' },
        invites: [
            {
                id: 'inv_smoke_1',
                roomId: 'room_smoke_1',
                inviteeId: 'rider_002',
                inviterId: 'host_001',
                status: 'ACCEPTED',
                source: 'HOST_INVITE',
                invitee: { id: 'rider_002', displayName: 'Second Rider', handle: 'rider2' }
            }
        ]
    }

    before(async () => {
        // Save prisma originals
        originals.roomFindUnique = prisma.room.findUnique
        originals.roomFindFirst = prisma.room.findFirst
        originals.roomCreate = prisma.room.create
        originals.roomUpdate = prisma.room.update
        originals.roomUpdateMany = prisma.room.updateMany
        originals.userFindUnique = prisma.user.findUnique
        originals.rideInviteFindUnique = prisma.rideInvite.findUnique
        originals.rideInviteFindFirst = prisma.rideInvite.findFirst
        originals.rideInviteFindMany = prisma.rideInvite.findMany
        originals.rideInviteCreate = prisma.rideInvite.create
        originals.rideInviteUpdate = prisma.rideInvite.update
        originals.rideInviteUpdateMany = prisma.rideInvite.updateMany
        originals.rideInviteCount = prisma.rideInvite.count
        originals.rideCreateRequestFindUnique = prisma.rideCreateRequest?.findUnique
        originals.rideCreateRequestCreate = prisma.rideCreateRequest?.create
        originals.emergencyAlertCreate = prisma.emergencyAlert?.create
        originals.emergencyAlertFindFirst = prisma.emergencyAlert?.findFirst
        originals.emergencyAlertUpdate = prisma.emergencyAlert?.update
        originals.diagnosticRunFindFirst = prisma.diagnosticRun?.findFirst
        originals.diagnosticRunCreate = prisma.diagnosticRun?.create
        originals.diagnosticParticipantCreate = prisma.diagnosticParticipant?.create
        originals.deviceTokenFindMany = prisma.deviceToken?.findMany
        originals.$transaction = prisma.$transaction

        prisma.$transaction = async (fn) => (typeof fn === 'function' ? fn(prisma) : fn)
        prisma.room.findUnique = async ({ where }) => {
            if (where.id === 'room_smoke_1' || where.name === 'Smoke Test Convoy') return mockRoom
            return null
        }
        prisma.room.findFirst = async ({ where }) => {
            if (where.name?.equals === 'Smoke Test Convoy' || where.name === 'Smoke Test Convoy') return mockRoom
            return null
        }
        prisma.room.create = async ({ data }) => ({
            id: data.id || 'room_smoke_new',
            name: data.name,
            lkRoomName: data.lkRoomName || `rv_${data.id}`,
            ownerId: data.ownerId,
            diagnosticsEnabled: data.diagnosticsEnabled || false,
            joinCode: data.joinCode || '123456',
            status: 'LOBBY'
        })
        prisma.room.update = async ({ data }) => ({ ...mockRoom, ...data })
        prisma.room.updateMany = async () => ({ count: 1 })
        prisma.user.findUnique = async ({ where }) => {
            if (where.id === 'host_001') return { id: 'host_001', displayName: 'Host Rider', handle: 'host', email: 'host@test.com' }
            if (where.id === 'rider_002') return { id: 'rider_002', displayName: 'Second Rider', handle: 'rider2', email: 'rider2@test.com' }
            return null
        }
        prisma.rideInvite.findUnique = async ({ where }) => {
            if (where.id === 'inv_smoke_1') return mockRoom.invites[0]
            if (where.roomId_inviteeId) return mockRoom.invites[0]
            return null
        }
        prisma.rideInvite.findMany = async () => mockRoom.invites
        prisma.rideInvite.count = async () => 1
        prisma.rideInvite.create = async ({ data }) => ({ id: 'inv_new', ...data })
        prisma.rideInvite.update = async ({ where, data }) => ({ id: where.id, ...data })
        prisma.rideInvite.updateMany = async () => ({ count: 1 })
        if (!prisma.rideCreateRequest) prisma.rideCreateRequest = {}
        prisma.rideCreateRequest.findUnique = async () => null
        prisma.rideCreateRequest.create = async ({ data }) => ({ id: 'req_smoke_123', ...data })

        if (prisma.deviceToken) {
            prisma.deviceToken.findMany = async () => []
        }
        if (prisma.emergencyAlert) {
            prisma.emergencyAlert.create = async ({ data }) => ({ id: 'alert_1', ...data, status: 'SENT' })
            prisma.emergencyAlert.findFirst = async () => ({ id: 'alert_1', status: 'SENT', roomId: 'room_smoke_1' })
            prisma.emergencyAlert.update = async ({ data }) => ({ id: 'alert_1', ...data, status: 'RESOLVED' })
        }

        app = express()
        app.use(express.json())
        const requestIdMiddleware = require('../src/middleware/requestIdMiddleware')
        app.use(requestIdMiddleware)

        // Mock auth middleware for smoke test
        app.use((req, res, next) => {
            req.user = currentUser
            next()
        })

        // Mount routers
        const lobbyRoutes = require('../src/routes/lobbyRoutes')
        const inviteRoutes = require('../src/routes/inviteRoutes')
        const emergencyRoutes = require('../src/routes/emergencyRoutes')
        const roomRoutes = require('../src/routes/roomRoutes')
        const healthRoute = require('../src/routes/healthRoute')

        app.use('/api/lobby', lobbyRoutes)
        app.use('/api/invites', inviteRoutes)
        app.use('/api/emergency', emergencyRoutes)
        app.use('/api/rooms', roomRoutes)
        app.use('/health', healthRoute)
        app.use('/ready', healthRoute)

        const errorMiddleware = require('../src/middleware/errorMiddleware')
        app.use(errorMiddleware)

        server = app.listen(0)
        await new Promise(resolve => server.once('listening', resolve))
        baseUrl = `http://127.0.0.1:${server.address().port}`
    })

    after(async () => {
        prisma.room.findUnique = originals.roomFindUnique
        prisma.room.findFirst = originals.roomFindFirst
        prisma.room.create = originals.roomCreate
        prisma.room.update = originals.roomUpdate
        prisma.room.updateMany = originals.roomUpdateMany
        prisma.user.findUnique = originals.userFindUnique
        prisma.rideInvite.findUnique = originals.rideInviteFindUnique
        prisma.rideInvite.findFirst = originals.rideInviteFindFirst
        prisma.rideInvite.findMany = originals.rideInviteFindMany
        prisma.rideInvite.create = originals.rideInviteCreate
        prisma.rideInvite.update = originals.rideInviteUpdate
        prisma.rideInvite.updateMany = originals.rideInviteUpdateMany
        prisma.rideInvite.count = originals.rideInviteCount
        if (prisma.rideCreateRequest) {
            prisma.rideCreateRequest.findUnique = originals.rideCreateRequestFindUnique
            prisma.rideCreateRequest.create = originals.rideCreateRequestCreate
        }
        if (originals.emergencyAlertCreate) prisma.emergencyAlert.create = originals.emergencyAlertCreate
        if (originals.emergencyAlertFindFirst) prisma.emergencyAlert.findFirst = originals.emergencyAlertFindFirst
        if (originals.emergencyAlertUpdate) prisma.emergencyAlert.update = originals.emergencyAlertUpdate
        if (originals.deviceTokenFindMany) prisma.deviceToken.findMany = originals.deviceTokenFindMany
        prisma.$transaction = originals.$transaction

        if (server) {
            await new Promise(r => server.close(r))
        }
    })

    it('GET /api/lobby/status calls riderCallsign cleanly without 500 or ReferenceError', async () => {
        const res = await fetch(`${baseUrl}/api/lobby/${encodeURIComponent('Smoke Test Convoy')}/status`)
        assert.notStrictEqual(res.status, 500, 'GET /lobby/status must never return 500')
        assert.strictEqual(res.status, 200)
        const body = await res.json()
        assert.strictEqual(body.convoyName, 'Smoke Test Convoy')
        assert.strictEqual(body.isHost, true)
        assert.strictEqual(Array.isArray(body.invites), true)
        assert.ok(res.headers.get('x-request-id'), 'X-Request-Id header must be present on responses')
    })

    it('POST /api/lobby/create executes cleanly and returns 201 with canonical lkRoomName', async () => {
        const res = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ convoyName: 'New Unique Convoy', clientRequestId: 'req_smoke_123' })
        })
        assert.notStrictEqual(res.status, 500)
        assert.strictEqual(res.status, 201)
        const body = await res.json()
        assert.strictEqual(body.convoyName, 'New Unique Convoy')
    })

    it('POST /api/lobby/join handles request cleanly without undefined references', async () => {
        const res = await fetch(`${baseUrl}/api/lobby/join`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ joinCode: '778899' })
        })
        assert.notStrictEqual(res.status, 500)
        assert.ok([200, 400, 403, 404, 409, 410].includes(res.status))
    })

    it('POST /api/lobby/transfer-host executes cleanly without ReferenceError', async () => {
        const res = await fetch(`${baseUrl}/api/lobby/transfer-host`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ roomName: 'Smoke Test Convoy', newHostId: 'rider_002' })
        })
        assert.notStrictEqual(res.status, 500)
        assert.ok([200, 400, 403, 404, 410].includes(res.status))
    })

    it('POST /api/lobby/end terminates room cleanly', async () => {
        const res = await fetch(`${baseUrl}/api/lobby/${encodeURIComponent('Smoke Test Convoy')}/end`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' }
        })
        assert.notStrictEqual(res.status, 500)
        assert.strictEqual(res.status, 200)
    })

    it('POST /api/invites/respond executes cleanly without ReferenceError (cacheService check)', async () => {
        currentUser = { uid: 'rider_002', id: 'rider_002', name: 'Second Rider', email: 'rider2@test.com' }
        try {
            const res = await fetch(`${baseUrl}/api/invites/respond`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ inviteId: 'inv_smoke_1', response: 'ACCEPTED' })
            })
            assert.notStrictEqual(res.status, 500)
            assert.strictEqual(res.status, 200)
        } finally {
            currentUser = { uid: 'host_001', id: 'host_001', name: 'Host Rider', email: 'host@ridervoice.test' }
        }
    })

    it('POST /api/emergency/alert executes cleanly without ReferenceError', async () => {
        const res = await fetch(`${baseUrl}/api/emergency/alert`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ roomId: 'room_smoke_1', lat: 18.5204, lng: 73.8567 })
        })
        assert.notStrictEqual(res.status, 500)
        assert.ok([200, 201, 400, 404].includes(res.status))
    })

    it('Health and Ready endpoints return 200', async () => {
        const healthRes = await fetch(`${baseUrl}/health`)
        assert.strictEqual(healthRes.status, 200)

        const readyRes = await fetch(`${baseUrl}/ready`)
        assert.ok([200, 503].includes(readyRes.status))
    })
})
