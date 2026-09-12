const admin = require('../config/firebaseAdmin')
const prisma = require('../db')

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

    try {
        const decodedToken = await admin.auth().verifyIdToken(token)
        req.user = decodedToken

        const now = Date.now()
        const lastSync = syncedUsers.get(decodedToken.uid)

        if (!lastSync || now - lastSync > SYNC_TTL_MS) {
            try {
                await prisma.user.upsert({
                    where: { id: decodedToken.uid },
                    update: {},
                    create: {
                        id: decodedToken.uid,
                        email: decodedToken.email || null,
                        displayName: decodedToken.name || (decodedToken.email ? decodedToken.email.split('@')[0] : 'Rider')
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
                            displayName: decodedToken.name || 'Rider'
                        }
                    }).catch(() => {})
                    syncedUsers.set(decodedToken.uid, now)
                }
            }
        }

        next()
    } catch (error) {
        console.error('Firebase Auth Error:', error.message)
        return res.status(401).json({ error: 'Unauthorized: invalid Firebase token' })
    }
}
