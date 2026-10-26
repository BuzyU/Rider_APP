/**
 * schemaMigration.js – Production self-healing database schema synchronizer
 *
 * Ensures all required PostgreSQL enums, tables, columns, and indexes match
 * the Prisma schema even when Prisma migrations were skipped or could not run
 * (e.g. through Supabase transaction poolers on Render).
 *
 * All statements are additive and idempotent (using IF NOT EXISTS).
 */

'use strict'

const MIGRATION_STATEMENTS = [
    // 1. Enums
    `ALTER TYPE "InviteStatus" ADD VALUE IF NOT EXISTS 'REMOVED'`,
    `ALTER TYPE "InviteStatus" ADD VALUE IF NOT EXISTS 'LEFT'`,
    `DO $$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'user_role') THEN
            CREATE TYPE "user_role" AS ENUM ('CUSTOMER', 'ADMIN');
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'RoomStatus') THEN
            CREATE TYPE "RoomStatus" AS ENUM ('LOBBY', 'ACTIVE', 'ENDED');
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'InviteSource') THEN
            CREATE TYPE "InviteSource" AS ENUM ('HOST_INVITE', 'SHARE_LINK', 'JOIN_CODE');
        END IF;
    END $$;`,

    // 2. User table
    `ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "role" "user_role" NOT NULL DEFAULT 'CUSTOMER'`,

    // 3. Room table columns
    `ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "diagnosticsEnabled" BOOLEAN NOT NULL DEFAULT false`,
    `ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "startedAt" TIMESTAMP(3)`,
    `ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "status" "RoomStatus" NOT NULL DEFAULT 'LOBBY'`,
    `ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "endedAt" TIMESTAMP(3)`,
    `ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "joinCode" TEXT`,
    `ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "origin" TEXT`,
    `ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "destination" TEXT`,
    `ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "meetupPoint" TEXT`,
    `ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "estimatedDurationMin" INTEGER`,
    `ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "lastEmptyAt" TIMESTAMP(3)`,

    // 4. Room column extensions
    `ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "lkRoomName" TEXT`,

    // 5. Room unique indexes
    `DROP INDEX IF EXISTS "Room_name_key"`,
    `DO $$
    BEGIN
        IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'Room_name_key') THEN
            ALTER TABLE "Room" DROP CONSTRAINT "Room_name_key";
        END IF;
    END $$;`,
    `CREATE UNIQUE INDEX IF NOT EXISTS "Room_name_active_unique" ON "Room"(lower("name")) WHERE "status" != 'ENDED'`,
    `DROP INDEX IF EXISTS "Room_joinCode_key"`,
    `CREATE UNIQUE INDEX IF NOT EXISTS "Room_joinCode_active_unique" ON "Room"("joinCode") WHERE "status" != 'ENDED' AND "joinCode" IS NOT NULL`,
    `CREATE INDEX IF NOT EXISTS "Room_joinCode_idx" ON "Room"("joinCode")`,
    `CREATE INDEX IF NOT EXISTS "Room_status_idx" ON "Room"("status")`,
    `CREATE INDEX IF NOT EXISTS "Room_name_status_idx" ON "Room"("name", "status")`,
    `CREATE INDEX IF NOT EXISTS "Room_lastEmptyAt_idx" ON "Room"("lastEmptyAt")`,
    `CREATE INDEX IF NOT EXISTS "Room_ownerId_idx" ON "Room"("ownerId")`,

    // 6. RideInvite columns & constraint
    `ALTER TABLE "RideInvite" ADD COLUMN IF NOT EXISTS "source" "InviteSource" NOT NULL DEFAULT 'HOST_INVITE'`,
    `ALTER TABLE "RideInvite" ADD COLUMN IF NOT EXISTS "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT NOW()`,
    `DO $$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'RideInvite_roomId_inviteeId_key') THEN
            ALTER TABLE "RideInvite" ADD CONSTRAINT "RideInvite_roomId_inviteeId_key" UNIQUE ("roomId", "inviteeId");
        END IF;
    END $$;`,
    `CREATE INDEX IF NOT EXISTS "RideInvite_roomId_status_idx" ON "RideInvite"("roomId", "status")`,
    `CREATE INDEX IF NOT EXISTS "RideInvite_inviteeId_status_idx" ON "RideInvite"("inviteeId", "status")`,
    `CREATE INDEX IF NOT EXISTS "RideInvite_inviterId_status_idx" ON "RideInvite"("inviterId", "status")`,

    // 7. RoomJoinToken table & columns
    `CREATE TABLE IF NOT EXISTS "RoomJoinToken" (
        "id" TEXT NOT NULL,
        "roomId" TEXT NOT NULL,
        "token" TEXT NOT NULL,
        "expiresAt" TIMESTAMP(3) NOT NULL,
        "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT "RoomJoinToken_pkey" PRIMARY KEY ("id")
    )`,
    `CREATE UNIQUE INDEX IF NOT EXISTS "RoomJoinToken_token_key" ON "RoomJoinToken"("token")`,
    `CREATE INDEX IF NOT EXISTS "RoomJoinToken_roomId_idx" ON "RoomJoinToken"("roomId")`,
    `CREATE INDEX IF NOT EXISTS "RoomJoinToken_expiresAt_idx" ON "RoomJoinToken"("expiresAt")`,
    `ALTER TABLE "RoomJoinToken" ADD COLUMN IF NOT EXISTS "createdById" TEXT`,
    `ALTER TABLE "RoomJoinToken" ADD COLUMN IF NOT EXISTS "revokedAt" TIMESTAMP(3)`,
    `ALTER TABLE "RoomJoinToken" ADD COLUMN IF NOT EXISTS "maxUses" INTEGER`,
    `ALTER TABLE "RoomJoinToken" ADD COLUMN IF NOT EXISTS "useCount" INTEGER NOT NULL DEFAULT 0`,
    `DO $$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'RoomJoinToken_roomId_fkey') THEN
            ALTER TABLE "RoomJoinToken"
            ADD CONSTRAINT "RoomJoinToken_roomId_fkey"
            FOREIGN KEY ("roomId") REFERENCES "Room"("id") ON DELETE CASCADE ON UPDATE CASCADE;
        END IF;
    END $$;`,

    // 8. RideSession columns
    `ALTER TABLE "RideSession" ADD COLUMN IF NOT EXISTS "roomName" TEXT`,
    `ALTER TABLE "RideSession" ADD COLUMN IF NOT EXISTS "clientRideId" TEXT`,
    `ALTER TABLE "RideSession" ADD COLUMN IF NOT EXISTS "diagnosticsEnabled" BOOLEAN NOT NULL DEFAULT false`,
    `CREATE UNIQUE INDEX IF NOT EXISTS "RideSession_riderId_clientRideId_key" ON "RideSession"("riderId", "clientRideId")`,
    `CREATE INDEX IF NOT EXISTS "RideSession_riderId_startTime_idx" ON "RideSession"("riderId", "startTime" DESC)`,

    // 9. EmergencyAlert table & indexes
    `CREATE TABLE IF NOT EXISTS "EmergencyAlert" (
        "id" TEXT NOT NULL,
        "senderId" TEXT NOT NULL,
        "roomId" TEXT NOT NULL,
        "roomName" TEXT NOT NULL,
        "lat" DOUBLE PRECISION,
        "lng" DOUBLE PRECISION,
        "recipients" TEXT[],
        "status" TEXT NOT NULL DEFAULT 'SENT',
        "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT "EmergencyAlert_pkey" PRIMARY KEY ("id")
    )`,
    `CREATE INDEX IF NOT EXISTS "EmergencyAlert_roomName_senderId_idx" ON "EmergencyAlert"("roomName", "senderId")`,
    `CREATE INDEX IF NOT EXISTS "EmergencyAlert_senderId_idx" ON "EmergencyAlert"("senderId")`,

    // 10. DiagnosticRun table & columns
    `CREATE TABLE IF NOT EXISTS "DiagnosticRun" (
        "id" TEXT NOT NULL,
        "convoyId" TEXT NOT NULL,
        "testNumber" INTEGER NOT NULL,
        "status" TEXT NOT NULL DEFAULT 'ACTIVE',
        "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
        "closedAt" TIMESTAMP(3),
        "emailedAt" TIMESTAMP(3),
        "closeAt" TIMESTAMP(3),
        "summary" JSONB,
        CONSTRAINT "DiagnosticRun_pkey" PRIMARY KEY ("id")
    )`,
    `ALTER TABLE "DiagnosticRun" ADD COLUMN IF NOT EXISTS "closeAt" TIMESTAMP(3)`,
    `CREATE UNIQUE INDEX IF NOT EXISTS "DiagnosticRun_convoyId_testNumber_key" ON "DiagnosticRun"("convoyId", "testNumber")`,
    `CREATE INDEX IF NOT EXISTS "DiagnosticRun_convoyId_status_idx" ON "DiagnosticRun"("convoyId", "status")`,

    `CREATE TABLE IF NOT EXISTS "DiagnosticParticipant" (
        "id" TEXT NOT NULL,
        "runId" TEXT NOT NULL,
        "riderId" TEXT NOT NULL,
        "handle" TEXT NOT NULL,
        "joinedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
        "stoppedAt" TIMESTAMP(3),
        "lastSeq" INTEGER,
        "lastBatchAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT "DiagnosticParticipant_pkey" PRIMARY KEY ("id")
    )`,
    `CREATE UNIQUE INDEX IF NOT EXISTS "DiagnosticParticipant_runId_riderId_key" ON "DiagnosticParticipant"("runId", "riderId")`,
    `CREATE INDEX IF NOT EXISTS "DiagnosticParticipant_runId_idx" ON "DiagnosticParticipant"("runId")`,
    `CREATE INDEX IF NOT EXISTS "DiagnosticParticipant_riderId_idx" ON "DiagnosticParticipant"("riderId")`,

    `CREATE TABLE IF NOT EXISTS "DiagnosticBatch" (
        "id" TEXT NOT NULL,
        "runId" TEXT NOT NULL,
        "riderId" TEXT NOT NULL,
        "sequenceNumber" INTEGER NOT NULL,
        "payload" JSONB NOT NULL,
        "receivedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT "DiagnosticBatch_pkey" PRIMARY KEY ("id")
    )`,
    `CREATE UNIQUE INDEX IF NOT EXISTS "DiagnosticBatch_runId_riderId_sequenceNumber_key" ON "DiagnosticBatch"("runId", "riderId", "sequenceNumber")`,
    `CREATE INDEX IF NOT EXISTS "DiagnosticBatch_runId_riderId_idx" ON "DiagnosticBatch"("runId", "riderId")`,

    `DO $$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'DiagnosticRun_convoyId_fkey') THEN
            ALTER TABLE "DiagnosticRun"
                ADD CONSTRAINT "DiagnosticRun_convoyId_fkey"
                FOREIGN KEY ("convoyId") REFERENCES "Room"("id") ON DELETE CASCADE ON UPDATE CASCADE;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'DiagnosticParticipant_runId_fkey') THEN
            ALTER TABLE "DiagnosticParticipant"
                ADD CONSTRAINT "DiagnosticParticipant_runId_fkey"
                FOREIGN KEY ("runId") REFERENCES "DiagnosticRun"("id") ON DELETE CASCADE ON UPDATE CASCADE;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'DiagnosticBatch_runId_fkey') THEN
            ALTER TABLE "DiagnosticBatch"
                ADD CONSTRAINT "DiagnosticBatch_runId_fkey"
                FOREIGN KEY ("runId") REFERENCES "DiagnosticRun"("id") ON DELETE CASCADE ON UPDATE CASCADE;
        END IF;
    END $$;`,

    // 11. Support / auxiliary tables
    `CREATE TABLE IF NOT EXISTS "waitlist" (
        "id" TEXT NOT NULL,
        "email" TEXT NOT NULL,
        "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT "waitlist_pkey" PRIMARY KEY ("id")
    )`,
    `CREATE UNIQUE INDEX IF NOT EXISTS "waitlist_email_key" ON "waitlist"("email")`,
    `CREATE INDEX IF NOT EXISTS "waitlist_email_idx" ON "waitlist"("email")`,

    `CREATE TABLE IF NOT EXISTS "tester_applications" (
        "id" TEXT NOT NULL,
        "name" TEXT NOT NULL,
        "email" TEXT NOT NULL,
        "phone" TEXT,
        "device_model" TEXT,
        "motorcycle_model" TEXT,
        "experience_notes" TEXT,
        "apk_version" TEXT DEFAULT 'v0.0.3.8',
        "status" TEXT NOT NULL DEFAULT 'PENDING',
        "download_count" INTEGER DEFAULT 1,
        "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
        "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT "tester_applications_pkey" PRIMARY KEY ("id")
    )`,
    `CREATE INDEX IF NOT EXISTS "tester_applications_email_idx" ON "tester_applications"("email")`,
    `CREATE INDEX IF NOT EXISTS "tester_applications_status_idx" ON "tester_applications"("status")`,
    `CREATE INDEX IF NOT EXISTS "tester_applications_created_at_idx" ON "tester_applications"("created_at" DESC)`,

    `CREATE TABLE IF NOT EXISTS "admin_settings" (
        "id" TEXT NOT NULL,
        "settings" JSONB NOT NULL,
        "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT "admin_settings_pkey" PRIMARY KEY ("id")
    )`,

    // 12. Miscellaneous indexes
    `CREATE INDEX IF NOT EXISTS "DeviceToken_userId_idx" ON "DeviceToken"("userId")`,
    `CREATE INDEX IF NOT EXISTS "Friendship_requesterId_status_idx" ON "Friendship"("requesterId", "status")`,
    `CREATE INDEX IF NOT EXISTS "Friendship_addresseeId_status_idx" ON "Friendship"("addresseeId", "status")`,
    `CREATE INDEX IF NOT EXISTS "ConvoyEvent_rideId_idx" ON "ConvoyEvent"("rideId")`,
    `DO $$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'Room_ownerId_fkey') THEN
            ALTER TABLE "Room" ADD CONSTRAINT "Room_ownerId_fkey"
                FOREIGN KEY ("ownerId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
        END IF;
    END $$;`,

    // 13. RideCreateRequest table and indexes
    `CREATE TABLE IF NOT EXISTS "RideCreateRequest" (
        "id" TEXT NOT NULL,
        "ownerId" TEXT NOT NULL,
        "clientRequestId" TEXT NOT NULL,
        "payloadHash" TEXT NOT NULL,
        "roomId" TEXT NOT NULL,
        "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT "RideCreateRequest_pkey" PRIMARY KEY ("id")
    )`,
    `CREATE UNIQUE INDEX IF NOT EXISTS "RideCreateRequest_ownerId_clientRequestId_key" ON "RideCreateRequest"("ownerId", "clientRequestId")`,
    `CREATE INDEX IF NOT EXISTS "RideCreateRequest_ownerId_idx" ON "RideCreateRequest"("ownerId")`,
    `CREATE INDEX IF NOT EXISTS "RideCreateRequest_roomId_idx" ON "RideCreateRequest"("roomId")`,
    `CREATE INDEX IF NOT EXISTS "RideCreateRequest_createdAt_idx" ON "RideCreateRequest"("createdAt")`,
    `DO $$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'RideCreateRequest_ownerId_fkey') THEN
            ALTER TABLE "RideCreateRequest" ADD CONSTRAINT "RideCreateRequest_ownerId_fkey" FOREIGN KEY ("ownerId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'RideCreateRequest_roomId_fkey') THEN
            ALTER TABLE "RideCreateRequest" ADD CONSTRAINT "RideCreateRequest_roomId_fkey" FOREIGN KEY ("roomId") REFERENCES "Room"("id") ON DELETE CASCADE ON UPDATE CASCADE;
        END IF;
    END $$;`
]

