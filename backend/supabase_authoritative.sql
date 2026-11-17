-- ==============================================================================
-- RIDER VOICE AUTHORITATIVE SUPABASE SCHEMA SCRIPT (IDEMPOTENT / RE-RUNNABLE)
-- ==============================================================================
-- Run in Supabase SQL Editor (Dashboard -> SQL Editor -> New Query -> Run).
-- Execution order:
--   Section 0: Non-destructive duplicate diagnostics
--   Section 1: Enum values (OUTSIDE any transaction block)
--   Section 2: Additive DDL (tables & columns)
--   Section 3: Indexes & unique constraints
--   Section 4: Foreign keys
--   Section 5: Verification SELECT (returns 0 rows if schema is complete)
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- SECTION 0: Duplicate Diagnostics (read-only on user tables, non-destructive)
-- If any rows are returned here, reconcile duplicates before creating unique indexes.
-- ------------------------------------------------------------------------------
CREATE TEMP TABLE IF NOT EXISTS "_rv_duplicate_diagnostics" (
    "check_name" TEXT,
    "dup_value"  TEXT,
    "count"      INTEGER
);
TRUNCATE TABLE "_rv_duplicate_diagnostics";

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'Room' AND column_name = 'status'
    ) THEN
        INSERT INTO "_rv_duplicate_diagnostics"
        SELECT 'DUPLICATE_ACTIVE_ROOM_NAMES', lower("name"), count(*)::int
        FROM "Room"
        WHERE "status" != 'ENDED'
        GROUP BY lower("name")
        HAVING count(*) > 1;
    ELSIF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'Room'
    ) THEN
        INSERT INTO "_rv_duplicate_diagnostics"
        SELECT 'DUPLICATE_ROOM_NAMES_LEGACY', lower("name"), count(*)::int
        FROM "Room"
        GROUP BY lower("name")
        HAVING count(*) > 1;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'Room' AND column_name = 'joinCode'
    ) AND EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'Room' AND column_name = 'status'
    ) THEN
        INSERT INTO "_rv_duplicate_diagnostics"
        SELECT 'DUPLICATE_ACTIVE_JOIN_CODES', "joinCode", count(*)::int
        FROM "Room"
        WHERE "status" != 'ENDED' AND "joinCode" IS NOT NULL
        GROUP BY "joinCode"
        HAVING count(*) > 1;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'RideInvite'
    ) THEN
        INSERT INTO "_rv_duplicate_diagnostics"
        SELECT 'DUPLICATE_RIDE_INVITES', "roomId" || ':' || "inviteeId", count(*)::int
        FROM "RideInvite"
        GROUP BY "roomId", "inviteeId"
        HAVING count(*) > 1;
    END IF;
END $$;

SELECT * FROM "_rv_duplicate_diagnostics";

-- ------------------------------------------------------------------------------
-- SECTION 1: Enum ADD VALUE statements (outside any transaction block)
-- ------------------------------------------------------------------------------
ALTER TYPE "InviteStatus" ADD VALUE IF NOT EXISTS 'REMOVED';
ALTER TYPE "InviteStatus" ADD VALUE IF NOT EXISTS 'LEFT';

DO $$
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
END $$;

-- ------------------------------------------------------------------------------
-- SECTION 2: Additive DDL (Tables & Columns)
-- ------------------------------------------------------------------------------

-- User
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "role" "user_role" NOT NULL DEFAULT 'CUSTOMER';

-- Room
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "lkRoomName"           TEXT;
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "status"               "RoomStatus" NOT NULL DEFAULT 'LOBBY';
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "diagnosticsEnabled"   BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "startedAt"            TIMESTAMP(3);
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "endedAt"              TIMESTAMP(3);
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "joinCode"             TEXT;
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "offlineBackupCode"    TEXT;
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "offlineMeshEnabled"   BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "origin"               TEXT;
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "destination"          TEXT;
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "meetupPoint"          TEXT;
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "estimatedDurationMin" INTEGER;
ALTER TABLE "Room" ADD COLUMN IF NOT EXISTS "lastEmptyAt"          TIMESTAMP(3);

-- RideInvite
ALTER TABLE "RideInvite" ADD COLUMN IF NOT EXISTS "source"                 "InviteSource" NOT NULL DEFAULT 'HOST_INVITE';
ALTER TABLE "RideInvite" ADD COLUMN IF NOT EXISTS "offlineConfigStatus"    TEXT;
ALTER TABLE "RideInvite" ADD COLUMN IF NOT EXISTS "offlineConfigUpdatedAt" TIMESTAMP(3);
ALTER TABLE "RideInvite" ADD COLUMN IF NOT EXISTS "lastLobbyPollAt"        TIMESTAMP(3);
ALTER TABLE "RideInvite" ADD COLUMN IF NOT EXISTS "updatedAt"              TIMESTAMP(3) NOT NULL DEFAULT NOW();

