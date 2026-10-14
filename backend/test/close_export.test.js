const { describe, it, before, after, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const prisma = require('../src/db')
const diagnosticService = require('../src/services/diagnosticService')

describe('Gate 4: Close and Export Run', () => {
    const originals = {}

    before(() => {
        originals.diagnosticRunFindUnique = prisma.diagnosticRun.findUnique
        originals.diagnosticRunUpdateMany = prisma.diagnosticRun.updateMany
        originals.diagnosticRunUpdate = prisma.diagnosticRun.update
        originals.diagnosticParticipantUpdate = prisma.diagnosticParticipant.update
        originals.userFindUnique = prisma.user.findUnique
    })

    after(() => {
        prisma.diagnosticRun.findUnique = originals.diagnosticRunFindUnique
        prisma.diagnosticRun.updateMany = originals.diagnosticRunUpdateMany
        prisma.diagnosticRun.update = originals.diagnosticRunUpdate
        prisma.diagnosticParticipant.update = originals.diagnosticParticipantUpdate
        prisma.user.findUnique = originals.userFindUnique
    })

    beforeEach(() => {
        delete process.env.DIAG_EMAIL_TO
        delete process.env.SMTP_HOST
        delete process.env.SMTP_USER
        delete process.env.SMTP_PASS
        process.env.DIAG_CLOSE_GRACE_SECONDS = '10'
        process.env.NODE_ENV = 'test'
    })

    it('host end with all riders stopped closes immediately without grace delay', async () => {
        const mockRun = {
            id: 'run_all_stopped',
            convoyId: 'convoy_1',
            testNumber: 1,
            status: 'ACTIVE',
            createdAt: new Date('2026-10-15T10:00:00Z'),
            room: { name: 'Full Convoy' },
            participants: [
                { id: 'p1', riderId: 'r1', handle: 'Rider1', joinedAt: new Date('2026-10-15T10:00:00Z'), stoppedAt: new Date('2026-10-15T10:15:00Z') },
                { id: 'p2', riderId: 'r2', handle: 'Rider2', joinedAt: new Date('2026-10-15T10:00:00Z'), stoppedAt: new Date('2026-10-15T10:15:00Z') }
            ],
            batches: [{ id: 'b1', riderId: 'r1', payload: { selfMetrics: [], inboundMetrics: [], events: [] } }]
        }

        prisma.diagnosticRun.findUnique = async () => mockRun
        let updatedStatus = null
        prisma.diagnosticRun.updateMany = async ({ where, data }) => {
            if (data && data.status === 'CLOSED') {
                updatedStatus = data.status
                return { count: 1 }
            }
            if (data && data.emailedAt) {
                return { count: 1 }
            }
            return { count: 0 }
        }
        prisma.diagnosticRun.update = async () => ({})
        prisma.user.findUnique = async ({ where }) => ({ id: where.id, handle: where.id })

        const mockMailer = {
            sendMail: async () => ({ messageId: 'm1' })
        }

        const result = await diagnosticService.closeAndExportRun('run_all_stopped', { checkGrace: true, mailer: mockMailer })
        assert.strictEqual(result, true, 'Must close immediately without waiting for grace')
        assert.strictEqual(updatedStatus, 'CLOSED')
    })

    it('with a missing rider it closes after DIAG_CLOSE_GRACE_SECONDS and marks that rider incomplete', async () => {
        process.env.DIAG_CLOSE_GRACE_SECONDS = '5'
        const recentJoinedAt = new Date(Date.now() - 1000) // 1 second ago (< 5s grace)

        const mockRunWithMissing = {
            id: 'run_missing_rider',
            convoyId: 'convoy_2',
            testNumber: 2,
            status: 'ACTIVE',
            createdAt: new Date('2026-10-15T10:00:00Z'),
            room: { name: 'Grace Convoy' },
            participants: [
                { id: 'p1', riderId: 'r1', handle: 'Rider1', joinedAt: new Date('2026-10-15T10:00:00Z'), stoppedAt: new Date('2026-10-15T10:10:00Z') },
                { id: 'p2', riderId: 'r2', handle: 'Rider2', joinedAt: recentJoinedAt, stoppedAt: null }
            ],
            batches: [{ id: 'b1', riderId: 'r1', payload: { selfMetrics: [], inboundMetrics: [], events: [] } }]
        }

        prisma.diagnosticRun.findUnique = async () => mockRunWithMissing

        // 1. When called within grace period with checkGrace=true, it returns waitingGrace
        const graceCheck = await diagnosticService.closeAndExportRun('run_missing_rider', { checkGrace: true })
        assert.ok(graceCheck && graceCheck.waitingGrace === true, 'Must return waitingGrace when elapsed < graceSeconds')
        assert.ok(graceCheck.remainingSec > 0)

        // 2. When grace has expired (or forced after grace timeout), missing rider is marked incomplete
        const expiredJoinedAt = new Date(Date.now() - 10000) // 10 seconds ago (> 5s grace)
        mockRunWithMissing.participants[1].joinedAt = expiredJoinedAt

        let participantUpdated = null
        prisma.diagnosticParticipant.update = async ({ where, data }) => {
            participantUpdated = { where, data }
            return {}
        }
        prisma.diagnosticRun.updateMany = async ({ where, data }) => ({ count: 1 })
        prisma.diagnosticRun.update = async () => ({})
        prisma.user.findUnique = async ({ where }) => ({ id: where.id, handle: where.id })

        const mockMailer = { sendMail: async () => ({ messageId: 'm2' }) }
        const result = await diagnosticService.closeAndExportRun('run_missing_rider', { checkGrace: true, mailer: mockMailer })

        assert.strictEqual(result, true)
        assert.ok(participantUpdated, 'Missing participant must be updated with stoppedAt')
        assert.ok(participantUpdated.data.stoppedAt instanceof Date)
        assert.strictEqual(mockRunWithMissing.participants[1].isIncomplete, true, 'Missing participant must be marked isIncomplete')
    })

    it('an opted-out rider shows as opted-out in summary and telemetry', async () => {
        const mockRunWithOptedOut = {
            id: 'run_opted_out',
            convoyId: 'convoy_3',
            testNumber: 3,
            status: 'ACTIVE',
            createdAt: new Date('2026-10-15T10:00:00Z'),
            room: { name: 'Opt Out Convoy' },
            participants: [
                { id: 'p1', riderId: 'r1', handle: 'HostRider', joinedAt: new Date('2026-10-15T10:00:00Z'), stoppedAt: new Date('2026-10-15T10:10:00Z'), isOptedOut: false },
                { id: 'p2', riderId: 'r2', handle: 'OptedOutRider', joinedAt: new Date('2026-10-15T10:00:00Z'), stoppedAt: new Date('2026-10-15T10:10:00Z'), isOptedOut: true }
            ],
            batches: [{ id: 'b1', riderId: 'r1', payload: { selfMetrics: [], inboundMetrics: [], events: [] } }]
        }

        prisma.diagnosticRun.findUnique = async () => mockRunWithOptedOut
        prisma.user.findUnique = async ({ where }) => ({ id: where.id, handle: where.id })

        const exportData = await diagnosticService.buildRunExportData('run_opted_out')
        assert.ok(exportData, 'Export data must be generated')

        const optedOutSummary = exportData.participantSummaries.find(p => p.riderId === 'r2')
        assert.ok(optedOutSummary)
        assert.strictEqual(optedOutSummary.isOptedOut, true)

        const excelBuffer = await diagnosticService.buildExcelBuffer(exportData)
        const ExcelJS = require('exceljs')
        const wb = new ExcelJS.Workbook()
        await wb.xlsx.load(excelBuffer)

        const summarySheet = wb.getWorksheet('Summary')
        let foundOptedOutStatus = false
        summarySheet.eachRow((row) => {
            row.eachCell((cell) => {
                if (cell.value === 'OPTED_OUT') foundOptedOutStatus = true
            })
        })
        assert.strictEqual(foundOptedOutStatus, true, 'OPTED_OUT status must appear on Summary sheet')

        const optedOutSheet = wb.getWorksheet('OptedOutRider')
        assert.ok(optedOutSheet, 'Opted out rider sheet must exist')
        const statusRow = optedOutSheet.getRow(2).getCell(1).value
        assert.ok(String(statusRow).includes('OPTED_OUT'), 'Rider sheet must show Status: OPTED_OUT')
    })

    it('closing twice sends ONE email (emailedAt claim)', async () => {
        let runStatus = 'ACTIVE'
        let runEmailedAt = null
        let sendMailCallCount = 0

        const mockRun = {
            id: 'run_double_close',
            convoyId: 'convoy_double',
            testNumber: 4,
            status: 'ACTIVE',
            createdAt: new Date('2026-10-15T10:00:00Z'),
            room: { name: 'Double Close Convoy' },
            participants: [
                { id: 'p1', riderId: 'r1', handle: 'Rider1', joinedAt: new Date('2026-10-15T10:00:00Z'), stoppedAt: new Date('2026-10-15T10:10:00Z') }
            ],
            batches: [{ id: 'b1', riderId: 'r1', payload: { selfMetrics: [], inboundMetrics: [], events: [] } }]
        }

        prisma.diagnosticRun.findUnique = async () => ({
            ...mockRun,
            status: runStatus,
            emailedAt: runEmailedAt
        })

        prisma.diagnosticRun.updateMany = async ({ where, data }) => {
            if (where.status === 'ACTIVE') {
                if (runStatus === 'ACTIVE') {
                    runStatus = 'CLOSED'
                    return { count: 1 }
                }
                return { count: 0 }
            }
            if (where.emailedAt === null) {
                if (runEmailedAt === null) {
                    runEmailedAt = new Date()
                    return { count: 1 }
                }
                return { count: 0 }
            }
            return { count: 0 }
        }
        prisma.diagnosticRun.update = async () => ({})
        prisma.user.findUnique = async ({ where }) => ({ id: where.id, handle: where.id })

        const mockMailer = {
            sendMail: async () => {
                sendMailCallCount++
                return { messageId: 'm_single' }
            }
        }

        // First call
        const firstCall = await diagnosticService.closeAndExportRun('run_double_close', { mailer: mockMailer })
        assert.strictEqual(firstCall, true)
        assert.strictEqual(sendMailCallCount, 1, 'First close must send email')

        // Second call
        const secondCall = await diagnosticService.closeAndExportRun('run_double_close', { mailer: mockMailer })
        assert.strictEqual(secondCall, false, 'Second close on already-closed run must return false')
        assert.strictEqual(sendMailCallCount, 1, 'Second close must NOT send another email')
    })

    it('mailer mock receives to=code4changeteam@gmail.com by default (and DIAG_EMAIL_TO when set) with one .xlsx attachment', async () => {
        const mockRun = {
            id: 'run_email_dest',
            convoyId: 'convoy_dest',
            testNumber: 5,
            status: 'ACTIVE',
            createdAt: new Date('2026-10-15T10:00:00Z'),
            room: { name: 'Email Destination' },
            participants: [
                { id: 'p1', riderId: 'r1', handle: 'Rider1', joinedAt: new Date('2026-10-15T10:00:00Z'), stoppedAt: new Date('2026-10-15T10:10:00Z') }
            ],
            batches: [{ id: 'b1', riderId: 'r1', payload: { selfMetrics: [], inboundMetrics: [], events: [] } }]
        }

        prisma.diagnosticRun.findUnique = async () => mockRun
        prisma.diagnosticRun.updateMany = async () => ({ count: 1 })
        prisma.diagnosticRun.update = async () => ({})
        prisma.user.findUnique = async ({ where }) => ({ id: where.id, handle: where.id })

        let sentOptionsDefault = null
        const mailer1 = {
            sendMail: async (opts) => {
                sentOptionsDefault = opts
                return { messageId: 'm_default' }
            }
        }

        // Default recipient
        delete process.env.DIAG_EMAIL_TO
        await diagnosticService.closeAndExportRun('run_email_dest', { mailer: mailer1 })
        assert.ok(sentOptionsDefault)
        assert.strictEqual(sentOptionsDefault.to, 'code4changeteam@gmail.com', 'Must default to code4changeteam@gmail.com')
        assert.strictEqual(sentOptionsDefault.attachments.length, 1, 'Must have exactly one attachment')
        assert.ok(sentOptionsDefault.attachments[0].filename.endsWith('.xlsx'), 'Attachment must be .xlsx')
        assert.ok(Buffer.isBuffer(sentOptionsDefault.attachments[0].content), 'Attachment content must be Buffer')

        // Custom DIAG_EMAIL_TO
        process.env.DIAG_EMAIL_TO = 'custom_diagnostics@example.com'
        let sentOptionsCustom = null
        const mailer2 = {
            sendMail: async (opts) => {
                sentOptionsCustom = opts
                return { messageId: 'm_custom' }
            }
        }
        await diagnosticService.closeAndExportRun('run_email_dest', { mailer: mailer2 })
        assert.ok(sentOptionsCustom)
        assert.strictEqual(sentOptionsCustom.to, 'custom_diagnostics@example.com', 'Must honor DIAG_EMAIL_TO')
    })

    it('over-20MB path sends the summary only without attachment', async () => {
        const mockRun = {
            id: 'run_large_payload',
            convoyId: 'convoy_large',
            testNumber: 6,
            status: 'ACTIVE',
            createdAt: new Date('2026-10-15T10:00:00Z'),
            room: { name: 'Large Report' },
            participants: [
                { id: 'p1', riderId: 'r1', handle: 'Rider1', joinedAt: new Date('2026-10-15T10:00:00Z'), stoppedAt: new Date('2026-10-15T10:10:00Z') }
            ],
            batches: [{ id: 'b1', riderId: 'r1', payload: { selfMetrics: [], inboundMetrics: [], events: [] } }]
        }

        prisma.diagnosticRun.findUnique = async () => mockRun
        prisma.diagnosticRun.updateMany = async () => ({ count: 1 })
        prisma.diagnosticRun.update = async () => ({})
        prisma.user.findUnique = async ({ where }) => ({ id: where.id, handle: where.id })

        // Stub buildExcelBuffer to return buffer > 20MB
        const originalBuildExcel = diagnosticService.buildExcelBuffer
        diagnosticService.buildExcelBuffer = async () => Buffer.alloc(21 * 1024 * 1024)

        let sentOptions = null
        const mockMailer = {
            sendMail: async (opts) => {
                sentOptions = opts
                return { messageId: 'm_large' }
            }
        }

        try {
            await diagnosticService.closeAndExportRun('run_large_payload', { mailer: mockMailer, excelBuffer: Buffer.alloc(21 * 1024 * 1024) })
            assert.ok(sentOptions)
            assert.strictEqual(sentOptions.attachments.length, 0, 'Must NOT attach over-20MB workbook')
            assert.ok(sentOptions.text.includes('exceeded the 20MB email threshold'), 'Must mention over-20MB notice in text')
        } finally {
            diagnosticService.buildExcelBuffer = originalBuildExcel
        }
    })

    it('SMTP unset logs a warning and does not throw', async () => {
        delete process.env.SMTP_HOST
        delete process.env.SMTP_USER
        delete process.env.SMTP_PASS
        process.env.NODE_ENV = 'production'

        const mockRun = {
            id: 'run_smtp_unset',
            convoyId: 'convoy_smtp',
            testNumber: 7,
            status: 'ACTIVE',
            createdAt: new Date('2026-10-15T10:00:00Z'),
            room: { name: 'No SMTP Convoy' },
            participants: [
                { id: 'p1', riderId: 'r1', handle: 'Rider1', joinedAt: new Date('2026-10-15T10:00:00Z'), stoppedAt: new Date('2026-10-15T10:10:00Z') }
            ],
            batches: [{ id: 'b1', riderId: 'r1', payload: { selfMetrics: [], inboundMetrics: [], events: [] } }]
        }

        prisma.diagnosticRun.findUnique = async () => mockRun
        prisma.diagnosticRun.updateMany = async () => ({ count: 1 })
        prisma.diagnosticRun.update = async () => ({})
        prisma.user.findUnique = async ({ where }) => ({ id: where.id, handle: where.id })

        let threw = false
        try {
            const res = await diagnosticService.closeAndExportRun('run_smtp_unset')
            assert.strictEqual(res, true)
        } catch (err) {
            threw = true
        }
        assert.strictEqual(threw, false, 'Must not throw when SMTP is unset')
    })
})
