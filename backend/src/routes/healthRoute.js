const express = require('express')
const router = express.Router()
const cacheService = require('../services/cacheService')
const eventPipeline = require('../services/eventPipeline')
const { getWebhookStatus } = require('./livekitWebhook')

let schemaStructuralOk = true
let dataWarnings = []
let schemaStatus = { valid: true, structuralOk: true }

function setSchemaHealth(arg1, arg2 = {}) {
    if (typeof arg1 === 'boolean') {
        const details = arg2 || {}
        // Determine if this is a genuine structural failure (missing tables/columns/enums or DDL statement failures)
        const hasMissing = Array.isArray(details.missing) && details.missing.length > 0
        const hasFailedStatements = Array.isArray(details.failedStatements) && details.failedStatements.length > 0
        const structuralOk = details.structuralOk !== undefined 
            ? Boolean(details.structuralOk)
            : (!hasMissing && !hasFailedStatements && arg1)

        schemaStructuralOk = structuralOk
        dataWarnings = Array.isArray(details.violations) ? details.violations : (Array.isArray(details.warnings) ? details.warnings : [])
        schemaStatus = {
            valid: structuralOk,
            structuralOk,
            dataWarnings,
            ...details
        }
    } else if (typeof arg1 === 'object' && arg1 !== null) {
        schemaStructuralOk = Boolean(
            arg1.schemaStructuralOk !== undefined 
                ? arg1.schemaStructuralOk 
                : (arg1.structuralOk !== undefined ? arg1.structuralOk : (arg1.valid !== undefined ? arg1.valid : true))
        )
        dataWarnings = Array.isArray(arg1.dataWarnings) 
            ? arg1.dataWarnings 
            : (Array.isArray(arg1.warnings) ? arg1.warnings : (Array.isArray(arg1.violations) ? arg1.violations : []))
        schemaStatus = {
            valid: schemaStructuralOk,
            structuralOk: schemaStructuralOk,
            dataWarnings,
            ...(arg1.details || arg1)
        }
    }
}

function getSchemaHealth() {
    return {
        schemaStructuralOk,
        schemaDegraded: !schemaStructuralOk,
        dataWarnings,
        ...schemaStatus
    }
}

router.get(['/', '/health', '/api/health'], (req, res) => {
    const hasWarnings = dataWarnings.length > 0
    res.json({
        status: !schemaStructuralOk ? 'degraded' : (hasWarnings ? 'warning' : 'ok'),
        uptime: Math.floor(process.uptime()),
        timestamp: new Date().toISOString(),
        cache: cacheService.stats(),
        pipeline: eventPipeline.getStats(),
        schemaStructuralOk,
        schemaDegraded: !schemaStructuralOk,
        dataWarnings,
        schemaStatus,
        memoryRssMb: (process.memoryUsage().rss / (1024 * 1024)).toFixed(1)
    })
})

router.get(['/ready', '/api/health/ready'], (req, res) => {
    const webhookEnabled = process.env.LIVEKIT_WEBHOOK_ENABLED === 'true'
    const isProduction = process.env.NODE_ENV === 'production'
    const allowWebhookDisabled = process.env.ALLOW_WEBHOOK_DISABLED === 'true'

    if (!schemaStructuralOk) {
        return res.status(503).json({
            status: 'degraded',
            error: schemaStatus.error || 'Database schema structural validation failed',
            schemaReady: false,
            schemaStructuralOk: false,
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
        schemaStructuralOk: true,
        schemaDegraded: false,
        dataWarnings,
        timestamp: new Date().toISOString()
    })
})

module.exports = router
module.exports.setSchemaHealth = setSchemaHealth
module.exports.getSchemaHealth = getSchemaHealth


