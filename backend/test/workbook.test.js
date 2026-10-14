const { describe, it } = require('node:test')
const assert = require('node:assert/strict')
const ExcelJS = require('exceljs')
const { buildExcelBuffer } = require('../src/services/diagnosticService')

describe('Gate 3: Diagnostic Workbook Generation', () => {
    it('generated .xlsx re-opened with exceljs has a Summary sheet plus exactly one sheet per rider', async () => {
        const mockExportData = {
            run: {
                id: 'run_test_101',
                convoyId: 'convoy_xyz',
                testNumber: 1,
                createdAt: new Date('2026-10-15T10:00:00Z'),
                closedAt: new Date('2026-10-15T10:30:00Z'),
                room: { name: 'Sunday Cruiser Convoy' },
                participants: [
                    { id: 'p1', riderId: 'rider_1', handle: 'Alice' },
                    { id: 'p2', riderId: 'rider_2', handle: 'Bob' },
                    { id: 'p3', riderId: 'rider_3', handle: 'Charlie' }
                ],
                batches: [{ id: 'b1' }, { id: 'b2' }]
            },
            convoyClean: 'Sunday Cruiser Convoy',
            participantSummaries: [
                {
                    riderId: 'rider_1',
                    handle: 'Alice',
                    totalBatches: 2,
                    totalSelfSamples: 2,
                    avgRttMs: '45.0',
                    lossPercent: '0.0%',
                    isOptedOut: false,
                    isIncomplete: false,
                    joinedAt: new Date('2026-10-15T10:00:00Z'),
                    stoppedAt: new Date('2026-10-15T10:30:00Z')
                },
                {
                    riderId: 'rider_2',
                    handle: 'Bob',
                    totalBatches: 1,
                    totalSelfSamples: 1,
                    avgRttMs: '62.5',
                    lossPercent: '1.2%',
                    isOptedOut: false,
                    isIncomplete: false,
                    joinedAt: new Date('2026-10-15T10:00:00Z'),
                    stoppedAt: new Date('2026-10-15T10:30:00Z')
                },
                {
                    riderId: 'rider_3',
                    handle: 'Charlie',
                    totalBatches: 0,
                    totalSelfSamples: 0,
                    avgRttMs: 'N/A',
                    lossPercent: '0.0%',
                    isOptedOut: true,
                    isIncomplete: false,
                    joinedAt: new Date('2026-10-15T10:00:00Z'),
                    stoppedAt: new Date('2026-10-15T10:30:00Z')
                }
            ],
            riderSelfRows: {
                rider_1: [
                    {
                        deviceEpochMs: 1729000000000,
                        elapsedRealtimeMs: 50000,
                        rtt_ms: 45,
                        packets_sent: 100,
                        packets_received: 99,
                        packets_lost: 1,
                        jitter_ms: 3.5,
                        bitrate_kbps: 48,
                        network_type: 'WIFI',
                        cell_operator: 'N/A',
                        signal_strength_dbm: -65,
                        battery_level: 88,
                        thermal_status: 'NORMAL',
                        is_foreground: true
                    }
                ],
                rider_2: [
                    {
                        deviceEpochMs: 1729000001000,
                        elapsedRealtimeMs: 51000,
                        rtt_ms: 62,
                        packets_sent: 80,
                        packets_received: 79,
                        packets_lost: 1,
                        jitter_ms: 5.1,
                        bitrate_kbps: 40,
                        network_type: 'CELLULAR',
                        cell_operator: 'Verizon',
                        signal_strength_dbm: -95,
                        battery_level: 65,
                        thermal_status: 'MODERATE',
                        is_foreground: true
                    }
                ],
                rider_3: []
            }
        }

        const buffer = await buildExcelBuffer(mockExportData)
        assert.ok(Buffer.isBuffer(buffer), 'Buffer must be returned')
        assert.ok(buffer.length > 0, 'Buffer must not be empty')

        // Re-open with exceljs
        const workbook = new ExcelJS.Workbook()
        await workbook.xlsx.load(buffer)

        // Summary sheet + exactly 3 rider sheets = 4 total sheets
        assert.strictEqual(workbook.worksheets.length, 4, 'Must have Summary + exactly 1 sheet per rider')
        const firstSheet = workbook.worksheets[0]
        assert.strictEqual(firstSheet.name, 'Summary', 'First sheet must be Summary')

        const sheetNames = workbook.worksheets.map(w => w.name)
        assert.deepStrictEqual(sheetNames, ['Summary', 'Alice', 'Bob', 'Charlie'])
    })

    it('sheet names are valid (<=31 chars, none of []:*?/\\), unique, with fallback name', async () => {
        const mockExportData = {
            run: {
                id: 'run_names_102',
                testNumber: 2,
                room: { name: 'Invalid Names Test' }
            },
            participantSummaries: [
                // Contains illegal characters []:*?/\
                { riderId: 'r_illegal', handle: '[Dave:Rider*1/2?3\\4]' },
                // Very long name > 31 characters
                { riderId: 'r_long', handle: 'SuperUltraMegaLongRiderHandleThatExceedsThirtyOneCharactersEasily' },
                // Duplicate handles
                { riderId: 'r_dup1', handle: 'Sam' },
                { riderId: 'r_dup2', handle: 'Sam' },
                // Blank/empty handle (should use fallback name 'Rider')
                { riderId: 'r_empty', handle: '' }
            ],
            riderSelfRows: {}
        }

        const buffer = await buildExcelBuffer(mockExportData)
        const workbook = new ExcelJS.Workbook()
        await workbook.xlsx.load(buffer)

        const illegalCharsRegex = /[\[\]:*?/\\]/
        const sheetNames = workbook.worksheets.map(w => w.name)

        // Check each sheet
        for (const name of sheetNames) {
            assert.ok(name.length <= 31, `Sheet name '${name}' must be <= 31 characters (got ${name.length})`)
            assert.ok(!illegalCharsRegex.test(name), `Sheet name '${name}' must not contain invalid characters`)
        }

        // Uniqueness
        const uniqueSet = new Set(sheetNames.map(s => s.toLowerCase()))
        assert.strictEqual(uniqueSet.size, sheetNames.length, 'All sheet names must be case-insensitively unique')

        // First sheet is Summary
        assert.strictEqual(sheetNames[0], 'Summary')

        // Sanitized illegal chars
        assert.ok(!sheetNames[1].includes('[') && !sheetNames[1].includes(']'))

        // Long handle was trimmed
        assert.ok(sheetNames[2].length <= 31)

        // Duplicates resolved (Sam and Sam_2)
        assert.ok(sheetNames.includes('Sam'))
        assert.ok(sheetNames.some(s => s.startsWith('Sam_')))

        // Empty handle fallback to Rider
        assert.ok(sheetNames.includes('Rider'))
    })

    it('timestamps are real date cells, numbers are numeric', async () => {
        const testEpoch = 1729000000000
        const mockExportData = {
            run: {
                id: 'run_types_103',
                testNumber: 3,
                room: { name: 'Type Validation' }
            },
            participantSummaries: [
                {
                    riderId: 'rider_types',
                    handle: 'Tester',
                    totalBatches: 5,
                    totalSelfSamples: 1,
                    avgRttMs: '55.2',
                    lossPercent: '0.5%',
                    joinedAt: new Date(testEpoch),
                    stoppedAt: new Date(testEpoch + 10000)
                }
            ],
            riderSelfRows: {
                rider_types: [
                    {
                        deviceEpochMs: testEpoch,
                        elapsedRealtimeMs: 12345,
                        rtt_ms: 55,
                        packets_sent: 1000,
                        packets_received: 995,
                        packets_lost: 5,
                        jitter_ms: 4.2,
                        bitrate_kbps: 64,
                        network_type: 'WIFI',
                        cell_operator: 'None',
                        signal_strength_dbm: -70,
                        battery_level: 90,
                        thermal_status: 'NORMAL',
                        is_foreground: true
                    }
                ]
            }
        }

        const buffer = await buildExcelBuffer(mockExportData)
        const workbook = new ExcelJS.Workbook()
        await workbook.xlsx.load(buffer)

        const riderSheet = workbook.getWorksheet('Tester')
        assert.ok(riderSheet, 'Rider worksheet must exist')

        // Data row is row 5 (Row 1: Title, Row 2: Status, Row 3: Blank, Row 4: Headers, Row 5: Data)
        const dataRow = riderSheet.getRow(5)
        const timestampCell = dataRow.getCell(1) // timestamp
        const rttCell = dataRow.getCell(4) // rtt_ms
        const packetsSentCell = dataRow.getCell(5) // packets_sent
        const jitterCell = dataRow.getCell(8) // jitter_ms

        // Timestamp cell value must be a Date
        assert.ok(timestampCell.value instanceof Date, `Timestamp cell value must be Date instance, got ${typeof timestampCell.value}`)

        // Numeric cells must be typeof number
        assert.strictEqual(typeof rttCell.value, 'number', `RTT cell must be numeric, got ${typeof rttCell.value}`)
        assert.strictEqual(rttCell.value, 55)

        assert.strictEqual(typeof packetsSentCell.value, 'number', `Packets sent must be numeric, got ${typeof packetsSentCell.value}`)
        assert.strictEqual(packetsSentCell.value, 1000)

        assert.strictEqual(typeof jitterCell.value, 'number', `Jitter cell must be numeric, got ${typeof jitterCell.value}`)
        assert.strictEqual(jitterCell.value, 4.2)
    })

    it('cells starting with = + - @ are neutralised against formula injection', async () => {
        const mockExportData = {
            run: {
                id: 'run_neutralize_104',
                testNumber: 4,
                room: { name: '=1+1MaliciousRoom' }
            },
            participantSummaries: [
                {
                    riderId: 'r_inject',
                    handle: '+cmdRider',
                    totalBatches: 1,
                    totalSelfSamples: 1,
                    avgRttMs: '50.0',
                    lossPercent: '0.0%',
                    isOptedOut: false
                }
            ],
            riderSelfRows: {
                r_inject: [
                    {
                        deviceEpochMs: 1729000000000,
                        elapsedRealtimeMs: 10000,
                        rtt_ms: 50,
                        packets_sent: 10,
                        packets_received: 10,
                        packets_lost: 0,
                        jitter_ms: 1,
                        bitrate_kbps: 32,
                        network_type: '=SUM(A1:A10)', // formula injection attempt
                        cell_operator: '@dangerousOperator', // formula injection attempt
                        signal_strength_dbm: -50,
                        battery_level: 80,
                        thermal_status: '-evilStatus', // formula injection attempt
                        is_foreground: true
                    }
                ]
            }
        }

        const buffer = await buildExcelBuffer(mockExportData)
        const workbook = new ExcelJS.Workbook()
        await workbook.xlsx.load(buffer)

        // Summary sheet check
        const summarySheet = workbook.getWorksheet('Summary')
        const row2Val = summarySheet.getRow(2).getCell(2).value
        assert.ok(typeof row2Val === 'string' && row2Val.startsWith("'="), `Convoy name with = must start with single quote, got ${row2Val}`)

        // Rider sheet check
        const riderSheet = workbook.worksheets[1]
        const dataRow = riderSheet.getRow(5)

        const netTypeVal = dataRow.getCell(10).value // network_type
        const operatorVal = dataRow.getCell(11).value // cell_operator
        const thermalVal = dataRow.getCell(14).value // thermal_status

        assert.ok(typeof netTypeVal === 'string' && netTypeVal.startsWith("'="), `=SUM formula must be neutralized to '=SUM, got ${netTypeVal}`)
        assert.ok(typeof operatorVal === 'string' && operatorVal.startsWith("'@"), `@ formula must be neutralized to '@, got ${operatorVal}`)
        assert.ok(typeof thermalVal === 'string' && thermalVal.startsWith("'-"), `- formula must be neutralized to '-, got ${thermalVal}`)
    })
})
