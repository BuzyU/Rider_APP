'use strict'

const { describe, it, afterEach } = require('node:test')
const assert = require('node:assert/strict')
const http = require('node:http')
const express = require('express')

const prisma = require('../src/db')
const cacheService = require('../src/services/cacheService')
const rateLimiter = require('../src/middleware/rateLimiter')
const userRoutes = require('../src/routes/userRoutes')
const healthRoute = require('../src/routes/healthRoute')

describe('WP-173 Flows (h) and (j)', () => {
    afterEach(() => {
        cacheService.clear()
        rateLimiter._reset()
        healthRoute.setSchemaHealth(true, { structuralOk: true, missing: [] })
    })

    it('Flow H: DELETE /api/users/account wipes user profile, tokens, invites, friends, rides, rooms, and cache', async () => {
        const deletedCalls = []
        const origTransaction = prisma.$transaction
        const originals = {
            deviceTokenDeleteMany: prisma.deviceToken.deleteMany,
            friendshipDeleteMany: prisma.friendship.deleteMany,
            rideInviteDeleteMany: prisma.rideInvite.deleteMany,
            emergencyAlertDeleteMany: prisma.emergencyAlert.deleteMany,
            convoyEventDeleteMany: prisma.convoyEvent.deleteMany,
            rideSessionFindMany: prisma.rideSession.findMany,
            rideSessionDeleteMany: prisma.rideSession.deleteMany,
            rideCreateRequestDeleteMany: prisma.rideCreateRequest.deleteMany,
            roomFindMany: prisma.room.findMany,
            roomDeleteMany: prisma.room.deleteMany,
            userDelete: prisma.user.delete
        }

        const txMock = {
            deviceToken: { deleteMany: async (a) => { deletedCalls.push(['deviceToken', a.where]); return { count: 1 } } },
            friendship: { deleteMany: async (a) => { deletedCalls.push(['friendship', a.where]); return { count: 1 } } },
            rideInvite: { deleteMany: async (a) => { deletedCalls.push(['rideInvite', a.where]); return { count: 1 } } },
            emergencyAlert: { deleteMany: async (a) => { deletedCalls.push(['emergencyAlert', a.where]); return { count: 1 } } },
            convoyEvent: { deleteMany: async (a) => { deletedCalls.push(['convoyEvent', a.where]); return { count: 1 } } },
            rideSession: {
                findMany: async () => [{ id: 'rs-1' }],
                deleteMany: async (a) => { deletedCalls.push(['rideSession', a.where]); return { count: 1 } }
            },
            rideCreateRequest: { deleteMany: async (a) => { deletedCalls.push(['rideCreateRequest', a.where]); return { count: 1 } } },
            room: {
                findMany: async () => [{ id: 'room-owned-1' }],
                deleteMany: async (a) => { deletedCalls.push(['room', a.where]); return { count: 1 } }
            },
            user: { delete: async (a) => { deletedCalls.push(['user', a.where]); return { id: 'u_del_1' } } }
        }
        prisma.$transaction = async (fn) => fn(txMock)
        cacheService.set('user_me:u_del_1', { id: 'u_del_1' }, 60)
        cacheService.set('user_name:u_del_1', 'DelRider', 60)

        const app = express()
        app.use(express.json())
        app.use((req, _res, next) => { req.user = { uid: 'u_del_1' }; next() })
        app.use('/api/users', userRoutes)
        const server = http.createServer(app)
        await new Promise(r => server.listen(0, r))
        const port = server.address().port

        try {
            const res = await fetch(`http://127.0.0.1:${port}/api/users/account`, {
                method: 'DELETE'
            })
            assert.strictEqual(res.status, 200, `Expected 200 on DELETE /api/users/account, got ${res.status}`)
            const body = await res.json()
            assert.strictEqual(body.deleted, true)
            assert.ok(deletedCalls.some(([m]) => m === 'deviceToken'), 'DeviceToken must be wiped')
            assert.ok(deletedCalls.some(([m]) => m === 'user'), 'User row must be deleted')
            assert.strictEqual(cacheService.get('user_me:u_del_1'), null)
            assert.strictEqual(cacheService.get('user_name:u_del_1'), null)
        } finally {
            prisma.$transaction = origTransaction
            Object.assign(prisma.deviceToken, { deleteMany: originals.deviceTokenDeleteMany })
            Object.assign(prisma.friendship, { deleteMany: originals.friendshipDeleteMany })
            Object.assign(prisma.rideInvite, { deleteMany: originals.rideInviteDeleteMany })
            Object.assign(prisma.emergencyAlert, { deleteMany: originals.emergencyAlertDeleteMany })
            Object.assign(prisma.convoyEvent, { deleteMany: originals.convoyEventDeleteMany })
            Object.assign(prisma.rideSession, { findMany: originals.rideSessionFindMany, deleteMany: originals.rideSessionDeleteMany })
            Object.assign(prisma.rideCreateRequest, { deleteMany: originals.rideCreateRequestDeleteMany })
            Object.assign(prisma.room, { findMany: originals.roomFindMany, deleteMany: originals.roomDeleteMany })
            Object.assign(prisma.user, { delete: originals.userDelete })
            await new Promise(r => server.close(r))
        }
    })

    it('Flow J: Backend degraded returns scoped 503 on create-ride while SOS/auth/health stay up, and recovers without restart', async () => {
        const { app } = require('../src/server')
        const notificationService = require('../src/services/notificationService')
        const origEnv = process.env.NODE_ENV
        const origUserUpsert = prisma.user.upsert
        const origUserFindUnique = prisma.user.findUnique
        const origRoomFindFirst = prisma.room.findFirst
        const origRoomFindUnique = prisma.room.findUnique
        const origRoomCreate = prisma.room.create
        const origRideCreateReqFind = prisma.rideCreateRequest.findUnique
        const origRideCreateReqCreate = prisma.rideCreateRequest.create
        const origAlertFindFirst = prisma.emergencyAlert.findFirst
        const origAlertCreate = prisma.emergencyAlert.create
        const origAlertUpdate = prisma.emergencyAlert.update
        const origDeviceFindMany = prisma.deviceToken.findMany
        const origSendEmergencyAlert = notificationService.sendEmergencyAlert
        const origTx = prisma.$transaction

        process.env.NODE_ENV = 'production'
        cacheService.set('auth_token:flow_j_tok', { uid: 'u_flow_j', email: 'j@test.com', name: 'RiderJ' }, 60000)
        prisma.user.upsert = async () => ({ id: 'u_flow_j', email: 'j@test.com' })
        prisma.user.findUnique = async () => ({ id: 'u_flow_j', handle: 'riderj', displayName: 'RiderJ', bikeModel: null, bio: null, email: 'j@test.com', phone: null, createdAt: new Date() })
        prisma.room.findFirst = async ({ where }) => {
            if (where?.name?.equals === 'SosUpRoom' || where?.name === 'SosUpRoom') {
                return { id: 'room-sos-j', name: 'SosUpRoom', status: 'ACTIVE', ownerId: 'u_flow_j', invites: [{ inviteeId: 'u_peer_j' }] }
            }
            return null
        }
        prisma.room.findUnique = prisma.room.findFirst
        prisma.rideCreateRequest.findUnique = async () => null
        prisma.rideCreateRequest.create = async ({ data }) => ({ id: 'rcr-1', ...data })
        prisma.room.create = async ({ data }) => ({
            id: 'room-recovered-j',
            name: data.name,
            lkRoomName: 'rv_room-recovered-j',
            ownerId: data.ownerId,
            diagnosticsEnabled: false,
            joinCode: '554433',
            status: 'LOBBY'
        })
        prisma.$transaction = async (fn) => fn(prisma)
        prisma.emergencyAlert.findFirst = async () => null
        prisma.emergencyAlert.create = async ({ data }) => ({ id: 'alert-j-1', ...data })
        prisma.emergencyAlert.update = async ({ data }) => ({ id: 'alert-j-1', ...data })
        prisma.deviceToken.findMany = async () => []
        notificationService.sendEmergencyAlert = async () => ({ delivered: 1, undeliverable: 0, recipientsCount: 1 })

        healthRoute.setSchemaHealth(false, { structuralOk: false, missing: ['Room.offlineBackupCode'] })

        const server = http.createServer(app)
        await new Promise(r => server.listen(0, r))
        const port = server.address().port
        const base = `http://127.0.0.1:${port}`
        const headers = { 'Content-Type': 'application/json', Authorization: 'Bearer flow_j_tok' }

        try {
            // 1. Create-ride must return scoped 503 SCHEMA_DEGRADED
            const degradedCreate = await fetch(`${base}/api/lobby/create`, {
                method: 'POST',
                headers,
                body: JSON.stringify({ convoyName: 'RecoveredConvoy', clientRequestId: 'req-j-1' })
            })
            assert.strictEqual(degradedCreate.status, 503)
            const degradedBody = await degradedCreate.json()
            assert.strictEqual(degradedBody.code, 'SCHEMA_DEGRADED')
            assert.strictEqual(degradedCreate.headers.get('retry-after'), '30')

            // 2. SOS stays up (200)
            const sosRes = await fetch(`${base}/api/emergency/alert`, {
                method: 'POST',
                headers,
                body: JSON.stringify({ roomName: 'SosUpRoom', clientAlertId: 'sos-j-1', lat: 18.5, lng: 73.8 })
            })
            assert.strictEqual(sosRes.status, 200)
            const sosBody = await sosRes.json()
            assert.strictEqual(sosBody.sent, true)

            // 3. Auth-protected /api/users/me and /health stay up (200)
            const meRes = await fetch(`${base}/api/users/me`, { headers })
            assert.strictEqual(meRes.status, 200)

            const healthRes = await fetch(`${base}/health`)
            assert.strictEqual(healthRes.status, 200)
            const healthBody = await healthRes.json()
            assert.strictEqual(healthBody.status, 'degraded')

            // 4. Recover without restart -> create-ride works again immediately
            healthRoute.setSchemaHealth(true, { structuralOk: true, missing: [] })
            const healedCreate = await fetch(`${base}/api/lobby/create`, {
                method: 'POST',
                headers,
                body: JSON.stringify({ convoyName: 'RecoveredConvoy', clientRequestId: 'req-j-2' })
            })
            assert.ok(healedCreate.status === 200 || healedCreate.status === 201, `Expected 200/201 after heal, got ${healedCreate.status}`)
            const healedBody = await healedCreate.json()
            assert.strictEqual(healedBody.convoyName, 'RecoveredConvoy')
        } finally {
            process.env.NODE_ENV = origEnv
            prisma.user.upsert = origUserUpsert
            prisma.user.findUnique = origUserFindUnique
            prisma.room.findFirst = origRoomFindFirst
            prisma.room.findUnique = origRoomFindUnique
            prisma.room.create = origRoomCreate
            prisma.rideCreateRequest.findUnique = origRideCreateReqFind
            prisma.rideCreateRequest.create = origRideCreateReqCreate
            prisma.emergencyAlert.findFirst = origAlertFindFirst
            prisma.emergencyAlert.create = origAlertCreate
            prisma.emergencyAlert.update = origAlertUpdate
            prisma.deviceToken.findMany = origDeviceFindMany
            notificationService.sendEmergencyAlert = origSendEmergencyAlert
            prisma.$transaction = origTx
            await new Promise(r => server.close(r))
        }
    })
})