-- RoomJoinToken
CREATE TABLE IF NOT EXISTS "RoomJoinToken" (
    "id"          TEXT NOT NULL,
    "roomId"      TEXT NOT NULL,
    "token"       TEXT NOT NULL,
    "expiresAt"   TIMESTAMP(3) NOT NULL,
    "createdAt"   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "createdById" TEXT,
    "revokedAt"   TIMESTAMP(3),
    "maxUses"     INTEGER,
    "useCount"    INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT "RoomJoinToken_pkey" PRIMARY KEY ("id")
);
ALTER TABLE "RoomJoinToken" ADD COLUMN IF NOT EXISTS "createdById" TEXT;
ALTER TABLE "RoomJoinToken" ADD COLUMN IF NOT EXISTS "revokedAt"   TIMESTAMP(3);
ALTER TABLE "RoomJoinToken" ADD COLUMN IF NOT EXISTS "maxUses"     INTEGER;
ALTER TABLE "RoomJoinToken" ADD COLUMN IF NOT EXISTS "useCount"    INTEGER NOT NULL DEFAULT 0;

-- RideSession
ALTER TABLE "RideSession" ADD COLUMN IF NOT EXISTS "roomName"           TEXT;
ALTER TABLE "RideSession" ADD COLUMN IF NOT EXISTS "clientRideId"       TEXT;
ALTER TABLE "RideSession" ADD COLUMN IF NOT EXISTS "diagnosticsEnabled" BOOLEAN NOT NULL DEFAULT false;

-- EmergencyAlert
CREATE TABLE IF NOT EXISTS "EmergencyAlert" (
    "id"            TEXT NOT NULL,
    "senderId"      TEXT NOT NULL,
    "roomId"        TEXT NOT NULL,
    "roomName"      TEXT NOT NULL,
    "lat"           DOUBLE PRECISION,
    "lng"           DOUBLE PRECISION,
    "recipients"    TEXT[],
    "status"        TEXT NOT NULL DEFAULT 'SENT',
    "clientAlertId" TEXT,
    "createdAt"     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "EmergencyAlert_pkey" PRIMARY KEY ("id")
);
ALTER TABLE "EmergencyAlert" ADD COLUMN IF NOT EXISTS "clientAlertId" TEXT;

-- DiagnosticRun / DiagnosticParticipant / DiagnosticBatch
CREATE TABLE IF NOT EXISTS "DiagnosticRun" (
    "id"         TEXT NOT NULL,
    "convoyId"   TEXT NOT NULL,
    "testNumber" INTEGER NOT NULL,
    "status"     TEXT NOT NULL DEFAULT 'ACTIVE',
    "createdAt"  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "closedAt"   TIMESTAMP(3),
    "emailedAt"  TIMESTAMP(3),
    "closeAt"    TIMESTAMP(3),
    "summary"    JSONB,
    CONSTRAINT "DiagnosticRun_pkey" PRIMARY KEY ("id")
);
ALTER TABLE "DiagnosticRun" ADD COLUMN IF NOT EXISTS "closeAt" TIMESTAMP(3);

CREATE TABLE IF NOT EXISTS "DiagnosticParticipant" (
    "id"          TEXT NOT NULL,
    "runId"       TEXT NOT NULL,
    "riderId"     TEXT NOT NULL,
    "handle"      TEXT NOT NULL,
    "joinedAt"    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "stoppedAt"   TIMESTAMP(3),
    "lastSeq"     INTEGER,
    "lastBatchAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "DiagnosticParticipant_pkey" PRIMARY KEY ("id")
);

CREATE TABLE IF NOT EXISTS "DiagnosticBatch" (
    "id"             TEXT NOT NULL,
    "runId"          TEXT NOT NULL,
    "riderId"        TEXT NOT NULL,
    "sequenceNumber" INTEGER NOT NULL,
    "payload"        JSONB NOT NULL,
    "receivedAt"     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "DiagnosticBatch_pkey" PRIMARY KEY ("id")
);

-- RideCreateRequest
CREATE TABLE IF NOT EXISTS "RideCreateRequest" (
    "id"              TEXT NOT NULL,
    "ownerId"         TEXT NOT NULL,
    "clientRequestId" TEXT NOT NULL,
    "payloadHash"     TEXT NOT NULL,
    "roomId"          TEXT NOT NULL,
    "createdAt"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "RideCreateRequest_pkey" PRIMARY KEY ("id")
);

