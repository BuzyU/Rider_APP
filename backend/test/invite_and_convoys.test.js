const { describe, it, before, after } = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const prisma = require('../src/db')

process.env.LIVEKIT_API_KEY = 'test_livekit_key'
process.env.LIVEKIT_API_SECRET = 'test_livekit_secret_1234567890123456'
process.env.LIVEKIT_URL = 'http://localhost:7880'
process.env.NODE_ENV = 'test'

describe('Invites Response and Convoy Name Disambiguation', () => {
    let app
    let server
    let baseUrl
    let currentUser = { uid: 'user_tester1', name: 'Tester One', email: 'tester1@ridervoice.test' }

    const originals = {}

    before(async () => {
        originals.rideInviteFindUnique = prisma.rideInvite.findUnique
        originals.rideInviteUpdate = prisma.rideInvite.update
        originals.rideInviteUpdateMany = prisma.rideInvite.updateMany
        originals.userFindUnique = prisma.user.findUnique
        originals.roomFindUnique = prisma.room.findUnique
        originals.roomFindFirst = prisma.room.findFirst
        originals.roomCreate = prisma.room.create
        originals.diagnosticRunFindFirst = prisma.diagnosticRun.findFirst

        prisma.room.findFirst = async ({ where }) => {
            const name = where?.name?.equals || where?.name
            if (name) {
                return prisma.room.findUnique({ where: { name } })
            }
            return null
        }

        app = express()
        app.use(express.json())
        app.use((req, res, next) => {
            req.user = currentUser
            next()
        })

        const inviteRoutes = require('../src/routes/inviteRoutes')
        const lobbyRoutes = require('../src/routes/lobbyRoutes')

        app.use('/api/invites', inviteRoutes)
        app.use('/api/lobby', lobbyRoutes)

        server = app.listen(0)
        await new Promise(resolve => server.once('listening', resolve))
        baseUrl = `http://127.0.0.1:${server.address().port}`
    })

    after(async () => {
        prisma.rideInvite.findUnique = originals.rideInviteFindUnique
        prisma.rideInvite.update = originals.rideInviteUpdate
        prisma.rideInvite.updateMany = originals.rideInviteUpdateMany
        prisma.user.findUnique = originals.userFindUnique
        prisma.room.findUnique = originals.roomFindUnique
        prisma.room.findFirst = originals.roomFindFirst
        prisma.room.create = originals.roomCreate
        prisma.diagnosticRun.findFirst = originals.diagnosticRunFindFirst

        if (server) {
            await new Promise(r => server.close(r))
        }
    })

    it('POST /api/invites/respond returns 200 and accepts invite without 500 error', async () => {
        currentUser = { uid: 'user_tester1', name: 'Tester One', email: 'tester1@ridervoice.test' }
        prisma.rideInvite.findUnique = async ({ where }) => {
            if (where.id === 'inv_100') {
                return {
                    id: 'inv_100',
                    inviteeId: 'user_tester1',
                    inviterId: 'host_001',
                    status: 'PENDING',
                    room: {
                        name: 'RIDER PROVIDER TEST 06',
                        ownerId: 'host_001'
                    }
                }
            }
            return null
        }
        prisma.rideInvite.update = async ({ where, data }) => ({
            id: where.id,
            status: data.status,
            roomId: 'room_100'
        })
        prisma.rideInvite.updateMany = async ({ where, data }) => ({
            count: 1
        })
        prisma.user.findUnique = async ({ where }) => {
            if (where.id === 'host_001') {
                return { displayName: 'Host Commander', handle: 'commander' }
            }
            return null
        }

        const res = await fetch(`${baseUrl}/api/invites/respond`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ inviteId: 'inv_100', response: 'ACCEPTED' })
        })

        assert.strictEqual(res.status, 200)
        const body = await res.json()
        assert.strictEqual(body.status, 'ACCEPTED')
    })

    it('POST /api/invites/respond returns 403 if invite does not belong to caller', async () => {
        currentUser = { uid: 'unauthorized_user', name: 'Intruder', email: 'intruder@test.com' }
        prisma.rideInvite.findUnique = async () => ({
            id: 'inv_200',
            inviteeId: 'target_user',
            inviterId: 'host_001',
            status: 'PENDING',
            room: { name: 'Alpha Squad', ownerId: 'host_001' }
        })

        const res = await fetch(`${baseUrl}/api/invites/respond`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ inviteId: 'inv_200', response: 'ACCEPTED' })
        })

        assert.strictEqual(res.status, 403)
        const body = await res.json()
        assert.strictEqual(body.error, 'Not your invite')
    })

    it('POST /api/lobby/create auto-disambiguates when convoy name already exists', async () => {
        currentUser = { uid: 'host_002', name: 'Host Two', email: 'host2@ridervoice.test' }
        const existingNames = new Set(['Weekend Pack'])
        let createdRecord = null

        prisma.room.findUnique = async ({ where }) => {
            if (existingNames.has(where.name)) {
                return { id: 'existing_room_id', name: where.name }
            }
            return null
        }
        prisma.room.create = async ({ data }) => {
            createdRecord = data
            existingNames.add(data.name)
            return {
                id: 'room_auto_1',
                name: data.name,
                ownerId: data.ownerId,
                diagnosticsEnabled: data.diagnosticsEnabled
            }
        }
        prisma.diagnosticRun.findFirst = async () => null

        const res = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ convoyName: 'Weekend Pack' })
        })

        assert.strictEqual(res.status, 201)
        const body = await res.json()
        assert.strictEqual(body.convoyName, 'Weekend Pack-2')
        assert.strictEqual(createdRecord.name, 'Weekend Pack-2')
    })

    it('POST /api/lobby/create supports multiple successive collisions', async () => {
        currentUser = { uid: 'host_003', name: 'Host Three', email: 'host3@ridervoice.test' }
        const existingNames = new Set(['Thunder Road', 'Thunder Road-2'])
        let createdRecord = null

        prisma.room.findUnique = async ({ where }) => {
            if (existingNames.has(where.name)) {
                return { id: 'room_collide', name: where.name }
            }
            return null
        }
        prisma.room.create = async ({ data }) => {
            createdRecord = data
            return {
                id: 'room_auto_2',
                name: data.name,
                ownerId: data.ownerId,
                diagnosticsEnabled: data.diagnosticsEnabled
            }
        }

        const res = await fetch(`${baseUrl}/api/lobby/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ convoyName: 'Thunder Road' })
        })

        assert.strictEqual(res.status, 201)
        const body = await res.json()
        assert.strictEqual(body.convoyName, 'Thunder Road-3')
        assert.strictEqual(createdRecord.name, 'Thunder Road-3')
    })
})

