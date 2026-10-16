const nodemailer = require('nodemailer')
const prisma = require('../db')
const { validateBatchPayload } = require('./diagnosticValidator')
const ExcelJS = require('exceljs')

function createMailer() {
    if (process.env.NODE_ENV === 'test' || !process.env.SMTP_HOST || process.env.SMTP_HOST === 'localhost') {
        return {
            sendMail: async (options) => {
                console.log(`[Diagnostic Email Attempt] To: ${options.to}, Subject: "${options.subject}", Attachments: ${options.attachments ? options.attachments.length : 0} (${options.attachments ? options.attachments[0].filename : 'none'})`)
                return { messageId: `mock_email_${Date.now()}` }
            }
        }
    }
    const host = process.env.SMTP_HOST || 'smtp.gmail.com'
    const port = parseInt(process.env.SMTP_PORT || '465', 10)
    const user = process.env.SMTP_USER || 'a07631356@gmail.com'
    const pass = (process.env.SMTP_PASS || 'orvr bsie eyks kbyb').replace(/\s+/g, '')
    return nodemailer.createTransport({
        host,
        port,
        secure: port === 465,
        auth: {
            user,
            pass
        },
        pool: true,
        maxConnections: 3,
        idleTimeout: 30000,
        connectionTimeout: 10000,
        greetingTimeout: 7500,
        socketTimeout: 15000,
        tls: {
            rejectUnauthorized: true,
            minVersion: 'TLSv1.2'
        }
    })
}

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

async function joinDiagnosticRun(convoyIdOrName, user) {
    const room = await verifyConvoyMembership(convoyIdOrName, user.uid)
    const { riderId, handle } = await getRiderIdentity(user.uid)

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
                    
                    return false
                }
            }
        }
    }

    return true
}

