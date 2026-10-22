const { describe, it, before, after } = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const prisma = require('../src/db')

process.env.LIVEKIT_API_KEY = 'test_livekit_key'
process.env.LIVEKIT_API_SECRET = 'test_livekit_secret_1234567890123456'
process.env.LIVEKIT_URL = 'http://localhost:7880'
process.env.NODE_ENV = 'test'

function decodeJwtPayload(token) {
    return JSON.parse(Buffer.from(token.split('.')[1], 'base64').toString('utf8'))
}

// Phase 1: room lifecycle + membership enforcement on token issuance.
describe('Phase 1: room lifecycle and membership', () => {
    let server
    let baseUrl
    let currentUser = { uid: 'rider_x', name: 'Rider X' }
    const originals = {}

    before(async () => {
        originals.roomFindFirst = prisma.room.findFirst
        originals.inviteFindUnique = prisma.rideInvite.findUnique
        originals.userFindUnique = prisma.user.findUnique
        originals.diagnosticRunFindFirst = prisma.diagnosticRun.findFirst
        prisma.user.findUnique = async () => null
        prisma.diagnosticRun.findFirst = async () => null

        const app = express()
        app.use(express.json())
        app.use((req, res, next) => { req.user = currentUser; next() })
        app.use('/api/lobby', require('../src/routes/lobbyRoutes'))
        server = app.listen(0)
        await new Promise(r => server.once('listening', r))
        baseUrl = `http://127.0.0.1:${server.address().port}`
    })

    after(async () => {
        prisma.room.findFirst = originals.roomFindFirst
        prisma.rideInvite.findUnique = originals.inviteFindUnique
        prisma.user.findUnique = originals.userFindUnique
        prisma.diagnosticRun.findFirst = originals.diagnosticRunFindFirst
        if (server) await new Promise(r => server.close(r))
    })

    const post = (body) => fetch(`${baseUrl}/api/lobby/join-token`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    })

    it('a REMOVED rider is refused a token (403)', async () => {
        currentUser = { uid: 'removed_rider', name: 'Gone' }
        prisma.room.findFirst = async () => ({ id: 'r1', name: 'Convoy-R', status: 'LOBBY', ownerId: 'host_1', diagnosticsEnabled: false })
        prisma.rideInvite.findUnique = async () => ({ status: 'REMOVED' })
        const res = await post({ roomName: 'Convoy-R' })
        assert.strictEqual(res.status, 403)
    })

    it('an ENDED room is never revived by a token request (no 200)', async () => {
        currentUser = { uid: 'late_rider', name: 'Late' }
        // Resolver only considers non-ENDED rooms for name lookups.
        prisma.room.findFirst = async () => null
        prisma.rideInvite.findUnique = async () => ({ status: 'ACCEPTED' })
        const res = await post({ roomName: 'Convoy-Old' })
        assert.ok([404, 410].includes(res.status), `expected 404/410, got ${res.status}`)
    })

    it('issued JWT does not grant roomCreate', async () => {
        currentUser = { uid: 'member_1', name: 'Member' }
        prisma.room.findFirst = async () => ({ id: 'r2', name: 'Convoy-OK', status: 'LOBBY', ownerId: 'host_2', diagnosticsEnabled: false })
        prisma.rideInvite.findUnique = async () => ({ status: 'ACCEPTED' })
        const res = await post({ roomName: 'Convoy-OK' })
        assert.strictEqual(res.status, 200)
        const payload = decodeJwtPayload((await res.json()).token)
        assert.ok(!payload.video.roomCreate, 'roomCreate must not be granted')
        assert.strictEqual(payload.video.roomJoin, true)
    })
})
