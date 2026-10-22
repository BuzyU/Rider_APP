const { describe, it, before, after, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const prisma = require('../src/db')

process.env.LIVEKIT_API_KEY = 'test_livekit_key'
process.env.LIVEKIT_API_SECRET = 'test_livekit_secret_1234567890123456'
process.env.LIVEKIT_URL = 'http://localhost:7880'
process.env.NODE_ENV = 'test'

function decodeJwtPayload(token) {
    const parts = token.split('.')
    assert.strictEqual(parts.length, 3, 'JWT must have 3 parts')
    const payloadJson = Buffer.from(parts[1], 'base64').toString('utf8')
    return JSON.parse(payloadJson)
}

describe('Gate 1: Room Tokens', () => {
    let app
    let server
    let baseUrl
    let currentUser = { uid: 'user_123', name: 'Rider Dave' }

    const originals = {}

    before(async () => {
        originals.roomFindUnique = prisma.room.findUnique
        originals.roomFindFirst = prisma.room.findFirst
        originals.inviteFindUnique = prisma.rideInvite.findUnique
        originals.userFindUnique = prisma.user.findUnique
        prisma.user.findUnique = async () => null
        originals.roomCreate = prisma.room.create
        originals.rideInviteCreate = prisma.rideInvite.create
        originals.rideInviteFindFirst = prisma.rideInvite.findFirst
        originals.rideInviteUpdate = prisma.rideInvite.update
        originals.roomJoinTokenFindUnique = prisma.roomJoinToken.findUnique
        originals.diagnosticRunFindFirst = prisma.diagnosticRun.findFirst

        app = express()
        app.use(express.json())
        app.use((req, res, next) => {
            req.user = currentUser
            next()
        })

        const roomRoutes = require('../src/routes/roomRoutes')
        const lobbyRoutes = require('../src/routes/lobbyRoutes')

        app.use('/api/rooms', roomRoutes)
        app.use('/api/lobby', lobbyRoutes)

        server = app.listen(0)
        await new Promise(resolve => server.once('listening', resolve))
        const port = server.address().port
        baseUrl = `http://127.0.0.1:${port}`
    })

    beforeEach(() => {
        currentUser = { uid: 'user_123', name: 'Rider Dave' }
        prisma.roomJoinToken.findUnique = async () => null
    })

    after(async () => {
        prisma.room.findUnique = originals.roomFindUnique
        prisma.room.findFirst = originals.roomFindFirst
        prisma.rideInvite.findUnique = originals.inviteFindUnique
        prisma.user.findUnique = originals.userFindUnique
        prisma.room.create = originals.roomCreate
        prisma.rideInvite.create = originals.rideInviteCreate
        prisma.rideInvite.findFirst = originals.rideInviteFindFirst
        prisma.rideInvite.update = originals.rideInviteUpdate
        prisma.roomJoinToken.findUnique = originals.roomJoinTokenFindUnique
        prisma.diagnosticRun.findFirst = originals.diagnosticRunFindFirst

        if (server) {
            await new Promise(r => server.close(r))
        }
    })

    it('POST /room/token for an unknown room returns 404 and creates nothing', async () => {
        let roomCreated = false
        let inviteCreated = false

        prisma.room.findFirst = async () => null
        prisma.room.create = async () => {
            roomCreated = true
            return { id: 'should_not_exist' }
        }
        prisma.rideInvite.create = async () => {
            inviteCreated = true
            return { id: 'should_not_exist' }
        }

        const res = await fetch(`${baseUrl}/api/rooms/room/token`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ roomName: 'NonExistentRoom' })
        })

        assert.strictEqual(res.status, 404)
        const body = await res.json()
        assert.strictEqual(body.error, 'Room not found')
        assert.strictEqual(roomCreated, false, 'No room should be created for unknown room token request')
        assert.strictEqual(inviteCreated, false, 'No invite should be created for unknown room token request')
    })

    it('POST /join-token returns 403 without an accepted invite', async () => {
        currentUser = { uid: 'uninvited_rider', name: 'Stranger' }

        prisma.room.findFirst = async () => ({
            id: 'room_abc',
            name: 'Convoy-Secret',
            status: 'LOBBY',
            ownerId: 'host_rider_999',
            diagnosticsEnabled: true
        })
        prisma.rideInvite.findUnique = async () => null // No invite for uninvited_rider

        const res = await fetch(`${baseUrl}/api/lobby/join-token`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ roomName: 'Convoy-Secret' })
        })

        assert.strictEqual(res.status, 403)
        const body = await res.json()
        assert.strictEqual(body.error, 'Not a member of this convoy')
    })

    it('JWT issued by /room/token has video.room == requested room', async () => {
        currentUser = { uid: 'accepted_rider_1', name: 'Alice' }

        prisma.room.findFirst = async () => ({
            id: 'room_123',
            name: 'Tour-De-Coast',
            status: 'LOBBY',
            ownerId: 'accepted_rider_1',
            diagnosticsEnabled: true
        })
        prisma.diagnosticRun.findFirst = async () => null

        const res = await fetch(`${baseUrl}/api/rooms/room/token`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ roomName: 'Tour-De-Coast' })
        })

        assert.strictEqual(res.status, 200)
        const body = await res.json()
        assert.ok(body.token, 'Token must be present in response')
        assert.strictEqual(body.roomName, 'Tour-De-Coast')

        const payload = decodeJwtPayload(body.token)
        assert.ok(payload.video, 'JWT payload must contain video grant')
        assert.strictEqual(payload.video.room, 'Tour-De-Coast', 'JWT video.room must match requested room')
        assert.strictEqual(payload.video.roomJoin, true)
    })

    it('JWT issued by /join-token has video.room == requested room', async () => {
        currentUser = { uid: 'accepted_rider_2', name: 'Bob' }

        prisma.room.findFirst = async () => ({
            id: 'room_456',
            name: 'Mountain-Ride',
            status: 'LOBBY',
            ownerId: 'host_999',
            diagnosticsEnabled: true
        })
        prisma.rideInvite.findUnique = async () => ({ status: 'ACCEPTED' })
        prisma.diagnosticRun.findFirst = async () => null

        const res = await fetch(`${baseUrl}/api/lobby/join-token`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ roomName: 'Mountain-Ride' })
        })

        assert.strictEqual(res.status, 200)
        const body = await res.json()
        assert.ok(body.token)
        assert.strictEqual(body.roomName, 'Mountain-Ride')

        const payload = decodeJwtPayload(body.token)
        assert.ok(payload.video)
        assert.strictEqual(payload.video.room, 'Mountain-Ride')
        assert.strictEqual(payload.video.roomJoin, true)
    })

    it('POST /room/token resolves invite token and full url to room and returns 200', async () => {
        currentUser = { uid: 'invite_rider_1', name: 'Charlie' }

        prisma.room.findUnique = async () => null
        prisma.roomJoinToken.findUnique = async ({ where }) => {
            if (where.token === 'abcdef1234567890') {
                return {
                    expiresAt: new Date(Date.now() + 60000),
                    room: {
                        id: 'room_from_token',
                        name: 'Lonavala-Express',
                        ownerId: 'host_leader',
                        diagnosticsEnabled: false
                    }
                }
            }
            return null
        }
        prisma.rideInvite.findFirst = async () => null
        let inviteCreated = false
        prisma.rideInvite.create = async () => {
            inviteCreated = true
            return { id: 'new_invite_id' }
        }
        prisma.diagnosticRun.findFirst = async () => null

        const res = await fetch(`${baseUrl}/api/rooms/room/token`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ roomName: 'ridervoice://join/abcdef1234567890?param=1#section' })
        })

        assert.strictEqual(res.status, 200)
        const body = await res.json()
        assert.ok(body.token)
        assert.strictEqual(body.roomName, 'Lonavala-Express')
        assert.strictEqual(inviteCreated, true)

        const payload = decodeJwtPayload(body.token)
        assert.strictEqual(payload.video.room, 'Lonavala-Express')
    })

    it('POST /join-via-token resolves uppercase and url-prefixed invite token and returns 200', async () => {
        currentUser = { uid: 'invite_rider_2', name: 'Diana' }

        prisma.roomJoinToken.findUnique = async ({ where }) => {
            if (where.token === 'fedcba0987654321') {
                return {
                    expiresAt: new Date(Date.now() + 60000),
                    room: {
                        id: 'room_from_token_2',
                        name: 'Goa-Coastal-Ride',
                        ownerId: 'host_leader_2',
                        diagnosticsEnabled: true
                    }
                }
            }
            return null
        }
        prisma.rideInvite.findFirst = async () => null
        prisma.rideInvite.create = async () => ({ id: 'new_inv_id_2' })

        const res = await fetch(`${baseUrl}/api/lobby/join-via-token`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ token: 'https://ridervoice.com/join/FEDCBA0987654321' })
        })

        assert.strictEqual(res.status, 200)
        const body = await res.json()
        assert.ok(body.token)
        assert.strictEqual(body.roomName, 'Goa-Coastal-Ride')
        assert.strictEqual(body.diagnosticsEnabled, true)

        const payload = decodeJwtPayload(body.token)
        assert.strictEqual(payload.video.room, 'Goa-Coastal-Ride')
    })
})
