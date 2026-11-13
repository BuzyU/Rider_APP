const { describe, it, before, after, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const prisma = require('../src/db')

process.env.NODE_ENV = 'test'

describe('SOS idempotency (clientAlertId)', () => {
    let server, baseUrl
    const saved = {}
    let alerts = []
    let created = 0
    const room = {
        id: 'e8b1d9c4-0000-4000-8000-0000000000aa',
        name: 'SosConvoy',
        status: 'ACTIVE',
        ownerId: 'sos_owner',
        invites: [{ inviteeId: 'sos_owner2' }]
    }
    let currentUser = { uid: 'sos_owner' }

    before(async () => {
        for (const k of ['room', 'emergencyAlert', 'user', 'deviceToken']) saved[k] = prisma[k]
        prisma.room = {
            ...prisma.room,
            findUnique: async ({ where, select }) => {
                if (where.id !== room.id) return null
                const w = select && select.invites && select.invites.where
                if (w && w.inviteeId) {
                    return { ...room, invites: room.invites.filter(i => i.inviteeId === w.inviteeId) }
                }
                return room
            },
            findFirst: async () => room
        }
        prisma.user = { ...prisma.user, findUnique: async () => null }
        prisma.deviceToken = { findMany: async () => [] }
        prisma.emergencyAlert = {
            findFirst: async ({ where }) =>
                alerts.find(a => a.senderId === where.senderId && a.clientAlertId === where.clientAlertId) || null,
            create: async ({ data }) => {
                if (data.clientAlertId && alerts.some(a => a.senderId === data.senderId && a.clientAlertId === data.clientAlertId)) {
                    const e = new Error('unique'); e.code = 'P2002'; throw e
                }
                created++
                const a = { id: `alert_${created}`, ...data }
                alerts.push(a)
                return a
            },
            update: async ({ where, data }) => {
                const a = alerts.find(x => x.id === where.id)
                if (a) Object.assign(a, data)
                return a
            }
        }
        const app = express()
        app.use(express.json())
        app.use((req, _res, next) => { req.user = currentUser; next() })
        app.use('/api/emergency', require('../src/routes/emergencyRoutes'))
        await new Promise(r => { server = app.listen(0, '127.0.0.1', () => { baseUrl = `http://127.0.0.1:${server.address().port}`; r() }) })
    })

    after(async () => {
        await new Promise(r => server.close(r))
        for (const [k, v] of Object.entries(saved)) prisma[k] = v
    })

    beforeEach(() => { alerts = []; created = 0; currentUser = { uid: 'sos_owner' } })

    const post = body => fetch(`${baseUrl}/api/emergency/alert`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
    })

    it('same clientAlertId twice creates exactly one alert and flags duplicate', async () => {
        const body = { roomName: room.id, lat: 1, lng: 2, clientAlertId: 'abcd1234-ef56-7890' }
        const r1 = await post(body)
        const j1 = await r1.json()
        const r2 = await post(body)
        const j2 = await r2.json()
        assert.equal(r1.status, 200)
        assert.equal(r2.status, 200)
        assert.equal(created, 1)
        assert.equal(j2.duplicate, true)
        assert.equal(j2.alertId, j1.alertId)
    })

    it('different clientAlertIds create separate alerts', async () => {
        await post({ roomName: room.id, lat: 1, lng: 2, clientAlertId: 'aaaaaaaa-0001' })
        await post({ roomName: room.id, lat: 1, lng: 2, clientAlertId: 'aaaaaaaa-0002' })
        assert.equal(created, 2)
    })

    it('invalid clientAlertId is ignored (legacy behaviour, still creates)', async () => {
        const r = await post({ roomName: room.id, lat: 1, lng: 2, clientAlertId: '!!bad!!' })
        assert.equal(r.status, 200)
        assert.equal(created, 1)
        assert.equal(alerts[0].clientAlertId, undefined)
    })

    it('non-member gets 403 and nothing is stored', async () => {
        currentUser = { uid: 'stranger_x' }
        prisma.roomJoinToken = prisma.roomJoinToken || {}
        const r = await post({ roomName: room.id, lat: 1, lng: 2, clientAlertId: 'bbbbbbbb-0001' })
        assert.equal(r.status, 403)
        assert.equal(created, 0)
    })
})
