-- ==============================================================================
-- RIDER VOICE DATABASE TABLE FOR TESTER APPLICATIONS
-- ==============================================================================
-- Run this script in the Supabase SQL Editor (Dashboard -> SQL Editor -> New Query -> Run)
-- This table stores beta tester applications submitted from the website before downloading the APK.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS "tester_applications" (
    "id" TEXT NOT NULL DEFAULT gen_random_uuid()::text,
    "name" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "phone" TEXT,
    "device_model" TEXT,
    "motorcycle_model" TEXT,
    "experience_notes" TEXT,
    "apk_version" TEXT DEFAULT 'v0.0.3.7',
    "download_count" INTEGER DEFAULT 1,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "tester_applications_pkey" PRIMARY KEY ("id")
);

-- Performance and lookup indexes
CREATE INDEX IF NOT EXISTS "tester_applications_email_idx" ON "tester_applications"("email");
CREATE INDEX IF NOT EXISTS "tester_applications_created_at_idx" ON "tester_applications"("created_at" DESC);

-- Enable Row Level Security (RLS)
ALTER TABLE "tester_applications" ENABLE ROW LEVEL SECURITY;

-- Allow anon and authenticated users to submit applications
DO $$
BEGIN
    DROP POLICY IF EXISTS "Allow public insert on tester_applications" ON "tester_applications";
    CREATE POLICY "Allow public insert on tester_applications"
        ON "tester_applications" FOR INSERT
        WITH CHECK (true);
EXCEPTION
    WHEN undefined_table THEN NULL;
END $$;

-- Allow service role full access
DO $$
BEGIN
    DROP POLICY IF EXISTS "Allow service role full access on tester_applications" ON "tester_applications";
    CREATE POLICY "Allow service role full access on tester_applications"
        ON "tester_applications" FOR ALL
        USING (auth.role() = 'service_role');
EXCEPTION
    WHEN undefined_table THEN NULL;
END $$;