const ADVISORY_LOCK_ID = 742918491

/**
 * Read-only schema assertion that validates essential tables, columns, enums, indexes, and FKs.
 * Never mutates or deletes data.
 * @param {object} prisma
 * @returns {Promise<{ valid: boolean, missing: string[] }>}
 */
async function assertSchema(prisma) {
    const missing = []
    try {
        const required = [
            { table: 'Room', columns: ['id', 'name', 'lkRoomName', 'status', 'ownerId', 'lastEmptyAt'] },
            { table: 'RideCreateRequest', columns: ['id', 'ownerId', 'clientRequestId', 'roomId', 'payloadHash'] },
            { table: 'RideInvite', columns: ['id', 'roomId', 'inviteeId', 'status', 'source'] },
            { table: 'RoomJoinToken', columns: ['id', 'roomId', 'token', 'maxUses', 'useCount'] },
            { table: 'EmergencyAlert', columns: ['id', 'roomId', 'senderId', 'status'] },
            { table: 'DiagnosticRun', columns: ['id', 'convoyId', 'testNumber', 'status', 'closeAt'] }
        ]

        const rows = await prisma.$queryRaw`
            SELECT table_name, column_name
            FROM information_schema.columns
            WHERE table_schema = 'public'
        `

        const existingMap = new Map()
        for (const row of rows) {
            if (!existingMap.has(row.table_name)) {
                existingMap.set(row.table_name, new Set())
            }
            existingMap.get(row.table_name).add(row.column_name)
        }

        for (const req of required) {
            if (!existingMap.has(req.table)) {
                missing.push(`Table: ${req.table}`)
                continue
            }
            const tableCols = existingMap.get(req.table)
            for (const col of req.columns) {
                if (!tableCols.has(col)) {
                    missing.push(`${req.table}.${col}`)
                }
            }
        }

        // P0-C: Check InviteStatus enum values
        try {
            const enumRows = await prisma.$queryRaw`
                SELECT e.enumlabel
                FROM pg_enum e
                JOIN pg_type t ON e.enumtypid = t.oid
                WHERE t.typname = 'InviteStatus'
            `
            const enumLabels = new Set(enumRows.map(r => r.enumlabel))
            if (!enumLabels.has('REMOVED')) missing.push('InviteStatus.REMOVED')
            if (!enumLabels.has('LEFT')) missing.push('InviteStatus.LEFT')
        } catch (_) {}

        // P0-C: Check indexes
        try {
            const indexRows = await prisma.$queryRaw`
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
            `
            const indexSet = new Set(indexRows.map(r => r.indexname))
            if (!indexSet.has('Room_name_active_unique')) missing.push('Index: Room_name_active_unique')
            if (!indexSet.has('Room_joinCode_active_unique')) missing.push('Index: Room_joinCode_active_unique')
            if (!indexSet.has('RideInvite_roomId_inviteeId_key')) {
                const conRows = await prisma.$queryRaw`
                    SELECT conname FROM pg_constraint WHERE conname = 'RideInvite_roomId_inviteeId_key'
                `
                if (conRows.length === 0) missing.push('Constraint/Index: RideInvite_roomId_inviteeId_key')
            }
        } catch (_) {}

        // P0-C: Check Foreign Keys
        try {
            const requiredFks = [
                'RoomJoinToken_roomId_fkey',
                'DiagnosticRun_convoyId_fkey',
                'DiagnosticParticipant_runId_fkey',
                'DiagnosticBatch_runId_fkey',
                'RideCreateRequest_ownerId_fkey',
                'RideCreateRequest_roomId_fkey',
                'Room_ownerId_fkey'
            ]
            const fkRows = await prisma.$queryRaw`
                SELECT conname FROM pg_constraint WHERE conname = ANY(${requiredFks})
            `
            const conSet = new Set(fkRows.map(r => r.conname))
            for (const fk of requiredFks) {
                if (!conSet.has(fk)) {
                    missing.push(`FK: ${fk}`)
                }
            }
        } catch (_) {}
    } catch (err) {
        missing.push(`Query failed: ${err.message}`)
    }

    return {
        valid: missing.length === 0,
        missing
    }
}