-- Auxiliary tables
CREATE TABLE IF NOT EXISTS "waitlist" (
    "id"        TEXT NOT NULL,
    "email"     TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "waitlist_pkey" PRIMARY KEY ("id")
);

CREATE TABLE IF NOT EXISTS "tester_applications" (
    "id"               TEXT NOT NULL,
    "name"             TEXT NOT NULL,
    "email"            TEXT NOT NULL,
    "phone"            TEXT,
    "device_model"     TEXT,
    "motorcycle_model" TEXT,
    "experience_notes" TEXT,
    "apk_version"      TEXT DEFAULT 'v0.0.3.8',
    "status"           TEXT NOT NULL DEFAULT 'PENDING',
    "download_count"   INTEGER DEFAULT 1,
    "created_at"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at"       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "tester_applications_pkey" PRIMARY KEY ("id")
);
ALTER TABLE "tester_applications" ADD COLUMN IF NOT EXISTS "status" TEXT NOT NULL DEFAULT 'PENDING';

CREATE TABLE IF NOT EXISTS "admin_settings" (
    "id"         TEXT NOT NULL,
    "settings"   JSONB NOT NULL,
    "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "admin_settings_pkey" PRIMARY KEY ("id")
);

-- ------------------------------------------------------------------------------
-- SECTION 3: Indexes & Unique Constraints
-- ------------------------------------------------------------------------------
DROP INDEX IF EXISTS "Room_name_key";
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'Room_name_key') THEN
        ALTER TABLE "Room" DROP CONSTRAINT "Room_name_key";
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS "Room_name_active_unique"
    ON "Room"(lower("name")) WHERE "status" != 'ENDED';

DROP INDEX IF EXISTS "Room_joinCode_key";
CREATE UNIQUE INDEX IF NOT EXISTS "Room_joinCode_active_unique"
    ON "Room"("joinCode") WHERE "status" != 'ENDED' AND "joinCode" IS NOT NULL;

CREATE INDEX IF NOT EXISTS "Room_joinCode_idx"    ON "Room"("joinCode");
CREATE INDEX IF NOT EXISTS "Room_status_idx"      ON "Room"("status");
CREATE INDEX IF NOT EXISTS "Room_name_status_idx" ON "Room"("name", "status");
CREATE INDEX IF NOT EXISTS "Room_lastEmptyAt_idx" ON "Room"("lastEmptyAt");
CREATE INDEX IF NOT EXISTS "Room_ownerId_idx"     ON "Room"("ownerId");
CREATE INDEX IF NOT EXISTS "Room_lkRoomName_idx"  ON "Room"("lkRoomName");

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'RideInvite_roomId_inviteeId_key') THEN
        ALTER TABLE "RideInvite" ADD CONSTRAINT "RideInvite_roomId_inviteeId_key" UNIQUE ("roomId", "inviteeId");
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS "RideInvite_roomId_status_idx"    ON "RideInvite"("roomId", "status");
CREATE INDEX IF NOT EXISTS "RideInvite_inviteeId_status_idx" ON "RideInvite"("inviteeId", "status");
CREATE INDEX IF NOT EXISTS "RideInvite_inviterId_status_idx" ON "RideInvite"("inviterId", "status");

CREATE UNIQUE INDEX IF NOT EXISTS "RoomJoinToken_token_key" ON "RoomJoinToken"("token");
CREATE INDEX IF NOT EXISTS "RoomJoinToken_roomId_idx"       ON "RoomJoinToken"("roomId");
CREATE INDEX IF NOT EXISTS "RoomJoinToken_expiresAt_idx"    ON "RoomJoinToken"("expiresAt");

CREATE UNIQUE INDEX IF NOT EXISTS "RideSession_riderId_clientRideId_key" ON "RideSession"("riderId", "clientRideId");
CREATE INDEX IF NOT EXISTS "RideSession_riderId_startTime_idx"       ON "RideSession"("riderId", "startTime" DESC);

CREATE UNIQUE INDEX IF NOT EXISTS "EmergencyAlert_senderId_clientAlertId_key" ON "EmergencyAlert"("senderId", "clientAlertId");
CREATE INDEX IF NOT EXISTS "EmergencyAlert_roomName_senderId_idx"          ON "EmergencyAlert"("roomName", "senderId");
CREATE INDEX IF NOT EXISTS "EmergencyAlert_senderId_idx"                   ON "EmergencyAlert"("senderId");

