-- ==============================================================================
-- RIDER VOICE DATABASE TABLES FOR WAITLIST & TESTER APPLICATIONS
-- ==============================================================================
-- Run this script in the Supabase SQL Editor (Dashboard -> SQL Editor -> New Query -> Run)
-- This creates both the "waitlist" and "tester_applications" tables with RLS and indexes.
-- ==============================================================================

-- 1. Create "waitlist" table (Homepage early access waitlist)
CREATE TABLE IF NOT EXISTS "waitlist" (
    "id" TEXT NOT NULL DEFAULT gen_random_uuid()::text,
    "email" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "waitlist_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "waitlist_email_key" ON "waitlist"("email");
CREATE INDEX IF NOT EXISTS "waitlist_email_idx" ON "waitlist"("email");

ALTER TABLE "waitlist" ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    DROP POLICY IF EXISTS "Allow public insert on waitlist" ON "waitlist";
    CREATE POLICY "Allow public insert on waitlist"
        ON "waitlist" FOR INSERT
        WITH CHECK (true);
EXCEPTION
    WHEN undefined_table THEN NULL;
END $$;

DO $$
BEGIN
    DROP POLICY IF EXISTS "Allow service role full access on waitlist" ON "waitlist";
    CREATE POLICY "Allow service role full access on waitlist"
        ON "waitlist" FOR ALL
        USING (auth.role() = 'service_role');
EXCEPTION
    WHEN undefined_table THEN NULL;
END $$;

-- 2. Create "tester_applications" table (Beta tester forum & APK download applications)
CREATE TABLE IF NOT EXISTS "tester_applications" (
    "id" TEXT NOT NULL DEFAULT gen_random_uuid()::text,
    "name" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "phone" TEXT,
    "device_model" TEXT,
    "motorcycle_model" TEXT,
    "experience_notes" TEXT,
    "apk_version" TEXT DEFAULT 'v0.0.3.7',
    "status" TEXT NOT NULL DEFAULT 'PENDING',
    "download_count" INTEGER DEFAULT 1,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "tester_applications_pkey" PRIMARY KEY ("id")
);

-- Ensure status column exists if table was previously created
ALTER TABLE "tester_applications" ADD COLUMN IF NOT EXISTS "status" TEXT NOT NULL DEFAULT 'PENDING';

CREATE INDEX IF NOT EXISTS "tester_applications_email_idx" ON "tester_applications"("email");
CREATE INDEX IF NOT EXISTS "tester_applications_created_at_idx" ON "tester_applications"("created_at" DESC);
CREATE INDEX IF NOT EXISTS "tester_applications_status_idx" ON "tester_applications"("status");

ALTER TABLE "tester_applications" ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    DROP POLICY IF EXISTS "Allow public insert on tester_applications" ON "tester_applications";
    CREATE POLICY "Allow public insert on tester_applications"
        ON "tester_applications" FOR INSERT
        WITH CHECK (true);
EXCEPTION
    WHEN undefined_table THEN NULL;
END $$;

DO $$
BEGIN
    DROP POLICY IF EXISTS "Allow service role full access on tester_applications" ON "tester_applications";
    CREATE POLICY "Allow service role full access on tester_applications"
        ON "tester_applications" FOR ALL
        USING (auth.role() = 'service_role');
EXCEPTION
    WHEN undefined_table THEN NULL;
END $$;