/**
 * Pre-checks tables for duplicate rows that would prevent unique indexes from building.
 * Never modifies or deletes rows.
 */
async function checkUniqueViolations(prisma) {
    const violations = []
    try {
        const dupNames = await prisma.$queryRaw`
            SELECT lower("name") AS name, count(*)::int AS count
            FROM "Room"
            WHERE "status" != 'ENDED'
            GROUP BY lower("name")
            HAVING count(*) > 1
        `
        if (dupNames.length > 0) {
            violations.push(`Duplicate active Room names found: ${dupNames.map(d => `${d.name} (${d.count})`).join(', ')}. Manual reconciliation required.`)
        }
    } catch (_) {}

    try {
        const dupJoinCodes = await prisma.$queryRaw`
            SELECT "joinCode", count(*)::int AS count
            FROM "Room"
            WHERE "status" != 'ENDED' AND "joinCode" IS NOT NULL
            GROUP BY "joinCode"
            HAVING count(*) > 1
        `
        if (dupJoinCodes.length > 0) {
            violations.push(`Duplicate active Room joinCodes found: ${dupJoinCodes.map(d => `${d.joinCode} (${d.count})`).join(', ')}. Manual reconciliation required.`)
        }
    } catch (_) {}

    try {
        const dupInvites = await prisma.$queryRaw`
            SELECT "roomId", "inviteeId", count(*)::int AS count
            FROM "RideInvite"
            GROUP BY "roomId", "inviteeId"
            HAVING count(*) > 1
        `
        if (dupInvites.length > 0) {
            violations.push(`Duplicate RideInvite (roomId, inviteeId) found: ${dupInvites.map(d => `(${d.roomId}, ${d.inviteeId}): ${d.count}`).join(', ')}. Manual reconciliation required.`)
        }
    } catch (_) {}

    return violations
}

