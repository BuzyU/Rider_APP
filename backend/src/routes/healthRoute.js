const express = require('express')
const router = express.Router()
const cacheService = require('../services/cacheService')
const eventPipeline = require('../services/eventPipeline')
const { getWebhookStatus } = require('./livekitWebhook')

let schemaStructuralOk = true
let dataWarnings = []
let schemaStatus = { valid: true, structuralOk: true }

let _prismaRef = null
let _assertSchemaFn = null
let _ensureDbSchemaFn = null
let revalidationTimer = null
let revalidationAttempts = 0
const REVALIDATION_BASE_MS = 15000
const REVALIDATION_MAX_MS = 300000
let _revalidationBaseMs = REVALIDATION_BASE_MS
let _revalidationMaxMs = REVALIDATION_MAX_MS

function resetRevalidation() {
    if (revalidationTimer) {
        clearTimeout(revalidationTimer)
        revalidationTimer = null
    }
    revalidationAttempts = 0
}

function scheduleRevalidation(customDelayMs) {
    if (!_prismaRef || !_assertSchemaFn) return false
    if (revalidationTimer) return true
    const exp = Math.min(revalidationAttempts, 10)
    const delayMs = typeof customDelayMs === 'number'
        ? customDelayMs
        : Math.min(_revalidationMaxMs, _revalidationBaseMs * Math.pow(2, exp))
    console.warn(`[SchemaHealth] Scheduling schema re-validation in ${delayMs}ms (attempt ${revalidationAttempts + 1})`)
    revalidationTimer = setTimeout(async () => {
        revalidationTimer = null
        revalidationAttempts++
        try {
            if (process.env.AUTO_SCHEMA_REPAIR !== 'false' && typeof _ensureDbSchemaFn === 'function') {
                await _ensureDbSchemaFn(_prismaRef).catch(repairErr => {
                    console.warn('[SchemaHealth] Additive schema repair attempt failed:', repairErr.message)
                })
            }
            const result = await _assertSchemaFn(_prismaRef)
            const missing = Array.isArray(result?.missing) ? result.missing : []
            const warnings = Array.isArray(result?.warnings) ? result.warnings : dataWarnings
            if (result && result.valid) {
                console.log('[SchemaHealth] Re-validation PASSED — clearing degraded flag')
                revalidationAttempts = 0
                setSchemaHealth(true, {
                    structuralOk: true,
                    missing: [],
                    warnings,
                    message: 'Schema healed after re-validation'
                })
            } else {
                const missingStr = missing.length > 0 ? missing.join(', ') : 'unknown'
                console.warn(`[SchemaHealth] Re-validation FAILED (attempt ${revalidationAttempts}) — still missing: ${missingStr}`)
                setSchemaHealth(false, {
                    structuralOk: false,
                    missing,
                    warnings,
                    error: `Database schema missing: ${missingStr}`,
                    message: 'Schema re-validation failed'
                })
            }
        } catch (err) {
            console.error('[SchemaHealth] Re-validation error:', err.message)
            scheduleRevalidation()
        }
    }, delayMs)
    if (typeof revalidationTimer.unref === 'function') {
        revalidationTimer.unref()
    }
    return true
}

function initSchemaHealthRevalidation(prisma, assertSchemaFn, ensureDbSchemaFn = null, options = {}) {
    _prismaRef = prisma
    _assertSchemaFn = assertSchemaFn
    _ensureDbSchemaFn = ensureDbSchemaFn || (
        typeof prisma?.$queryRawUnsafe === 'function'
            ? require('../services/schemaMigration').ensureDbSchema
            : null
    )
    _revalidationBaseMs = typeof options?.baseMs === 'number' ? options.baseMs : REVALIDATION_BASE_MS
    _revalidationMaxMs = typeof options?.maxMs === 'number' ? options.maxMs : REVALIDATION_MAX_MS
    if (!schemaStructuralOk) {
        scheduleRevalidation()
    }
}

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

    if (!schemaStructuralOk && _prismaRef && _assertSchemaFn) {
        scheduleRevalidation()
    } else if (schemaStructuralOk) {
        resetRevalidation()
    }
}

function getSchemaHealth() {
    return {
        schemaStructuralOk,
        schemaDegraded: !schemaStructuralOk,
        dataWarnings,
        revalidationAttempts,
        revalidationScheduled: revalidationTimer !== null,
        ...schemaStatus
    }
}

router.get(['/', '/health', '/api/health'], (req, res) => {
    const hasWarnings = dataWarnings.length > 0
    const missing = Array.isArray(schemaStatus.missing) ? schemaStatus.missing : []
    res.json({
        status: !schemaStructuralOk ? 'degraded' : (hasWarnings ? 'warning' : 'ok'),
        uptime: Math.floor(process.uptime()),
        timestamp: new Date().toISOString(),
        cache: cacheService.stats(),
        pipeline: eventPipeline.getStats(),
        schemaStructuralOk,
        schemaDegraded: !schemaStructuralOk,
        missing,
        dataWarnings,
        revalidationAttempts,
        revalidationScheduled: revalidationTimer !== null,
        schemaStatus,
        memoryRssMb: (process.memoryUsage().rss / (1024 * 1024)).toFixed(1)
    })
})

router.get(['/ready', '/api/health/ready'], (req, res) => {
    const webhookEnabled = process.env.LIVEKIT_WEBHOOK_ENABLED === 'true'
    const isProduction = process.env.NODE_ENV === 'production'
    const allowWebhookDisabled = process.env.ALLOW_WEBHOOK_DISABLED === 'true'

    if (!schemaStructuralOk) {
        const missing = Array.isArray(schemaStatus.missing) ? schemaStatus.missing : []
        const errorMsg = missing.length > 0
            ? `Database schema structural validation failed (missing: ${missing.join(', ')})`
            : (schemaStatus.error || 'Database schema structural validation failed')
        return res.status(503).json({
            status: 'degraded',
            error: errorMsg,
            missing,
            schemaReady: false,
            schemaStructuralOk: false,
            revalidationAttempts,
            revalidationScheduled: revalidationTimer !== null,
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
module.exports.initSchemaHealthRevalidation = initSchemaHealthRevalidation
module.exports.scheduleRevalidation = scheduleRevalidation
module.exports.resetRevalidation = resetRevalidation


