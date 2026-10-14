const { describe, it, before, after, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const prisma = require('../src/db')

process.env.LIVEKIT_API_KEY = 'test_livekit_key'
process.env.LIVEKIT_API_SECRET = 'test_livekit_secret_1234567890123456'
process.env.LIVEKIT_URL = 'http://localhost:7880'
process.env.NODE_ENV = 'test'

describe('Gate 2: Lobby Diagnostics Flag', () => {
    let app
    let server
    let baseUrl
    let currentUser = { uid: 'host_123', name: 'Host Rider' }

    const originals = {}

    before(async () => {
        originals.roomFindUnique = prisma.room.findUnique
        originals.roomCreate = prisma.room.create
        originals.roomUpdate = prisma.room.update
        originals.roomFindFirst = prisma.room.findFirst
        originals.diagnosticRunFindFirst = prisma.diagnosticRun.findFirst
        originals.diagnosticRunCreate = prisma.diagnosticRun.create
        originals.diagnosticParticipantCreate = prisma.diagnosticParticipant.create
        originals.userFindUnique = prisma.user.findUnique
        originals.roomJoinTokenFindUnique = prisma.roomJoinToken.findUnique
        originals.rideInviteFindFirst = prisma.rideInvite.findFirst
        originals.rideInviteCreate = prisma.rideInvite.create

        app = express()
        app.use(express.json())
        app.use((req, res, next) => {
            req.user = currentUser
            next()
        })

        const lobbyRoutes = require('../src/routes/lobbyRoutes')
        app.use('/api/lobby', lobbyRoutes)

        prisma.room.findFirst = async () => null

        server = app.listen(0)
        await new Promise(resolve => server.once('listening', resolve))
        const port = server.address().port
        baseUrl = `http://127.0.0.1:${port}`
    })

    after(async () => {
        prisma.room.findUnique = originals.roomFindUnique
        prisma.room.create = originals.roomCreate
        prisma.room.update = originals.roomUpdate
        prisma.room.findFirst = originals.roomFindFirst
        prisma.diagnosticRun.findFirst = originals.diagnosticRunFindFirst
        prisma.diagnosticRun.create = originals.diagnosticRunCreate
        prisma.diagnosticParticipant.create = originals.diagnosticParticipantCreate
        prisma.user.findUnique = originals.userFindUnique
        prisma.roomJoinToken.findUnique = originals.roomJoinTokenFindUnique
        prisma.rideInvite.findFirst = originals.rideInviteFindFirst
        prisma.rideInvite.create = originals.rideInviteCreate

        if (server) {
            await new Promise(r => server.close(r))
        }
    })

    it('/create persists diagnosticsEnabled (enabled=true by default and explicit)', async () => {
        let createdData = null
        prisma.room.findUnique = async () => null // no duplicate
        prisma.room.create = async ({ data }) => {
            createdData = data
            return {
                id: 'room_diag_true',
                name: data.name,
                ownerId: data.ownerId,
                diagnosticsEnabled: data.diagnosticsEnabled
            }
        }
        prisma.diagnosticRun.findFirst = async () => null
        prisma.diagnosticRun.create = async () => ({ id: 'run_1', testNumber: 1 })
        prisma.diagnosticParticipant.create = async () => ({ id: 'p_1' })
        prisma.user.findUnique = async () => ({ id: 'host_123', handle: 'HostRider' })

        const res = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ convoyName: 'Sunday-Morning-Pack', diagnosticsEnabled: true })
        })

        assert.strictEqual(res.status, 201)
        const body = await res.json()
        assert.strictEqual(body.convoyName, 'Sunday-Morning-Pack')
        assert.strictEqual(body.diagnosticsEnabled, true)
        assert.strictEqual(createdData.diagnosticsEnabled, true)
    })

    it('/create persists diagnosticsEnabled=false when explicitly requested', async () => {
        let createdData = null
        prisma.room.findUnique = async () => null
        prisma.room.create = async ({ data }) => {
            createdData = data
            return {
                id: 'room_diag_false',
                name: data.name,
                ownerId: data.ownerId,
                diagnosticsEnabled: data.diagnosticsEnabled
            }
        }

        const res = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ convoyName: 'Quiet-Ride', diagnosticsEnabled: false })
        })

        assert.strictEqual(res.status, 201)
        const body = await res.json()
        assert.strictEqual(body.diagnosticsEnabled, false)
        assert.strictEqual(createdData.diagnosticsEnabled, false)
    })

    it('/status returns diagnosticsEnabled', async () => {
        currentUser = { uid: 'host_123', name: 'Host Rider' }
        prisma.room.findUnique = async () => ({
            id: 'room_xyz',
            name: 'Status-Test-Room',
            ownerId: 'host_123',
            diagnosticsEnabled: true,
            invites: []
        })

        const res = await fetch(`${baseUrl}/api/lobby/Status-Test-Room/status`)
        assert.strictEqual(res.status, 200)
        const body = await res.json()
        assert.strictEqual(body.diagnosticsEnabled, true)
    })

    it('/start returns diagnosticsEnabled', async () => {
        currentUser = { uid: 'host_123', name: 'Host Rider' }
        prisma.room.findUnique = async () => ({
            id: 'room_xyz',
            name: 'Start-Test-Room',
            ownerId: 'host_123',
            diagnosticsEnabled: true,
            startedAt: null
        })
        prisma.room.update = async () => ({})

        const res = await fetch(`${baseUrl}/api/lobby/Start-Test-Room/start`, {
            method: 'POST'
        })
        assert.strictEqual(res.status, 200)
        const body = await res.json()
        assert.strictEqual(body.diagnosticsEnabled, true)
        assert.ok(body.token)
    })

    it('/join-token returns diagnosticsEnabled', async () => {
        currentUser = { uid: 'rider_accepted', name: 'Rider Ace' }
        prisma.room.findUnique = async () => ({
            id: 'room_xyz',
            name: 'Join-Test-Room',
            ownerId: 'host_123',
            diagnosticsEnabled: false,
            invites: [{ id: 'invite_ace' }]
        })

        const res = await fetch(`${baseUrl}/api/lobby/join-token`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ roomName: 'Join-Test-Room' })
        })
        assert.strictEqual(res.status, 200)
        const body = await res.json()
        assert.strictEqual(body.diagnosticsEnabled, false)
    })

    it('/join-via-token returns diagnosticsEnabled', async () => {
        currentUser = { uid: 'rider_link', name: 'Rider Via Link' }
        prisma.roomJoinToken.findUnique = async () => ({
            id: 'token_1',
            token: 'valid_link_token',
            expiresAt: new Date(Date.now() + 100000),
            room: {
                id: 'room_link_xyz',
                name: 'Link-Joined-Room',
                ownerId: 'host_123',
                diagnosticsEnabled: true
            }
        })
        prisma.rideInvite.findFirst = async () => null
        prisma.rideInvite.create = async () => ({ id: 'inv_link' })

        const res = await fetch(`${baseUrl}/api/lobby/join-via-token`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ token: 'valid_link_token' })
        })
        assert.strictEqual(res.status, 200)
        const body = await res.json()
        assert.strictEqual(body.diagnosticsEnabled, true)
        assert.strictEqual(body.roomName, 'Link-Joined-Room')
    })

    it('only the owner can change diagnostics flag, and only before ride starts', async () => {
        // 1. Non-owner tries to change -> 403
        currentUser = { uid: 'random_rider', name: 'Not Owner' }
        prisma.room.findUnique = async () => ({
            id: 'room_modify',
            name: 'Protected-Convoy',
            ownerId: 'host_123',
            diagnosticsEnabled: true,
            startedAt: null
        })

        let res = await fetch(`${baseUrl}/api/lobby/Protected-Convoy/diagnostics`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ diagnosticsEnabled: false })
        })
        assert.strictEqual(res.status, 403)
        let body = await res.json()
        assert.strictEqual(body.error, 'Only the host can modify diagnostics settings')

        // 2. Owner tries to change after ride has started -> 400
        currentUser = { uid: 'host_123', name: 'Host Rider' }
        prisma.room.findUnique = async () => ({
            id: 'room_modify',
            name: 'Protected-Convoy',
            ownerId: 'host_123',
            diagnosticsEnabled: true,
            startedAt: new Date() // ride already started!
        })

        res = await fetch(`${baseUrl}/api/lobby/Protected-Convoy/diagnostics`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ diagnosticsEnabled: false })
        })
        assert.strictEqual(res.status, 400)
        body = await res.json()
        assert.strictEqual(body.error, 'Cannot change diagnostics setting after ride has started')

        // 3. Owner changes before ride starts -> 200 and updates
        prisma.room.findUnique = async () => ({
            id: 'room_modify',
            name: 'Protected-Convoy',
            ownerId: 'host_123',
            diagnosticsEnabled: true,
            startedAt: null
        })
        let updatedFlag = null
        prisma.room.update = async ({ data }) => {
            updatedFlag = data.diagnosticsEnabled
            return { diagnosticsEnabled: data.diagnosticsEnabled }
        }

        res = await fetch(`${baseUrl}/api/lobby/Protected-Convoy/diagnostics`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ diagnosticsEnabled: false })
        })
        assert.strictEqual(res.status, 200)
        body = await res.json()
        assert.strictEqual(body.success, true)
        assert.strictEqual(body.diagnosticsEnabled, false)
        assert.strictEqual(updatedFlag, false)
    })
})