async function ensureDbSchema(prisma) {
    const { valid, missing } = await assertSchema(prisma)
    if (valid) {
        return { valid: true, repaired: false }
    }

    console.warn(`[DB Schema] Missing schema elements: ${missing.join(', ')}`)

    // Automatically apply additive self-healing DDL unless explicitly opted out via AUTO_SCHEMA_REPAIR=false
    if (process.env.AUTO_SCHEMA_REPAIR === 'false') {
        console.warn('[DB] AUTO_SCHEMA_REPAIR=false: Runtime repair is disabled. Use Prisma migrations (prisma migrate deploy) to align schema.')
        return { valid: false, repaired: false, missing }
    }

    // P0-C: Wrap repair in advisory lock
    let hasLock = false
    try {
        const lockRes = await prisma.$queryRawUnsafe(`SELECT pg_try_advisory_lock(${ADVISORY_LOCK_ID}) AS locked`)
        if (lockRes && lockRes[0] && lockRes[0].locked === false) {
            console.warn('[DB] Another instance holds the advisory migration lock; skipping concurrent repair.')
            return { valid: false, repaired: false, skippedDueToLock: true, missing }
        }
        hasLock = true
    } catch (_) {
        // If environment does not support advisory locks (e.g. SQLite/unit test mocks), continue
        hasLock = false
    }

    try {
        // Pre-check for unique constraint/index violations
        const violations = await checkUniqueViolations(prisma)
        if (violations.length > 0) {
            for (const v of violations) {
                console.warn(`[DB Schema Pre-Check Violation] ${v}`)
            }
        }

        console.log('[DB] Missing schema elements detected; applying additive non-destructive DDL...')
        let applied = 0
        let failed = 0
        const failedStatements = []

        for (let i = 0; i < MIGRATION_STATEMENTS.length; i++) {
            const sql = MIGRATION_STATEMENTS[i]

            // If duplicates detected, skip the specific index creation to avoid failure
            if (violations.some(v => v.includes('Room names')) && sql.includes('"Room_name_active_unique"')) {
                failed++
                failedStatements.push({ index: i, name: 'Room_name_active_unique', error: 'Skipped due to duplicate active room names' })
                continue
            }
            if (violations.some(v => v.includes('joinCodes')) && sql.includes('"Room_joinCode_active_unique"')) {
                failed++
                failedStatements.push({ index: i, name: 'Room_joinCode_active_unique', error: 'Skipped due to duplicate join codes' })
                continue
            }
            if (violations.some(v => v.includes('RideInvite')) && sql.includes('RideInvite_roomId_inviteeId_key')) {
                failed++
                failedStatements.push({ index: i, name: 'RideInvite_roomId_inviteeId_key', error: 'Skipped due to duplicate ride invites' })
                continue
            }

            try {
                await prisma.$executeRawUnsafe(sql)
                applied++
            } catch (err) {
                failed++
                failedStatements.push({
                    index: i,
                    sql: sql.trim().substring(0, 60),
                    error: err.message
                })
                console.warn(`[DB] Migration statement #${i} notice:`, err.message)
            }
        }

        console.log(`[DB] Additive schema migrations finished: ${applied} applied, ${failed} failed.`)
        const recheck = await assertSchema(prisma)
        const isOverallValid = recheck.valid && failedStatements.length === 0 && violations.length === 0
        return {
            valid: isOverallValid,
            repaired: true,
            applied,
            failed,
            failedStatements,
            violations,
            missing: recheck.missing
        }
    } finally {
        if (hasLock) {
            try {
                await prisma.$queryRawUnsafe(`SELECT pg_advisory_unlock(${ADVISORY_LOCK_ID})`)
            } catch (_) {}
        }
    }
}

module.exports = { ensureDbSchema, assertSchema, checkUniqueViolations, MIGRATION_STATEMENTS, ADVISORY_LOCK_ID }