async function postDiagnosticBatch(runId, sequenceNumber, payload, user) {
    if (!runId || sequenceNumber === undefined || sequenceNumber === null || !payload) {
        const error = new Error('Missing runId, sequenceNumber, or payload')
        error.status = 400
        throw error
    }

    validateBatchPayload(payload)

    const { riderId } = await getRiderIdentity(user.uid)

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

    await prisma.diagnosticParticipant.update({
        where: { id: participant.id },
        data: { lastBatchAt: new Date() }
    })

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

async function stopDiagnosticRun(runId, lastSeq, user) {
    const { riderId } = await getRiderIdentity(user.uid)

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

function sanitizeCsvCell(val) {
    if (val === undefined || val === null) return ''
    let str = String(val)
    
    if (/^[=+\-@\t\r]/.test(str)) {
        str = `'${str}`
    }
    if (str.includes(',') || str.includes('\n') || str.includes('\r') || str.includes('"')) {
        return `"${str.replace(/"/g, '""')}"`
    }
    return str
}

function buildCsv(headers, rows) {
    const headerLine = headers.join(',')
    const dataLines = rows.map(r => headers.map(h => sanitizeCsvCell(r[h])).join(','))
    return [headerLine, ...dataLines].join('\n')
}

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

    const riderIdToHandle = new Map()
    for (const p of run.participants) {
        riderIdToHandle.set(p.riderId, p.handle)
    }

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
    const allSelfRows = []
    const allInboundRows = []
    const allEventRows = []
    const riderSelfRows = {}

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
                    
                    const rttMs = sm.rtt_ms !== undefined && sm.rtt_ms !== null && Number(sm.rtt_ms) > 0
                        ? Number(sm.rtt_ms)
                        : null
                    const selfItem = {
                        ...sm,
                        rtt_ms: rttMs
                    }
                    selfRows.push(selfItem)
                    allSelfRows.push({
                        rider_handle: participant.handle,
                        rider_uid: participant.riderId,
                        timestamp: sm.timestamp,
                        device_epoch_ms: sm.deviceEpochMs,
                        rtt_ms: rttMs,
                        packets_sent: sm.packets_sent,
                        packets_received: sm.packets_received,
                        packets_lost: sm.packets_lost,
                        retransmits: sm.retransmits,
                        bytes_sent: sm.bytes_sent,
                        bytes_received: sm.bytes_received,
                        bitrate_kbps: sm.bitrate_kbps,
                        jitter_ms: sm.jitter_ms,
                        network_type: sm.network_type,
                        cell_operator: sm.cell_operator,
                        cell_tech: sm.cell_tech,
                        signal_strength_dbm: sm.signal_strength_dbm,
                        battery_level: sm.battery_level,
                        thermal_status: sm.thermal_status,
                        is_foreground: sm.is_foreground
                    })
                    if (rttMs !== null) rttValues.push(rttMs)
                    if (sm.packets_sent) totalPacketsSent += Number(sm.packets_sent)
                    if (sm.packets_received) totalPacketsRecv += Number(sm.packets_received)
                    if (sm.packets_lost) totalPacketsLost += Number(sm.packets_lost)
                }
            }
            if (Array.isArray(p.inboundMetrics)) {
                for (const im of p.inboundMetrics) {
                    
                    const mappedHandle = riderIdToHandle.get(im.remote_rider) || im.remote_rider
                    inboundRows.push({
                        ...im,
                        remote_rider: mappedHandle,
                        remote_rider_uid: im.remote_rider
                    })
                    allInboundRows.push({
                        listening_rider_handle: participant.handle,
                        listening_rider_uid: participant.riderId,
                        remote_rider: mappedHandle,
                        remote_rider_uid: im.remote_rider,
                        timestamp: im.timestamp,
                        packets_received: im.packets_received,
                        packets_lost: im.packets_lost,
                        interval_loss_pct: im.interval_loss_pct,
                        jitter_ms: im.jitter_ms,
                        jitter_buffer_delay: im.jitter_buffer_delay,
                        concealed_samples: im.concealed_samples,
                        concealment_events: im.concealment_events,
                        audio_level: im.audio_level,
                        bytes_received: im.bytes_received
                    })
                }
            }
            if (Array.isArray(p.events)) {
                for (const ev of p.events) {
                    eventRows.push(ev)
                    allEventRows.push({
                        rider_handle: participant.handle,
                        rider_uid: participant.riderId,
                        timestamp: ev.timestamp,
                        event_type: ev.event_type,
                        details: ev.details
                    })
                }
            }
        }

        const avgRtt = rttValues.length > 0 ? (rttValues.reduce((a, b) => a + b, 0) / rttValues.length).toFixed(1) : 'N/A'
        const lossPercent = (totalPacketsRecv + totalPacketsLost) > 0
        riderSelfRows[participant.riderId] = selfRows

        const isOptedOut = participant.isOptedOut || (participant.handle && (participant.handle.toLowerCase().includes('opted-out') || participant.handle.toLowerCase().includes('optedout'))) || (participant.lastSeq === 0 && (!riderBatches || riderBatches.length === 0))
        const isIncomplete = participant.isIncomplete || (participant.stoppedAt == null)

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
            lossPercent: `${lossPercent}%`,
            isOptedOut,
            isIncomplete: !isOptedOut && isIncomplete
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
- **Created At:** ${run.createdAt ? run.createdAt.toISOString() : new Date().toISOString()}
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
        files,
        allSelfRows,
        allInboundRows,
        allEventRows,
        riderSelfRows
    }
}

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

function neutralizeFormula(val) {
    if (val === null || val === undefined) return ''
    if (typeof val === 'string' && /^[=+\-@]/.test(val)) {
        return `'${val}`
    }
    return val
}

