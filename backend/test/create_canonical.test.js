'use strict'

const { describe, it, before, after, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const crypto = require('crypto')
const prisma = require('../src/db')

process.env.LIVEKIT_API_KEY = 'test_livekit_key'
process.env.LIVEKIT_API_SECRET = 'test_livekit_secret_1234567890123456'
process.env.LIVEKIT_URL = 'http://localhost:7880'
process.env.NODE_ENV = 'test'

describe('Finding V3-4: Canonical Create Route, Idempotency & Schema Degradation', () => {
    let app
    let server
    let baseUrl
    let currentUser = { uid: 'user_host_1', id: 'user_host_1', name: 'Host Rider', email: 'host@test.com' }

    const originals = {}
    let existingRequests = new Map()
    let existingRooms = new Map()
    let simulateStructuralFailure = false

    before(async () => {
        originals.roomFindUnique = prisma.room.findUnique
        originals.roomFindFirst = prisma.room.findFirst
        originals.roomCreate = prisma.room.create
        originals.userFindUnique = prisma.user.findUnique
        originals.rideCreateRequestFindUnique = prisma.rideCreateRequest?.findUnique
        originals.rideCreateRequestCreate = prisma.rideCreateRequest?.create
        originals.$transaction = prisma.$transaction

        if (!prisma.rideCreateRequest) prisma.rideCreateRequest = {}

        prisma.$transaction = async (fn) => {
            if (simulateStructuralFailure) {
                const err = new Error('Column "lkRoomName" does not exist')
                err.code = 'P2022'
                throw err
            }
            return typeof fn === 'function' ? fn(prisma) : fn
        }

        prisma.rideCreateRequest.findUnique = async ({ where }) => {
            const key = `${where.ownerId_clientRequestId.ownerId}:${where.ownerId_clientRequestId.clientRequestId}`
            const req = existingRequests.get(key)
            if (!req) return null
            return {
                ...req,
                room: existingRooms.get(req.roomId) || null
            }
        }

        prisma.rideCreateRequest.create = async ({ data }) => {
            const key = `${data.ownerId}:${data.clientRequestId}`
            const row = { id: `req_${Date.now()}`, ...data }
            existingRequests.set(key, row)
            return row
        }

        prisma.room.findUnique = async ({ where }) => {
            if (where.id) return existingRooms.get(where.id) || null
            return null
        }

        prisma.room.findFirst = async ({ where }) => {
            for (const room of existingRooms.values()) {
                if (where.joinCode && room.joinCode === where.joinCode) {
                    if (!where.status || (where.status.not && room.status !== where.status.not)) return room
                }
                const nameFilter = where.name?.equals || where.name
                if (nameFilter && room.name.toLowerCase() === nameFilter.toLowerCase()) {
                    if (where.status && where.status.not && room.status === where.status.not) continue
                    return room
                }
            }
            return null
        }

        prisma.room.create = async ({ data }) => {
            if (simulateStructuralFailure) {
                const err = new Error('Column "lkRoomName" does not exist')
                err.code = 'P2022'
                throw err
            }
            const room = {
                id: data.id || `room_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
                name: data.name,
                lkRoomName: data.lkRoomName || `rv_${data.id}`,
                ownerId: data.ownerId,
                status: 'LOBBY',
                joinCode: data.joinCode || 'ABCD2345',
                createdAt: new Date(),
                updatedAt: new Date()
            }
            existingRooms.set(room.id, room)
            return room
        }

        prisma.user.findUnique = async ({ where }) => {
            return { id: where.id, displayName: 'Host Rider', handle: 'host', email: 'host@test.com' }
        }

        app = express()
        app.use(express.json())
        const requestIdMiddleware = require('../src/middleware/requestIdMiddleware')
        app.use(requestIdMiddleware)

        app.use((req, res, next) => {
            req.user = currentUser
            next()
        })

        const lobbyRoutes = require('../src/routes/lobbyRoutes')
        app.use('/api/lobby', lobbyRoutes)

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
        prisma.user.findUnique = originals.userFindUnique
        if (prisma.rideCreateRequest) {
            prisma.rideCreateRequest.findUnique = originals.rideCreateRequestFindUnique
            prisma.rideCreateRequest.create = originals.rideCreateRequestCreate
        }
        prisma.$transaction = originals.$transaction

        if (server) {
            await new Promise(r => server.close(r))
        }
    })

    beforeEach(() => {
        existingRequests.clear()
        existingRooms.clear()
        simulateStructuralFailure = false
    })

    it('creates room and returns 201 with canonical rv_<id> lkRoomName on first attempt', async () => {
        const res = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                convoyName: 'Alpha Convoy',
                clientRequestId: 'req_001',
                origin: 'Origin A',
                destination: 'Dest B'
            })
        })

        assert.strictEqual(res.status, 201)
        const body = await res.json()
        assert.ok(body.roomId)
        assert.strictEqual(body.lkRoomName, `rv_${body.roomId}`)
        assert.strictEqual(body.convoyName, 'Alpha Convoy')
    })

    it('retry with identical clientRequestId returns 200 and identical room', async () => {
        const payload = {
            convoyName: 'Beta Convoy',
            clientRequestId: 'req_002',
            origin: 'Mumbai',
            destination: 'Pune'
        }

        const res1 = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
        assert.strictEqual(res1.status, 201)
        const body1 = await res1.json()

        const res2 = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
        assert.strictEqual(res2.status, 200, 'Idempotent retry must return 200')
        const body2 = await res2.json()
        assert.strictEqual(body2.roomId, body1.roomId)
        assert.strictEqual(body2.lkRoomName, body1.lkRoomName)
        assert.strictEqual(body2.convoyName, 'Beta Convoy')
    })

    it('retry with same clientRequestId but DIFFERENT payload returns 409 IDEMPOTENCY_CONFLICT', async () => {
        const res1 = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                convoyName: 'Gamma Convoy',
                clientRequestId: 'req_003',
                destination: 'Location 1'
            })
        })
        assert.strictEqual(res1.status, 201)

        const res2 = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                convoyName: 'Gamma Convoy Modified',
                clientRequestId: 'req_003',
                destination: 'Location 2'
            })
        })
        assert.strictEqual(res2.status, 409)
        const body2 = await res2.json()
        assert.ok(['IDEMPOTENCY_KEY_REUSED', 'IDEMPOTENCY_CONFLICT'].includes(body2.code))
    })

    it('structural schema failure returns 503 SCHEMA_DEGRADED without creating non-canonical room', async () => {
        simulateStructuralFailure = true
        const initialRoomCount = existingRooms.size

        const res = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                convoyName: 'Delta Convoy',
                clientRequestId: 'req_004'
            })
        })

        assert.strictEqual(res.status, 503)
        const body = await res.json()
        assert.strictEqual(body.code, 'SCHEMA_DEGRADED')
        assert.strictEqual(existingRooms.size, initialRoomCount, 'No non-canonical rooms must be created')
    })

    it('allows ride-name reuse after previous room is ENDED', async () => {
        // Create initial room
        const res1 = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                convoyName: 'Sunday Morning Ride',
                clientRequestId: 'req_005'
            })
        })
        assert.strictEqual(res1.status, 201)
        const body1 = await res1.json()

        // End the first room
        const room1 = existingRooms.get(body1.roomId)
        room1.status = 'ENDED'

        // Create new room with the same name and different clientRequestId
        const res2 = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                convoyName: 'Sunday Morning Ride',
                clientRequestId: 'req_006'
            })
        })
        assert.strictEqual(res2.status, 201, 'Reusing name of ended convoy should succeed')
        const body2 = await res2.json()
        assert.notStrictEqual(body2.roomId, body1.roomId)
        assert.strictEqual(body2.convoyName, 'Sunday Morning Ride')
    })
})
