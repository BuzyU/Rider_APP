/**
 * diagnosticRoutes.js – Phase 6 fix (H13)
 *
 * H13 – IDOR: diagnostic routes required only auth, not membership.
 * Fix: all run-level routes check membership before serving data.
 *   - /runs/:runId/excel – ACCEPTED member or owner only
 *   - /runs/:runId/finalize – owner only
 *   - /runs/:runId/export – admin key (unchanged, OK)
 *   - /runs/:runId/close – admin key (unchanged, OK)
 *   - /runs/room/:roomName/latest – ACCEPTED member or owner
 *   - /runs/room/:roomName/finalize – owner only
 *   - /join – already guarded by diagnosticService (member must be in active run)
 *   - /batches – guarded by diagnosticService (runId must match participant)
 *   - /stop – guarded by diagnosticService
 */

'use strict'

const express = require('express')
const crypto = require('crypto')
const prisma = require('../db')
const diagnosticService = require('../services/diagnosticService')
const authMiddleware = require('../middleware/authMiddleware')
const { resolveRoom, ResolverError } = require('../services/roomResolver')

const router = express.Router()

// ─── Admin key guard ──────────────────────────────────────────────────────────

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

// ─── Batch body parser ────────────────────────────────────────────────────────

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

// ─── H13 membership guard helpers ────────────────────────────────────────────

/**
 * Get the room for a diagnostic run. Returns null if not found.
 */
async function getRoomForRun(runId) {
    const run = await prisma.diagnosticRun.findUnique({
        where: { id: runId },
        select: {
            id: true,
            room: {
                select: { id: true, ownerId: true, status: true }
            }
        }
    })
    return run
}

/**
 * Check if a user is the owner of a run's room.
 */
async function isRoomOwner(runId, userId) {
    const run = await getRoomForRun(runId)
    return run?.room?.ownerId === userId
}

/**
 * Check if a user is an ACCEPTED member (or owner) of a run's room.
 */
async function isRoomMember(runId, userId) {
    const run = await getRoomForRun(runId)
    if (!run) return false
    const room = run.room
    if (room.ownerId === userId) return true
    const invite = await prisma.rideInvite.findUnique({
        where: { roomId_inviteeId: { roomId: room.id, inviteeId: userId } },
        select: { status: true }
    })
    return invite?.status === 'ACCEPTED'
}

// ─── Routes ───────────────────────────────────────────────────────────────────

// POST /api/diagnostics/join
// Member must be in the convoy for this run
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

// POST /api/diagnostics/batches
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

// POST /api/diagnostics/stop
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

// GET /api/diagnostics/runs/:runId/export – admin key only (H13: unchanged, admin-only)
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

// GET /api/diagnostics/runs/room/:roomRef/latest – H13: ACCEPTED member or owner
router.get('/runs/room/:roomRef/latest', authMiddleware, async (req, res, next) => {
    try {
        const { roomRef } = req.params
        const userId = req.user.uid

        const room = await resolveRoom(roomRef)
        const isOwner = room.ownerId === userId
        if (!isOwner) {
            const invite = await prisma.rideInvite.findUnique({
                where: { roomId_inviteeId: { roomId: room.id, inviteeId: userId } },
                select: { status: true }
            })
            if (!invite || invite.status !== 'ACCEPTED') {
                return res.status(403).json({ error: 'Only convoy members can view diagnostic runs' })
            }
        }

        const run = await prisma.diagnosticRun.findFirst({
            where: { convoyId: room.id },
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
        if (err instanceof ResolverError) {
            return res.status(err.status).json({ error: err.message, code: err.code })
        }
        next(err)
    }
})

// GET /api/diagnostics/runs/:runId/excel – H13: ACCEPTED member or owner
router.get('/runs/:runId/excel', authMiddleware, async (req, res, next) => {
    try {
        const { runId } = req.params
        const userId = req.user.uid

        // H13 – authorization check
        if (!await isRoomMember(runId, userId)) {
            return res.status(403).json({ error: 'Only convoy members can download diagnostic reports' })
        }

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

// POST /api/diagnostics/runs/:runId/finalize – H13: OWNER only
router.post('/runs/:runId/finalize', authMiddleware, async (req, res, next) => {
    try {
        const { runId } = req.params
        const userId = req.user.uid

        // H13 – owner-only
        if (!await isRoomOwner(runId, userId)) {
            return res.status(403).json({ error: 'Only the convoy owner can finalize diagnostic runs' })
        }

        const closed = await diagnosticService.closeAndExportRun(runId)
        res.json({ success: true, runClosed: closed })
    } catch (err) {
        next(err)
    }
})

// POST /api/diagnostics/runs/room/:roomRef/finalize – H13: OWNER only
router.post('/runs/room/:roomRef/finalize', authMiddleware, async (req, res, next) => {
    try {
        const { roomRef } = req.params
        const userId = req.user.uid

        const room = await resolveRoom(roomRef).catch(() => null)
        if (!room) {
            return res.status(404).json({ error: 'Room not found' })
        }

        // H13 – owner only
        if (room.ownerId !== userId) {
            return res.status(403).json({ error: 'Only the convoy owner can finalize diagnostic runs' })
        }

        const activeRun = await prisma.diagnosticRun.findFirst({
            where: { convoyId: room.id, status: 'ACTIVE' },
            orderBy: { testNumber: 'desc' }
        })
        if (!activeRun) {
            return res.json({ success: true, message: 'No active diagnostic run found for room' })
        }
        const closed = await diagnosticService.closeAndExportRun(activeRun.id)
        res.json({ success: true, runClosed: closed, runId: activeRun.id })
    } catch (err) {
        if (err instanceof ResolverError) {
            return res.status(err.status).json({ error: err.message, code: err.code })
        }
        next(err)
    }
})

// POST /api/diagnostics/runs/:runId/close – admin key only (unchanged)
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
