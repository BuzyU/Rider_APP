/**
 * server.js – Phase 8 fixes (H12)
 *
 * H12 fixes:
 *   - Raw DDL moved to Prisma migration (20261023_phase1_room_lifecycle_membership/migration.sql)
 *   - ensureDbExtensions() reduced to a lightweight connectivity check only
 *   - heartbeat log interval: 10s → 5min
 *   - Single-instance caveat documented (process.env.SINGLE_INSTANCE=true check)
 *   - Grace-close sweeper: on boot, finds any DiagnosticRun with closeAt in the past
 *     and closes them (B6 – handles restart recovery)
 *
 * Phase 8 also registers the /token/refresh route added to roomRoutes.
 */

'use strict'

require('dotenv').config()

const express = require('express')
const cors = require('cors')

const healthRoute = require('./routes/healthRoute')
const authMiddleware = require('./middleware/authMiddleware')
const { ipLimiter, userLimiter } = require('./middleware/rateLimiter')
const errorMiddleware = require('./middleware/errorMiddleware')
const requestLogger = require('./middleware/requestLogger')
const profileRoutes = require('./routes/userRoutes')
const friendRoutes = require('./routes/friendRoutes')
const inviteRoutes = require('./routes/inviteRoutes')
const rideRoutes = require('./routes/rideRoutes')
const roomRoutes = require('./routes/roomRoutes')
const lobbyRoutes = require('./routes/lobbyRoutes')
const emergencyRoutes = require('./routes/emergencyRoutes')
const diagnosticRoutes = require('./routes/diagnosticRoutes')
const testerRoutes = require('./routes/testerRoutes')

const app = express()
app.set('trust proxy', 1)

app.use((req, res, next) => {
    res.setHeader('Connection', 'keep-alive')
    res.setHeader('Keep-Alive', 'timeout=65')
    next()
})

app.use(cors())
app.use(requestLogger)

// Global IP rate limiter (skips /health, /api/health, /j/, /api/app/config)
app.use(ipLimiter)

// LiveKit webhook receiver mounted BEFORE express.json with raw body parser (D1)
const { createWebhookHandler } = require('./routes/livekitWebhook')
app.post(
    '/api/livekit/webhook',
    express.raw({ type: ['application/webhook+json', 'application/json'], limit: '256kb' }),
    createWebhookHandler()
)

// Larger 5mb JSON parser mounted specifically for diagnostic batches and ride telemetry sync
app.use('/api/diagnostics/batches', express.json({ limit: '5mb' }))
app.use('/api/rides/sync', express.json({ limit: '5mb' }))

// Standard 100kb JSON body parser for all other endpoints
app.use(express.json({ limit: '100kb' }))

// Public share landing page (D2)
const landingRoutes = require('./routes/landingRoutes')
app.use('/j', landingRoutes)

// Public health and app config routes
app.use('/api/health', healthRoute)
app.use('/health', healthRoute)
app.use('/', healthRoute)

// Public app configuration endpoint (C5)
app.get('/api/app/config', (req, res) => {
    res.setHeader('Cache-Control', 'public, max-age=60')
    res.json({
        roomIdMode: process.env.ROOM_ID_MODE === 'true',
        minAppVersion: process.env.MIN_APP_VERSION || '0.0.3.14',
        latestVersion: process.env.LATEST_APP_VERSION || process.env.MIN_APP_VERSION || '0.0.3.19',
        apkUrl: process.env.APK_DOWNLOAD_URL || (process.env.APP_BASE_URL ? `${process.env.APP_BASE_URL}/download` : '/download'),
        maintenance: process.env.MAINTENANCE_MODE === 'true'
    })
})

app.use('/api/tester-application', testerRoutes)
app.use('/api/testers', testerRoutes)
app.use('/api/diagnostics', diagnosticRoutes)

// P0-C / Fix 2: Fail-closed on write operations ONLY when database schema has a structural failure in production.
// NEVER block emergency (SOS), auth, webhooks, or health endpoints. Data warnings do not block writes.
app.use((req, res, next) => {
    const isWrite = ['POST', 'PUT', 'PATCH', 'DELETE'].includes(req.method)
    if (isWrite && process.env.NODE_ENV === 'production') {
        const { schemaStructuralOk, ...schemaDetails } = healthRoute.getSchemaHealth()
        const isExempt = req.path.startsWith('/api/emergency') ||
            req.path.startsWith('/api/auth') ||
            req.path.startsWith('/api/health') ||
            req.path.startsWith('/health') ||
            req.path.startsWith('/api/livekit') ||
            req.path.startsWith('/webhook')

        if (!schemaStructuralOk && !isExempt) {
            res.set('Retry-After', '30')
            return res.status(503).json({
                error: 'Service Unavailable: Database schema structural validation failed or degraded',
                code: 'SCHEMA_DEGRADED',
                details: schemaDetails
            })
        }
    }
    next()
})

// Protected routes: authMiddleware authenticates caller, then userLimiter enforces per-user quotas
app.use('/api/rooms', authMiddleware, userLimiter, roomRoutes)
app.use('/api/users', authMiddleware, userLimiter, profileRoutes)
app.use('/api/friends', authMiddleware, userLimiter, friendRoutes)
app.use('/api/rides', authMiddleware, userLimiter, rideRoutes)
app.use('/api/invites', authMiddleware, userLimiter, inviteRoutes)
app.use('/api/lobby', authMiddleware, userLimiter, lobbyRoutes)
app.use('/api/emergency', authMiddleware, userLimiter, emergencyRoutes)