CREATE UNIQUE INDEX IF NOT EXISTS "DiagnosticRun_convoyId_testNumber_key" ON "DiagnosticRun"("convoyId", "testNumber");
CREATE INDEX IF NOT EXISTS "DiagnosticRun_convoyId_status_idx"           ON "DiagnosticRun"("convoyId", "status");

CREATE UNIQUE INDEX IF NOT EXISTS "DiagnosticParticipant_runId_riderId_key" ON "DiagnosticParticipant"("runId", "riderId");
CREATE INDEX IF NOT EXISTS "DiagnosticParticipant_runId_idx"             ON "DiagnosticParticipant"("runId");
CREATE INDEX IF NOT EXISTS "DiagnosticParticipant_riderId_idx"           ON "DiagnosticParticipant"("riderId");

CREATE UNIQUE INDEX IF NOT EXISTS "DiagnosticBatch_runId_riderId_sequenceNumber_key" ON "DiagnosticBatch"("runId", "riderId", "sequenceNumber");
CREATE INDEX IF NOT EXISTS "DiagnosticBatch_runId_riderId_idx"                      ON "DiagnosticBatch"("runId", "riderId");

CREATE UNIQUE INDEX IF NOT EXISTS "RideCreateRequest_ownerId_clientRequestId_key" ON "RideCreateRequest"("ownerId", "clientRequestId");
CREATE INDEX IF NOT EXISTS "RideCreateRequest_ownerId_idx"                        ON "RideCreateRequest"("ownerId");
CREATE INDEX IF NOT EXISTS "RideCreateRequest_roomId_idx"                         ON "RideCreateRequest"("roomId");
CREATE INDEX IF NOT EXISTS "RideCreateRequest_createdAt_idx"                      ON "RideCreateRequest"("createdAt");

CREATE UNIQUE INDEX IF NOT EXISTS "waitlist_email_key" ON "waitlist"("email");
CREATE INDEX IF NOT EXISTS "waitlist_email_idx"        ON "waitlist"("email");

CREATE INDEX IF NOT EXISTS "tester_applications_email_idx"      ON "tester_applications"("email");
CREATE INDEX IF NOT EXISTS "tester_applications_status_idx"     ON "tester_applications"("status");
CREATE INDEX IF NOT EXISTS "tester_applications_created_at_idx" ON "tester_applications"("created_at" DESC);

CREATE INDEX IF NOT EXISTS "DeviceToken_userId_idx"            ON "DeviceToken"("userId");
CREATE INDEX IF NOT EXISTS "Friendship_requesterId_status_idx" ON "Friendship"("requesterId", "status");
CREATE INDEX IF NOT EXISTS "Friendship_addresseeId_status_idx" ON "Friendship"("addresseeId", "status");
CREATE INDEX IF NOT EXISTS "ConvoyEvent_rideId_idx"            ON "ConvoyEvent"("rideId");

