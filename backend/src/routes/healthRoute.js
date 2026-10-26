const express = require('express')
const router = express.Router()
const cacheService = require('../services/cacheService')
const eventPipeline = require('../services/eventPipeline')
const { getWebhookStatus } = require('./livekitWebhook')

let schemaDegraded = false
let schemaStatus = { valid: true }

function setSchemaHealth(valid, details = {}) {
    schemaDegraded = !valid
    schemaStatus = { valid, ...details }
}

function getSchemaHealth() {
    return { schemaDegraded, ...schemaStatus }
}

router.get(['/', '/health', '/api/health'], (req, res) => {
    res.json({
        status: schemaDegraded ? 'degraded' : 'ok',
        uptime: Math.floor(process.uptime()),
        timestamp: new Date().toISOString(),
        cache: cacheService.stats(),
        pipeline: eventPipeline.getStats(),
        schemaDegraded,
        schemaStatus,
        memoryRssMb: (process.memoryUsage().rss / (1024 * 1024)).toFixed(1)
    })
})

router.get(['/ready', '/api/health/ready'], (req, res) => {
    const webhookEnabled = process.env.LIVEKIT_WEBHOOK_ENABLED === 'true'
    const isProduction = process.env.NODE_ENV === 'production'
    const allowWebhookDisabled = process.env.ALLOW_WEBHOOK_DISABLED === 'true'

    if (schemaDegraded) {
        return res.status(503).json({
            status: 'degraded',
            error: schemaStatus.error || 'Database schema is degraded',
            schemaReady: false,
            schemaStatus,
            timestamp: new Date().toISOString()
        })
    }

    if (isProduction && !webhookEnabled && !allowWebhookDisabled) {
        return res.status(503).json({
            status: 'not_ready',
            error: 'LIVEKIT_WEBHOOK_ENABLED is required in production (or set ALLOW_WEBHOOK_DISABLED=true override)',
            webhookEnabled: false
        })
    }

    const whStatus = getWebhookStatus()

    res.json({
        status: 'ok',
        webhookEnabled,
        lastSuccessfulWebhook: whStatus.lastSuccessfulEvent,
        lastWebhookError: whStatus.lastProcessingError,
        schemaReady: true,
        schemaDegraded: false,
        timestamp: new Date().toISOString()
    })
})

module.exports = router
module.exports.setSchemaHealth = setSchemaHealth
module.exports.getSchemaHealth = getSchemaHealth

