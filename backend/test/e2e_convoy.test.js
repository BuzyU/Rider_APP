const { describe, it } = require('node:test')
const assert = require('node:assert/strict')
const crypto = require('crypto')
const fs = require('fs')
const path = require('path')
const ExcelJS = require('exceljs')
const EmbeddedPostgresPkg = require('embedded-postgres')
const EmbeddedPostgres = EmbeddedPostgresPkg.default || EmbeddedPostgresPkg
const { Client } = require('pg')
const express = require('express')

describe('End-to-End Convoy Diagnostics Integration Test', () => {
    it('creates convoy, joins 3 riders (one opted out), ingests telemetry, ends ride, sends email with multi-sheet xlsx evidence', { timeout: 90000 }, async () => {
        const testPort = 5438
        const dbName = 'ridervoice_e2e'
        const testDataDir = path.join(__dirname, '../.pg_data_e2e_test')

        const pg = new EmbeddedPostgres({
            databaseDir: testDataDir,
            user: 'postgres',
            password: 'password',
            port: testPort,
            persistent: false
        })

        let server
        try {
            await pg.initialise()
            await pg.start()
            await pg.createDatabase(dbName)

            const dbUrl = `postgresql://postgres:password@localhost:${testPort}/${dbName}`
            process.env.DATABASE_URL = dbUrl
            process.env.LIVEKIT_API_KEY = 'e2e_fake_livekit_key'
            process.env.LIVEKIT_API_SECRET = 'e2e_fake_livekit_secret_123456789012'
            process.env.LIVEKIT_URL = 'http://localhost:7880'
            process.env.NODE_ENV = 'test'
            delete process.env.DIAG_EMAIL_TO

            // Setup DB tables
            async function runSqlFile(filePath) {
                const client = new Client({ host: 'localhost', port: testPort, user: 'postgres', password: 'password', database: dbName })
                await client.connect()
                const sql = fs.readFileSync(filePath, 'utf8')
                await client.query(sql)
                await client.end()
            }

            await runSqlFile(path.join(__dirname, '../supabase_schema.sql'))
            const client = new Client({ host: 'localhost', port: testPort, user: 'postgres', password: 'password', database: dbName })
            await client.connect()
            await client.query(`
                CREATE SCHEMA IF NOT EXISTS auth;
                CREATE OR REPLACE FUNCTION auth.role() RETURNS text AS $$ SELECT 'service_role'::text; $$ LANGUAGE sql;
                ALTER TABLE "admin_settings" ALTER COLUMN "updated_at" DROP DEFAULT;
            `)
            await client.end()

            await runSqlFile(path.join(__dirname, '../supabase_tester_applications.sql'))
            const client2 = new Client({ host: 'localhost', port: testPort, user: 'postgres', password: 'password', database: dbName })
            await client2.connect()
            await client2.query(`
                ALTER TABLE "tester_applications" ALTER COLUMN "id" DROP DEFAULT;
                ALTER TABLE "tester_applications" ALTER COLUMN "apk_version" SET DEFAULT 'v0.0.3.8';
                ALTER TABLE "tester_applications" ALTER COLUMN "updated_at" DROP DEFAULT;
                ALTER TABLE "waitlist" ALTER COLUMN "id" DROP DEFAULT;
            `)
            await client2.end()

            await runSqlFile(path.join(__dirname, '../prisma/migrations/20261014_add_network_diagnostics/migration.sql'))
            await runSqlFile(path.join(__dirname, '../prisma/migrations/20261015_add_room_diagnostics_and_started_at/migration.sql'))

            // Instantiate prisma connection to the e2e test database (process.env.DATABASE_URL set above)
            const prisma = require('../src/db')
            await prisma.$connect()

            // Seed 3 riders
            const hostUser = await prisma.user.upsert({
                where: { id: 'test_rider_host' },
                update: {},
                create: { id: 'test_rider_host', handle: 'HostCaptain', displayName: 'Host Captain', email: 'host@test.com' }
            })
            const activeUser = await prisma.user.upsert({
                where: { id: 'test_rider_active' },
                update: {},
                create: { id: 'test_rider_active', handle: 'ActiveRider', displayName: 'Active Rider', email: 'active@test.com' }
            })
            const optedOutUser = await prisma.user.upsert({
                where: { id: 'test_rider_optedout' },
                update: {},
                create: { id: 'test_rider_optedout', handle: 'OptedOutRider', displayName: 'Opted Out Rider', email: 'optout@test.com' }
            })

            // Mock mailer capture
            let capturedEmail = null
            const mockMailer = {
                sendMail: async (mailOpts) => {
                    capturedEmail = mailOpts
                    return { messageId: 'e2e_mock_msg_123' }
                }
            }

            // Create Express app
            const app = express()
            app.use(express.json({ limit: '10mb' }))

            const authMiddleware = require('../src/middleware/authMiddleware')
            const lobbyRoutes = require('../src/routes/lobbyRoutes')
            const diagnosticRoutes = require('../src/routes/diagnosticRoutes')
            const diagnosticService = require('../src/services/diagnosticService')

            app.use('/api/lobby', authMiddleware, lobbyRoutes)
            app.use('/api/diagnostics', diagnosticRoutes)

            server = app.listen(0)
            await new Promise(r => server.once('listening', r))
            const port = server.address().port
            const baseUrl = `http://127.0.0.1:${port}`

            const authHeaders = (u) => ({
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${u.id}`
            })

            // 1. Host creates convoy with diagnosticsEnabled
            const createRes = await fetch(`${baseUrl}/api/lobby/create`, {
                method: 'POST',
                headers: authHeaders(hostUser),
                body: JSON.stringify({ convoyName: 'E2E-Grand-Tour', diagnosticsEnabled: true })
            })
            assert.strictEqual(createRes.status, 201)
            const createdConvoy = await createRes.json()
            assert.strictEqual(createdConvoy.diagnosticsEnabled, true)
            const convoyId = createdConvoy.roomId

            // Create accepted invites for active and opted-out riders
            await prisma.rideInvite.createMany({
                data: [
                    { id: crypto.randomUUID(), roomId: convoyId, inviterId: hostUser.id, inviteeId: activeUser.id, status: 'ACCEPTED' },
                    { id: crypto.randomUUID(), roomId: convoyId, inviterId: hostUser.id, inviteeId: optedOutUser.id, status: 'ACCEPTED' }
                ]
            })

            // 2. Riders join diagnostic run
            // Rider 1 (Host) joins/confirms diagnostic run
            const joinHostRes = await fetch(`${baseUrl}/api/diagnostics/join`, {
                method: 'POST',
                headers: authHeaders(hostUser),
                body: JSON.stringify({ convoyId })
            })
            const hostJoinText = await joinHostRes.text()
            assert.strictEqual(joinHostRes.status, 200, `Host join failed with status ${joinHostRes.status}: ${hostJoinText}`)
            const hostJoinData = JSON.parse(hostJoinText)
            const runId = hostJoinData.runId
            assert.ok(runId)

            // Rider 2 (Active rider) joins
            const joinActiveRes = await fetch(`${baseUrl}/api/diagnostics/join`, {
                method: 'POST',
                headers: authHeaders(activeUser),
                body: JSON.stringify({ convoyId })
            })
            const activeJoinText = await joinActiveRes.text()
            assert.strictEqual(joinActiveRes.status, 200, `Active join failed with status ${joinActiveRes.status}: ${activeJoinText}`)

            // Rider 3 (Opted out) joins
            const joinOptOutRes = await fetch(`${baseUrl}/api/diagnostics/join`, {
                method: 'POST',
                headers: authHeaders(optedOutUser),
                body: JSON.stringify({ convoyId })
            })
            const optOutJoinText = await joinOptOutRes.text()
            assert.strictEqual(joinOptOutRes.status, 200, `Optout join failed with status ${joinOptOutRes.status}: ${optOutJoinText}`)

            // Mark participant 3 as opted-out
            await prisma.diagnosticParticipant.update({
                where: { runId_riderId: { runId, riderId: optedOutUser.id } },
                data: { stoppedAt: new Date() }
            })

            // 3. Post telemetry batches for Rider 1 and Rider 2
            // Rider 1 (Host) posts batch 1
            const hostBatch = {
                runId,
                sequenceNumber: 1,
                payload: {
                    deviceInfo: { app_version: 'v0.0.3.11', os_version: 'Android 14', device_model: 'Pixel 8' },
                    selfMetrics: [
                        {
                            timestamp: Math.floor(Date.now() / 1000),
                            deviceEpochMs: Date.now(),
                            elapsedRealtimeMs: 120000,
                            rtt_ms: 38,
                            packets_sent: 50,
                            packets_received: 50,
                            packets_lost: 0,
                            retransmits: 0,
                            bytes_sent: 5000,
                            bytes_received: 5000,
                            bitrate_kbps: 48,
                            jitter_ms: 2.1,
                            network_type: 'WIFI',
                            cell_operator: 'None',
                            signal_strength_dbm: -60,
                            battery_level: 95,
                            thermal_status: 'NORMAL',
                            is_foreground: true
                        }
                    ],
                    inboundMetrics: [],
                    events: [
                        { timestamp: Math.floor(Date.now() / 1000), deviceEpochMs: Date.now(), elapsedRealtimeMs: 120000, event_type: 'AUDIO_DEVICE_ROUTED', details: 'Bluetooth SCO connected' }
                    ]
                }
            }
            const hostBatchRes = await fetch(`${baseUrl}/api/diagnostics/batches`, {
                method: 'POST',
                headers: authHeaders(hostUser),
                body: JSON.stringify(hostBatch)
            })
            const hostBatchText = await hostBatchRes.text()
            assert.strictEqual(hostBatchRes.status, 200, `hostBatchRes expected 200 but got ${hostBatchRes.status}: ${hostBatchText}`)

            // Rider 2 (Active) posts batch 1
            const activeBatch = {
                runId,
                sequenceNumber: 1,
                payload: {
                    deviceInfo: { app_version: 'v0.0.3.11', os_version: 'Android 13', device_model: 'Galaxy S23' },
                    selfMetrics: [
                        {
                            timestamp: Math.floor(Date.now() / 1000),
                            deviceEpochMs: Date.now(),
                            elapsedRealtimeMs: 90000,
                            rtt_ms: 54,
                            packets_sent: 45,
                            packets_received: 44,
                            packets_lost: 1,
                            retransmits: 0,
                            bytes_sent: 4500,
                            bytes_received: 4400,
                            bitrate_kbps: 40,
                            jitter_ms: 3.8,
                            network_type: 'CELLULAR',
                            cell_operator: 'T-Mobile',
                            signal_strength_dbm: -85,
                            battery_level: 70,
                            thermal_status: 'MODERATE',
                            is_foreground: true
                        }
                    ],
                    inboundMetrics: [
                        {
                            timestamp: Math.floor(Date.now() / 1000),
                            deviceEpochMs: Date.now(),
                            elapsedRealtimeMs: 90000,
                            remote_rider: hostUser.id,
                            packets_received: 50,
                            packets_lost: 0,
                            interval_loss_pct: 0,
                            jitter_ms: 2.5,
                            jitter_buffer_delay: 20,
                            concealed_samples: 0,
                            concealment_events: 0,
                            audio_level: 0.8,
                            bytes_received: 5000
                        }
                    ],
                    events: []
                }
            }
            const activeBatchRes = await fetch(`${baseUrl}/api/diagnostics/batches`, {
                method: 'POST',
                headers: authHeaders(activeUser),
                body: JSON.stringify(activeBatch)
            })
            const activeBatchText = await activeBatchRes.text()
            assert.strictEqual(activeBatchRes.status, 200, `activeBatchRes expected 200 but got ${activeBatchRes.status}: ${activeBatchText}`)

            // 4. Host marks finished, Rider 2 left without final batch (incomplete)
            await fetch(`${baseUrl}/api/diagnostics/stop`, {
                method: 'POST',
                headers: authHeaders(hostUser),
                body: JSON.stringify({ runId, lastSeq: 1 })
            })

            // 5. Host ends the ride -> closeAndExportRun with mock mailer
            await diagnosticService.closeAndExportRun(runId, { forceClose: true, mailer: mockMailer })

            // 6. Assert captured email
            assert.ok(capturedEmail, 'Diagnostic email must be captured')
            assert.strictEqual(capturedEmail.to, process.env.DIAG_EMAIL_TO || 'code4changeteam@gmail.com')
            assert.strictEqual(capturedEmail.attachments.length, 1)
            const attachment = capturedEmail.attachments[0]
            assert.ok(attachment.filename.endsWith('.xlsx'))
            assert.ok(Buffer.isBuffer(attachment.content))

            // 7. Verify Workbook sheets and contents
            const wb = new ExcelJS.Workbook()
            await wb.xlsx.load(attachment.content)

            const sheetNames = wb.worksheets.map(w => w.name)
            assert.ok(sheetNames.includes('Summary'), 'Workbook must include Summary sheet')
            assert.ok(sheetNames.includes('HostCaptain'), 'Workbook must include HostCaptain sheet')
            assert.ok(sheetNames.includes('ActiveRider'), 'Workbook must include ActiveRider sheet')
            assert.ok(sheetNames.includes('OptedOutRider'), 'Workbook must include OptedOutRider sheet')

            // Verify opted-out rider shows as OPTED_OUT
            const optedOutSheet = wb.getWorksheet('OptedOutRider')
            assert.ok(optedOutSheet)
            const statusCell = optedOutSheet.getRow(2).getCell(1).value
            assert.ok(String(statusCell).includes('OPTED_OUT'), 'Opted out sheet must indicate OPTED_OUT')

            // 8. Save evidence file locally and to brain directory
            const exportDir = path.join(__dirname, '../exports')
            if (!fs.existsSync(exportDir)) fs.mkdirSync(exportDir, { recursive: true })
            const evidencePath = path.join(exportDir, 'e2e_convoy_evidence.xlsx')
            fs.writeFileSync(evidencePath, attachment.content)
            console.log(`[E2E] Saved generated evidence workbook to: ${evidencePath} (${attachment.content.length} bytes)`)

            const brainEvidencePath = 'C:\\Users\\umerz\\.gemini\\antigravity-ide\\brain\\cd220b9f-ef25-4a78-9ed4-d5c4c159a6be\\e2e_convoy_evidence.xlsx'
            try {
                if (fs.existsSync(path.dirname(brainEvidencePath))) {
                    fs.writeFileSync(brainEvidencePath, attachment.content)
                    console.log(`[E2E] Saved artifact evidence workbook to: ${brainEvidencePath}`)
                }
            } catch (e) {
                // Ignore in non-local environments
            }

        } finally {
            const prisma = require('../src/db')
            try { await prisma.$disconnect() } catch (e) {}
            if (server) await new Promise(r => server.close(r))
            try {
                await pg.stop()
            } catch (e) {}
        }
    })
})
