const admin = require('../config/firebaseAdmin')
const prisma = require('../db')
const cacheService = require('../services/cacheService')

const syncedUsers = new Map()
const SYNC_TTL_MS = 5 * 60 * 1000

function sweepSyncedUsers() {
    const now = Date.now()
    for (const [uid, lastSync] of syncedUsers.entries()) {
        if (now - lastSync > SYNC_TTL_MS) {
            syncedUsers.delete(uid)
        }
    }
}

const sweepInterval = setInterval(sweepSyncedUsers, 5 * 60 * 1000)
if (sweepInterval.unref) sweepInterval.unref()

/**
 * Phase 8.9 – Legacy client gate helper.
 * Returns true if the client should be blocked (upgrade required).
 */
function isLegacyBlocked(appVersion) {
    if (process.env.LEGACY_CLIENTS_ALLOWED !== 'false') return false
    const minVersion = process.env.MIN_APP_VERSION
    if (!minVersion || !appVersion) return false
    // Simple semver compare (major.minor.patch only)
    const parse = v => v.replace(/[^0-9.]/g, '').split('.').map(Number)
    const [mj, mn, mp] = parse(minVersion)
    const [aj, an, ap] = parse(appVersion)
    if (aj < mj) return true
    if (aj > mj) return false
    if (an < mn) return true
    if (an > mn) return false
    return ap < mp
}

module.exports = async (req, res, next) => {
    const authHeader = req.headers.authorization

    if (!authHeader) {
        return res.status(401).json({ error: 'Unauthorized: missing Authorization header' })
    }

    const parts = authHeader.split(' ')
    if (parts.length !== 2 || parts[0] !== 'Bearer') {
        return res.status(401).json({ error: 'Unauthorized: malformed Authorization header' })
    }

    const token = parts[1]

    // Phase 0.6 – attach app version to every authenticated request
    req.appVersion = req.headers['x-app-version'] || null

    try {
        const tokenKey = token.length > 32 ? token.slice(-32) : token
        let decodedToken = cacheService.get(`auth_token:${tokenKey}`)
        if (!decodedToken) {
            if (token.startsWith('test_')) {
                if (process.env.NODE_ENV === 'production') {
                    console.error('[SecurityViolation] Test token rejected: test tokens are strictly forbidden in production.')
                    return res.status(401).json({ error: 'Unauthorized: test tokens are strictly forbidden in production' })
                }
                if (process.env.NODE_ENV !== 'test') {
                    return res.status(401).json({ error: 'Unauthorized: test tokens are only permitted when NODE_ENV=test' })
                }
                const dbUrl = process.env.DATABASE_URL || ''
                const isLocalDb = dbUrl.includes('localhost') || dbUrl.includes('127.0.0.1')
                if (!isLocalDb) {
                    console.error('[SecurityViolation] Test tokens rejected: DATABASE_URL host is not localhost/127.0.0.1.')
                    return res.status(401).json({ error: 'Unauthorized: test auth is disabled when DATABASE_URL is not localhost/127.0.0.1' })
                }
                decodedToken = { uid: token, email: `${token}@test.ridervoice.com`, name: `Rider_${token}` }
            } else {
                decodedToken = await admin.auth().verifyIdToken(token)
            }
            cacheService.set(`auth_token:${tokenKey}`, decodedToken, 60000)
        }
        req.user = decodedToken

        const now = Date.now()
        const lastSync = syncedUsers.get(decodedToken.uid)

        if (!lastSync || now - lastSync > SYNC_TTL_MS) {
            try {
                const isGuestEmail = decodedToken.email && decodedToken.email.endsWith('@guest.ridervoice.local')
                const resolvedEmail = isGuestEmail ? null : (decodedToken.email || null)
                const resolvedName = decodedToken.name || (isGuestEmail ? 'Guest Rider' : (decodedToken.email ? decodedToken.email.split('@')[0] : 'Rider'))
                await prisma.user.upsert({
                    where: { id: decodedToken.uid },
                    update: {},
                    create: {
                        id: decodedToken.uid,
                        email: resolvedEmail,
                        displayName: resolvedName
                    }
                })
                syncedUsers.set(decodedToken.uid, now)
            } catch (syncErr) {
                if (syncErr.code === 'P2002') {
                    await prisma.user.upsert({
                        where: { id: decodedToken.uid },
                        update: {},
                        create: {
                            id: decodedToken.uid,
                            email: null,
                            displayName: decodedToken.name || 'Guest Rider'
                        }
                    }).catch(() => {})
                    syncedUsers.set(decodedToken.uid, now)
                }
            }
        }

        // Phase 8.9 – legacy client gate (426 Upgrade Required)
        if (isLegacyBlocked(req.appVersion)) {
            const apkUrl = process.env.APK_DOWNLOAD_URL || 'https://ridervoice.app/download'
            return res.status(426).json({
                error: 'Upgrade required',
                code: 'UPGRADE_REQUIRED',
                minVersion: process.env.MIN_APP_VERSION,
                apkUrl
            })
        }

        next()
    } catch (error) {
        console.error('Firebase Auth Error:', error.message)
        return res.status(401).json({ error: 'Unauthorized: invalid Firebase token' })
    }
}
