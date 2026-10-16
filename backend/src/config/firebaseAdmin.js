const admin = require('firebase-admin')

let serviceAccount = null

if (process.env.FIREBASE_SERVICE_ACCOUNT) {
    try {
        serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT)
    } catch (e) {
        throw new Error(`[FirebaseAdmin] Failed to parse FIREBASE_SERVICE_ACCOUNT JSON: ${e.message}`)
    }
} else {
    try {
        serviceAccount = require('../../riderapp-5ce68-firebase-adminsdk-fbsvc-7c77e002fa.json')
    } catch (e) {
    }
}

if (!serviceAccount) {
    if (process.env.NODE_ENV === 'production') {
        throw new Error(
            '[FirebaseAdmin] FATAL: Production environment requires Firebase credentials via FIREBASE_SERVICE_ACCOUNT env var or local credential JSON. Server refusing to start.'
        )
    }
    if (process.env.NODE_ENV === 'test') {
        console.warn('[FirebaseAdmin] Running in TEST mode without Firebase service account.')
    } else {
        console.warn('[FirebaseAdmin] Running in DEVELOPMENT mode without Firebase credentials. Verify FIREBASE_SERVICE_ACCOUNT.')
    }
} else if (!admin.apps.length) {
    try {
        if (serviceAccount.private_key) {
            serviceAccount.private_key = serviceAccount.private_key.replace(/\\n/g, '\n')
        }
        admin.initializeApp({
            credential: admin.credential.cert(serviceAccount)
        })
    } catch (e) {
        if (process.env.NODE_ENV === 'test') {
            console.warn('[FirebaseAdmin] Test mode initialized without live Firebase certificate:', e.message)
        } else {
            throw e
        }
    }
}

module.exports = admin
