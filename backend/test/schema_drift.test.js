'use strict'

const { describe, it, afterEach } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const http = require('node:http')
const express = require('express')
const EmbeddedPostgresPkg = require('embedded-postgres')
const EmbeddedPostgres = EmbeddedPostgresPkg.default || EmbeddedPostgresPkg
const { Client } = require('pg')
const { PrismaClient } = require('@prisma/client')

const { assertSchema, ensureDbSchema, MIGRATION_STATEMENTS } = require('../src/services/schemaMigration')
const healthRoute = require('../src/routes/healthRoute')
const lobbyRoutes = require('../src/routes/lobbyRoutes')
const cacheService = require('../src/services/cacheService')
const prisma = require('../src/db')

/**
 * Parse scalar fields from backend/prisma/schema.prisma for each model.
 */
function parsePrismaModels(schemaPath) {
    const content = fs.readFileSync(schemaPath, 'utf8')
    const models = {}
    let current = null
    const scalarTypes = new Set(['String', 'Int', 'Float', 'Boolean', 'DateTime', 'Json', 'user_role', 'RoomStatus', 'InviteStatus', 'InviteSource', 'FriendStatus'])

    for (const rawLine of content.split(/\r?\n/)) {
        const line = rawLine.trim()
        const modelMatch = line.match(/^model\s+(\w+)\s+\{/)
        if (modelMatch) {
            current = modelMatch[1]
            models[current] = { table: current, columns: [] }
            continue
        }
        if (current && line === '}') {
            current = null
            continue
        }
        if (!current || !line || line.startsWith('//')) continue
        const mapMatch = line.match(/^@@map\("([^"]+)"\)/)
        if (mapMatch) {
            models[current].table = mapMatch[1]
            continue
        }
        if (line.startsWith('@@')) continue
        const fieldMatch = line.match(/^(\w+)\s+([^\s]+)(.*)$/)
        if (fieldMatch) {
            const [, fieldName, fieldTypeRaw, rest] = fieldMatch
            const baseType = fieldTypeRaw.replace(/[?\[\]]/g, '')
            if (scalarTypes.has(baseType)) {
                const colMapMatch = rest.match(/@map\("([^"]+)"\)/)
                models[current].columns.push(colMapMatch ? colMapMatch[1] : fieldName)
            }
        }
    }
    return models
}

describe('WP-142: Schema Drift, Re-validation, Sticky Flag & Authoritative SQL', () => {
    const origHealth = healthRoute.getSchemaHealth()

    afterEach(() => {
        healthRoute.initSchemaHealthRevalidation(null, null)
        healthRoute.resetRevalidation()
        healthRoute.setSchemaHealth(origHealth)
    })

    it('all post-baseline columns in schema.prisma appear in MIGRATION_STATEMENTS', () => {
        const schemaPath = path.join(__dirname, '../prisma/schema.prisma')
        const models = parsePrismaModels(schemaPath)
        const allMigrationSql = MIGRATION_STATEMENTS.join('\n')

        const expectedMigratedFields = {
            User: ['role'],
            Room: [
                'lkRoomName', 'status', 'diagnosticsEnabled', 'startedAt', 'endedAt',
                'joinCode', 'offlineBackupCode', 'offlineMeshEnabled', 'origin',
                'destination', 'meetupPoint', 'estimatedDurationMin', 'lastEmptyAt'
            ],
            RideInvite: ['source', 'offlineConfigStatus', 'offlineConfigUpdatedAt', 'lastLobbyPollAt', 'updatedAt'],
            RoomJoinToken: ['id', 'roomId', 'token', 'expiresAt', 'createdAt', 'createdById', 'revokedAt', 'maxUses', 'useCount'],
            RideSession: ['roomName', 'clientRideId', 'diagnosticsEnabled'],
            EmergencyAlert: ['id', 'senderId', 'roomId', 'roomName', 'lat', 'lng', 'recipients', 'status', 'clientAlertId', 'createdAt'],
            DiagnosticRun: ['id', 'convoyId', 'testNumber', 'status', 'createdAt', 'closedAt', 'emailedAt', 'closeAt', 'summary'],
            DiagnosticParticipant: ['id', 'runId', 'riderId', 'handle', 'joinedAt', 'stoppedAt', 'lastSeq', 'lastBatchAt'],
            DiagnosticBatch: ['id', 'runId', 'riderId', 'sequenceNumber', 'payload', 'receivedAt'],
            RideCreateRequest: ['id', 'ownerId', 'clientRequestId', 'payloadHash', 'roomId', 'createdAt']
        }

        for (const [modelName, fields] of Object.entries(expectedMigratedFields)) {
            assert.ok(models[modelName], `Model ${modelName} must exist in schema.prisma`)
            for (const col of fields) {
                assert.ok(
                    models[modelName].columns.includes(col),
                    `Field ${modelName}.${col} missing from schema.prisma`
                )
                assert.ok(
                    allMigrationSql.includes(`"${col}"`),
                    `Schema drift: ${modelName}.${col} is in schema.prisma but missing from MIGRATION_STATEMENTS`
                )
            }
        }
    })

    it('assertSchema fails when Room.offlineBackupCode or RideInvite.lastLobbyPollAt or EmergencyAlert.clientAlertId is omitted', async () => {
        const fullCols = [
            ['Room', ['id', 'name', 'lkRoomName', 'status', 'ownerId', 'lastEmptyAt', 'offlineBackupCode', 'offlineMeshEnabled']],
            ['RideCreateRequest', ['id', 'ownerId', 'clientRequestId', 'roomId', 'payloadHash']],
            ['RideInvite', ['id', 'roomId', 'inviteeId', 'status', 'source', 'offlineConfigStatus', 'offlineConfigUpdatedAt', 'lastLobbyPollAt']],
            ['RoomJoinToken', ['id', 'roomId', 'token', 'maxUses', 'useCount']],
            ['EmergencyAlert', ['id', 'roomId', 'senderId', 'status', 'clientAlertId']],
            ['DiagnosticRun', ['id', 'convoyId', 'testNumber', 'status', 'closeAt']],
            ['RideSession', ['id', 'riderId', 'roomName', 'clientRideId', 'diagnosticsEnabled']]
        ]

        const makeMock = (omitTable, omitCol) => ({
            $queryRaw: async (strings) => {
                const q = strings.join('?')
                if (q.includes('information_schema.columns')) {
                    const out = []
                    for (const [tbl, cols] of fullCols) {
                        for (const col of cols) {
                            if (tbl === omitTable && col === omitCol) continue
                            out.push({ table_name: tbl, column_name: col })
                        }
                    }
                    return out
                }
                if (q.includes('pg_enum')) return [{ enumlabel: 'REMOVED' }, { enumlabel: 'LEFT' }]
                if (q.includes('pg_indexes')) return [
                    { indexname: 'Room_name_active_unique' },
                    { indexname: 'Room_joinCode_active_unique' },
                    { indexname: 'RideInvite_roomId_inviteeId_key' },
                    { indexname: 'EmergencyAlert_senderId_clientAlertId_key' },
                    { indexname: 'RideSession_riderId_clientRideId_key' },
                    { indexname: 'RideCreateRequest_ownerId_clientRequestId_key' }
                ]
                if (q.includes('pg_constraint')) return [
                    { conname: 'RoomJoinToken_roomId_fkey' },
                    { conname: 'DiagnosticRun_convoyId_fkey' },
                    { conname: 'DiagnosticParticipant_runId_fkey' },
                    { conname: 'DiagnosticBatch_runId_fkey' },
                    { conname: 'RideCreateRequest_ownerId_fkey' },
                    { conname: 'RideCreateRequest_roomId_fkey' },
                    { conname: 'Room_ownerId_fkey' }
                ]
                return []
            }
        })

        const res1 = await assertSchema(makeMock('Room', 'offlineBackupCode'))
        assert.strictEqual(res1.valid, false)
        assert.ok(res1.missing.includes('Room.offlineBackupCode'))

        const res2 = await assertSchema(makeMock('RideInvite', 'lastLobbyPollAt'))
        assert.strictEqual(res2.valid, false)
        assert.ok(res2.missing.includes('RideInvite.lastLobbyPollAt'))

        const res3 = await assertSchema(makeMock('EmergencyAlert', 'clientAlertId'))
        assert.strictEqual(res3.valid, false)
        assert.ok(res3.missing.includes('EmergencyAlert.clientAlertId'))
    })

    it('duplicate active Room names defer Room_name_active_unique as a warning without structural failure', async () => {
        const mockPrisma = {
            $queryRaw: async (strings) => {
                const q = strings.join('?')
                if (q.includes('information_schema.columns')) {
                    return [
                        { table_name: 'Room', column_name: 'id' },
                        { table_name: 'Room', column_name: 'name' },
                        { table_name: 'Room', column_name: 'lkRoomName' },
                        { table_name: 'Room', column_name: 'status' },
                        { table_name: 'Room', column_name: 'ownerId' },
                        { table_name: 'Room', column_name: 'lastEmptyAt' },
                        { table_name: 'Room', column_name: 'offlineBackupCode' },
                        { table_name: 'Room', column_name: 'offlineMeshEnabled' },
                        { table_name: 'RideCreateRequest', column_name: 'id' },
                        { table_name: 'RideCreateRequest', column_name: 'ownerId' },
                        { table_name: 'RideCreateRequest', column_name: 'clientRequestId' },
                        { table_name: 'RideCreateRequest', column_name: 'roomId' },
                        { table_name: 'RideCreateRequest', column_name: 'payloadHash' },
                        { table_name: 'RideInvite', column_name: 'id' },
                        { table_name: 'RideInvite', column_name: 'roomId' },
                        { table_name: 'RideInvite', column_name: 'inviteeId' },
                        { table_name: 'RideInvite', column_name: 'status' },
                        { table_name: 'RideInvite', column_name: 'source' },
                        { table_name: 'RideInvite', column_name: 'offlineConfigStatus' },
                        { table_name: 'RideInvite', column_name: 'offlineConfigUpdatedAt' },
                        { table_name: 'RideInvite', column_name: 'lastLobbyPollAt' },
                        { table_name: 'RoomJoinToken', column_name: 'id' },
                        { table_name: 'RoomJoinToken', column_name: 'roomId' },
                        { table_name: 'RoomJoinToken', column_name: 'token' },
                        { table_name: 'RoomJoinToken', column_name: 'maxUses' },
                        { table_name: 'RoomJoinToken', column_name: 'useCount' },
                        { table_name: 'EmergencyAlert', column_name: 'id' },
                        { table_name: 'EmergencyAlert', column_name: 'roomId' },
                        { table_name: 'EmergencyAlert', column_name: 'senderId' },
                        { table_name: 'EmergencyAlert', column_name: 'status' },
                        { table_name: 'EmergencyAlert', column_name: 'clientAlertId' },
                        { table_name: 'DiagnosticRun', column_name: 'id' },
                        { table_name: 'DiagnosticRun', column_name: 'convoyId' },
                        { table_name: 'DiagnosticRun', column_name: 'testNumber' },
                        { table_name: 'DiagnosticRun', column_name: 'status' },
                        { table_name: 'DiagnosticRun', column_name: 'closeAt' },
                        { table_name: 'RideSession', column_name: 'id' },
                        { table_name: 'RideSession', column_name: 'riderId' },
                        { table_name: 'RideSession', column_name: 'roomName' },
                        { table_name: 'RideSession', column_name: 'clientRideId' },
                        { table_name: 'RideSession', column_name: 'diagnosticsEnabled' }
                    ]
                }
                if (q.includes('pg_enum')) return [{ enumlabel: 'REMOVED' }, { enumlabel: 'LEFT' }]
                if (q.includes('pg_indexes')) return [
                    // Room_name_active_unique omitted intentionally
                    { indexname: 'Room_joinCode_active_unique' },
                    { indexname: 'RideInvite_roomId_inviteeId_key' },
                    { indexname: 'EmergencyAlert_senderId_clientAlertId_key' },
                    { indexname: 'RideSession_riderId_clientRideId_key' },
                    { indexname: 'RideCreateRequest_ownerId_clientRequestId_key' }
                ]
                if (q.includes('lower("name")')) return [{ name: 'sunday ride', count: 2 }]
                if (q.includes('pg_constraint')) return [
                    { conname: 'RoomJoinToken_roomId_fkey' },
                    { conname: 'DiagnosticRun_convoyId_fkey' },
                    { conname: 'DiagnosticParticipant_runId_fkey' },
                    { conname: 'DiagnosticBatch_runId_fkey' },
                    { conname: 'RideCreateRequest_ownerId_fkey' },
                    { conname: 'RideCreateRequest_roomId_fkey' },
                    { conname: 'Room_ownerId_fkey' }
                ]
                return []
            }
        }

        const res = await assertSchema(mockPrisma)
        assert.strictEqual(res.valid, true)
        assert.strictEqual(res.missing.length, 0)
        assert.ok(res.warnings.length > 0)
        assert.match(res.warnings[0], /Room_name_active_unique/)
    })

    it('single-request P2022 in lobbyRoutes does NOT poison global schemaStructuralOk flag', async () => {
        healthRoute.setSchemaHealth(true, { structuralOk: true, missing: [] })
        const origCreate = prisma.room.create
        const origFindFirst = prisma.room.findFirst
        const origFindUnique = prisma.room.findUnique
        const origReqFind = prisma.rideCreateRequest.findUnique
        const origTransaction = prisma.$transaction

        const p2022 = new Error('The column `offlineBackupCode` does not exist in the current database.')
        p2022.code = 'P2022'

        prisma.rideCreateRequest.findUnique = async () => null
        prisma.room.findFirst = async () => null
        prisma.room.findUnique = async () => null
        prisma.room.create = async () => { throw p2022 }
        prisma.$transaction = async (fn) => fn(prisma)

        const testApp = express()
        testApp.use(express.json())
        testApp.use((req, _res, next) => { req.user = { uid: 'host-1', email: 'h@test.com', name: 'Host' }; next() })
        testApp.use('/api/lobby', lobbyRoutes)

        const server = http.createServer(testApp)
        await new Promise(r => server.listen(0, r))
        const port = server.address().port

        try {
            const res = await fetch(`http://127.0.0.1:${port}/api/lobby/create`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ convoyName: 'StickyTest', clientRequestId: 'req-sticky-1' })
            })
            assert.strictEqual(res.status, 503)
            const body = await res.json()
            assert.strictEqual(body.code, 'SCHEMA_DEGRADED')
            assert.strictEqual(
                healthRoute.getSchemaHealth().schemaStructuralOk,
                true,
                'Global schemaStructuralOk must remain true after a single-request P2022'
            )
        } finally {
            prisma.room.create = origCreate
            prisma.room.findFirst = origFindFirst
            prisma.room.findUnique = origFindUnique
            prisma.rideCreateRequest.findUnique = origReqFind
            prisma.$transaction = origTransaction
            await new Promise(r => server.close(r))
        }
    })

    it('setSchemaHealth(false) exposes missing items on /ready and auto-heals via re-validation without restart', async () => {
        let shouldPass = false
        const fakePrisma = {}
        const fakeAssert = async () => {
            if (shouldPass) return { valid: true, missing: [], warnings: [] }
            return { valid: false, missing: ['Room.offlineBackupCode'], warnings: [] }
        }

        healthRoute.initSchemaHealthRevalidation(fakePrisma, fakeAssert)
        healthRoute.setSchemaHealth(false, {
            structuralOk: false,
            missing: ['Room.offlineBackupCode'],
            error: 'Missing Room.offlineBackupCode'
        })

        const testApp = express()
        testApp.use('/', healthRoute)
        const server = http.createServer(testApp)
        await new Promise(r => server.listen(0, r))
        const port = server.address().port

        try {
            const degradedRes = await fetch(`http://127.0.0.1:${port}/ready`)
            assert.strictEqual(degradedRes.status, 503)
            const degradedBody = await degradedRes.json()
            assert.ok(degradedBody.missing.includes('Room.offlineBackupCode'))
            assert.match(degradedBody.error, /Room\.offlineBackupCode/)

            // Simulate schema being repaired in DB, then revalidation running
            shouldPass = true
            healthRoute.resetRevalidation()
            healthRoute.scheduleRevalidation(20)
            await new Promise(r => setTimeout(r, 100))

            assert.strictEqual(healthRoute.getSchemaHealth().schemaStructuralOk, true)
            const healedRes = await fetch(`http://127.0.0.1:${port}/ready`)
            assert.strictEqual(healedRes.status, 200)
        } finally {
            await new Promise(r => server.close(r))
        }
    })

    it('real Postgres: supabase_authoritative.sql is idempotent across two runs and recovers from dropped offlineBackupCode', { timeout: 120000 }, async () => {
        const testPort = 5439
        const testDataDir = path.join(__dirname, '../.pg_data_authoritative_test')
        const pg = new EmbeddedPostgres({
            databaseDir: testDataDir,
            user: 'postgres',
            password: 'password',
            port: testPort,
            persistent: false
        })

        let localPrisma = null
        try {
            if (fs.existsSync(testDataDir)) {
                fs.rmSync(testDataDir, { recursive: true, force: true })
            }
            await pg.initialise()
            await pg.start()
            await pg.createDatabase('ridervoice_auth_sql')

            const dbUrl = `postgresql://postgres:password@localhost:${testPort}/ridervoice_auth_sql`
            const client = new Client({ connectionString: dbUrl })
            await client.connect()

            // Seed base schema
            await client.query(fs.readFileSync(path.join(__dirname, '../supabase_schema.sql'), 'utf8'))

            const authSql = fs.readFileSync(path.join(__dirname, '../supabase_authoritative.sql'), 'utf8')

            // Run authoritative SQL twice (idempotency test)
            const runAuthoritative = async () => {
                // Split SECTION 1 enum ALTER TYPE statements out of multi-statement query so PG runs them safely
                const lines = authSql.split(/\r?\n/)
                const preEnum = []
                const rest = []
                let inEnum = false
                for (const l of lines) {
                    if (l.includes('SECTION 1:')) inEnum = true
                    if (inEnum && l.startsWith('ALTER TYPE ')) {
                        preEnum.push(l)
                    } else {
                        if (l.includes('SECTION 2:')) inEnum = false
                        rest.push(l)
                    }
                }
                for (const stmt of preEnum) {
                    await client.query(stmt)
                }
                return await client.query(rest.join('\n'))
            }

            await runAuthoritative()
            const secondResult = await runAuthoritative()
            const lastQueryResult = Array.isArray(secondResult) ? secondResult[secondResult.length - 1] : secondResult
            assert.strictEqual(
                lastQueryResult.rows.length,
                0,
                `Verification SELECT must return 0 missing items, got: ${JSON.stringify(lastQueryResult.rows)}`
            )

            // Verify real assertSchema + missing column simulation + self-heal recovery
            localPrisma = new PrismaClient({ datasources: { db: { url: dbUrl } } })
            await localPrisma.$connect()

            const healthyCheck = await assertSchema(localPrisma)
            assert.strictEqual(healthyCheck.valid, true)

            // Drop offlineBackupCode and verify assertSchema detects it
            await client.query('ALTER TABLE "Room" DROP COLUMN "offlineBackupCode"')
            const brokenCheck = await assertSchema(localPrisma)
            assert.strictEqual(brokenCheck.valid, false)
            assert.ok(brokenCheck.missing.includes('Room.offlineBackupCode'))

            // Run ensureDbSchema self-heal and verify recovery
            const repairResult = await ensureDbSchema(localPrisma)
            assert.strictEqual(repairResult.structuralOk, true)
            assert.strictEqual(repairResult.repaired, true)

            const recoveredCheck = await assertSchema(localPrisma)
            assert.strictEqual(recoveredCheck.valid, true)

            await client.end()
        } finally {
            if (localPrisma) {
                await localPrisma.$disconnect().catch(() => {})
            }
            try {
                await Promise.race([
                    pg.stop(),
                    new Promise(resolve => setTimeout(resolve, 2000))
                ])
            } catch (_) {}
            if (process.platform === 'win32') {
                try {
                    require('child_process').execSync('taskkill /F /IM postgres.exe /T', { stdio: 'ignore' })
                } catch (_) {}
            }
        }
    })

    it('WP-151: UNAVAILABLE and UNAVAILABLE_DEVICE do not block GET /status or POST /start', async () => {
        cacheService.clear()
        const origKey = process.env.LIVEKIT_API_KEY
        const origSecret = process.env.LIVEKIT_API_SECRET
        const origUrl = process.env.LIVEKIT_URL
        process.env.LIVEKIT_API_KEY = 'test_livekit_key'
        process.env.LIVEKIT_API_SECRET = 'test_livekit_secret_1234567890123456'
        process.env.LIVEKIT_URL = ''

        const orig = {
            roomFindFirst: prisma.room.findFirst,
            roomFindUnique: prisma.room.findUnique,
            roomUpdate: prisma.room.update,
            roomUpdateMany: prisma.room.updateMany,
            userFindUnique: prisma.user.findUnique,
            inviteFindMany: prisma.rideInvite.findMany,
            inviteCount: prisma.rideInvite.count,
            inviteUpdateMany: prisma.rideInvite.updateMany,
            diagFindFirst: prisma.diagnosticRun?.findFirst
        }

        const now = Date.now()
        const mockRoom = {
            id: '22222222-2222-4222-8222-222222222222',
            name: 'NonGmsConvoy',
            lkRoomName: 'rv_22222222-2222-4222-8222-222222222222',
            ownerId: 'host_u1',
            status: 'LOBBY',
            diagnosticsEnabled: false,
            offlineMeshEnabled: true,
            joinCode: 'NGM12345',
            offlineBackupCode: 'K7QX-M2PA-9TDE-4WZH',
            startedAt: null
        }
        const mockInvites = [
            {
                id: 'inv_u1',
                roomId: mockRoom.id,
                inviteeId: 'rider_unavail_1',
                status: 'ACCEPTED',
                source: 'HOST_INVITE',
                createdAt: new Date(now),
                offlineConfigStatus: 'UNAVAILABLE',
                offlineConfigUpdatedAt: new Date(now),
                lastLobbyPollAt: new Date(now),
                invitee: { id: 'rider_unavail_1', displayName: 'NonGMS One' }
            },
            {
                id: 'inv_u2',
                roomId: mockRoom.id,
                inviteeId: 'rider_unavail_dev',
                status: 'ACCEPTED',
                source: 'HOST_INVITE',
                createdAt: new Date(now),
                offlineConfigStatus: 'UNAVAILABLE_DEVICE',
                offlineConfigUpdatedAt: new Date(now),
                lastLobbyPollAt: new Date(now),
                invitee: { id: 'rider_unavail_dev', displayName: 'NonGMS Device' }
            }
        ]
        cacheService.set(`lobby_presence:${mockRoom.id}:rider_unavail_1`, now, 60)
        cacheService.set(`lobby_presence:${mockRoom.id}:rider_unavail_dev`, now, 60)

        prisma.room.findFirst = async () => ({ ...mockRoom, invites: mockInvites })
        prisma.room.findUnique = async () => ({ ...mockRoom, invites: mockInvites })
        prisma.room.update = async (args) => { Object.assign(mockRoom, args.data); return mockRoom }
        prisma.room.updateMany = async (args) => { Object.assign(mockRoom, args.data); return { count: 1 } }
        prisma.user.findUnique = async () => null
        prisma.rideInvite.findMany = async () => mockInvites
        prisma.rideInvite.count = async () => mockInvites.length
        prisma.rideInvite.updateMany = async () => ({ count: 1 })
        if (prisma.diagnosticRun) prisma.diagnosticRun.findFirst = async () => null

        const testApp = express()
        testApp.use(express.json())
        testApp.use((req, _res, next) => { req.user = { uid: 'host_u1', name: 'Host' }; next() })
        testApp.use('/api/lobby', lobbyRoutes)
        const server = http.createServer(testApp)
        await new Promise(r => server.listen(0, r))
        const port = server.address().port

        try {
            const statusRes = await fetch(`http://127.0.0.1:${port}/api/lobby/NonGmsConvoy/status`)
            assert.strictEqual(statusRes.status, 200)
            const statusJson = await statusRes.json()
            assert.strictEqual(statusJson.blockingRidersCount, 0)
            assert.strictEqual(statusJson.canStart, true)

            const startRes = await fetch(`http://127.0.0.1:${port}/api/lobby/NonGmsConvoy/start`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            })
            assert.strictEqual(startRes.status, 200)
            const startJson = await startRes.json()
            assert.ok(startJson.token)
        } finally {
            prisma.room.findFirst = orig.roomFindFirst
            prisma.room.findUnique = orig.roomFindUnique
            prisma.room.update = orig.roomUpdate
            prisma.room.updateMany = orig.roomUpdateMany
            prisma.user.findUnique = orig.userFindUnique
            prisma.rideInvite.findMany = orig.inviteFindMany
            prisma.rideInvite.count = orig.inviteCount
            prisma.rideInvite.updateMany = orig.inviteUpdateMany
            if (prisma.diagnosticRun) prisma.diagnosticRun.findFirst = orig.diagFindFirst
            process.env.LIVEKIT_API_KEY = origKey
            process.env.LIVEKIT_API_SECRET = origSecret
            process.env.LIVEKIT_URL = origUrl
            cacheService.clear()
            await new Promise(r => server.close(r))
        }
    })

    it('WP-151: GET /status with 30 accepted riders (29 READY + 1 UNAVAILABLE_DEVICE) returns 30-rider roster and canStart=true', async () => {
        cacheService.clear()
        const orig = {
            roomFindFirst: prisma.room.findFirst,
            roomFindUnique: prisma.room.findUnique,
            inviteUpdateMany: prisma.rideInvite.updateMany
        }
        const now = Date.now()
        const mockRoom = {
            id: '33333333-3333-4333-8333-333333333333',
            name: 'Pack30',
            lkRoomName: 'rv_33333333-3333-4333-8333-333333333333',
            ownerId: 'host_u1',
            status: 'LOBBY',
            offlineMeshEnabled: true,
            joinCode: 'PCK30123',
            offlineBackupCode: 'K7QX-M2PA-9TDE-4WZH'
        }
        const mockInvites = Array.from({ length: 30 }, (_, idx) => {
            const uid = `rider_${idx + 1}`
            const st = idx === 29 ? 'UNAVAILABLE_DEVICE' : 'READY'
            cacheService.set(`lobby_presence:${mockRoom.id}:${uid}`, now, 60)
            cacheService.set(`offline_config:${mockRoom.id}:${uid}`, { status: st, updatedAtMs: now }, 60)
            return {
                id: `inv_${idx + 1}`,
                roomId: mockRoom.id,
                inviteeId: uid,
                status: 'ACCEPTED',
                source: 'HOST_INVITE',
                createdAt: new Date(now),
                offlineConfigStatus: st,
                offlineConfigUpdatedAt: new Date(now),
                invitee: { id: uid, displayName: `Rider ${idx + 1}` }
            }
        })

        prisma.room.findFirst = async () => ({ ...mockRoom, invites: mockInvites })
        prisma.room.findUnique = async () => ({ ...mockRoom, invites: mockInvites })
        prisma.rideInvite.updateMany = async () => ({ count: 1 })

        const testApp = express()
        testApp.use(express.json())
        testApp.use((req, _res, next) => { req.user = { uid: 'host_u1', name: 'Host' }; next() })
        testApp.use('/api/lobby', lobbyRoutes)
        const server = http.createServer(testApp)
        await new Promise(r => server.listen(0, r))
        const port = server.address().port

        try {
            const res = await fetch(`http://127.0.0.1:${port}/api/lobby/Pack30/status`)
            assert.strictEqual(res.status, 200)
            const body = await res.json()
            assert.strictEqual(body.acceptedCount, 30)
            assert.strictEqual(body.invites.length, 30)
            assert.strictEqual(body.blockingRidersCount, 0)
            assert.strictEqual(body.canStart, true)
        } finally {
            prisma.room.findFirst = orig.roomFindFirst
            prisma.room.findUnique = orig.roomFindUnique
            prisma.rideInvite.updateMany = orig.inviteUpdateMany
            cacheService.clear()
            await new Promise(r => server.close(r))
        }
    })

    it('WP-151: POST /create with duplicate active ride name from another user returns 409 ROOM_NAME_TAKEN without degrading schema', async () => {
        healthRoute.setSchemaHealth(true, { structuralOk: true, missing: [] })
        const orig = {
            roomFindFirst: prisma.room.findFirst,
            roomFindUnique: prisma.room.findUnique,
            reqFindUnique: prisma.rideCreateRequest?.findUnique
        }

        prisma.room.findFirst = async () => ({
            id: 'existing-room-1',
            ownerId: 'other_host_uid',
            name: 'Sunday-Ride'
        })
        prisma.room.findUnique = async () => ({
            id: 'existing-room-1',
            ownerId: 'other_host_uid',
            name: 'Sunday-Ride'
        })
        if (prisma.rideCreateRequest) {
            prisma.rideCreateRequest.findUnique = async () => null
        }

        const testApp = express()
        testApp.use(express.json())
        testApp.use((req, _res, next) => { req.user = { uid: 'new_host_uid', name: 'New Host' }; next() })
        testApp.use('/api/lobby', lobbyRoutes)
        const server = http.createServer(testApp)
        await new Promise(r => server.listen(0, r))
        const port = server.address().port

        try {
            const res = await fetch(`http://127.0.0.1:${port}/api/lobby/create`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ convoyName: 'Sunday-Ride' })
            })
            assert.strictEqual(res.status, 409)
            const body = await res.json()
            assert.strictEqual(body.code, 'ROOM_NAME_TAKEN')
            assert.strictEqual(healthRoute.getSchemaHealth().schemaStructuralOk, true)
        } finally {
            prisma.room.findFirst = orig.roomFindFirst
            prisma.room.findUnique = orig.roomFindUnique
            if (prisma.rideCreateRequest) prisma.rideCreateRequest.findUnique = orig.reqFindUnique
            await new Promise(r => server.close(r))
        }
    })

    it('WP-151: P2002 on Room.name returns 409 ROOM_NAME_TAKEN (not 503) and keeps schemaStructuralOk=true', async () => {
        healthRoute.setSchemaHealth(true, { structuralOk: true, missing: [] })
        const orig = {
            roomFindFirst: prisma.room.findFirst,
            roomFindUnique: prisma.room.findUnique,
            roomCreate: prisma.room.create,
            reqFindUnique: prisma.rideCreateRequest?.findUnique,
            transaction: prisma.$transaction
        }

        prisma.room.findFirst = async () => null
        prisma.room.findUnique = async () => null
        if (prisma.rideCreateRequest) prisma.rideCreateRequest.findUnique = async () => null
        prisma.$transaction = async (fn) => fn(prisma)
        prisma.room.create = async () => {
            const err = new Error('Unique constraint failed on the fields: (`name`)')
            err.code = 'P2002'
            err.meta = { target: ['name'] }
            throw err
        }

        const testApp = express()
        testApp.use(express.json())
        testApp.use((req, _res, next) => { req.user = { uid: 'host_p2002', name: 'Host P2002' }; next() })
        testApp.use('/api/lobby', lobbyRoutes)
        const server = http.createServer(testApp)
        await new Promise(r => server.listen(0, r))
        const port = server.address().port

        try {
            const res = await fetch(`http://127.0.0.1:${port}/api/lobby/create`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ convoyName: 'RaceName', clientRequestId: 'req-p2002-1' })
            })
            assert.strictEqual(res.status, 409)
            const body = await res.json()
            assert.strictEqual(body.code, 'ROOM_NAME_TAKEN')
            assert.strictEqual(healthRoute.getSchemaHealth().schemaStructuralOk, true)
        } finally {
            prisma.room.findFirst = orig.roomFindFirst
            prisma.room.findUnique = orig.roomFindUnique
            prisma.room.create = orig.roomCreate
            prisma.$transaction = orig.transaction
            if (prisma.rideCreateRequest) prisma.rideCreateRequest.findUnique = orig.reqFindUnique
            await new Promise(r => server.close(r))
        }
    })

    it('WP-151: production degraded write-block returns 503 Retry-After:30 on /api/rooms while /api/emergency/alert returns 200', async () => {
        const { app } = require('../src/server')
        const origEnv = process.env.NODE_ENV
        const origUserUpsert = prisma.user.upsert
        process.env.NODE_ENV = 'production'
        cacheService.clear()
        cacheService.set('auth_token:prod_sos_token', { uid: 'sos_u1', email: 'sos@test.com', name: 'SOS Rider' }, 60)
        prisma.user.upsert = async () => ({ id: 'sos_u1', email: 'sos@test.com', displayName: 'SOS Rider' })
        healthRoute.setSchemaHealth(false, { structuralOk: false, missing: ['Room.offlineBackupCode'] })

        const server = http.createServer(app)
        await new Promise(r => server.listen(0, r))
        const port = server.address().port

        try {
            const blockedRes = await fetch(`http://127.0.0.1:${port}/api/rooms`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: 'BlockedRide' })
            })
            assert.strictEqual(blockedRes.status, 503)
            assert.strictEqual(blockedRes.headers.get('retry-after'), '30')
            const blockedBody = await blockedRes.json()
            assert.strictEqual(blockedBody.code, 'SCHEMA_DEGRADED')

            const sosRes = await fetch(`http://127.0.0.1:${port}/api/emergency/alert`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    Authorization: 'Bearer prod_sos_token'
                },
                body: JSON.stringify({})
            })
            assert.strictEqual(sosRes.status, 200)
            const sosBody = await sosRes.json()
            assert.strictEqual(sosBody.reason, 'NO_ACTIVE_RIDE')
        } finally {
            process.env.NODE_ENV = origEnv
            prisma.user.upsert = origUserUpsert
            cacheService.clear()
            await new Promise(r => server.close(r))
        }
    })
})
