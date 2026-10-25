/**
 * reconcile_data.js – Standalone Operator Data Reconciliation Script
 *
 * Scans for:
 * 1. Stale unended active/lobby rooms older than 12h.
 * 2. Duplicate active room names.
 *
 * Default is DRY-RUN mode (safe, read-only inspection).
 * Pass --apply flag to commit changes.
 */

'use strict'

const prisma = require('../src/db')

async function main() {
    const isApply = process.argv.includes('--apply')
    console.log(`[Reconcile] Mode: ${isApply ? 'APPLY (mutations will be committed)' : 'DRY-RUN (read-only inspection)'}`)

    const twelveHoursAgo = new Date(Date.now() - 12 * 60 * 60 * 1000)

    // 1. Stale rooms older than 12h
    const staleRooms = await prisma.room.findMany({
        where: {
            status: { not: 'ENDED' },
            OR: [
                { startedAt: { lte: twelveHoursAgo } },
                { startedAt: null, createdAt: { lte: twelveHoursAgo } }
            ]
        },
        select: { id: true, name: true, status: true, createdAt: true, startedAt: true }
    })

    console.log(`[Reconcile] Found ${staleRooms.length} stale non-ended rooms older than 12h:`)
    for (const r of staleRooms) {
        console.log(`  - Room [${r.id}] "${r.name}" (${r.status}) created=${r.createdAt?.toISOString()}`)
    }

    // 2. Duplicate active room names
    const activeRooms = await prisma.room.findMany({
        where: { status: { not: 'ENDED' } },
        select: { id: true, name: true, createdAt: true },
        orderBy: { createdAt: 'desc' }
    })

    const seenNames = new Map()
    const duplicateRooms = []

    for (const r of activeRooms) {
        const lower = r.name.toLowerCase()
        if (seenNames.has(lower)) {
            duplicateRooms.push({ ...r, primaryId: seenNames.get(lower) })
        } else {
            seenNames.set(lower, r.id)
        }
    }

    console.log(`[Reconcile] Found ${duplicateRooms.length} duplicate active rooms:`)
    for (const d of duplicateRooms) {
        console.log(`  - Duplicate Room [${d.id}] "${d.name}" (newer duplicate of [${d.primaryId}])`)
    }

    if (!isApply) {
        console.log('\n[Reconcile] Dry-run complete. No database changes were made.')
        console.log('To apply these reconciliation updates, rerun with: node scripts/reconcile_data.js --apply\n')
        return
    }

    console.log('\n[Reconcile] Applying reconciliation updates...')
    const now = new Date()

    const staleIds = staleRooms.map(r => r.id)
    const dupIds = duplicateRooms.map(d => d.id)
    const allToClose = Array.from(new Set([...staleIds, ...dupIds]))

    if (allToClose.length > 0) {
        const res = await prisma.room.updateMany({
            where: { id: { in: allToClose }, status: { not: 'ENDED' } },
            data: { status: 'ENDED', endedAt: now, joinCode: null }
        })
        console.log(`[Reconcile] Successfully ended ${res.count} rooms.`)
    } else {
        console.log('[Reconcile] No rooms required status updates.')
    }

    console.log('[Reconcile] Reconciliation complete.\n')
}

main()
    .catch(err => {
        console.error('[Reconcile] Error:', err)
        process.exit(1)
    })
    .finally(() => prisma.$disconnect())
