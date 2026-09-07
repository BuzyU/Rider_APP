const admin = require('../config/firebaseAdmin')
const prisma = require('../db')

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
        req.user = decodedToken // Inject user info into the request

        // Auto-sync user to Supabase so foreign key constraints never fail
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
        } catch (syncErr) {
            if (syncErr.code === 'P2002') {
                // Unique email conflict fallback
                await prisma.user.upsert({
                    where: { id: decodedToken.uid },
                    update: {},
                    create: {
                        id: decodedToken.uid,
                        email: null,
                        displayName: decodedToken.name || 'Rider'
                    }
                }).catch(() => {})
            }
        }

        next()
    } catch (error) {
        console.error('Firebase Auth Error:', error.message)
        return res.status(401).json({ error: 'Unauthorized: invalid Firebase token' })
    }
}
