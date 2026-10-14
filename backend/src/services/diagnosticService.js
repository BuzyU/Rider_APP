const nodemailer = require('nodemailer')
const prisma = require('../db')
const { validateBatchPayload } = require('./diagnosticValidator')

/**
 * Creates nodemailer transporter. Uses SMTP credentials from env if available,
 * otherwise falls back to mock logger transport for safe development/testing.
 */
function createMailer() {
    if (process.env.NODE_ENV === 'test' || !process.env.SMTP_HOST || process.env.SMTP_HOST === 'localhost') {
        return {
            sendMail: async (options) => {
                console.log(`[Diagnostic Email Attempt] To: ${options.to}, Subject: "${options.subject}", Attachments: ${options.attachments ? options.attachments.length : 0} (${options.attachments ? options.attachments[0].filename : 'none'})`)
                return { messageId: `mock_email_${Date.now()}` }
            }
        }
    }
    return nodemailer.createTransport({
        host: process.env.SMTP_HOST,
        port: parseInt(process.env.SMTP_PORT || '587', 10),
        secure: process.env.SMTP_PORT === '465',
        auth: {
            user: process.env.SMTP_USER,
            pass: (process.env.SMTP_PASS || '').replace(/\s+/g, '')
        }
    })
}

/**
 * Verifies that the authenticated caller is a member of the convoy (owner or accepted invite).
 * Returns the Room record.
 */
async function verifyConvoyMembership(convoyIdOrName, userId) {
    if (!convoyIdOrName) {
        const error = new Error('Missing convoyId in request')
        error.status = 400
        throw error
    }

    const room = await prisma.room.findFirst({
        where: {
            OR: [
                { id: convoyIdOrName },
                { name: convoyIdOrName }
            ]
        },
        select: {
            id: true,
            name: true,
            ownerId: true,
            invites: {
                where: { inviteeId: userId, status: 'ACCEPTED' },
                select: { id: true },
                take: 1
            }
        }
    })

    if (!room) {
        const error = new Error(`Convoy '${convoyIdOrName}' not found`)
        error.status = 404
        throw error
    }

    const isAuthorized = room.ownerId === userId || (room.invites && room.invites.length > 0)
    if (!isAuthorized) {
        const error = new Error('Access denied: caller is not a member of this convoy')
        error.status = 403
        throw error
    }

    return room
}

/**
 * Retrieves the rider's ID and handle strictly from the database.
 * Uses real handle when present; Rider_<uid> only as fallback.
 */
async function getRiderIdentity(userId) {
    const user = await prisma.user.findUnique({
        where: { id: userId },
        select: { id: true, handle: true, displayName: true }
    })
    if (!user) {
        const error = new Error('User record not found')
        error.status = 404
        throw error
    }
    return {
        riderId: user.id,
        handle: user.handle || user.displayName || `Rider_${user.id.slice(0, 8)}`
    }
}

/**
 * Rider joins or starts an active DiagnosticRun for a convoy.
 * Allocates testNumber sequentially inside a transaction.
 * Concurrency-safe: simultaneous joins yield exactly ONE active run.
 * Re-joining clears any previous stoppedAt and lastSeq.
 */
async function joinDiagnosticRun(convoyIdOrName, user) {
    const room = await verifyConvoyMembership(convoyIdOrName, user.uid)
    const { riderId, handle } = await getRiderIdentity(user.uid)

    // 1. Find or create active run
    let activeRun = await prisma.diagnosticRun.findFirst({
        where: {
            convoyId: room.id,
            status: 'ACTIVE'
        },
        orderBy: { testNumber: 'desc' }
    })

    if (!activeRun) {
        try {
            const lastRun = await prisma.diagnosticRun.findFirst({
                where: { convoyId: room.id },
                orderBy: { testNumber: 'desc' },
                select: { testNumber: true }
            })
            const testNumber = lastRun ? lastRun.testNumber + 1 : 1

            activeRun = await prisma.diagnosticRun.create({
                data: {
                    convoyId: room.id,
                    testNumber,
                    status: 'ACTIVE'
                }
            })
        } catch (err) {
            // If a concurrent join raced and created the active run first
            activeRun = await prisma.diagnosticRun.findFirst({
                where: {
                    convoyId: room.id,
                    status: 'ACTIVE'
                },
                orderBy: { testNumber: 'desc' }
            })
            if (!activeRun) throw err
        }
    }

    // 2. Re-joining clears stoppedAt and lastSeq
    const participant = await prisma.diagnosticParticipant.upsert({
        where: {
            runId_riderId: {
                runId: activeRun.id,
                riderId
            }
        },
        update: {
            handle,
            stoppedAt: null,
            lastSeq: null,
            lastBatchAt: new Date()
        },
        create: {
            runId: activeRun.id,
            riderId,
            handle,
            joinedAt: new Date(),
            stoppedAt: null,
            lastSeq: null,
            lastBatchAt: new Date()
        }
    })

    return {
        runId: activeRun.id,
        convoyId: room.id,
        convoyName: room.name,
        testNumber: activeRun.testNumber,
        participantId: participant.id,
        handle
    }
}