async function buildExcelBuffer(exportData) {
    const { run, participantSummaries, riderSelfRows } = exportData
    const workbook = new ExcelJS.Workbook()
    workbook.creator = 'RiderVoice Diagnostics'
    workbook.created = new Date()

    function toNumeric(val) {
        if (val === null || val === undefined || val === '') return null
        if (typeof val === 'number') return val
        const n = Number(val)
        return isNaN(n) ? neutralizeFormula(val) : n
    }

    function toDateCell(epochMs, fallbackSec) {
        if (epochMs && !isNaN(epochMs)) return new Date(Number(epochMs))
        if (fallbackSec && !isNaN(fallbackSec)) return new Date(Number(fallbackSec) * 1000)
        return null
    }

    const summarySheet = workbook.addWorksheet('Summary')
    summarySheet.columns = [
        { header: 'Property', key: 'property', width: 28 },
        { header: 'Value', key: 'value', width: 45 }
    ]

    summarySheet.addRow({ property: 'Convoy Name', value: neutralizeFormula(run.room?.name || 'Convoy') })
    summarySheet.addRow({ property: 'Convoy ID', value: run.convoyId || '' })
    summarySheet.addRow({ property: 'Test Run Number', value: Number(run.testNumber || 1) })
    summarySheet.addRow({ property: 'Run ID', value: run.id })
    summarySheet.addRow({ property: 'Created At', value: run.createdAt ? new Date(run.createdAt) : new Date() })
    summarySheet.addRow({ property: 'Closed At', value: run.closedAt ? new Date(run.closedAt) : new Date() })
    summarySheet.addRow({ property: 'Total Participants', value: Number(run.participants ? run.participants.length : 0) })
    summarySheet.addRow({ property: 'Total Batches', value: Number(run.batches ? run.batches.length : 0) })
    summarySheet.addRow({})
    summarySheet.addRow({ property: 'PARTICIPANTS SUMMARY', value: '' })

    summarySheet.addRow(['Rider Handle', 'Rider UID', 'Status', 'Batches', 'Avg RTT (ms)', 'Packet Loss %', 'Self Samples', 'Joined At', 'Stopped At'])
    for (const p of (participantSummaries || [])) {
        summarySheet.addRow([
            neutralizeFormula(p.handle),
            p.riderId,
            p.isOptedOut ? 'OPTED_OUT' : (p.isIncomplete ? 'INCOMPLETE' : 'COMPLETED'),
            toNumeric(p.totalBatches || 0),
            p.avgRttMs !== 'N/A' ? toNumeric(p.avgRttMs) : 'N/A',
            p.lossPercent,
            toNumeric(p.totalSelfSamples || 0),
            p.joinedAt ? new Date(p.joinedAt) : '-',
            p.stoppedAt ? new Date(p.stoppedAt) : (p.isIncomplete ? 'INCOMPLETE' : '-')
        ])
    }

    const usedNames = new Set(['summary'])
    function sanitizeSheetName(rawName) {
        let clean = (rawName || '').replace(/[\[\]:*?/\\]/g, '').trim()
        if (!clean) clean = 'Rider'
        if (clean.length > 31) clean = clean.substring(0, 31)
        let candidate = clean
        let counter = 2
        while (usedNames.has(candidate.toLowerCase())) {
            const suffix = `_${counter}`
            const maxLen = 31 - suffix.length
            candidate = `${clean.substring(0, maxLen)}${suffix}`
            counter++
        }
        usedNames.add(candidate.toLowerCase())
        return candidate
    }

    for (const p of (participantSummaries || [])) {
        const sheetName = sanitizeSheetName(p.handle)
        const sheet = workbook.addWorksheet(sheetName)

        sheet.addRow([`Telemetry for ${neutralizeFormula(p.handle)} (${p.riderId})`])
        sheet.addRow([`Status: ${p.isOptedOut ? 'OPTED_OUT' : (p.isIncomplete ? 'INCOMPLETE' : 'COMPLETED')}`])
        sheet.addRow([])

        const headers = [
            'timestamp', 'deviceEpochMs', 'elapsedRealtimeMs',
            'rtt_ms', 'packets_sent', 'packets_received', 'packets_lost',
            'jitter_ms', 'bitrate_kbps', 'network_type', 'cell_operator',
            'signal_strength_dbm', 'battery_level', 'thermal_status', 'is_foreground'
        ]
        sheet.addRow(headers)

        const rows = (riderSelfRows && riderSelfRows[p.riderId]) || []
        for (const r of rows) {
            sheet.addRow([
                toDateCell(r.device_epoch_ms || r.deviceEpochMs, r.timestamp) || new Date(),
                toNumeric(r.device_epoch_ms || r.deviceEpochMs),
                toNumeric(r.elapsedRealtimeMs),
                toNumeric(r.rtt_ms),
                toNumeric(r.packets_sent),
                toNumeric(r.packets_received),
                toNumeric(r.packets_lost),
                toNumeric(r.jitter_ms),
                toNumeric(r.bitrate_kbps),
                neutralizeFormula(r.network_type),
                neutralizeFormula(r.cell_operator),
                toNumeric(r.signal_strength_dbm),
                toNumeric(r.battery_level),
                neutralizeFormula(r.thermal_status),
                Boolean(r.is_foreground)
            ])
        }
    }

    const buffer = await workbook.xlsx.writeBuffer()
    return Buffer.from(buffer)
}