-- ------------------------------------------------------------------------------
-- SECTION 4: Foreign Keys
-- ------------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'Room_ownerId_fkey') THEN
        ALTER TABLE "Room" ADD CONSTRAINT "Room_ownerId_fkey"
            FOREIGN KEY ("ownerId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'RoomJoinToken_roomId_fkey') THEN
        ALTER TABLE "RoomJoinToken" ADD CONSTRAINT "RoomJoinToken_roomId_fkey"
            FOREIGN KEY ("roomId") REFERENCES "Room"("id") ON DELETE CASCADE ON UPDATE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'EmergencyAlert_senderId_fkey') THEN
        ALTER TABLE "EmergencyAlert" ADD CONSTRAINT "EmergencyAlert_senderId_fkey"
            FOREIGN KEY ("senderId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'EmergencyAlert_roomId_fkey') THEN
        ALTER TABLE "EmergencyAlert" ADD CONSTRAINT "EmergencyAlert_roomId_fkey"
            FOREIGN KEY ("roomId") REFERENCES "Room"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'DiagnosticRun_convoyId_fkey') THEN
        ALTER TABLE "DiagnosticRun" ADD CONSTRAINT "DiagnosticRun_convoyId_fkey"
            FOREIGN KEY ("convoyId") REFERENCES "Room"("id") ON DELETE CASCADE ON UPDATE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'DiagnosticParticipant_runId_fkey') THEN
        ALTER TABLE "DiagnosticParticipant" ADD CONSTRAINT "DiagnosticParticipant_runId_fkey"
            FOREIGN KEY ("runId") REFERENCES "DiagnosticRun"("id") ON DELETE CASCADE ON UPDATE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'DiagnosticBatch_runId_fkey') THEN
        ALTER TABLE "DiagnosticBatch" ADD CONSTRAINT "DiagnosticBatch_runId_fkey"
            FOREIGN KEY ("runId") REFERENCES "DiagnosticRun"("id") ON DELETE CASCADE ON UPDATE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'RideCreateRequest_ownerId_fkey') THEN
        ALTER TABLE "RideCreateRequest" ADD CONSTRAINT "RideCreateRequest_ownerId_fkey"
            FOREIGN KEY ("ownerId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'RideCreateRequest_roomId_fkey') THEN
        ALTER TABLE "RideCreateRequest" ADD CONSTRAINT "RideCreateRequest_roomId_fkey"
            FOREIGN KEY ("roomId") REFERENCES "Room"("id") ON DELETE CASCADE ON UPDATE CASCADE;
    END IF;
END $$;

-- ------------------------------------------------------------------------------
-- SECTION 5: Verification SELECT (Expected: 0 rows returned)
-- Lists any still-missing required column, index/unique constraint, or FK.
-- ------------------------------------------------------------------------------
WITH required_cols AS (
    SELECT 'User' AS tbl, UNNEST(ARRAY['id','role']) AS col
    UNION ALL
    SELECT 'Room', UNNEST(ARRAY[
        'id','name','lkRoomName','status','ownerId','lastEmptyAt',
        'offlineBackupCode','offlineMeshEnabled','joinCode','startedAt','endedAt','diagnosticsEnabled'
    ])
    UNION ALL
    SELECT 'RideInvite', UNNEST(ARRAY[
        'id','roomId','inviteeId','status','source',
        'offlineConfigStatus','offlineConfigUpdatedAt','lastLobbyPollAt','updatedAt'
    ])
    UNION ALL
    SELECT 'RoomJoinToken', UNNEST(ARRAY[
        'id','roomId','token','expiresAt','createdById','revokedAt','maxUses','useCount'
    ])
    UNION ALL
    SELECT 'RideSession', UNNEST(ARRAY[
        'id','riderId','roomName','clientRideId','diagnosticsEnabled'
    ])
    UNION ALL
    SELECT 'EmergencyAlert', UNNEST(ARRAY[
        'id','senderId','roomId','roomName','status','clientAlertId'
    ])
    UNION ALL
    SELECT 'DiagnosticRun', UNNEST(ARRAY[
        'id','convoyId','testNumber','status','closeAt'
    ])
    UNION ALL
    SELECT 'RideCreateRequest', UNNEST(ARRAY[
        'id','ownerId','clientRequestId','payloadHash','roomId'
    ])
),
actual_cols AS (
    SELECT table_name AS tbl, column_name AS col
    FROM information_schema.columns
    WHERE table_schema = 'public'
),
required_indexes AS (
    SELECT UNNEST(ARRAY[
        'Room_name_active_unique',
        'Room_joinCode_active_unique',
        'Room_lkRoomName_idx',
        'RideInvite_roomId_inviteeId_key',
        'EmergencyAlert_senderId_clientAlertId_key',
        'RideSession_riderId_clientRideId_key',
        'RideCreateRequest_ownerId_clientRequestId_key'
    ]) AS idx
),
actual_indexes AS (
    SELECT indexname AS idx FROM pg_indexes WHERE schemaname = 'public'
    UNION
    SELECT conname AS idx FROM pg_constraint WHERE contype IN ('u','p')
),
required_fks AS (
    SELECT UNNEST(ARRAY[
        'Room_ownerId_fkey',
        'RoomJoinToken_roomId_fkey',
        'EmergencyAlert_senderId_fkey',
        'EmergencyAlert_roomId_fkey',
        'DiagnosticRun_convoyId_fkey',
        'DiagnosticParticipant_runId_fkey',
        'DiagnosticBatch_runId_fkey',
        'RideCreateRequest_ownerId_fkey',
        'RideCreateRequest_roomId_fkey'
    ]) AS fk
),
actual_fks AS (
    SELECT conname AS fk FROM pg_constraint WHERE contype = 'f'
)
SELECT 'MISSING_COLUMN' AS issue_type, rc.tbl || '.' || rc.col AS item
FROM required_cols rc
LEFT JOIN actual_cols ac ON rc.tbl = ac.tbl AND rc.col = ac.col
WHERE ac.col IS NULL
UNION ALL
SELECT 'MISSING_INDEX', ri.idx
FROM required_indexes ri
LEFT JOIN actual_indexes ai ON ri.idx = ai.idx
WHERE ai.idx IS NULL
UNION ALL
SELECT 'MISSING_FK', rf.fk
FROM required_fks rf
LEFT JOIN actual_fks af ON rf.fk = af.fk
WHERE af.fk IS NULL
ORDER BY 1, 2;
