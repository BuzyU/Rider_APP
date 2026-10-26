const { describe, it } = require('node:test')
const assert = require('node:assert/strict')
const { ensureDbSchema, assertSchema, checkUniqueViolations, ADVISORY_LOCK_ID } = require('../src/services/schemaMigration')

describe('P0-C: Migration and Startup Safety', () => {
    it('assertSchema validates tables, columns, enums, indexes, and FKs', async () => {
        const fakePrisma = {
            $queryRaw: async (strings, ...values) => {
                const query = strings.join('?')
                if (query.includes('information_schema.columns')) {
                    return [
                        { table_name: 'Room', column_name: 'id' },
                        { table_name: 'Room', column_name: 'name' },
                        { table_name: 'Room', column_name: 'lkRoomName' },
                        { table_name: 'Room', column_name: 'status' },
                        { table_name: 'Room', column_name: 'ownerId' },
                        { table_name: 'Room', column_name: 'lastEmptyAt' },
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
                        { table_name: 'RoomJoinToken', column_name: 'id' },
                        { table_name: 'RoomJoinToken', column_name: 'roomId' },
                        { table_name: 'RoomJoinToken', column_name: 'token' },
                        { table_name: 'RoomJoinToken', column_name: 'maxUses' },
                        { table_name: 'RoomJoinToken', column_name: 'useCount' },
                        { table_name: 'EmergencyAlert', column_name: 'id' },
                        { table_name: 'EmergencyAlert', column_name: 'roomId' },
                        { table_name: 'EmergencyAlert', column_name: 'senderId' },
                        { table_name: 'EmergencyAlert', column_name: 'status' },
                        { table_name: 'DiagnosticRun', column_name: 'id' },
                        { table_name: 'DiagnosticRun', column_name: 'convoyId' },
                        { table_name: 'DiagnosticRun', column_name: 'testNumber' },
                        { table_name: 'DiagnosticRun', column_name: 'status' },
                        { table_name: 'DiagnosticRun', column_name: 'closeAt' }
                    ]
                }
                if (query.includes('pg_enum')) {
                    return [{ enumlabel: 'REMOVED' }, { enumlabel: 'LEFT' }]
                }
                if (query.includes('pg_indexes')) {
                    return [
                        { indexname: 'Room_name_active_unique' },
                        { indexname: 'Room_joinCode_active_unique' },
                        { indexname: 'RideInvite_roomId_inviteeId_key' }
                    ]
                }
                if (query.includes('pg_constraint')) {
                    return [
                        { conname: 'RoomJoinToken_roomId_fkey' },
                        { conname: 'DiagnosticRun_convoyId_fkey' },
                        { conname: 'DiagnosticParticipant_runId_fkey' },
                        { conname: 'DiagnosticBatch_runId_fkey' },
                        { conname: 'RideCreateRequest_ownerId_fkey' },
                        { conname: 'RideCreateRequest_roomId_fkey' },
                        { conname: 'Room_ownerId_fkey' }
                    ]
                }
                return []
            }
        }

        const res = await assertSchema(fakePrisma)
        assert.strictEqual(res.valid, true)
        assert.strictEqual(res.missing.length, 0)
    })

    it('ensureDbSchema is idempotent when schema is already valid', async () => {
        const fakePrisma = {
            $queryRaw: async (strings) => {
                const query = strings.join('?')
                if (query.includes('information_schema.columns')) {
                    return [
                        { table_name: 'Room', column_name: 'id' },
                        { table_name: 'Room', column_name: 'name' },
                        { table_name: 'Room', column_name: 'lkRoomName' },
                        { table_name: 'Room', column_name: 'status' },
                        { table_name: 'Room', column_name: 'ownerId' },
                        { table_name: 'Room', column_name: 'lastEmptyAt' },
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
                        { table_name: 'RoomJoinToken', column_name: 'id' },
                        { table_name: 'RoomJoinToken', column_name: 'roomId' },
                        { table_name: 'RoomJoinToken', column_name: 'token' },
                        { table_name: 'RoomJoinToken', column_name: 'maxUses' },
                        { table_name: 'RoomJoinToken', column_name: 'useCount' },
                        { table_name: 'EmergencyAlert', column_name: 'id' },
                        { table_name: 'EmergencyAlert', column_name: 'roomId' },
                        { table_name: 'EmergencyAlert', column_name: 'senderId' },
                        { table_name: 'EmergencyAlert', column_name: 'status' },
                        { table_name: 'DiagnosticRun', column_name: 'id' },
                        { table_name: 'DiagnosticRun', column_name: 'convoyId' },
                        { table_name: 'DiagnosticRun', column_name: 'testNumber' },
                        { table_name: 'DiagnosticRun', column_name: 'status' },
                        { table_name: 'DiagnosticRun', column_name: 'closeAt' }
                    ]
                }
                if (query.includes('pg_enum')) return [{ enumlabel: 'REMOVED' }, { enumlabel: 'LEFT' }]
                if (query.includes('pg_indexes')) return [{ indexname: 'Room_name_active_unique' }, { indexname: 'Room_joinCode_active_unique' }, { indexname: 'RideInvite_roomId_inviteeId_key' }]
                if (query.includes('pg_constraint')) return [
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

        const run1 = await ensureDbSchema(fakePrisma)
        assert.strictEqual(run1.valid, true)
        assert.strictEqual(run1.repaired, false)

        const run2 = await ensureDbSchema(fakePrisma)
        assert.strictEqual(run2.valid, true)
        assert.strictEqual(run2.repaired, false)
    })

    it('concurrent calls with advisory lock: second call skips repair', async () => {
        let firstCheck = true
        const fakePrisma = {
            $queryRaw: async (strings) => {
                const query = strings.join('?')
                if (query.includes('information_schema.columns')) {
                    if (firstCheck) return [] // Missing tables
                    return []
                }
                return []
            },
            $queryRawUnsafe: async (sql) => {
                if (sql.includes('pg_try_advisory_lock') || sql.includes('pg_try_advisory_xact_lock')) {
                    return [{ locked: false }] // Lock already held by another replica
                }
                return []
            }
        }

        const res = await ensureDbSchema(fakePrisma)
        assert.strictEqual(res.valid, false)
        assert.strictEqual(res.repaired, false)
        assert.strictEqual(res.skippedDueToLock, true)
    })

    it('pre-check detects duplicate active room names, does not delete rows, and reports manual reconciliation', async () => {
        const rows = [
            { id: '1', name: 'sunday ride', status: 'LOBBY' },
            { id: '2', name: 'sunday ride', status: 'ACTIVE' }
        ]

        const fakePrisma = {
            $queryRaw: async (strings) => {
                const query = strings.join('?')
                if (query.includes('lower("name")')) {
                    return [{ name: 'sunday ride', count: 2 }]
                }
                return []
            }
        }

        const violations = await checkUniqueViolations(fakePrisma)
        assert.strictEqual(violations.length, 1)
        assert.match(violations[0], /Duplicate active Room names found/i)
        assert.match(violations[0], /Manual reconciliation required/i)
        assert.strictEqual(rows.length, 2, 'Never delete rows automatically')
    })

    it('simulated failing statement is reported with index and marked valid:false', async () => {
        let calls = 0
        const fakePrisma = {
            $queryRaw: async () => [], // Always returns missing elements
            $queryRawUnsafe: async (sql) => {
                if (sql.includes('pg_try_advisory_lock') || sql.includes('pg_try_advisory_xact_lock')) return [{ locked: true }]
                if (sql.includes('pg_advisory_unlock')) return []
                return []
            },
            $executeRawUnsafe: async (sql) => {
                calls++
                if (calls === 3) {
                    throw new Error('Simulated syntax or permission error in DDL')
                }
                return 1
            }
        }

        const res = await ensureDbSchema(fakePrisma)
        assert.strictEqual(res.valid, false)
        assert.strictEqual(res.repaired, true)
        assert.ok(res.failed > 0)
        assert.ok(res.failedStatements.length > 0)
        assert.strictEqual(res.failedStatements[0].index, 2)
        assert.match(res.failedStatements[0].error, /Simulated syntax or permission error/i)
    })
})
