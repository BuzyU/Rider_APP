'use strict'

const { describe, it, afterEach } = require('node:test')
const assert = require('node:assert/strict')
const http = require('node:http')
const express = require('express')

const prisma = require('../src/db')
const cacheService = require('../src/services/cacheService')
const rateLimiter = require('../src/middleware/rateLimiter')
const lobbyRoutes = require('../src/routes/lobbyRoutes')
const userRoutes = require('../src/routes/userRoutes')
const healthRoute = require('../src/routes/healthRoute')
const emergencyRoutes = require('../src/routes/emergencyRoutes')

describe('WP-160..WP-166: Cross-Feature Backend E2E Flows & 30-Rider Polling Rate Limit', () => {
    afterEach(() => {
        cacheService.clear()
        rateLimiter._reset()
        healthRoute.setSchemaHealth(true, { structuralOk: true, missing: [] })
    })

    it('Flow F: DELETE /api/users/fcm-token accepts query param ?token=... and unregisters token on sign-out', async () => {
        const origDeleteMany = prisma.deviceToken.deleteMany
        let deletedWhere = null
        prisma.deviceToken.deleteMany = async (args) => {
            deletedWhere = args.where
            return { count: 1 }
        }

        const app = express()
        app.use(express.json())
        app.use((req, _res, next) => { req.user = { uid: 'rider_fcm_1' }; next() })
        app.use('/api/users', userRoutes)
        const server = http.createServer(app)
        await new Promise(r => server.listen(0, r))
        const port = server.address().port

        try {
            const res = await fetch(`http://127.0.0.1:${port}/api/users/fcm-token?token=fcm_tok_xyz`, {
                method: 'DELETE'
            })
            assert.strictEqual(res.status, 200)
            assert.deepStrictEqual(deletedWhere, { userId: 'rider_fcm_1', token: 'fcm_tok_xyz' })
        } finally {
            prisma.deviceToken.deleteMany = origDeleteMany
            await new Promise(r => server.close(r))
        }
    })

    it('Flow E: Duplicate SOS retry with same clientAlertId is idempotent and succeeds even when schema is degraded in production', async () => {
        const { app } = require('../src/server')
        const notificationService = require('../src/services/notificationService')
        const origEnv = process.env.NODE_ENV
        const origUserUpsert = prisma.user.upsert
        const origRoomFindFirst = prisma.room.findFirst
        const origRoomFindUnique = prisma.room.findUnique
        const origUserFindUnique = prisma.user.findUnique
        const origAlertCreate = prisma.emergencyAlert.create
        const origAlertFindFirst = prisma.emergencyAlert.findFirst
        const origAlertUpdate = prisma.emergencyAlert.update
        const origSendEmergencyAlert = notificationService.sendEmergencyAlert

        process.env.NODE_ENV = 'production'
        healthRoute.setSchemaHealth(false, { structuralOk: false, missing: ['Room.offlineBackupCode'] })
        cacheService.set('auth_token:sos_idemp_tok', { uid: 'sos_member', email: 'm@test.com', name: 'Member' }, 60)
        prisma.user.upsert = async () => ({ id: 'sos_member', email: 'm@test.com' })
        prisma.room.findFirst = async () => ({
            id: 'room-sos-1',
            name: 'SosConvoy',
            status: 'LIVE',
            ownerId: 'host_u',
            invites: [{ inviteeId: 'sos_member' }]
        })
        prisma.room.findUnique = prisma.room.findFirst
        prisma.user.findUnique = async () => ({ handle: 'sosrider', displayName: 'SOS Rider' })

        const seenAlerts = new Map()
        prisma.emergencyAlert.findFirst = async ({ where }) => seenAlerts.get(where.clientAlertId) || null
        prisma.emergencyAlert.create = async ({ data }) => {
            if (seenAlerts.has(data.clientAlertId)) {
                const err = new Error('Unique constraint')
                err.code = 'P2002'
                throw err
            }
            const row = { id: `alt-${data.clientAlertId}`, ...data }
            seenAlerts.set(data.clientAlertId, row)
            return row
        }
        prisma.emergencyAlert.update = async ({ where, data }) => {
            for (const row of seenAlerts.values()) {
                if (row.id === where.id) Object.assign(row, data)
            }
            return { id: where.id, ...data }
        }
        notificationService.sendEmergencyAlert = async () => ({
            sent: true,
            recipientsCount: 1,
            delivered: 1,
            undeliverable: 0
        })

        const server = http.createServer(app)
        await new Promise(r => server.listen(0, r))
        const port = server.address().port

        try {
            const payload = { roomName: 'SosConvoy', lat: 18.52, lng: 73.85, clientAlertId: 'client-alert-123456' }
            const r1 = await fetch(`http://127.0.0.1:${port}/api/emergency/alert`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', Authorization: 'Bearer sos_idemp_tok' },
                body: JSON.stringify(payload)
            })
            assert.strictEqual(r1.status, 200)
            const b1 = await r1.json()
            assert.strictEqual(b1.sent, true)

            const r2 = await fetch(`http://127.0.0.1:${port}/api/emergency/alert`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', Authorization: 'Bearer sos_idemp_tok' },
                body: JSON.stringify(payload)
            })
            assert.strictEqual(r2.status, 200)
            const b2 = await r2.json()
            assert.strictEqual(b2.alertId, b1.alertId)
            assert.strictEqual(b2.duplicate, true)
            assert.strictEqual(seenAlerts.size, 1, 'Duplicate retry must not create a second EmergencyAlert row')
        } finally {
            process.env.NODE_ENV = origEnv
            prisma.user.upsert = origUserUpsert
            prisma.room.findFirst = origRoomFindFirst
            prisma.room.findUnique = origRoomFindUnique
            prisma.user.findUnique = origUserFindUnique
            prisma.emergencyAlert.create = origAlertCreate
            prisma.emergencyAlert.findFirst = origAlertFindFirst
            prisma.emergencyAlert.update = origAlertUpdate
            notificationService.sendEmergencyAlert = origSendEmergencyAlert
            await new Promise(r => server.close(r))
        }
    })

    it('WP-165: 30 riders polling on one shared hotspot IP (20 req/min/rider = 600+ total -> up to 1800 allowed) do not trigger 429', async () => {
        rateLimiter._reset()
        const app = express()
        app.use(rateLimiter.ipLimiter)
        app.use((req, _res, next) => { req.user = { uid: req.headers['x-uid'] || 'rider_1' }; next() })
        app.use(rateLimiter.userLimiter)
        app.get('/api/lobby/Pack30/status', (_req, res) => res.json({ ok: true }))

        const server = http.createServer(app)
        await new Promise(r => server.listen(0, r))
        const port = server.address().port

        try {
            // Simulate 30 riders each polling 25 times in 1 minute from the same shared hotspot IP (= 750 requests total, previously breached 600 limit!)
            let failures = 0
            for (let poll = 0; poll < 25; poll++) {
                for (let r = 1; r <= 30; r++) {
                    const res = await fetch(`http://127.0.0.1:${port}/api/lobby/Pack30/status`, {
                        headers: { 'x-uid': `rider_${r}` }
                    })
                    if (res.status !== 200) failures++
                }
            }
            assert.strictEqual(failures, 0, '30 riders behind one shared hotspot IP must never trigger 429 during normal lobby polling')
        } finally {
            await new Promise(r => server.close(r))
        }
    })
})