/**
 * Checks whether a run is ready to close:
 * Every participant must have stopped (stoppedAt != null) and reported lastSeq,
 * and every batch from sequence 1..lastSeq must have been received for all participants.
 */
async function isRunReadyToClose(runId) {
    const run = await prisma.diagnosticRun.findUnique({
        where: { id: runId },
        include: {
            participants: true,
            batches: {
                select: { riderId: true, sequenceNumber: true }
            }
        }
    })

    if (!run || run.status !== 'ACTIVE') {
        return false
    }

    if (run.participants.length === 0) {
        return false
    }

    for (const p of run.participants) {
        if (!p.stoppedAt) {
            return false
        }

        const requiredLastSeq = p.lastSeq
        if (requiredLastSeq > 0) {
            const riderBatches = new Set(
                run.batches
                    .filter(b => b.riderId === p.riderId)
                    .map(b => b.sequenceNumber)
            )

            for (let seq = 1; seq <= requiredLastSeq; seq++) {
                if (!riderBatches.has(seq)) {
                    // Sequence gap: in-flight batch still missing
                    return false
                }
            }
        }
    }

    return true
}

/**
 * Posts a batch of diagnostic metrics idempotently.
 * Enforces that caller is a registered DiagnosticParticipant of this run.
 * Validates payload schema against contract.
 * If run was already closed, stores the late batch for subsequent exports without re-emailing.
 */
async function postDiagnosticBatch(runId, sequenceNumber, payload, user) {
    if (!runId || sequenceNumber === undefined || sequenceNumber === null || !payload) {
        const error = new Error('Missing runId, sequenceNumber, or payload')
        error.status = 400
        throw error
    }

    // Validate payload types, ranges, limits
    validateBatchPayload(payload)

    const { riderId } = await getRiderIdentity(user.uid)

    // Authorization: Verify authenticated user is a registered participant in this run
    const participant = await prisma.diagnosticParticipant.findUnique({
        where: {
            runId_riderId: {
                runId,
                riderId
            }
        }
    })

    if (!participant) {
        const error = new Error('Forbidden: Caller is not a registered DiagnosticParticipant of this run')
        error.status = 403
        throw error
    }

    const run = await prisma.diagnosticRun.findUnique({
        where: { id: runId }
    })

    if (!run) {
        const error = new Error('Diagnostic run not found')
        error.status = 404
        throw error
    }

    // Update participant's last batch timestamp
    await prisma.diagnosticParticipant.update({
        where: { id: participant.id },
        data: { lastBatchAt: new Date() }
    })

    // Upsert batch for idempotent deduplication
    const seqNum = parseInt(sequenceNumber, 10)
    const batch = await prisma.diagnosticBatch.upsert({
        where: {
            runId_riderId_sequenceNumber: {
                runId,
                riderId,
                sequenceNumber: seqNum
            }
        },
        update: {
            payload,
            receivedAt: new Date()
        },
        create: {
            runId,
            riderId,
            sequenceNumber: seqNum,
            payload
        }
    })

    // If run is still ACTIVE, check if this batch completes all expected batches 1..lastSeq
    if (run.status === 'ACTIVE') {
        const ready = await isRunReadyToClose(runId)
        if (ready) {
            await closeAndExportRun(runId)
        }
    }

    return {
        status: 'success',
        batchId: batch.id,
        sequenceNumber: batch.sequenceNumber
    }
}

/**
 * Rider stops diagnostic recording.
 * Requires lastSeq (integer).
 * Enforces that caller is a registered DiagnosticParticipant of this run.
 */