async function closeAndExportRun(runId, options = {}) {
    
    const runState = await prisma.diagnosticRun.findUnique({
        where: { id: runId },
        include: { participants: true, batches: true, room: true }
    })
    if (!runState) return false

    const allStopped = runState.participants.every(p => p.stoppedAt != null)
    const graceSeconds = parseInt(process.env.DIAG_CLOSE_GRACE_SECONDS || '90', 10)

    if (!allStopped && options.checkGrace && !options.forceClose) {
        const oldestMissingJoined = Math.min(...runState.participants.filter(p => !p.stoppedAt).map(p => new Date(p.joinedAt).getTime()))
        const elapsedSec = (Date.now() - oldestMissingJoined) / 1000
        if (elapsedSec < graceSeconds) {
            return { waitingGrace: true, remainingSec: Math.ceil(graceSeconds - elapsedSec) }
        }
    }

    for (const p of runState.participants) {
        if (!p.stoppedAt) {
            await prisma.diagnosticParticipant.update({
                where: { id: p.id },
                data: { stoppedAt: new Date() }
            }).catch(() => {})
            p.stoppedAt = new Date()
            p.isIncomplete = true
        }
    }

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

    if (updateCount.count === 0 && runState.status !== 'ACTIVE') {
        
        return false
    }

    const exportData = await buildRunExportData(runId)
    if (!exportData) return false

    const { run, convoyClean, participantSummaries, runSummaryMd, files } = exportData

    const zipBuffer = await buildZipBuffer(files)
    const fileSizeMb = (zipBuffer.length / (1024 * 1024)).toFixed(2)

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

    if (run.batches.length === 0) {
        console.log(`[Diagnostic] Run ${run.id} has 0 batches. Skipping email dispatch.`)
        return true
    }

    const emailTo = process.env.DIAG_EMAIL_TO || 'code4changeteam@gmail.com'
    const isSmtpConfigured = (process.env.SMTP_HOST && process.env.SMTP_USER) || process.env.NODE_ENV === 'test' || options.mailer != null

    if (!isSmtpConfigured) {
        console.warn('[Diagnostic Email] WARNING: SMTP is not configured (SMTP_HOST/SMTP_USER missing). Leaving run exportable without email.')
        return true
    }

    if (emailTo) {
        
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
            const mailer = options.mailer || createMailer()
            const excelFileName = `Convoy_${convoyClean}_Diagnostics_Test${run.testNumber}.xlsx`
            const excelBuffer = options.excelBuffer || await (module.exports.buildExcelBuffer || buildExcelBuffer)(exportData)

            const fromName = process.env.SMTP_FROM_NAME || 'RiderVoice Diagnostics'
            const fromHeader = process.env.SMTP_FROM || (process.env.SMTP_USER ? `"${fromName}" <${process.env.SMTP_USER}>` : `"${fromName}" <diagnostics@ridervoice.com>`)

            const mailOptions = {
                from: fromHeader,
                to: emailTo,
                subject: `[Diagnostic Report] ${run.room?.name || 'Convoy'} - Test ${run.testNumber} (Excel Spreadsheet Attached)`,
                text: runSummaryMd + `\n\nConvoy diagnostic Excel spreadsheet attached (${excelFileName}). Distinguishes each rider's telemetry, cross-rider audio quality, and timeline events.`,
                attachments: []
            }

            if (excelBuffer.length <= 20 * 1024 * 1024) {
                mailOptions.attachments.push({
                    filename: excelFileName,
                    content: excelBuffer,
                    contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
                })
            } else {
                mailOptions.text += `\n\n[Notice: Excel report file size (${(excelBuffer.length / (1024 * 1024)).toFixed(1)} MB) exceeded the 20MB email threshold. Summary sent without attachment.]`
            }

            try {
                await mailer.sendMail(mailOptions)
                console.log(`[Diagnostic] Emailed Excel report to ${emailTo} (size: ${(excelBuffer.length / 1024).toFixed(1)} KB)`)
            } catch (emailErr) {
                
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

async function checkIdleDiagnosticRuns() {
    try {
        const timeoutMs = parseInt(process.env.DIAG_IDLE_TIMEOUT_MS || '600000', 10) 
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
    finalizeAndExportRun: closeAndExportRun,
    buildExcelBuffer,
    checkIdleDiagnosticRuns,
    cleanOldDiagnosticRuns,
    buildRunExportData,
    buildZipBuffer
}
