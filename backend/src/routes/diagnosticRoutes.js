const express = require('express')
const crypto = require('crypto')
const diagnosticService = require('../services/diagnosticService')
const authMiddleware = require('../middleware/authMiddleware')

const router = express.Router()

/**
 * Timing-safe admin key verification middleware.
 * Disabled completely if DIAG_ADMIN_KEY is unset or empty.
 */
function requireAdminKey(req, res, next) {
    const configuredKey = process.env.DIAG_ADMIN_KEY
    if (!configuredKey || configuredKey.trim() === '') {
        return res.status(503).json({
            error: 'Admin export is disabled: DIAG_ADMIN_KEY is not configured on the server'
        })
    }

    const providedKey = req.headers['x-diag-admin-key'] || req.query.adminKey
    if (!providedKey || typeof providedKey !== 'string') {
        return res.status(401).json({ error: 'Unauthorized: missing admin key' })
    }

    const hashProvided = crypto.createHash('sha256').update(providedKey).digest()
    const hashConfigured = crypto.createHash('sha256').update(configuredKey).digest()

    if (!crypto.timingSafeEqual(hashProvided, hashConfigured)) {
        return res.status(401).json({ error: 'Unauthorized: invalid admin key' })
    }

    next()
}

/**
 * Dedicated body parser with 5MB limit for batch telemetry.
 * Returns HTTP 413 with explicit error when exceeded.
 */
function batchBodyParser(req, res, next) {
    express.json({ limit: '5mb' })(req, res, (err) => {
        if (err) {
            if (err.type === 'entity.too.large' || err.status === 413) {
                return res.status(413).json({
                    error: 'Payload Too Large: Diagnostic telemetry batch exceeds the 5MB size limit'
                })
            }
            return res.status(400).json({ error: `Malformed JSON payload: ${err.message}` })
        }
        next()
    })
}

// Rider joins or starts diagnostic run for a convoy
router.post('/join', express.json(), authMiddleware, async (req, res, next) => {
    try {
        const { convoyId } = req.body
        const result = await diagnosticService.joinDiagnosticRun(convoyId, req.user)
        res.json(result)
    } catch (err) {
        if (err.status) {
            return res.status(err.status).json({ error: err.message })
        }
        console.error('[Diagnostic Join Error]:', err)
        next(err)
    }
})

// Ingest a batch of diagnostic telemetry (idempotent via runId + riderId + sequenceNumber)
router.post('/batches', batchBodyParser, authMiddleware, async (req, res, next) => {
    try {
        const { runId, sequenceNumber, payload } = req.body
        const result = await diagnosticService.postDiagnosticBatch(runId, sequenceNumber, payload, req.user)
        res.json(result)
    } catch (err) {
        if (err.status) {
            return res.status(err.status).json({ error: err.message })
        }
        next(err)
    }
})

// Rider stops recording (requires lastSeq)
router.post('/stop', express.json(), authMiddleware, async (req, res, next) => {
    try {
        const { runId, lastSeq } = req.body
        const result = await diagnosticService.stopDiagnosticRun(runId, lastSeq, req.user)
        res.json(result)
    } catch (err) {
        if (err.status) {
            return res.status(err.status).json({ error: err.message })
        }
        next(err)
    }
})

// Export on-demand ZIP archive built from DB in memory (admin key protected)
router.get('/runs/:runId/export', requireAdminKey, async (req, res, next) => {
    try {
        const { runId } = req.params
        const exportData = await diagnosticService.buildRunExportData(runId)

        if (!exportData) {
            return res.status(404).json({ error: 'Diagnostic run not found' })
        }

        const zipBuffer = await diagnosticService.buildZipBuffer(exportData.files)
        const zipFileName = `diagnostic_${exportData.convoyClean}_test${exportData.run.testNumber}_${Date.now()}.zip`

        res.setHeader('Content-Type', 'application/zip')
        res.setHeader('Content-Disposition', `attachment; filename="${zipFileName}"`)
        res.setHeader('Content-Length', zipBuffer.length)
        return res.send(zipBuffer)
    } catch (err) {
        next(err)
    }
})

// Manually trigger run closure and export (admin key protected)
router.post('/runs/:runId/close', requireAdminKey, async (req, res, next) => {
    try {
        const { runId } = req.params
        const closed = await diagnosticService.closeAndExportRun(runId)
        res.json({ success: true, runClosed: closed })
    } catch (err) {
        next(err)
    }
})

module.exports = router
