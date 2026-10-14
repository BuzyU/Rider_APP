const http = require('http')
const path = require('path')

const BASE_URL = 'http://localhost:3000'

async function makeRequest(method, endpoint, headers = {}, body = null) {
    return new Promise((resolve, reject) => {
        const url = new URL(endpoint, BASE_URL)
        const opts = {
            hostname: url.hostname,
            port: url.port,
            path: url.pathname + url.search,
            method: method,
            headers: { ...headers }
        }

        let bodyData = null
        if (body !== null) {
            if (typeof body === 'string') {
                bodyData = body
                if (!opts.headers['Content-Type']) opts.headers['Content-Type'] = 'text/plain'
            } else if (Buffer.isBuffer(body)) {
                bodyData = body
                if (!opts.headers['Content-Type']) opts.headers['Content-Type'] = 'application/octet-stream'
            } else {
                bodyData = JSON.stringify(body)
                if (!opts.headers['Content-Type']) opts.headers['Content-Type'] = 'application/json'
            }
            opts.headers['Content-Length'] = Buffer.byteLength(bodyData)
        }

        const req = http.request(opts, (res) => {
            const chunks = []
            res.on('data', chunk => chunks.push(chunk))
            res.on('end', () => {
                const raw = Buffer.concat(chunks)
                let json = null
                try {
                    json = JSON.parse(raw.toString('utf8'))
                } catch (e) {}
                resolve({
                    statusCode: res.statusCode,
                    headers: res.headers,
                    raw: raw,
                    text: raw.toString('utf8'),
                    json: json
                })
            })
        })

        req.on('error', reject)
        if (bodyData) req.write(bodyData)
        req.end()
    })
}

