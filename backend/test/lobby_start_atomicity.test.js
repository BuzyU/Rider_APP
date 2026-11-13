'use strict'

const { describe, it, before, after, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const prisma = require('../src/db')

process.env.LIVEKIT_API_KEY = 'test_livekit_key'
process.env.LIVEKIT_API_SECRET = 'test_livekit_secret_1234567890123456'
process.env.LIVEKIT_URL = 'http://localhost:7880'
process.env.NODE_ENV = 'test'

describe('FX-02: Backend Start Atomicity & Error Handling', () => {
    let app
    let server
    let baseUrl
    let currentUser = { uid: 'host_001', name: 'Host Rider', handle: 'host001' }

    const originals = {}

    before(async () => {
        originals.roomFindUnique = prisma.room.findUnique
        originals.roomFindFirst = prisma.room.findFirst
        originals.roomUpdateMany = prisma.room.updateMany
        originals.rideInviteCount = prisma.rideInvite.count
        originals.rideInviteFindMany = prisma.rideInvite.findMany
        originals.userFindUnique = prisma.user.findUnique

        app = express()
        app.use(express.json())
        app.use((req, res, next) => {
            req.user = currentUser
            next()
        })

        const lobbyRoutes = require('../src/routes/lobbyRoutes')
        app.use('/api/lobby', lobbyRoutes)

        // Error middleware to ensure uncaught route errors return 500
        app.use((err, req, res, next) => {
            const status = err.status || 500
            res.status(status).json({ error: err.message || 'Internal Server Error', code: err.code || 'INTERNAL_ERROR' })
        })

        server = app.listen(0)
        await new Promise(resolve => server.once('listening', resolve))
        baseUrl = `http://127.0.0.1:${server.address().port}`
    })

    after(async () => {
        prisma.room.findUnique = originals.roomFindUnique
        prisma.room.findFirst = originals.roomFindFirst
        prisma.room.updateMany = originals.roomUpdateMany
        prisma.rideInvite.count = originals.rideInviteCount
        prisma.rideInvite.findMany = originals.rideInviteFindMany
        prisma.user.findUnique = originals.userFindUnique

        if (server) {
            await new Promise(resolve => server.close(resolve))
        }
    })

    beforeEach(() => {
        currentUser = { uid: 'host_001', name: 'Host Rider', handle: 'host001' }
        prisma.user.findUnique = async () => ({ id: 'host_001', displayName: 'Host Rider', handle: 'host001' })
        prisma.rideInvite.count = async () => 1
        prisma.rideInvite.findMany = async () => []
    })

    it('injected DB error gives 5xx and issues NO token', async () => {
        const mockRoom = {
            id: 'room_err_1',
            name: 'Atomicity-Err-Room',
            status: 'LOBBY',
            ownerId: 'host_001',
            offlineBackupCode: '0123456789ABCDEF'
        }

        prisma.room.findFirst = async () => mockRoom
        prisma.room.findUnique = async () => mockRoom
        prisma.room.updateMany = async () => {
            throw new Error('Injected database connection timeout during room start')
        }

        const res = await fetch(`${baseUrl}/api/lobby/Atomicity-Err-Room/start`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ allowSolo: true })
        })

        assert.strictEqual(res.status, 500, 'Expected 500 status on unhandled DB error')
        const body = await res.json()
        assert.ok(body.error, 'Expected error message in response')
        assert.strictEqual(body.token, undefined, 'NO token must be issued on DB error')
    })

    it('failed update (count 0) never counts as transitioned; returns 409 conflict when room remains in non-ACTIVE status', async () => {
        const mockRoom = {
            id: 'room_zero_count',
            name: 'Atomicity-Zero-Count',
            status: 'LOBBY',
            ownerId: 'host_001'
        }

        prisma.room.findFirst = async () => mockRoom
        // updateMany returns count 0 (e.g. status was not matched or concurrent state lock)
        prisma.room.updateMany = async () => ({ count: 0 })
        // findUnique confirms room did not transition to ACTIVE
        prisma.room.findUnique = async () => ({ ...mockRoom, status: 'LOBBY' })

        const res = await fetch(`${baseUrl}/api/lobby/Atomicity-Zero-Count/start`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ allowSolo: true })
        })

        assert.strictEqual(res.status, 409, 'Expected 409 when update count was 0 and room is not ACTIVE')
        const body = await res.json()
        assert.strictEqual(body.code, 'ROOM_STATUS_CONFLICT')
        assert.strictEqual(body.token, undefined, 'NO token must be issued when transition fails')
    })

    it('count 0 re-reads room and returns 409 ROOM_ENDED if room transitioned to ENDED', async () => {
        const mockRoom = {
            id: 'room_ended_conflict',
            name: 'Atomicity-Ended-Room',
            status: 'LOBBY',
            ownerId: 'host_001'
        }

        prisma.room.findFirst = async () => mockRoom
        prisma.room.updateMany = async () => ({ count: 0 })
        // Room was closed / ended concurrently
        prisma.room.findUnique = async () => ({ ...mockRoom, status: 'ENDED', endedAt: new Date() })

        const res = await fetch(`${baseUrl}/api/lobby/Atomicity-Ended-Room/start`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ allowSolo: true })
        })

        assert.strictEqual(res.status, 409, 'Expected 409 for room ended')
        const body = await res.json()
        assert.strictEqual(body.code, 'ROOM_ENDED')
        assert.strictEqual(body.token, undefined)
    })

    it('two concurrent starts produce exactly one transition; both receive valid tokens', async () => {
        let roomState = {
            id: 'room_concurrent_1',
            name: 'Atomicity-Concurrent-Room',
            status: 'LOBBY',
            ownerId: 'host_001',
            startedAt: null
        }

        let transitionCount = 0

        prisma.room.findFirst = async () => roomState
        prisma.room.findUnique = async () => roomState

        prisma.room.updateMany = async ({ where, data }) => {
            if (where.id === roomState.id && where.status === 'LOBBY' && roomState.status === 'LOBBY') {
                roomState = { ...roomState, ...data, status: 'ACTIVE' }
                transitionCount++
                return { count: 1 }
            }
            return { count: 0 }
        }

        // Fire two concurrent start requests
        const [res1, res2] = await Promise.all([
            fetch(`${baseUrl}/api/lobby/Atomicity-Concurrent-Room/start`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ allowSolo: true })
            }),
            fetch(`${baseUrl}/api/lobby/Atomicity-Concurrent-Room/start`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ allowSolo: true })
            })
        ])

        assert.strictEqual(res1.status, 200)
        assert.strictEqual(res2.status, 200)

        const [body1, body2] = await Promise.all([res1.json(), res2.json()])
        assert.ok(body1.token, 'Caller 1 received token')
        assert.ok(body2.token, 'Caller 2 received token')

        assert.strictEqual(transitionCount, 1, 'Exactly one transition from LOBBY to ACTIVE must happen')
        assert.strictEqual(roomState.status, 'ACTIVE')
    })

    it('start works cleanly with NO request body (undefined body)', async () => {
        const mockRoom = {
            id: 'room_no_body',
            name: 'Atomicity-NoBody-Room',
            status: 'LOBBY',
            ownerId: 'host_001',
            offlineMeshEnabled: false
        }

        prisma.room.findFirst = async () => mockRoom
        prisma.room.findUnique = async () => mockRoom
        // 1 accepted rider exists, so allowSolo is not needed
        prisma.rideInvite.count = async () => 1
        prisma.room.updateMany = async () => ({ count: 1 })

        // No Content-Type, no body
        const res = await fetch(`${baseUrl}/api/lobby/Atomicity-NoBody-Room/start`, {
            method: 'POST'
        })

        assert.strictEqual(res.status, 200, 'Start without request body should succeed when accepted riders exist')
        const body = await res.json()
        assert.ok(body.token, 'Token must be issued')
    })
})
