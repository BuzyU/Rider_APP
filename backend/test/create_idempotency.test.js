const { describe, it, before, after } = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const prisma = require('../src/db')

process.env.LIVEKIT_API_KEY = 'test_livekit_key'
process.env.LIVEKIT_API_SECRET = 'test_livekit_secret_1234567890123456'
process.env.LIVEKIT_URL = 'http://localhost:7880'
process.env.NODE_ENV = 'test'

describe('P0 Workstream 1: Durable Create Idempotency (RM-2, RM-3, RM-7, SEC-3)', () => {
    let app
    let server
    let baseUrl
    let currentUser = { uid: 'owner_alpha', name: 'Alpha Rider', email: 'alpha@ridervoice.test' }

    const originals = {}
    const inMemoryRooms = new Map()
    const inMemoryIdempotency = new Map()

    before(async () => {
        originals.roomCreate = prisma.room.create
        originals.roomFindUnique = prisma.room.findUnique
        originals.roomFindFirst = prisma.room.findFirst
        originals.$transaction = prisma.$transaction
        originals.rideCreateRequestFindUnique = prisma.rideCreateRequest?.findUnique
        originals.rideCreateRequestCreate = prisma.rideCreateRequest?.create
        originals.diagnosticRunFindFirst = prisma.diagnosticRun.findFirst

        app = express()
        app.use(express.json())
        app.use((req, res, next) => {
            req.user = currentUser
            next()
        })

        const lobbyRoutes = require('../src/routes/lobbyRoutes')
        app.use('/api/lobby', lobbyRoutes)

        // Mock DB implementation backing RideCreateRequest and Room
        prisma.room.findFirst = async ({ where }) => {
            const nameLower = where?.name?.equals?.toLowerCase()
            for (const r of inMemoryRooms.values()) {
                if (r.name.toLowerCase() === nameLower && r.status !== 'ENDED') {
                    return r
                }
            }
            return null
        }

        prisma.room.findUnique = async ({ where }) => {
            if (where.id) return inMemoryRooms.get(where.id) || null
            if (where.name) {
                for (const r of inMemoryRooms.values()) {
                    if (r.name === where.name) return r
                }
            }
            return null
        }

        prisma.room.create = async ({ data }) => {
            const roomId = `room_${Date.now()}_${Math.random().toString(36).substring(7)}`
            const roomRecord = {
                id: roomId,
                name: data.name,
                ownerId: data.ownerId,
                status: 'LOBBY',
                diagnosticsEnabled: data.diagnosticsEnabled ?? false,
                joinCode: data.joinCode || 'JOIN1234',
                origin: data.origin || null,
                destination: data.destination || null,
                meetupPoint: data.meetupPoint || null,
                estimatedDurationMin: data.estimatedDurationMin || null
            }
            inMemoryRooms.set(roomId, roomRecord)
            return roomRecord
        }

        if (!prisma.rideCreateRequest) {
            prisma.rideCreateRequest = {}
        }

        prisma.rideCreateRequest.findUnique = async ({ where }) => {
            const key = `${where.ownerId_clientRequestId.ownerId}:${where.ownerId_clientRequestId.clientRequestId}`
            const found = inMemoryIdempotency.get(key)
            if (!found) return null
            const room = inMemoryRooms.get(found.roomId)
            return {
                ...found,
                room
            }
        }

        prisma.rideCreateRequest.create = async ({ data }) => {
            const key = `${data.ownerId}:${data.clientRequestId}`
            if (inMemoryIdempotency.has(key)) {
                const err = new Error('Unique constraint failed on the fields: (`ownerId`,`clientRequestId`)')
                err.code = 'P2002'
                err.meta = { target: ['ownerId', 'clientRequestId'] }
                throw err
            }
            const record = {
                id: `req_${Date.now()}_${Math.random().toString(36).substring(7)}`,
                ...data,
                createdAt: new Date()
            }
            inMemoryIdempotency.set(key, record)
            return record
        }

        prisma.$transaction = async (fn) => {
            return await fn(prisma)
        }

        prisma.diagnosticRun.findFirst = async () => null

        server = app.listen(0)
        await new Promise(resolve => server.once('listening', resolve))
        baseUrl = `http://127.0.0.1:${server.address().port}`
    })

    after(async () => {
        prisma.room.create = originals.roomCreate
        prisma.room.findUnique = originals.roomFindUnique
        prisma.room.findFirst = originals.roomFindFirst
        prisma.$transaction = originals.$transaction
        if (originals.rideCreateRequestFindUnique) {
            prisma.rideCreateRequest.findUnique = originals.rideCreateRequestFindUnique
        }
        if (originals.rideCreateRequestCreate) {
            prisma.rideCreateRequest.create = originals.rideCreateRequestCreate
        }
        prisma.diagnosticRun.findFirst = originals.diagnosticRunFindFirst

        if (server) {
            await new Promise(r => server.close(r))
        }
    })

    it('20 concurrent identical requests with same clientRequestId produce exactly one room and identical results', async () => {
        currentUser = { uid: 'owner_alpha', name: 'Alpha Rider', email: 'alpha@ridervoice.test' }
        const clientRequestId = 'req_concurrent_test_1'
        const payload = {
            convoyName: 'Concurrent Sunday Cruise',
            origin: 'Downtown',
            destination: 'Beach',
            clientRequestId
        }

        const requests = Array.from({ length: 20 }, () =>
            fetch(`${baseUrl}/api/lobby/create`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            })
        )

        const responses = await Promise.all(requests)
        for (const res of responses) {
            assert.ok(res.status === 200 || res.status === 201, `Status was ${res.status}`)
        }

        const bodies = await Promise.all(responses.map(r => r.json()))
        const firstRoomId = bodies[0].roomId
        for (const body of bodies) {
            assert.strictEqual(body.roomId, firstRoomId, 'All responses must return the same roomId')
            assert.strictEqual(body.convoyName, 'Concurrent Sunday Cruise', 'Must not have Name-2 suffix')
        }

        // Verify only 1 room was actually created in the DB
        let matchingRooms = 0
        for (const r of inMemoryRooms.values()) {
            if (r.name.startsWith('Concurrent Sunday Cruise')) {
                matchingRooms++
            }
        }
        assert.strictEqual(matchingRooms, 1, 'Only one database room should have been created')
    })

    it('reusing clientRequestId with a different payload returns HTTP 409 IDEMPOTENCY_KEY_REUSED', async () => {
        currentUser = { uid: 'owner_alpha', name: 'Alpha Rider', email: 'alpha@ridervoice.test' }
        const clientRequestId = 'req_reused_key_diff_payload'

        // First create
        const res1 = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                convoyName: 'Initial Ride Name',
                clientRequestId
            })
        })
        assert.strictEqual(res1.status, 201)
        const body1 = await res1.json()

        // Second create with same key but altered payload
        const res2 = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                convoyName: 'Completely Different Name',
                clientRequestId
            })
        })
        assert.strictEqual(res2.status, 409)
        const body2 = await res2.json()
        assert.strictEqual(body2.code, 'IDEMPOTENCY_KEY_REUSED')

        // Original room remains untouched
        const originalRoom = inMemoryRooms.get(body1.roomId)
        assert.strictEqual(originalRoom.name, 'Initial Ride Name')
    })

    it('cross-owner replay cannot read another owner’s room or join code', async () => {
        const clientRequestId = 'shared_key_between_users'

        // Owner 1 creates room
        currentUser = { uid: 'owner_first', name: 'First User', email: 'first@test.com' }
        const res1 = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                convoyName: 'First Owner Convoy',
                clientRequestId
            })
        })
        assert.strictEqual(res1.status, 201)
        const body1 = await res1.json()

        // Owner 2 tries to use same clientRequestId with their own payload
        currentUser = { uid: 'owner_second', name: 'Second User', email: 'second@test.com' }
        const res2 = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                convoyName: 'Second Owner Convoy',
                clientRequestId
            })
        })
        assert.strictEqual(res2.status, 201)
        const body2 = await res2.json()

        // Completely separate rooms, no cross-contamination
        assert.notStrictEqual(body1.roomId, body2.roomId)
        assert.strictEqual(body1.convoyName, 'First Owner Convoy')
        assert.strictEqual(body2.convoyName, 'Second Owner Convoy')
        assert.notStrictEqual(body1.joinCode, body2.joinCode)
    })

    it('retry after timeout returns the original room with 200 without creating Name-2', async () => {
        currentUser = { uid: 'owner_retry', name: 'Retry User', email: 'retry@test.com' }
        const clientRequestId = 'req_timeout_retry_key'
        const payload = {
            convoyName: 'Mountain Vista Pass',
            origin: 'Base Camp',
            destination: 'Summit',
            clientRequestId
        }

        // First attempt (assume network timeout dropped the response on the client)
        const res1 = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
        assert.strictEqual(res1.status, 201)
        const body1 = await res1.json()
        assert.strictEqual(body1.convoyName, 'Mountain Vista Pass')

        // Client retry with exact same request ID and payload
        const res2 = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
        assert.strictEqual(res2.status, 200)
        const body2 = await res2.json()
        assert.strictEqual(body2.roomId, body1.roomId)
        assert.strictEqual(body2.convoyName, 'Mountain Vista Pass', 'Must NOT be Mountain Vista Pass-2')
    })

    it('different owner with the same active convoy name receives safe 409 without join code leakage', async () => {
        // Owner A creates an active convoy
        currentUser = { uid: 'owner_a', name: 'Owner A', email: 'ownera@test.com' }
        const resA = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                convoyName: 'Popular Sunset Ride',
                clientRequestId: 'key_owner_a'
            })
        })
        assert.strictEqual(resA.status, 201)

        // Owner B attempts to create with the exact same name
        currentUser = { uid: 'owner_b', name: 'Owner B', email: 'ownerb@test.com' }
        const resB = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                convoyName: 'Popular Sunset Ride',
                clientRequestId: 'key_owner_b'
            })
        })
        assert.strictEqual(resB.status, 409)
        const bodyB = await resB.json()
        assert.strictEqual(bodyB.code, 'ROOM_NAME_TAKEN')
        assert.strictEqual(bodyB.joinCode, undefined, 'Must not leak joinCode of the existing room')
    })

    it('returns 503 SCHEMA_DEGRADED without creating non-canonical room when RideCreateRequest table does not exist (P2021)', async () => {
        currentUser = { uid: 'owner_fallback_p2021', name: 'Fallback Rider', email: 'fallback@test.com' }
        const origFindUnique = prisma.rideCreateRequest.findUnique
        const origCreate = prisma.rideCreateRequest.create

        // Simulate P2021 on RideCreateRequest
        const p2021Err = new Error('The table `public.RideCreateRequest` does not exist in the current database.')
        p2021Err.code = 'P2021'
        prisma.rideCreateRequest.findUnique = async () => { throw p2021Err }
        prisma.rideCreateRequest.create = async () => { throw p2021Err }

        try {
            const clientReqId = 'p2021_req_test_1'
            const res1 = await fetch(`${baseUrl}/api/lobby/create`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    convoyName: 'Resilient Ride P2021',
                    clientRequestId: clientReqId
                })
            })
            assert.strictEqual(res1.status, 503, 'P2021 structural failure must return 503 SCHEMA_DEGRADED')
            const body1 = await res1.json()
            assert.strictEqual(body1.code, 'SCHEMA_DEGRADED')
        } finally {
            prisma.rideCreateRequest.findUnique = origFindUnique
            prisma.rideCreateRequest.create = origCreate
        }
    })

    it('returns 503 SCHEMA_DEGRADED without creating non-canonical room when lkRoomName column does not exist (P2022)', async () => {
        currentUser = { uid: 'owner_fallback_p2022', name: 'Fallback Rider 2', email: 'fallback2@test.com' }
        const origRoomCreate = prisma.room.create

        // Simulate P2022 when lkRoomName is present in data
        const p2022Err = new Error('The column `lkRoomName` does not exist in the current database.')
        p2022Err.code = 'P2022'
        p2022Err.meta = { modelName: 'Room', column: 'lkRoomName' }

        prisma.room.create = async ({ data, select }) => {
            if (data.lkRoomName !== undefined) {
                throw p2022Err
            }
            return origRoomCreate({ data, select })
        }

        try {
            const res = await fetch(`${baseUrl}/api/lobby/create`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    convoyName: 'Resilient Ride P2022',
                    clientRequestId: 'p2022_req_test_1'
                })
            })
            assert.strictEqual(res.status, 503, 'P2022 structural failure must return 503 SCHEMA_DEGRADED')
            const body = await res.json()
            assert.strictEqual(body.code, 'SCHEMA_DEGRADED')
        } finally {
            prisma.room.create = origRoomCreate
        }
    })
})
