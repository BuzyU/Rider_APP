'use strict'

const { describe, it, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const express = require('express')
const { ipLimiter, userLimiter, _reset } = require('../src/middleware/rateLimiter')
const { isLegacyBlocked } = require('../src/middleware/authMiddleware')
const { riderCallsign, disambiguateCallsigns } = require('../src/services/identity')

describe('P0: Rate Limiter, Legacy Client Gate, and Callsign Identity', () => {

    beforeEach(() => {
        _reset()
        delete process.env.RATE_LIMIT_IP_PER_MIN
        delete process.env.RATE_LIMIT_USER_PER_MIN
        delete process.env.MIN_APP_VERSION
        delete process.env.LEGACY_CLIENTS_ALLOWED
    })

    describe('C6: Rate Limiting', () => {
        it('enforces IP rate limit when limit is exceeded and sets Retry-After', async () => {
            process.env.RATE_LIMIT_IP_PER_MIN = '5'
            const app = express()
            app.use(ipLimiter)
            app.get('/test', (req, res) => res.json({ ok: true }))

            const server = app.listen(0)
            await new Promise(r => server.once('listening', r))
            const port = server.address().port
            const url = `http://127.0.0.1:${port}/test`

            try {
                for (let i = 0; i < 5; i++) {
                    const res = await fetch(url)
                    assert.strictEqual(res.status, 200)
                }
                // 6th request must be blocked with 429
                const blockedRes = await fetch(url)
                assert.strictEqual(blockedRes.status, 429)
                assert.strictEqual(blockedRes.headers.get('retry-after'), '60')
                const body = await blockedRes.json()
                assert.strictEqual(body.code, 'IP_RATE_LIMIT')
            } finally {
                await new Promise(r => server.close(r))
            }
        })

        it('bypasses IP rate limit for /health, /api/health, and /api/app/config', async () => {
            process.env.RATE_LIMIT_IP_PER_MIN = '1'
            const app = express()
            app.use(ipLimiter)
            app.get('/health', (req, res) => res.json({ ok: true }))
            app.get('/api/health', (req, res) => res.json({ ok: true }))
            app.get('/api/app/config', (req, res) => res.json({ ok: true }))

            const server = app.listen(0)
            await new Promise(r => server.once('listening', r))
            const port = server.address().port

            try {
                for (let i = 0; i < 5; i++) {
                    const r1 = await fetch(`http://127.0.0.1:${port}/health`)
                    assert.strictEqual(r1.status, 200)
                    const r2 = await fetch(`http://127.0.0.1:${port}/api/health`)
                    assert.strictEqual(r2.status, 200)
                    const r3 = await fetch(`http://127.0.0.1:${port}/api/app/config`)
                    assert.strictEqual(r3.status, 200)
                }
            } finally {
                await new Promise(r => server.close(r))
            }
        })

        it('enforces per-user rate limit after auth', async () => {
            process.env.RATE_LIMIT_USER_PER_MIN = '3'
            const app = express()
            let mockUid = 'user_1'
            app.use((req, res, next) => {
                req.user = { uid: mockUid }
                next()
            })
            app.use(userLimiter)
            app.get('/protected', (req, res) => res.json({ ok: true }))

            const server = app.listen(0)
            await new Promise(r => server.once('listening', r))
            const port = server.address().port
            const url = `http://127.0.0.1:${port}/protected`

            try {
                for (let i = 0; i < 3; i++) {
                    const res = await fetch(url)
                    assert.strictEqual(res.status, 200)
                }
                const blocked = await fetch(url)
                assert.strictEqual(blocked.status, 429)
                assert.strictEqual(blocked.headers.get('retry-after'), '60')
                const body = await blocked.json()
                assert.strictEqual(body.code, 'USER_RATE_LIMIT')

                // Different user on same IP is NOT blocked
                mockUid = 'user_2'
                const user2Res = await fetch(url)
                assert.strictEqual(user2Res.status, 200)
            } finally {
                await new Promise(r => server.close(r))
            }
        })
    })

    describe('C7: Legacy Client Gate', () => {
        it('blocks outdated versions using 4 numeric segments and handles prefixes', () => {
            process.env.LEGACY_CLIENTS_ALLOWED = 'false'
            process.env.MIN_APP_VERSION = '0.0.3.15'

            // Below minimum
            assert.strictEqual(isLegacyBlocked('0.0.3.14'), true)
            assert.strictEqual(isLegacyBlocked('v0.0.3.14'), true)
            assert.strictEqual(isLegacyBlocked('V0.0.3.14'), true)
            assert.strictEqual(isLegacyBlocked('0.0.2.99'), true)
            assert.strictEqual(isLegacyBlocked('0.0.3'), true)

            // Equal or above minimum
            assert.strictEqual(isLegacyBlocked('0.0.3.15'), false)
            assert.strictEqual(isLegacyBlocked('v0.0.3.15'), false)
            assert.strictEqual(isLegacyBlocked('0.0.3.16'), false)
            assert.strictEqual(isLegacyBlocked('0.0.4.0'), false)
            assert.strictEqual(isLegacyBlocked('1.0.0.0'), false)
        })

        it('does not block when LEGACY_CLIENTS_ALLOWED is not false', () => {
            process.env.LEGACY_CLIENTS_ALLOWED = 'true'
            process.env.MIN_APP_VERSION = '0.0.3.15'
            assert.strictEqual(isLegacyBlocked('0.0.3.14'), false)
        })
    })

    describe('J3: Callsign Identity and Disambiguation', () => {
        it('computes callsign using displayName -> handle -> Rider <last4>', () => {
            assert.strictEqual(riderCallsign({ displayName: 'Maaz Khan', handle: 'maaz', id: 'usr_12345678' }), 'Maaz Khan')
            assert.strictEqual(riderCallsign({ displayName: '', handle: 'speedy', id: 'usr_12345678' }), '@speedy')
            assert.strictEqual(riderCallsign({ displayName: 'Rider', handle: '', id: 'usr_98765432' }), 'Rider 5432')
            assert.strictEqual(riderCallsign({ displayName: 'Guest Rider', handle: '', id: 'usr_87654321' }), 'Rider 4321')
            assert.strictEqual(riderCallsign(null), 'Rider')
        })

        it('disambiguates duplicate callsigns in the same ride', () => {
            const riders = [
                { id: '1', displayName: 'Ravi' },
                { id: '2', displayName: 'Ravi' },
                { id: '3', displayName: 'Amit' },
                { id: '4', displayName: 'Ravi' }
            ]
            const disambiguated = disambiguateCallsigns(riders)
            assert.strictEqual(disambiguated[0].callsign, 'Ravi')
            assert.strictEqual(disambiguated[1].callsign, 'Ravi (2)')
            assert.strictEqual(disambiguated[2].callsign, 'Amit')
            assert.strictEqual(disambiguated[3].callsign, 'Ravi (3)')
        })
    })
})
