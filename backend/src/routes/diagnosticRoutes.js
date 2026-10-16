const express = require('express')
const crypto = require('crypto')
const diagnosticService = require('../services/diagnosticService')
const authMiddleware = require('../middleware/authMiddleware')

const router = express.Router()

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

router.get('/runs/room/:roomName/latest', authMiddleware, async (req, res, next) => {
    try {
        const { roomName } = req.params
        const prisma = require('../db')
        const run = await prisma.diagnosticRun.findFirst({
            where: {
                room: { name: roomName }
            },
            orderBy: { createdAt: 'desc' },
            select: {
                id: true,
                status: true,
                testNumber: true,
                createdAt: true,
                closedAt: true,
                emailedAt: true,
                summary: true,
                participants: {
                    select: {
                        riderId: true,
                        handle: true,
                        joinedAt: true,
                        stoppedAt: true,
                        lastSeq: true
                    }
                }
            }
        })
        if (!run) {
            return res.status(404).json({ error: 'No diagnostic run found for convoy' })
        }
        res.json(run)
    } catch (err) {
        next(err)
    }
})

router.get('/runs/:runId/excel', authMiddleware, async (req, res, next) => {
    try {
        const { runId } = req.params
        const exportData = await diagnosticService.buildRunExportData(runId)

        if (!exportData) {
            return res.status(404).json({ error: 'Diagnostic run not found' })
        }

        const excelBuffer = await diagnosticService.buildExcelBuffer(exportData)
        const excelFileName = `Convoy_${exportData.convoyClean}_Diagnostics_Test${exportData.run.testNumber}.xlsx`

        res.setHeader('Content-Type', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')
        res.setHeader('Content-Disposition', `attachment; filename="${excelFileName}"`)
        res.setHeader('Content-Length', excelBuffer.length)
        return res.send(excelBuffer)
    } catch (err) {
        next(err)
    }
})

router.post('/runs/:runId/finalize', authMiddleware, async (req, res, next) => {
    try {
        const { runId } = req.params
        const closed = await diagnosticService.closeAndExportRun(runId)
        res.json({ success: true, runClosed: closed })
    } catch (err) {
        next(err)
    }
})

router.post('/runs/room/:roomName/finalize', authMiddleware, async (req, res, next) => {
    try {
        const { roomName } = req.params
        const prisma = require('../db')
        const activeRun = await prisma.diagnosticRun.findFirst({
            where: {
                room: { name: roomName },
                status: 'ACTIVE'
            },
            orderBy: { testNumber: 'desc' }
        })
        if (!activeRun) {
            return res.json({ success: true, message: 'No active diagnostic run found for room' })
        }
        const closed = await diagnosticService.closeAndExportRun(activeRun.id)
        res.json({ success: true, runClosed: closed, runId: activeRun.id })
    } catch (err) {
        next(err)
    }
})

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