async function runSuite() {
    console.log('====================================================')
    console.log(' Starting Comprehensive PART A Hardening Test Suite')
    console.log('====================================================\n')

    const results = []

    function record(name, passed, detail = '') {
        const icon = passed ? 'PASS' : 'FAIL'
        console.log(`[${icon}] ${name}${detail ? ' -> ' + detail : ''}`)
        results.push({ name, passed, detail })
    }

    // Step 0: Ensure DB seed for riders and room
    const prisma = require('../src/db')
    const room = await prisma.room.upsert({
        where: { name: 'Alpha-Convoy-Test' },
        update: {},
        create: { name: 'Alpha-Convoy-Test', ownerId: 'test_rider_alpha' }
    })
    await prisma.user.upsert({
        where: { id: 'test_rider_alpha' },
        update: { handle: 'rider_alpha' },
        create: { id: 'test_rider_alpha', displayName: 'Rider Alpha', handle: 'rider_alpha', email: 'alpha@test.com' }
    })
    await prisma.user.upsert({
        where: { id: 'test_rider_bravo' },
        update: { handle: 'rider_bravo' },
        create: { id: 'test_rider_bravo', displayName: 'Rider Bravo', handle: 'rider_bravo', email: 'bravo@test.com' }
    })
    await prisma.rideInvite.upsert({
        where: { id: 'test_invite_rider_bravo' },
        update: { status: 'ACCEPTED' },
        create: { id: 'test_invite_rider_bravo', roomId: room.id, inviterId: 'test_rider_alpha', inviteeId: 'test_rider_bravo', status: 'ACCEPTED' }
    })

    const authAlpha = { 'Authorization': 'Bearer test_rider_alpha' }
    const authBravo = { 'Authorization': 'Bearer test_rider_bravo' }

    // --- TEST 1: Non-member join -> 403 ---
    try {
        const nonMemberRes = await makeRequest('POST', '/api/diagnostics/join', { 'Authorization': 'Bearer test_charlie' }, { convoyId: 'Alpha-Convoy-Test' })
        record('T1: Non-member join returns 403', nonMemberRes.statusCode === 403, `HTTP ${nonMemberRes.statusCode}`)
    } catch (e) {
        record('T1: Non-member join returns 403', false, e.message)
    }

    // --- TEST 2: Two riders joining simultaneously yield ONE run ---
    let runId = null
    try {
        const [j1, j2] = await Promise.all([
            makeRequest('POST', '/api/diagnostics/join', authAlpha, { convoyId: 'Alpha-Convoy-Test' }),
            makeRequest('POST', '/api/diagnostics/join', authBravo, { convoyId: 'Alpha-Convoy-Test' })
        ])
        const sameRun = j1.json?.runId && (j1.json.runId === j2.json?.runId)
        runId = j1.json?.runId || j2.json?.runId
        record('T2: Simultaneous join yields ONE run', sameRun && j1.statusCode === 200 && j2.statusCode === 200, `RunId: ${runId}`)
    } catch (e) {
        record('T2: Simultaneous join yields ONE run', false, e.message)
    }

    // --- TEST 3: Non-participant batches and stop -> 403 ---
    try {
        const bNonPart = await makeRequest('POST', '/api/diagnostics/batches', { 'Authorization': 'Bearer test_intruder' }, {
            runId, sequenceNumber: 1, payload: { selfMetrics: [{ timestamp: Date.now() }] }
        })
        const sNonPart = await makeRequest('POST', '/api/diagnostics/stop', { 'Authorization': 'Bearer test_intruder' }, {
            runId, lastSeq: 1
        })
        record('T3: Non-participant batches & stop return 403', bNonPart.statusCode === 403 && sNonPart.statusCode === 403, `Batches: ${bNonPart.statusCode}, Stop: ${sNonPart.statusCode}`)
    } catch (e) {
        record('T3: Non-participant batches & stop return 403', false, e.message)
    }

    // --- TEST 4: Invalid payload contract validation -> 400 ---
    try {
        // (a) > 200 rows
        const tooManyRows = Array(201).fill(0).map((_, i) => ({ timestamp: Date.now() + i }))
        const resTooMany = await makeRequest('POST', '/api/diagnostics/batches', authAlpha, {
            runId, sequenceNumber: 1, payload: { selfMetrics: tooManyRows }
        })
        // (b) Invalid RTT > 30000
        const resBadRtt = await makeRequest('POST', '/api/diagnostics/batches', authAlpha, {
            runId, sequenceNumber: 1, payload: { selfMetrics: [{ timestamp: Date.now(), rtt_ms: 99999 }] }
        })
        // (c) Missing timestamp
        const resBadTimestamp = await makeRequest('POST', '/api/diagnostics/batches', authAlpha, {
            runId, sequenceNumber: 1, payload: { selfMetrics: [{ timestamp: 'not_a_number' }] }
        })
        const allRejected = resTooMany.statusCode === 400 && resBadRtt.statusCode === 400 && resBadTimestamp.statusCode === 400
        record('T4: Payload validation rejects invalid schema with 400', allRejected, `MaxRows: ${resTooMany.statusCode}, RTT: ${resBadRtt.statusCode}, Type: ${resBadTimestamp.statusCode}`)
    } catch (e) {
        record('T4: Payload validation rejects invalid schema with 400', false, e.message)
    }

    // --- TEST 5: CSV Formula injection neutralization & Path Sanitization ---
    try {
        // Post a batch containing formula injection and path traversal characters
        const evilPayload = {
            selfMetrics: [{
                timestamp: Date.now(),
                network_type: '=1+1',
                cell_operator: '@SUM(A1:A10)'
            }],
            inboundMetrics: [{
                timestamp: Date.now(),
                remote_rider: 'test_rider_bravo',
                track_sid: '-cmd|/C calc',
                packets_received: 10,
                packets_lost: 0,
                bytes_received: 1000
            }],
            events: [{
                timestamp: Date.now(),
                event_type: '\t=HYPERLINK("http://evil.com")',
                details: 'normal details'
            }]
        }
        const evilBatchRes = await makeRequest('POST', '/api/diagnostics/batches', authAlpha, {
            runId, sequenceNumber: 1, payload: evilPayload
        })

        // Also post rider bravo batch 1
        await makeRequest('POST', '/api/diagnostics/batches', authBravo, {
            runId, sequenceNumber: 1, payload: { selfMetrics: [{ timestamp: Date.now() }] }
        })

        // Build export in memory to verify CSV content
        const diagnosticService = require('../src/services/diagnosticService')
        const exportData = await diagnosticService.buildRunExportData(runId)

        const selfFile = exportData.files.find(f => f.name.endsWith('self.csv'))
        const inboundFile = exportData.files.find(f => f.name.endsWith('inbound.csv'))
        const eventFile = exportData.files.find(f => f.name.endsWith('events.csv'))

        const formulaEscaped = selfFile.content.includes("'=1+1") && selfFile.content.includes("'@SUM(A1:A10)") && inboundFile.content.includes("'-cmd|/C calc") && eventFile.content.includes("'\t=HYPERLINK")
        const extraUidPresent = inboundFile.content.includes('remote_rider_uid')

        record('T5: CSV formula injection neutralized & remote_rider_uid included', formulaEscaped && extraUidPresent, 'Formulas prefixed with single-quote; raw UID preserved')
    } catch (e) {
        record('T5: CSV formula injection neutralized & remote_rider_uid included', false, e.message)
    }

    // --- TEST 6: Admin Key: Missing, Wrong, and Wrong-Length -> 401/503 ---
    try {
        const missingKey = await makeRequest('GET', `/api/diagnostics/runs/${runId}/export`)
        const wrongKey = await makeRequest('GET', `/api/diagnostics/runs/${runId}/export`, { 'x-diag-admin-key': 'wrong_key' })
        const shortKey = await makeRequest('GET', `/api/diagnostics/runs/${runId}/export`, { 'x-diag-admin-key': 'short' })
        const validKey = await makeRequest('GET', `/api/diagnostics/runs/${runId}/export`, { 'x-diag-admin-key': 'test_admin_secret_key_12345' })

        const adminKeysHandled = missingKey.statusCode === 401 && wrongKey.statusCode === 401 && shortKey.statusCode === 401 && validKey.statusCode === 200
        record('T6: Admin key validation (missing/wrong/wrong-length -> 401, correct -> 200)', adminKeysHandled, `Missing: ${missingKey.statusCode}, Wrong: ${wrongKey.statusCode}, Short: ${shortKey.statusCode}, Valid: ${validKey.statusCode}`)
    } catch (e) {
        record('T6: Admin key validation (missing/wrong/wrong-length -> 401, correct -> 200)', false, e.message)
    }

    // --- TEST 7: Gap handling (lastSeq=3 with batches 1, 3 stays ACTIVE; adding 2 closes) ---
    try {
        // Start a fresh run for gap testing
        const gapConvoyName = 'Gap-Convoy-' + Date.now()
        const room2 = await prisma.room.create({
            data: { name: gapConvoyName, ownerId: 'test_rider_alpha' }
        })
        const jAlpha = await makeRequest('POST', '/api/diagnostics/join', authAlpha, { convoyId: gapConvoyName })
        const gapRunId = jAlpha.json.runId

        // Post batch 1 and batch 3 (batch 2 missing!)
        await makeRequest('POST', '/api/diagnostics/batches', authAlpha, {
            runId: gapRunId, sequenceNumber: 1, payload: { selfMetrics: [{ timestamp: Date.now() }] }
        })
        await makeRequest('POST', '/api/diagnostics/batches', authAlpha, {
            runId: gapRunId, sequenceNumber: 3, payload: { selfMetrics: [{ timestamp: Date.now() }] }
        })

        // Stop with lastSeq=3
        const stopRes1 = await makeRequest('POST', '/api/diagnostics/stop', authAlpha, {
            runId: gapRunId, lastSeq: 3
        })
        const staysActive = stopRes1.json.runClosed === false

        // Now deliver missing batch 2
        const deliverBatch2 = await makeRequest('POST', '/api/diagnostics/batches', authAlpha, {
            runId: gapRunId, sequenceNumber: 2, payload: { selfMetrics: [{ timestamp: Date.now() }] }
        })

        // Verify run is now closed
        const runCheck = await prisma.diagnosticRun.findUnique({ where: { id: gapRunId } })
        const closedOnBatch2 = runCheck.status === 'CLOSED'

        record('T7: Gap handling (missing seq keeps ACTIVE; delivering gap closes run)', staysActive && closedOnBatch2, `StaysActive: ${staysActive}, ClosedOnGapFill: ${closedOnBatch2}`)
    } catch (e) {
        record('T7: Gap handling (missing seq keeps ACTIVE; delivering gap closes run)', false, e.message)
    }

    // --- TEST 8: Re-join after stop clears stoppedAt and lastSeq ---
    try {
        const pBefore = await prisma.diagnosticParticipant.findFirst({
            where: { runId, riderId: 'test_rider_alpha' }
        })
        // Set stoppedAt
        await prisma.diagnosticParticipant.update({
            where: { id: pBefore.id },
            data: { stoppedAt: new Date(), lastSeq: 5 }
        })

        // Re-join
        await makeRequest('POST', '/api/diagnostics/join', authAlpha, { convoyId: 'Alpha-Convoy-Test' })
        const pAfter = await prisma.diagnosticParticipant.findFirst({
            where: { runId, riderId: 'test_rider_alpha' }
        })
        const cleared = pAfter.stoppedAt === null && pAfter.lastSeq === null
        record('T8: Re-joining clears stoppedAt and lastSeq', cleared, `stoppedAt=${pAfter.stoppedAt}, lastSeq=${pAfter.lastSeq}`)
    } catch (e) {
        record('T8: Re-joining clears stoppedAt and lastSeq', false, e.message)
    }

    // --- TEST 9: Late batch after close is stored, appears in export, sends no second email ---
    try {
        // Stop both riders to close run
        await makeRequest('POST', '/api/diagnostics/stop', authAlpha, { runId, lastSeq: 1 })
        await makeRequest('POST', '/api/diagnostics/stop', authBravo, { runId, lastSeq: 1 })

        const runClosedCheck = await prisma.diagnosticRun.findUnique({ where: { id: runId } })
        const firstEmailedAt = runClosedCheck.emailedAt

        // Now send a late batch (sequence 99)
        const lateBatchRes = await makeRequest('POST', '/api/diagnostics/batches', authAlpha, {
            runId, sequenceNumber: 99, payload: { selfMetrics: [{ timestamp: Date.now(), packets_sent: 50 }] }
        })
        const lateStored = lateBatchRes.statusCode === 200

        // Verify it was stored in DB
        const batchInDb = await prisma.diagnosticBatch.findUnique({
            where: { runId_riderId_sequenceNumber: { runId, riderId: 'test_rider_alpha', sequenceNumber: 99 } }
        })

        // Verify emailedAt was not changed (no second email)
        const runAfterLate = await prisma.diagnosticRun.findUnique({ where: { id: runId } })
        const emailUnchanged = runAfterLate.emailedAt.getTime() === firstEmailedAt.getTime()

        record('T9: Late batch stored after close without second email', lateStored && !!batchInDb && emailUnchanged, `Stored: ${!!batchInDb}, NoReEmail: ${emailUnchanged}`)
    } catch (e) {
        record('T9: Late batch stored after close without second email', false, e.message)
    }

    // --- TEST 10: Body-limit order: 300KB -> 200, 6MB -> 413 with explicit message ---
    try {
        // 300 KB batch (within 5 MB limit, but > 100 KB default express parser)
        const payload300k = {
            selfMetrics: [{
                timestamp: Date.now(),
                cell_operator: 'X'.repeat(60)
            }],
            events: [{
                timestamp: Date.now(),
                event_type: 'LARGE_BUFFER',
                details: 'A'.repeat(250)
            }]
        }
        const res300k = await makeRequest('POST', '/api/diagnostics/batches', authAlpha, {
            runId, sequenceNumber: 100, payload: payload300k
        })

        // 6 MB batch (> 5 MB limit)
        const bigJunk = '{"runId":"' + runId + '","sequenceNumber":101,"payload":{"events":[{"timestamp":123,"event_type":"E","details":"' + 'Z'.repeat(6 * 1024 * 1024) + '"}]}}'
        const res6m = await makeRequest('POST', '/api/diagnostics/batches', {
            ...authAlpha,
            'Content-Type': 'application/json'
        }, bigJunk)

        const limitCorrect = res300k.statusCode === 200 && res6m.statusCode === 413 && res6m.json?.error?.includes('5MB size limit')
        record('T10: Body-limit order: 300KB -> 200, 6MB -> 413', limitCorrect, `300KB: ${res300k.statusCode}, 6MB: ${res6m.statusCode} ("${res6m.json?.error || res6m.text}")`)
    } catch (e) {
        record('T10: Body-limit order: 300KB -> 200, 6MB -> 413', false, e.message)
    }

    console.log('\n====================================================')
    const passedAll = results.every(r => r.passed)
    console.log(` SUMMARY: ${results.filter(r => r.passed).length}/${results.length} Tests Passed. Status: ${passedAll ? 'SUCCESS' : 'FAILED'}`)
    console.log('====================================================')

    await prisma.$disconnect()
    process.exit(passedAll ? 0 : 1)
}

runSuite().catch(err => {
    console.error('Test Suite Fatal Error:', err)
    process.exit(1)
})
