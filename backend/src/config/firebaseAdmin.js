const admin = require('firebase-admin');
const path = require('path');

// Try to load from environment variable first (for Render deployment), otherwise fallback to local file
let serviceAccount;
if (process.env.FIREBASE_SERVICE_ACCOUNT) {
    serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
} else {
    serviceAccount = require('../../riderapp-5ce68-firebase-adminsdk-fbsvc-7c77e002fa.json');
}

if (!admin.apps.length) {
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount)
    });
}

module.exports = admin;