async function stopDiagnosticRun(runId, lastSeq, user) {
    const { riderId } = await getRiderIdentity(user.uid)

    // Authorization: Caller must be a registered participant
    const participant = await prisma.diagnosticParticipant.findUnique({
        where: {
            runId_riderId: {
                runId,
                riderId
            }
        }
    })

    if (!participant) {
        const error = new Error('Forbidden: Caller is not a registered DiagnosticParticipant of this run')
        error.status = 403
        throw error
    }

    const parsedLastSeq = lastSeq !== undefined && lastSeq !== null ? parseInt(lastSeq, 10) : 0

    await prisma.diagnosticParticipant.update({
        where: { id: participant.id },
        data: {
            stoppedAt: new Date(),
            lastSeq: parsedLastSeq
        }
    })

    // Check if run is ready to close (all participants stopped and 1..lastSeq received)
    const ready = await isRunReadyToClose(runId)
    let closed = false
    if (ready) {
        closed = await closeAndExportRun(runId)
    }

    return {
        status: 'stopped',
        runClosed: closed
    }
}

/**
 * Neutralizes CSV formula injection and escapes special characters.
 */
function sanitizeCsvCell(val) {
    if (val === undefined || val === null) return ''
    let str = String(val)
    // Neutralize formula injection (=, +, -, @, tab, CR)
    if (/^[=+\-@\t\r]/.test(str)) {
        str = `'${str}`
    }
    if (str.includes(',') || str.includes('\n') || str.includes('\r') || str.includes('"')) {
        return `"${str.replace(/"/g, '""')}"`
    }
    return str
}

/**
 * Converts metric samples to CSV strings safely.
 */
function buildCsv(headers, rows) {
    const headerLine = headers.join(',')
    const dataLines = rows.map(r => headers.map(h => sanitizeCsvCell(r[h])).join(','))
    return [headerLine, ...dataLines].join('\n')
}

/**
 * Sanitizes zip folder and file path segments.
 * Strips path separators, '..', control chars, trailing dots/spaces, and caps length at 50 chars.
 */