app.use(errorMiddleware)

const prisma = require('./db')
const diagnosticService = require('./services/diagnosticService')

/**
 * ensureDbExtensions: verifies database connectivity and applies self-healing
 * schema synchronizations if migrations were skipped (e.g. on Render with pooled connections).
 */
async function ensureDbExtensions() {
    // 1. Simple connectivity check – fail fast on misconfigured DATABASE_URL
    const result = await prisma.$queryRaw`SELECT 1 AS ok`
    if (!result || result[0]?.ok !== 1) {
        throw new Error('Database connectivity check returned unexpected result')
    }
    console.log('[DB] Connectivity check passed.')

    // 2. Self-healing schema synchronization (Fix 2, Fix 3, Fix 4)
    const { ensureDbSchema, assertSchema, checkUniqueViolations } = require('./services/schemaMigration')
    const { setSchemaHealth } = require('./routes/healthRoute')
    const { probeColumns } = require('./services/roomResolver')

    const schemaResult = await ensureDbSchema(prisma)

    // Probe columns after schema migration attempt
    await probeColumns(prisma).catch(() => {})

    if (schemaResult.skippedDueToLock) {
        console.warn('[DB] Advisory migration lock held by another instance; waiting 3s to re-verify schema...')
        await new Promise(r => setTimeout(r, 3000))
        const recheck = await assertSchema(prisma).catch(() => ({ valid: false, missing: ['recheck_failed'] }))
        const violations = await checkUniqueViolations(prisma).catch(() => [])
        const structuralOk = recheck.valid && (recheck.missing?.length === 0)
        setSchemaHealth(structuralOk, {
            structuralOk,
            missing: recheck.missing,
            violations,
            message: structuralOk ? 'Schema ready (verified after concurrent repair)' : 'Schema still missing elements after concurrent repair wait'
        })
    } else {
        const structuralOk = schemaResult.structuralOk !== undefined ? schemaResult.structuralOk : schemaResult.valid
        setSchemaHealth(structuralOk, {
            structuralOk,
            missing: schemaResult.missing,
            failedStatements: schemaResult.failedStatements,
            violations: schemaResult.violations
        })
        if (!structuralOk) {
            console.error('[DB] Schema structural validation failed:', schemaResult.missing || schemaResult.failedStatements)
        } else if (schemaResult.violations && schemaResult.violations.length > 0) {
            console.warn('[DB] Schema structural validation passed, but data warnings present:', schemaResult.violations)
        }
    }
    return schemaResult
}

/**
 * B6 – On boot, recover any diagnostic runs that had their closeAt timestamp
 * set before the server was restarted (persisted grace-close recovery).
 */
async function recoverPendingDiagnosticCloses() {
    try {
        const overdueRuns = await prisma.diagnosticRun.findMany({
            where: {
                status: 'ACTIVE',
                closeAt: { lte: new Date() }
            },
            select: { id: true }
        })
        if (overdueRuns.length > 0) {
            console.log(`[Diagnostic] Recovering ${overdueRuns.length} overdue grace-close runs on startup`)
            for (const run of overdueRuns) {
                diagnosticService.closeAndExportRun(run.id, { forceClose: true }).catch(err => {
                    console.error(`[Diagnostic Recovery] Failed to close run ${run.id}:`, err.message)
                })
            }
        }
    } catch (err) {
        console.warn('[Diagnostic] Grace-close recovery check failed:', err.message)
    }
}

const PORT = process.env.PORT || 3000
const keepAliveService = require('./services/keepAliveService')

let serverInstance = null
if (require.main === module) {
    serverInstance = app.listen(PORT, () => {
        console.log(`Server running on port ${PORT}`)
        keepAliveService.start()
        ensureDbExtensions().then((schemaResult) => {
            if (schemaResult && schemaResult.valid) {
                console.log('[DB] Database schema verified.')
            } else {
                console.error('[DB] Database schema verification failed or degraded; readiness will report degraded (503).')
            }

            // B6 – Recover pending diagnostic closes from before restart
            recoverPendingDiagnosticCloses()

            // 1.6 / D3 – Stale-room sweeper (sweeps expired lobbies and abandoned active rooms)
            const { startRoomSweeper } = require('./services/roomSweeper')
            startRoomSweeper()

            // Diagnostic idle checker: 60s interval (unchanged, lightweight)
            const idleInterval = setInterval(() => {
                diagnosticService.checkIdleDiagnosticRuns().catch(() => {})
            }, 60 * 1000)
            if (idleInterval.unref) idleInterval.unref()

            // Diagnostic retention cleanup: daily
            const retentionInterval = setInterval(() => {
                diagnosticService.cleanOldDiagnosticRuns().catch(() => {})
            }, 24 * 60 * 60 * 1000)
            if (retentionInterval.unref) retentionInterval.unref()

            // H12 – heartbeat log: 10s → 5min (reduces noise in Render logs)
            const heartbeatInterval = setInterval(() => {
                if (process.env.NODE_ENV !== 'production') return
                console.log(`[Heartbeat] ${new Date().toISOString()} – server alive on port ${PORT}`)
            }, 5 * 60 * 1000)
            if (heartbeatInterval.unref) heartbeatInterval.unref()
        }).catch((e) => {
            console.warn('[DB] ensureDbExtensions warning:', e.message)
        })
    })
}

module.exports = { app, ensureDbExtensions }