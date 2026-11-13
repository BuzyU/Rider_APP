const { describe, it, before, after, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const prisma = require('../src/db')
const { issueRoomToken, lkRoomName } = require('../src/services/tokenService')
const { joinWithLinkToken } = require('../src/services/joinService')

process.env.LIVEKIT_API_KEY = 'test_livekit_key'
process.env.LIVEKIT_API_SECRET = 'test_livekit_secret_1234567890123456'
process.env.LIVEKIT_URL = '' // Empty so livekit-server-sdk does not attempt real HTTP connection
process.env.NODE_ENV = 'test'

describe('WP-141: Room, invite, sharing, lifecycle & concurrency', () => {
    let app
    let server
    let baseUrl
    let currentUser = { uid: 'host_wp141', name: 'Host Rider', email: 'host@test.com' }

    const originals = {}
    let mockRoom = null
    let mockInvites = []
    let mockTokens = []

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
        originals.roomJoinTokenFindUnique = prisma.roomJoinToken?.findUnique
        originals.roomJoinTokenUpdateMany = prisma.roomJoinToken?.updateMany
        originals.roomJoinTokenCount = prisma.roomJoinToken?.count
        originals.$transaction = prisma.$transaction
        originals.diagnosticRunFindFirst = prisma.diagnosticRun?.findFirst

        prisma.$transaction = async (fn) => (typeof fn === 'function' ? fn(prisma) : fn)
        if (!prisma.rideCreateRequest) prisma.rideCreateRequest = {}
        originals.rideCreateRequestFindUnique = prisma.rideCreateRequest?.findUnique
        originals.rideCreateRequestCreate = prisma.rideCreateRequest?.create
        prisma.rideCreateRequest.findUnique = async () => null
        prisma.rideCreateRequest.create = async ({ data }) => ({ id: 'req_wp141', ...data })

        prisma.user.findUnique = async () => null
        if (prisma.diagnosticRun) {
            prisma.diagnosticRun.findFirst = async () => null
        }
        originals.deviceTokenFindMany = prisma.deviceToken?.findMany
        if (!prisma.deviceToken) prisma.deviceToken = {}
        prisma.deviceToken.findMany = async () => []

        app = express()
        app.use(express.json())
        app.use((req, res, next) => {
            const authHeader = req.headers['x-test-uid']
            if (authHeader) {
                req.user = { uid: authHeader, name: authHeader, email: `${authHeader}@test.com` }
            } else {
                req.user = currentUser
            }
            next()
        })

        const lobbyRoutes = require('../src/routes/lobbyRoutes')
        app.use('/api/lobby', lobbyRoutes)

        await new Promise((resolve) => {
            server = app.listen(0, '127.0.0.1', () => {
                baseUrl = `http://127.0.0.1:${server.address().port}`
                resolve()
            })
        })
    })

    after(async () => {
        if (server) await new Promise((r) => server.close(r))
        Object.entries(originals).forEach(([k, fn]) => {
            if (fn !== undefined) prisma[k] = fn
        })
    })

    beforeEach(() => {
        currentUser = { uid: 'host_wp141', name: 'Host Rider', email: 'host@test.com' }
        mockRoom = {
            id: 'e8b1d9c4-0000-4000-8000-000000000001',
            name: 'WP141_Convoy',
            lkRoomName: 'rv_e8b1d9c4-0000-4000-8000-000000000001',
            status: 'LOBBY',
            ownerId: 'host_wp141',
            joinCode: 'WP141A',
            offlineBackupCode: 'ABCDEFGH23456789',
            offlineMeshEnabled: true,
            diagnosticsEnabled: false,
            createdAt: new Date(),
            updatedAt: new Date()
        }
        mockInvites = []
        mockTokens = []

        prisma.room.findUnique = async ({ where }) => {
            if (!mockRoom) return null
            if (where.id && where.id === mockRoom.id) return mockRoom
            if (where.name && where.name.toLowerCase() === mockRoom.name.toLowerCase()) return mockRoom
            if (where.joinCode && where.joinCode.toUpperCase() === (mockRoom.joinCode || '').toUpperCase()) return mockRoom
            return null
        }

        prisma.room.findFirst = async ({ where }) => {
            if (!mockRoom) return null
            if (where.status && where.status.not === 'ENDED' && mockRoom.status === 'ENDED') {
                return null
            }
            if (where.OR) {
                const match = where.OR.some(cond => {
                    const nameCond = typeof cond.name === 'object' ? cond.name?.equals : cond.name
                    if (nameCond && nameCond.toLowerCase() === mockRoom.name.toLowerCase()) return true
                    if (cond.lkRoomName && cond.lkRoomName === mockRoom.lkRoomName) return true
                    if (cond.joinCode && cond.joinCode.toUpperCase() === (mockRoom.joinCode || '').toUpperCase()) return true
                    return false
                })
                if (match) return mockRoom
            }
            const nameTarget = typeof where.name === 'object' ? where.name?.equals : where.name
            if (nameTarget && nameTarget.toLowerCase() === mockRoom.name.toLowerCase()) return mockRoom
            if (where.id && where.id === mockRoom.id) return mockRoom
            return null
        }

        prisma.room.update = async ({ where, data }) => {
            if (mockRoom && mockRoom.id === where.id) {
                Object.assign(mockRoom, data)
                return mockRoom
            }
            return mockRoom
        }

        prisma.room.updateMany = async ({ where, data }) => {
            if (mockRoom && (where.id === mockRoom.id || !where.id)) {
                if (where.status && where.status !== mockRoom.status) {
                    return { count: 0 }
                }
                Object.assign(mockRoom, data)
                return { count: 1 }
            }
            return { count: 0 }
        }

        prisma.rideInvite.findMany = async () => mockInvites
        prisma.rideInvite.findUnique = async ({ where }) => {
            if (where.id) {
                return mockInvites.find(i => i.id === where.id) || null
            }
            if (where.roomId_inviteeId) {
                return mockInvites.find(i => i.roomId === where.roomId_inviteeId.roomId && i.inviteeId === where.roomId_inviteeId.inviteeId) || null
            }
            return null
        }
        prisma.rideInvite.findFirst = async ({ where }) => {
            if (where.roomId && where.inviteeId) {
                return mockInvites.find(i => i.roomId === where.roomId && i.inviteeId === where.inviteeId) || null
            }
            return null
        }
        prisma.rideInvite.update = async ({ where, data }) => {
            const inv = mockInvites.find(i => i.id === where.id)
            if (inv) {
                Object.assign(inv, data)
                return inv
            }
            return { id: where.id, ...data }
        }
        prisma.rideInvite.count = async () => mockInvites.filter(i => i.status === 'ACCEPTED').length

        if (!prisma.roomJoinToken) prisma.roomJoinToken = {}
        prisma.roomJoinToken.findUnique = async ({ where }) => {
            return mockTokens.find(t => t.token === where.token) || null
        }
        prisma.roomJoinToken.updateMany = async ({ where, data }) => {
            const token = mockTokens.find(t => t.id === where.id)
            if (token) {
                if (data.useCount && data.useCount.increment) {
                    token.useCount = (token.useCount || 0) + data.useCount.increment
                }
                return { count: 1 }
            }
            return { count: 0 }
        }
    })

    // =========================================================================
    // Item 1: Exact same LiveKit room name & identity
    // =========================================================================
    it('Item 1: Host and invitee receive identical canonical lkRoomName and unique user identities', async () => {
        const hostUser = { uid: 'host_wp141', name: 'Host' }
        const inviteeUser = { uid: 'rider_wp141_1', name: 'Rider One' }

        mockInvites.push({
            id: 'inv_1',
            roomId: mockRoom.id,
            inviteeId: inviteeUser.uid,
            status: 'ACCEPTED',
            offlineConfigStatus: 'READY'
        })

        const hostTokenPayload = await issueRoomToken(hostUser, mockRoom)
        const inviteeTokenPayload = await issueRoomToken(inviteeUser, mockRoom)

        assert.equal(hostTokenPayload.lkRoomName, mockRoom.lkRoomName)
        assert.equal(inviteeTokenPayload.lkRoomName, mockRoom.lkRoomName)
        assert.equal(hostTokenPayload.lkRoomName, inviteeTokenPayload.lkRoomName)
        assert.equal(hostTokenPayload.roomId, mockRoom.id)
        assert.equal(inviteeTokenPayload.roomId, mockRoom.id)

        // Tokens must be distinct because identity is distinct
        assert.notEqual(hostTokenPayload.token, inviteeTokenPayload.token)
    })

    // =========================================================================
    // Item 2: Closed/ended room cannot be reopened by link, rejoin, or retry
    // =========================================================================
    it('Item 2: Closed or ended room rejects start, token issuance, and join with 410 ROOM_ENDED', async () => {
        mockRoom.status = 'ENDED'
        mockRoom.endedAt = new Date()

        const riderUser = { uid: 'rider_wp141_1', name: 'Rider One' }
        mockInvites.push({
            id: 'inv_1',
            roomId: mockRoom.id,
            inviteeId: riderUser.uid,
            status: 'ACCEPTED'
        })

        // Token issuance for ended room
        await assert.rejects(
            async () => { await issueRoomToken(riderUser, mockRoom) },
            (err) => {
                assert.equal(err.status, 410)
                assert.equal(err.code, 'ROOM_ENDED')
                return true
            }
        )

        // Start call for ended room
        currentUser = { uid: 'host_wp141', name: 'Host' }
        const startRes = await fetch(`${baseUrl}/api/lobby/${mockRoom.id}/start`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ allowSolo: true })
        })
        assert.equal(startRes.status, 410)
        const startData = await startRes.json()
        assert.equal(startData.code, 'ROOM_ENDED')

        // Status poll for ended room returns 410
        const statusRes = await fetch(`${baseUrl}/api/lobby/${mockRoom.id}/status`)
        assert.equal(statusRes.status, 410)
    })

    // =========================================================================
    // Item 5: Concurrency Tests
    // =========================================================================
    it('Item 5a: Concurrency: Two riders joining simultaneously with link token', async () => {
        const linkToken = {
            id: 'tok_1',
            token: 'link_concurrent_123',
            roomId: mockRoom.id,
            room: mockRoom,
            expiresAt: new Date(Date.now() + 3600000),
            revokedAt: null,
            useCount: 0,
            maxUses: 10
        }
        mockTokens.push(linkToken)

        let createInviteCount = 0
        prisma.rideInvite.create = async ({ data }) => {
            createInviteCount++
            const created = { id: `inv_concurrent_${createInviteCount}`, ...data, status: 'ACCEPTED' }
            mockInvites.push(created)
            return created
        }

        const riderA = { uid: 'rider_concurrent_a', name: 'Rider A' }
        const riderB = { uid: 'rider_concurrent_b', name: 'Rider B' }

        // Execute both joins concurrently
        const [resultA, resultB] = await Promise.all([
            joinWithLinkToken(linkToken.token, riderA),
            joinWithLinkToken(linkToken.token, riderB)
        ])

        assert.ok(resultA.token)
        assert.ok(resultB.token)
        assert.equal(resultA.lkRoomName, mockRoom.lkRoomName)
        assert.equal(resultB.lkRoomName, mockRoom.lkRoomName)
        // Both riders successfully joined without collision
        assert.equal(linkToken.useCount, 2)
        assert.equal(mockInvites.length, 2)
    })

    it('Item 5b: Concurrency: Two starts executed at the exact same instant', async () => {
        currentUser = { uid: 'host_wp141', name: 'Host' }

        let startCallCount = 0
        const origUpdate = prisma.room.updateMany
        prisma.room.updateMany = async (params) => {
            startCallCount++
            // Simulate slight asynchronous processing delay
            await new Promise(r => setTimeout(r, 10))
            return origUpdate(params)
        }

        // Two simultaneous POST /start calls
        const [res1, res2] = await Promise.all([
            fetch(`${baseUrl}/api/lobby/${mockRoom.id}/start`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ allowSolo: true })
            }),
            fetch(`${baseUrl}/api/lobby/${mockRoom.id}/start`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ allowSolo: true })
            })
        ])

        assert.equal(res1.status, 200)
        assert.equal(res2.status, 200)
        const data1 = await res1.json()
        const data2 = await res2.json()

        // Both return valid tokens for the exact same LiveKit room
        assert.equal(data1.lkRoomName, mockRoom.lkRoomName)
        assert.equal(data2.lkRoomName, mockRoom.lkRoomName)
        assert.equal(mockRoom.status, 'ACTIVE')
    })

    it('Item 5c: Concurrency: Leave during start (rider leaves while host starts ride)', async () => {
        const rider = { uid: 'rider_leave_during_start', name: 'Rider Leaver' }
        const invite = {
            id: 'inv_leaver',
            roomId: mockRoom.id,
            inviteeId: rider.uid,
            status: 'ACCEPTED',
            offlineConfigStatus: 'READY'
        }
        mockInvites.push(invite)

        currentUser = { uid: 'host_wp141', name: 'Host' }

        // Start call by host and leave call by rider in parallel
        const startPromise = fetch(`${baseUrl}/api/lobby/${mockRoom.id}/start`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ allowSolo: true })
        })

        // Rider leaves simultaneously using custom auth header
        const leavePromise = fetch(`${baseUrl}/api/lobby/${mockRoom.id}/leave`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'x-test-uid': rider.uid
            }
        })

        const [startRes, leaveRes] = await Promise.all([startPromise, leavePromise])
        // Host start succeeds without crashing
        assert.equal(startRes.status, 200)
        const startData = await startRes.json()
        assert.equal(startData.lkRoomName, mockRoom.lkRoomName)
        assert.equal(mockRoom.status, 'ACTIVE')

        // Rider leave succeeds
        assert.equal(leaveRes.status, 200)
        const leaveData = await leaveRes.json()
        assert.equal(leaveData.success, true)
        assert.equal(invite.status, 'LEFT')
    })
})
