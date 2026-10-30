'use strict'

const { describe, it, beforeEach, afterEach } = require('node:test')
const assert = require('node:assert/strict')
const emailService = require('../src/services/emailService')

describe('Finding V3-5: Email Service Resilience & Circuit Breaker', () => {
    const originalEnv = { ...process.env }

    beforeEach(() => {
        emailService.recordSuccess()
    })

    afterEach(() => {
        process.env = { ...originalEnv }
        emailService.recordSuccess()
    })

    it('bypasses sending when EMAIL_ENABLED=false', async () => {
        process.env.EMAIL_ENABLED = 'false'
        const res = await emailService.sendMailWithRetry({
            to: 'rider@example.com',
            subject: 'Test',
            text: 'Hello'
        })
        assert.strictEqual(res.messageId, 'disabled')
    })

    it('trips circuit breaker after 3 consecutive failures and fast-fails', async () => {
        assert.strictEqual(emailService.isCircuitOpen(), false)

        emailService.recordFailure(new Error('Connection timeout 1'))
        emailService.recordFailure(new Error('Connection timeout 2'))
        assert.strictEqual(emailService.isCircuitOpen(), false)

        emailService.recordFailure(new Error('Connection timeout 3'))
        assert.strictEqual(emailService.isCircuitOpen(), true, 'Circuit breaker must be open after 3 consecutive failures')

        const res = await emailService.sendMailWithRetry({
            to: 'rider@example.com',
            subject: 'Test',
            text: 'Hello'
        })
        assert.strictEqual(res.messageId, 'circuit-open', 'Subsequent email must fast-fail with circuit-open without attempting SMTP')
    })

    it('successful send resets consecutive failures and closes circuit', async () => {
        emailService.recordFailure(new Error('Temporary glitch 1'))
        emailService.recordFailure(new Error('Temporary glitch 2'))
        assert.strictEqual(emailService.consecutiveFailures, 2)

        emailService.recordSuccess()
        assert.strictEqual(emailService.consecutiveFailures, 0)
        assert.strictEqual(emailService.isCircuitOpen(), false)
    })
})
