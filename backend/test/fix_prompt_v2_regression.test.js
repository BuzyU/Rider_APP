const { describe, it, before, after } = require('node:test')
const assert = require('node:assert/strict')
const http = require('node:http')
const { app } = require('../src/server')
const healthRoute = require('../src/routes/healthRoute')
const {
    resolveRoom,
    buildBaseSelect,
    setAvailableColumns,
    getAvailableColumns
} = require('../src/services/roomResolver')
const { ensureDbSchema } = require('../src/services/schemaMigration')
const prisma = require('../src/db')

describe('Fix Prompt V2: Backend Regressions and Circuit Breaker Safety', () => {
    let server
    let baseUrl
    let originalEnv
    const originalSchemaHealth = healthRoute.getSchemaHealth()

    before(async () => {
        originalEnv = process.env.NODE_ENV
        process.env.NODE_ENV = 'production'
        process.env.ALLOW_WEBHOOK_DISABLED = 'true'

        await new Promise(resolve => {
            server = http.createServer(app)
            server.listen(0, () => {
                const port = server.address().port
                baseUrl = `http://127.0.0.1:${port}`
                resolve()
            })
        })
    })

    after(async () => {
        process.env.NODE_ENV = originalEnv
        delete process.env.ALLOW_WEBHOOK_DISABLED
        healthRoute.setSchemaHealth(originalSchemaHealth)
        if (server) {
            await new Promise(r => server.close(r))
        }
    })

    describe('Fix 2: Circuit Breaker & Emergency / Webhook Exemption', () => {
        it('structural failure blocks standard write endpoints with 503 and SCHEMA_DEGRADED', async () => {
            healthRoute.setSchemaHealth({
                schemaDegraded: true,
                schemaStructuralOk: false,
                dataWarnings: [],
                schemaStatus: { valid: false, missing: ['Room.lastEmptyAt'] }
            })

            const res = await fetch(`${baseUrl}/api/rooms`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: 'Test Room' })
            })

            assert.strictEqual(res.status, 503)
            assert.strictEqual(res.headers.get('retry-after'), '30')
            const body = await res.json()
            assert.strictEqual(body.code, 'SCHEMA_DEGRADED')
            assert.match(body.error, /structural validation failed or degraded/i)
        })

        it('structural failure does NEVER block POST /api/emergency (SOS)', async () => {
            healthRoute.setSchemaHealth({
                schemaDegraded: true,
                schemaStructuralOk: false,
                dataWarnings: [],
                schemaStatus: { valid: false, missing: ['Room.lastEmptyAt'] }
            })

            const res = await fetch(`${baseUrl}/api/emergency/alert`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ roomId: 'any' })
            })

            // Not 503 SCHEMA_DEGRADED - should pass through to auth or handler (e.g. 401 Unauthorized or 400)
            assert.notStrictEqual(res.status, 503, 'Emergency POST must never be blocked by schema degradation')
        })

        it('structural failure does NEVER block POST /api/livekit/webhook', async () => {
            healthRoute.setSchemaHealth({
                schemaDegraded: true,
                schemaStructuralOk: false,
                dataWarnings: [],
                schemaStatus: { valid: false, missing: ['Room.lastEmptyAt'] }
            })

            const res = await fetch(`${baseUrl}/api/livekit/webhook`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            })

            assert.notStrictEqual(res.status, 503, 'LiveKit webhook must never be blocked by schema degradation')
        })

        it('data warnings alone (e.g. duplicate active rooms) DO NOT block write traffic', async () => {
            healthRoute.setSchemaHealth({
                schemaDegraded: false,
                schemaStructuralOk: true,
                dataWarnings: ['Duplicate active Room names found: "sunday ride" (count: 2). Manual reconciliation required.'],
                schemaStatus: { valid: true, missing: [], violations: ['Duplicate active Room names found'] }
            })

            const res = await fetch(`${baseUrl}/api/rooms`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: 'Test Room' })
            })

            // Not 503 - passes through to authMiddleware (e.g. 401 Unauthorized)
            assert.notStrictEqual(res.status, 503, 'Data warnings alone must never trigger 503 circuit breaker')
        })

        it('/health reports warning status for data warnings without marking degraded', async () => {
            healthRoute.setSchemaHealth({
                schemaDegraded: false,
                schemaStructuralOk: true,
                dataWarnings: ['Duplicate active Room names found'],
                schemaStatus: { valid: true, missing: [], violations: ['Duplicate active Room names found'] }
            })

            const res = await fetch(`${baseUrl}/health`)
            assert.strictEqual(res.status, 200)
            const body = await res.json()
            assert.strictEqual(body.status, 'warning')
            assert.strictEqual(body.schemaDegraded, false)
            assert.strictEqual(body.schemaStructuralOk, true)
            assert.strictEqual(body.dataWarnings.length, 1)
        })

        it('/ready returns 200 when only data warnings exist, and 503 when structural failure', async () => {
            healthRoute.setSchemaHealth({
                schemaDegraded: false,
                schemaStructuralOk: true,
                dataWarnings: ['Duplicate active Room names found']
            })

            let res = await fetch(`${baseUrl}/ready`)
            assert.strictEqual(res.status, 200)

            healthRoute.setSchemaHealth({
                schemaDegraded: true,
                schemaStructuralOk: false,
                dataWarnings: []
            })

            res = await fetch(`${baseUrl}/ready`)
            assert.strictEqual(res.status, 503)
        })
    })

    describe('Fix 4: Missing lastEmptyAt Resilience in Room Resolver', () => {
        const originals = {}

        before(() => {
            originals.roomFindUnique = prisma.room.findUnique
            originals.roomFindFirst = prisma.room.findFirst
        })

        after(() => {
            prisma.room.findUnique = originals.roomFindUnique
            prisma.room.findFirst = originals.roomFindFirst
        })

        it('buildBaseSelect omits lastEmptyAt when column is not available', () => {
            setAvailableColumns({ hasLastEmptyAt: false, hasLkRoomName: true })
            const select = buildBaseSelect()
            assert.strictEqual(select.id, true)
            assert.strictEqual(select.lkRoomName, true)
            assert.strictEqual(select.lastEmptyAt, undefined)
        })

        it('buildBaseSelect includes lastEmptyAt when column is available', () => {
            setAvailableColumns({ hasLastEmptyAt: true, hasLkRoomName: true })
            const select = buildBaseSelect()
            assert.strictEqual(select.lastEmptyAt, true)
        })

        it('resolveRoom resolves room successfully even when lastEmptyAt column is missing', async () => {
            setAvailableColumns({ hasLastEmptyAt: false, hasLkRoomName: true })

            const testRoom = {
                id: '11111111-1111-1111-1111-111111111111',
                name: 'Test Room',
                status: 'ACTIVE',
                ownerId: 'user-1'
            }

            prisma.room.findUnique = async ({ where, select }) => {
                assert.strictEqual(select.lastEmptyAt, undefined, 'Must not query lastEmptyAt when unavailable')
                if (where.id === testRoom.id) return testRoom
                return null
            }

            const room = await resolveRoom(testRoom.id)
            assert.ok(room)
            assert.strictEqual(room.id, testRoom.id)
            assert.strictEqual(room.lastEmptyAt, undefined)
        })

        it('resolveRoom catches P2022 missing column error and falls back gracefully', async () => {
            setAvailableColumns({ hasLastEmptyAt: true, hasLkRoomName: true })

            const testRoom = {
                id: '22222222-2222-2222-2222-222222222222',
                name: 'Fallback Room',
                status: 'LOBBY',
                ownerId: 'user-2'
            }

            let attempt = 0
            prisma.room.findUnique = async ({ where, select }) => {
                attempt++
                if (attempt === 1 && select.lastEmptyAt) {
                    const p2022Error = new Error('The column `lastEmptyAt` does not exist in the current database.')
                    p2022Error.code = 'P2022'
                    throw p2022Error
                }
                if (where.id === testRoom.id) return testRoom
                return null
            }

            const room = await resolveRoom(testRoom.id)
            assert.ok(room)
            assert.strictEqual(room.id, testRoom.id)
            assert.strictEqual(getAvailableColumns().hasLastEmptyAt, false, 'Should have updated availableColumns')
        })
    })

    describe('Fix 3: Transaction-scoped Advisory Lock Concurrency', () => {
        it('concurrent runner holding lock causes second runner to skip without fatal error', async () => {
            const mockPrisma = {
                $queryRaw: async () => [],
                $transaction: async (fn) => {
                    const tx = {
                        $queryRawUnsafe: async (sql) => {
                            if (sql.includes('pg_try_advisory_xact_lock')) {
                                return [{ locked: false }] // Another replica holds lock
                            }
                            return []
                        }
                    }
                    return await fn(tx)
                }
            }

            const res = await ensureDbSchema(mockPrisma)
            assert.strictEqual(res.skippedDueToLock, true)
            assert.strictEqual(res.repaired, false)
        })
    })
})