function sanitizeZipSegment(name, fallback = 'item') {
    if (!name) return fallback
    let clean = name
        .replace(/[\/\\:\*\?"<>\|]/g, '_')
        .replace(/\.\./g, '_')
        .replace(/[\x00-\x1F\x7F]/g, '')
        .trim()
        .replace(/[.\s]+$/, '')
    if (clean.length > 50) clean = clean.slice(0, 50)
    return clean || fallback
}

/**
 * Collects and prepares all files and summaries for a run in-memory.
 */
async function buildRunExportData(runId) {
    const run = await prisma.diagnosticRun.findUnique({
        where: { id: runId },
        include: {
            room: true,
            participants: true,
            batches: {
                orderBy: { sequenceNumber: 'asc' }
            }
        }
    })

    if (!run) return null

    const convoyClean = sanitizeZipSegment(run.room?.name, 'Convoy')
    const testFolder = `Test_${run.testNumber}`
    const rootPathInZip = `${convoyClean}/${testFolder}`

    // Map of rider IDs to handles for server-side resolution
    const riderIdToHandle = new Map()
    for (const p of run.participants) {
        riderIdToHandle.set(p.riderId, p.handle)
    }

    // Resolve any remaining remote rider IDs that may not be in participants
    const allBatches = run.batches || []
    const unknownRemoteIds = new Set()
    for (const b of allBatches) {
        const inbounds = b.payload?.inboundMetrics || []
        for (const im of inbounds) {
            if (im.remote_rider && !riderIdToHandle.has(im.remote_rider)) {
                unknownRemoteIds.add(im.remote_rider)
            }
        }
    }

    if (unknownRemoteIds.size > 0) {
        const extraUsers = await prisma.user.findMany({
            where: { id: { in: Array.from(unknownRemoteIds) } },
            select: { id: true, handle: true, displayName: true }
        })
        for (const u of extraUsers) {
            riderIdToHandle.set(u.id, u.handle || u.displayName || u.id)
        }
    }

    // Group batches by riderId
    const batchesByRider = new Map()
    for (const batch of allBatches) {
        if (!batchesByRider.has(batch.riderId)) {
            batchesByRider.set(batch.riderId, [])
        }
        batchesByRider.get(batch.riderId).push(batch)
    }

    const participantSummaries = []
    const files = []
    const usedFolders = new Set()

    for (const participant of run.participants) {
        let handleFolder = sanitizeZipSegment(participant.handle, `rider_${participant.riderId.slice(0, 8)}`)
        if (usedFolders.has(handleFolder)) {
            handleFolder = `${handleFolder}_${participant.riderId.slice(0, 6)}`
        }
        usedFolders.add(handleFolder)

        const riderBatches = batchesByRider.get(participant.riderId) || []

        const selfRows = []
        const inboundRows = []
        const eventRows = []

        let totalPacketsSent = 0
        let totalPacketsRecv = 0
        let totalPacketsLost = 0
        const rttValues = []

        for (const b of riderBatches) {
            const p = b.payload || {}
            if (Array.isArray(p.selfMetrics)) {
                for (const sm of p.selfMetrics) {
                    // Ensure rtt_ms is explicitly null when missing, never 0
                    const rttMs = sm.rtt_ms !== undefined && sm.rtt_ms !== null && Number(sm.rtt_ms) > 0
                        ? Number(sm.rtt_ms)
                        : null
                    selfRows.push({
                        ...sm,
                        rtt_ms: rttMs
                    })
                    if (rttMs !== null) rttValues.push(rttMs)
                    if (sm.packets_sent) totalPacketsSent += Number(sm.packets_sent)
                    if (sm.packets_received) totalPacketsRecv += Number(sm.packets_received)
                    if (sm.packets_lost) totalPacketsLost += Number(sm.packets_lost)
                }
            }
            if (Array.isArray(p.inboundMetrics)) {
                for (const im of p.inboundMetrics) {
                    // Resolve remote rider identity (uid) to handle, keep raw uid as extra column
                    const mappedHandle = riderIdToHandle.get(im.remote_rider) || im.remote_rider
                    inboundRows.push({
                        ...im,
                        remote_rider: mappedHandle,
                        remote_rider_uid: im.remote_rider
                    })
                }
            }
            if (Array.isArray(p.events)) {
                for (const ev of p.events) {
                    eventRows.push(ev)
                }
            }
        }

        const avgRtt = rttValues.length > 0 ? (rttValues.reduce((a, b) => a + b, 0) / rttValues.length).toFixed(1) : 'N/A'
        const lossPercent = (totalPacketsRecv + totalPacketsLost) > 0
            ? ((totalPacketsLost / (totalPacketsRecv + totalPacketsLost)) * 100).toFixed(2)
            : '0.00'

        const riderSummary = {
            riderId: participant.riderId,
            handle: participant.handle,
            joinedAt: participant.joinedAt,
            stoppedAt: participant.stoppedAt,
            lastSeq: participant.lastSeq,
            totalBatches: riderBatches.length,
            totalSelfSamples: selfRows.length,
            totalInboundSamples: inboundRows.length,
            totalEvents: eventRows.length,
            avgRttMs: avgRtt,
            lossPercent: `${lossPercent}%`
        }
        participantSummaries.push(riderSummary)

        const selfHeaders = [
            'timestamp',
            'deviceEpochMs',
            'elapsedRealtimeMs',
            'rtt_ms',
            'packets_sent',
            'packets_received',
            'packets_lost',
            'retransmits',
            'bytes_sent',
            'bytes_received',
            'bitrate_kbps',
            'jitter_ms',
            'network_type',
            'cell_operator',
            'cell_tech',
            'signal_strength_dbm',
            'battery_level',
            'thermal_status',
            'is_foreground'
        ]
        const inboundHeaders = [
            'timestamp',
            'deviceEpochMs',
            'elapsedRealtimeMs',
            'remote_rider',
            'remote_rider_uid',
            'track_sid',
            'packets_received',
            'packets_lost',
            'interval_loss_pct',
            'jitter_ms',
            'jitter_buffer_delay',
            'concealed_samples',
            'concealment_events',
            'audio_level',
            'bytes_received'
        ]
        const eventHeaders = [
            'timestamp',
            'deviceEpochMs',
            'elapsedRealtimeMs',
            'event_type',
            'details'
        ]

        const riderPath = `${rootPathInZip}/${handleFolder}`

        files.push({
            name: `${riderPath}/self.csv`,
            content: buildCsv(selfHeaders, selfRows)
        })
        files.push({
            name: `${riderPath}/inbound.csv`,
            content: buildCsv(inboundHeaders, inboundRows)
        })
        files.push({
            name: `${riderPath}/events.csv`,
            content: buildCsv(eventHeaders, eventRows)
        })
        files.push({
            name: `${riderPath}/summary.json`,
            content: JSON.stringify(riderSummary, null, 2)
        })
    }

    const runSummaryMd = `# Network Diagnostics Report: ${run.room?.name || 'Convoy'} - Test ${run.testNumber}

- **Convoy ID:** ${run.convoyId}
- **Run ID:** ${run.id}
- **Created At:** ${run.createdAt.toISOString()}
- **Closed At:** ${run.closedAt ? run.closedAt.toISOString() : new Date().toISOString()}
- **Total Participants:** ${run.participants.length}
- **Total Batches Received:** ${run.batches.length}

## Participants Summary

| Rider Handle | Total Batches | Last Seq | Avg RTT | Packet Loss % | Joined At | Stopped At |
|---|---|---|---|---|---|---|
${participantSummaries.map(p => `| ${p.handle} | ${p.totalBatches} | ${p.lastSeq ?? '-'} | ${p.avgRttMs} ms | ${p.lossPercent} | ${p.joinedAt?.toISOString() || '-'} | ${p.stoppedAt?.toISOString() || '-'} |`).join('\n')}

---
*Generated automatically by RiderVoice Network Diagnostics Service.*
`

    files.push({
        name: `${rootPathInZip}/run_summary.md`,
        content: runSummaryMd
    })

    return {
        run,
        convoyClean,
        participantSummaries,
        runSummaryMd,
        files
    }
}

/**
 * Builds in-memory ZIP archive as a Buffer. Never persists to disk.
 */
function buildZipBuffer(files) {
    return new Promise((resolve, reject) => {
        const archiverModule = require('archiver')
        const archive = archiverModule.ZipArchive
            ? new archiverModule.ZipArchive({ zlib: { level: 9 } })
            : (typeof archiverModule === 'function' ? archiverModule('zip', { zlib: { level: 9 } }) : archiverModule.default('zip', { zlib: { level: 9 } }))
        const chunks = []

        archive.on('data', chunk => chunks.push(chunk))
        archive.on('end', () => resolve(Buffer.concat(chunks)))
        archive.on('error', reject)

        for (const file of files) {
            archive.append(file.content, { name: file.name })
        }

        archive.finalize()
    })
}

/**
 * Closes and exports a DiagnosticRun idempotently.
 * Never persists ZIP to disk.
 * Uses conditional updateMany to claim emailedAt preventing double-sends,
 * and releases the claim if SMTP fails.
 */
async function closeAndExportRun(runId) {
    // 1. Atomic state transition: ACTIVE -> CLOSED
    const updateCount = await prisma.diagnosticRun.updateMany({
        where: {
            id: runId,
            status: 'ACTIVE'
        },
        data: {
            status: 'CLOSED',
            closedAt: new Date()
        }
    })

    if (updateCount.count === 0) {
        // Already closed by another request
        return false
    }

    const exportData = await buildRunExportData(runId)
    if (!exportData) return false

    const { run, convoyClean, participantSummaries, runSummaryMd, files } = exportData

    // Build ZIP in memory
    const zipBuffer = await buildZipBuffer(files)
    const fileSizeMb = (zipBuffer.length / (1024 * 1024)).toFixed(2)

    // Save metadata summary in DB
    await prisma.diagnosticRun.update({
        where: { id: run.id },
        data: {
            summary: {
                totalParticipants: run.participants.length,
                totalBatches: run.batches.length,
                fileSizeBytes: zipBuffer.length,
                fileSizeMb,
                participants: participantSummaries
            }
        }
    })

    // Don't email runs with zero batches
    if (run.batches.length === 0) {
        console.log(`[Diagnostic] Run ${run.id} has 0 batches. Skipping email dispatch.`)
        return true
    }

    // 2. Email dispatch with pre-claim to prevent double-send
    const emailTo = process.env.DIAG_EMAIL_TO || process.env.SMTP_USER
    const isSmtpConfigured = (process.env.SMTP_HOST && process.env.SMTP_USER) || process.env.NODE_ENV === 'test'

    if (!isSmtpConfigured) {
        console.warn('[Diagnostic Email] WARNING: SMTP is not configured (SMTP_HOST/SMTP_USER missing). Leaving run exportable without email.')
        return true
    }

    if (emailTo) {
        // Atomically claim the right to email this run
        const claimResult = await prisma.diagnosticRun.updateMany({
            where: {
                id: run.id,
                emailedAt: null
            },
            data: {
                emailedAt: new Date()
            }
        })

        if (claimResult.count > 0) {
            const mailer = createMailer()
            const zipFileName = `diagnostic_${convoyClean}_test${run.testNumber}_${Date.now()}.zip`

            const fromName = process.env.SMTP_FROM_NAME || 'RiderVoice Diagnostics'
            const fromHeader = process.env.SMTP_FROM || (process.env.SMTP_USER ? `"${fromName}" <${process.env.SMTP_USER}>` : `"${fromName}" <diagnostics@ridervoice.com>`)

            const mailOptions = {
                from: fromHeader,
                to: emailTo,
                subject: `[Diagnostic Report] ${run.room?.name || 'Convoy'} - Test ${run.testNumber}`,
                text: runSummaryMd
            }

            // If <= 20MB: attach in-memory buffer. If > 20MB: email only summary.
            if (zipBuffer.length <= 20 * 1024 * 1024) {
                mailOptions.attachments = [
                    {
                        filename: zipFileName,
                        content: zipBuffer
                    }
                ]
            } else {
                mailOptions.text += `\n\n[NOTICE] ZIP file size (${fileSizeMb} MB) exceeds the 20MB email attachment threshold. Emailing summary only. Full archive can be downloaded via the admin export endpoint.`
            }

            try {
                await mailer.sendMail(mailOptions)
                console.log(`[Diagnostic] Emailed report to ${emailTo} (size: ${fileSizeMb} MB)`)
            } catch (emailErr) {
                // On failure, release the claim so it can be retried
                await prisma.diagnosticRun.update({
                    where: { id: run.id },
                    data: { emailedAt: null }
                })
                console.error('[Diagnostic] SMTP dispatch failed, released email claim:', emailErr.message)
            }
        }
    }

    return true
}

/**
 * Checks for idle runs and closes them automatically.
 * Evaluates max(lastBatchAt, joinedAt) per participant so non-uploading riders cannot hold a run open.
 */
async function checkIdleDiagnosticRuns() {
    try {
        const timeoutMs = parseInt(process.env.DIAG_IDLE_TIMEOUT_MS || '600000', 10) // 10 minutes default
        const now = Date.now()

        const activeRuns = await prisma.diagnosticRun.findMany({
            where: { status: 'ACTIVE' },
            include: {
                participants: {
                    select: { lastBatchAt: true, joinedAt: true }
                }
            }
        })

        for (const run of activeRuns) {
            if (run.participants.length === 0) continue

            // Determine if all participants are idle past timeout threshold
            const allParticipantsIdle = run.participants.every(p => {
                const lastActivity = p.lastBatchAt && p.lastBatchAt > p.joinedAt ? p.lastBatchAt : p.joinedAt
                return (now - new Date(lastActivity).getTime()) > timeoutMs
            })

            if (allParticipantsIdle) {
                console.log(`[Diagnostic] Auto-closing idle run ${run.id} (idle timeout: ${timeoutMs}ms)`)
                await closeAndExportRun(run.id)
            }
        }
    } catch (e) {
        console.error('[Diagnostic] Error during checkIdleDiagnosticRuns:', e.message)
    }
}

/**
 * Data retention job: deletes runs and batches older than DIAG_RETENTION_DAYS (default: 30 days).
 */
async function cleanOldDiagnosticRuns() {
    try {
        const retentionDays = parseInt(process.env.DIAG_RETENTION_DAYS || '30', 10)
        const cutoffDate = new Date(Date.now() - retentionDays * 24 * 60 * 60 * 1000)

        const deletedRuns = await prisma.diagnosticRun.deleteMany({
            where: {
                createdAt: { lt: cutoffDate }
            }
        })

        if (deletedRuns.count > 0) {
            console.log(`[Diagnostic Retention] Cleaned ${deletedRuns.count} runs older than ${retentionDays} days.`)
        }
        return deletedRuns.count
    } catch (e) {
        console.error('[Diagnostic Retention] Error during cleanup:', e.message)
        return 0
    }
}

module.exports = {
    joinDiagnosticRun,
    postDiagnosticBatch,
    stopDiagnosticRun,
    closeAndExportRun,
    checkIdleDiagnosticRuns,
    cleanOldDiagnosticRuns,
    buildRunExportData,
    buildZipBuffer
}
