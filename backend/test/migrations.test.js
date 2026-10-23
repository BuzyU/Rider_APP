const { describe, it } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const { execSync } = require('child_process')
const EmbeddedPostgresPkg = require('embedded-postgres')
const EmbeddedPostgres = EmbeddedPostgresPkg.default || EmbeddedPostgresPkg
const { Client } = require('pg')

describe('Gate 5: Database Migrations & Schema Drift', () => {
    const migrationSqlPath = path.join(__dirname, '../prisma/migrations/20261015_add_room_diagnostics_and_started_at/migration.sql')

    it('new migration must be additive only (new column with default, no drops or renames)', () => {
        assert.ok(fs.existsSync(migrationSqlPath), 'Migration file must exist')
        const sqlContent = fs.readFileSync(migrationSqlPath, 'utf8')

        // Must not drop tables or columns
        assert.doesNotMatch(sqlContent, /\bDROP\b/i, 'Migration must not contain DROP statements')

        // Must not rename tables or columns
        assert.doesNotMatch(sqlContent, /\bRENAME\b/i, 'Migration must not contain RENAME statements')

        // Must be additive: ALTER TABLE ADD COLUMN
        assert.match(sqlContent, /ALTER\s+TABLE\s+"Room"\s+ADD\s+COLUMN/i, 'Migration must add columns to Room')
        assert.match(sqlContent, /"diagnosticsEnabled"\s+BOOLEAN/i, 'Must add diagnosticsEnabled column')
        assert.match(sqlContent, /DEFAULT\s+false/i, 'diagnosticsEnabled must have default false')
        assert.match(sqlContent, /"startedAt"\s+TIMESTAMP/i, 'Must add startedAt column')
    })

    it('npx prisma validate passes without errors', () => {
        const output = execSync('npx prisma validate', {
            cwd: path.join(__dirname, '..'),
            encoding: 'utf8',
            env: { ...process.env, DIRECT_URL: process.env.DIRECT_URL || process.env.DATABASE_URL || 'postgresql://postgres:password@localhost:5432/ridervoice_test' }
        })
        assert.match(output, /is valid/i, 'Prisma schema must be valid')
    })

    it('applies migrations to fresh DB and previous DB state without schema drift', { timeout: 120000 }, async () => {
        const testPort = 5437
        const testDataDir = path.join(__dirname, '../.pg_data_migration_test')
        const pg = new EmbeddedPostgres({
            databaseDir: testDataDir,
            user: 'postgres',
            password: 'password',
            port: testPort,
            persistent: false
        })

        try {
            if (fs.existsSync(testDataDir)) {
                fs.rmSync(testDataDir, { recursive: true, force: true })
            }
            await pg.initialise()
            await pg.start()

            await pg.createDatabase('ridervoice_prev')
            await pg.createDatabase('ridervoice_fresh')

            async function runSqlFile(dbName, filePath) {
                const client = new Client({
                    host: 'localhost',
                    port: testPort,
                    user: 'postgres',
                    password: 'password',
                    database: dbName
                })
                await client.connect()
                const sql = fs.readFileSync(filePath, 'utf8')
                await client.query(sql)
                await client.end()
            }

            const baseSchemaPath = path.join(__dirname, '../supabase_schema.sql')
            const testerAppSchemaPath = path.join(__dirname, '../supabase_tester_applications.sql')
            const diagMigrationPath = path.join(__dirname, '../prisma/migrations/20261014_add_network_diagnostics/migration.sql')
            const enumValuesMigrationPath = path.join(__dirname, '../prisma/migrations/20261023000000_a_enum_values/migration.sql')
            const phase1MigrationPath = path.join(__dirname, '../prisma/migrations/20261023_phase1_room_lifecycle_membership/migration.sql')
            const postPhase1MigrationPath = path.join(__dirname, '../prisma/migrations/20261024_post_phase1_fixups/migration.sql')

            async function setupDb(dbName) {
                await runSqlFile(dbName, baseSchemaPath)
                const client = new Client({ host: 'localhost', port: testPort, user: 'postgres', password: 'password', database: dbName })
                await client.connect()
                await client.query(`
                    CREATE SCHEMA IF NOT EXISTS auth;
                    CREATE OR REPLACE FUNCTION auth.role() RETURNS text AS $$ SELECT 'service_role'::text; $$ LANGUAGE sql;
                    ALTER TABLE "admin_settings" ALTER COLUMN "updated_at" DROP DEFAULT;
                `)
                await client.end()
                await runSqlFile(dbName, testerAppSchemaPath)
                const client2 = new Client({ host: 'localhost', port: testPort, user: 'postgres', password: 'password', database: dbName })
                await client2.connect()
                await client2.query(`
                    ALTER TABLE "tester_applications" ALTER COLUMN "id" DROP DEFAULT;
                    ALTER TABLE "tester_applications" ALTER COLUMN "apk_version" SET DEFAULT 'v0.0.3.8';
                    ALTER TABLE "tester_applications" ALTER COLUMN "updated_at" DROP DEFAULT;
                    ALTER TABLE "waitlist" ALTER COLUMN "id" DROP DEFAULT;
                `)
                await client2.end()
                await runSqlFile(dbName, diagMigrationPath)
            }

            // 1. Previous DB State (base schema + diagnostics migration)
            await setupDb('ridervoice_prev')

            // Verify room table in previous DB state does NOT have diagnosticsEnabled yet
            const prevClient = new Client({ host: 'localhost', port: testPort, user: 'postgres', password: 'password', database: 'ridervoice_prev' })
            await prevClient.connect()
            const prevCols = await prevClient.query(`
                SELECT column_name FROM information_schema.columns 
                WHERE table_name = 'Room' AND column_name IN ('diagnosticsEnabled', 'startedAt');
            `)
            assert.strictEqual(prevCols.rows.length, 0, 'Previous state must not have new columns before migration')
            await prevClient.end()

            // Apply new additive migration to previous DB state
            await runSqlFile('ridervoice_prev', migrationSqlPath)
            await runSqlFile('ridervoice_prev', enumValuesMigrationPath)
            await runSqlFile('ridervoice_prev', phase1MigrationPath)
            await runSqlFile('ridervoice_prev', postPhase1MigrationPath)

            // Verify columns now exist in ridervoice_prev
            const upgradedClient = new Client({ host: 'localhost', port: testPort, user: 'postgres', password: 'password', database: 'ridervoice_prev' })
            await upgradedClient.connect()
            const upgradedCols = await upgradedClient.query(`
                SELECT column_name FROM information_schema.columns 
                WHERE table_name = 'Room' AND column_name IN ('diagnosticsEnabled', 'startedAt');
            `)
            assert.strictEqual(upgradedCols.rows.length, 2, 'Upgraded DB state must have both new columns')
            await upgradedClient.end()

            // 2. Fresh DB (base schema + past migration + new migration)
            await setupDb('ridervoice_fresh')
            await runSqlFile('ridervoice_fresh', migrationSqlPath)
            await runSqlFile('ridervoice_fresh', enumValuesMigrationPath)
            await runSqlFile('ridervoice_fresh', phase1MigrationPath)
            await runSqlFile('ridervoice_fresh', postPhase1MigrationPath)

            // 3. Diff fresh DB against schema.prisma and assert no drift
            const dbUrl = `postgresql://postgres:password@localhost:${testPort}/ridervoice_fresh`
            const diffOutput = execSync(`npx prisma migrate diff --from-url "${dbUrl}" --to-schema-datamodel prisma/schema.prisma --exit-code`, {
                cwd: path.join(__dirname, '..'),
                encoding: 'utf8',
                env: { ...process.env, DIRECT_URL: dbUrl }
            })
            assert.ok(
                diffOutput.includes('No difference detected') || diffOutput.trim() === '',
                `There must be zero schema drift between applied migrations and schema.prisma. Diff: ${diffOutput}`
            )
        } finally {
            try {
                await Promise.race([
                    pg.stop(),
                    new Promise(resolve => setTimeout(resolve, 2000))
                ])
            } catch (e) {}
        }
    })
})
