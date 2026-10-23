/**
 * preflight.js - Production deploy preflight verification
 *
 * Verifies that all required environment variables, database connectivity,
 * and expected schema columns exist prior to starting the HTTP server.
 * Fails fast with code 1 if any preflight check fails.
 */

'use strict'

require('dotenv').config()
const prisma = require('../src/db')

async function runPreflight() {
    console.log('[Preflight] Running deploy preflight verification...')

    const errors = []

    // 1. Verify required environment variables
    const requiredEnvs = ['DATABASE_URL', 'LIVEKIT_URL', 'LIVEKIT_API_KEY', 'LIVEKIT_API_SECRET']
    for (const key of requiredEnvs) {
        if (!process.env[key] || process.env[key].trim() === '') {
            errors.push(`Missing required environment variable: ${key}`)
        }
    }

    // Check Firebase service account (either FIREBASE_SERVICE_ACCOUNT or GOOGLE_APPLICATION_CREDENTIALS)
    if (process.env.NODE_ENV === 'production') {
        const hasFirebase = process.env.FIREBASE_SERVICE_ACCOUNT || process.env.GOOGLE_APPLICATION_CREDENTIALS
        if (!hasFirebase) {
            errors.push('Missing FIREBASE_SERVICE_ACCOUNT or GOOGLE_APPLICATION_CREDENTIALS in production environment')
        }
    }

    // Check pooler vs DIRECT_URL
    const dbUrl = process.env.DATABASE_URL || ''
    if ((dbUrl.includes(':6543') || dbUrl.includes('pgbouncer=true')) && !process.env.DIRECT_URL) {
        console.warn('[Preflight] WARNING: DATABASE_URL appears to use a connection pooler (:6543 or pgbouncer=true), but DIRECT_URL is not set. Prisma migrate requires DIRECT_URL for non-pooled connection.')
    }

    // 2. Database connectivity and Room.status column check
    try {
        const dbCheck = await prisma.$queryRaw`SELECT 1 AS ok`
        if (!dbCheck || dbCheck[0]?.ok !== 1) {
            errors.push('Database connectivity check failed (SELECT 1 returned unexpected result)')
        }

        // Verify Room.status column exists
        const colCheck = await prisma.$queryRaw`
            SELECT column_name
            FROM information_schema.columns
            WHERE table_name = 'Room' AND column_name = 'status'
        `
        if (!colCheck || colCheck.length === 0) {
            errors.push('Database schema verification failed: Room.status column does not exist. Did prisma migrate deploy run?')
        }
    } catch (dbErr) {
        errors.push(`Database connection/query error during preflight: ${dbErr.message}`)
    }

    if (errors.length > 0) {
        console.error('\n========================================')
        console.error('PREFLIGHT VERIFICATION FAILED:')
        errors.forEach(err => console.error(`  - ${err}`))
        console.error('========================================\n')
        process.exit(1)
    }

    console.log('[Preflight] All preflight checks passed successfully.')
    await prisma.$disconnect().catch(() => {})
    process.exit(0)
}

runPreflight().catch(err => {
    console.error('[Preflight] Unexpected error:', err)
    process.exit(1)
})
