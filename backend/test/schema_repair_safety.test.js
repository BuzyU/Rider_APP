'use strict'

const { describe, it } = require('node:test')
const assert = require('node:assert/strict')
const { ensureDbSchema, ADVISORY_LOCK_ID } = require('../src/services/schemaMigration')

describe('Finding V3-3: Schema Repair Lock Safety & Transactional Integrity', () => {
    it('lock unavailable -> refuses to run DDL and does not leak session locks', async () => {
        const executedQueries = []
        const mockPrisma = {
            $queryRaw: async () => [],
            $executeRawUnsafe: async (sql) => {
                executedQueries.push(sql)
                return 1
            },
            $queryRawUnsafe: async (sql) => {
                // Advisory lock attempt returns false (locked by another instance)
                if (sql.includes('pg_try_advisory_xact_lock')) {
                    return [{ locked: false }]
                }
                return []
            },
            $transaction: async (fn) => fn(mockPrisma)
        }

        const result = await ensureDbSchema(mockPrisma)
        assert.strictEqual(result.repaired, false, 'Should not attempt repair when lock is held elsewhere')
        assert.strictEqual(result.skippedDueToLock, true)
        // No migration DDL should be executed
        const ddlExecuted = executedQueries.some(q => q.includes('ALTER TABLE') || q.includes('CREATE TABLE'))
        assert.strictEqual(ddlExecuted, false, 'No DDL statements should be executed without the lock')
    })

    it('lock query throws error -> refuses to run DDL and stops', async () => {
        const executedQueries = []
        const mockPrisma = {
            $queryRaw: async () => [],
            $executeRawUnsafe: async (sql) => {
                executedQueries.push(sql)
                return 1
            },
            $queryRawUnsafe: async (sql) => {
                if (sql.includes('pg_try_advisory_xact_lock')) {
                    throw new Error('Connection lost')
                }
                return []
            },
            $transaction: async (fn) => fn(mockPrisma)
        }

        const result = await ensureDbSchema(mockPrisma)
        assert.strictEqual(result.repaired, false)
        assert.strictEqual(result.structuralOk, false)
        assert.strictEqual(executedQueries.length, 0, 'Must not run any DDL if lock query throws')
    })

    it('savepoint failure -> reported, run stops immediately without swallowing', async () => {
        let savepointAttempted = false
        const executedQueries = []
        const mockPrisma = {
            $queryRaw: async () => [],
            $queryRawUnsafe: async (sql) => {
                if (sql.includes('pg_try_advisory_xact_lock')) {
                    return [{ locked: true }]
                }
                return []
            },
            $executeRawUnsafe: async (sql) => {
                executedQueries.push(sql)
                if (sql.startsWith('SAVEPOINT stmt_0')) {
                    savepointAttempted = true
                    throw new Error('Transaction aborted: savepoint creation failed')
                }
                return 1
            },
            $transaction: async (fn) => fn(mockPrisma)
        }

        const result = await ensureDbSchema(mockPrisma)
        assert.strictEqual(savepointAttempted, true, 'Savepoint was attempted')
        assert.strictEqual(result.valid, false, 'Result must be invalid after savepoint failure')
        assert.ok(result.failedStatements.length > 0, 'Failed statement must be recorded')
        assert.ok(result.failedStatements[0].error.includes('Savepoint failed'))
        // Further statements should not have run
        const stmtCount = executedQueries.filter(q => q.startsWith('SAVEPOINT')).length
        assert.strictEqual(stmtCount, 1, 'Run must stop on first savepoint failure, not continue to next statements')
    })

    it('two concurrent runs -> exactly one acquires lock and executes DDL', async () => {
        let lockHeld = false
        let winnerExecutions = 0
        let loserExecutions = 0

        const createMockPrisma = (instanceId) => ({
            $queryRaw: async () => [],
            $queryRawUnsafe: async (sql) => {
                if (sql.includes('pg_try_advisory_xact_lock')) {
                    if (!lockHeld) {
                        lockHeld = true
                        return [{ locked: true }]
                    }
                    return [{ locked: false }]
                }
                return []
            },
            $executeRawUnsafe: async (sql) => {
                if (instanceId === 'winner') winnerExecutions++
                else loserExecutions++
                return 1
            },
            $transaction: async (fn) => fn(createMockPrisma(instanceId))
        })

        const [res1, res2] = await Promise.all([
            ensureDbSchema(createMockPrisma('winner')),
            ensureDbSchema(createMockPrisma('loser'))
        ])

        const oneRepaired = (res1.repaired && !res2.repaired) || (!res1.repaired && res2.repaired)
        assert.strictEqual(oneRepaired, true, 'Exactly one concurrent runner must perform repair')
        assert.ok(winnerExecutions > 0, 'Winner executed DDL')
        assert.strictEqual(loserExecutions, 0, 'Loser did not execute any DDL')
    })
})
