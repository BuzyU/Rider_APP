import { cert, getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";

let adminApp = getApps()[0];

const getAdminApp = () => {
  if (adminApp) {
    return adminApp;
  }

  const stripQuotes = (val?: string) => val?.replace(/^["'\s,]+|["'\s,]+$/g, '');

  const projectId = stripQuotes(process.env.FIREBASE_PROJECT_ID);
  const clientEmail = stripQuotes(process.env.FIREBASE_CLIENT_EMAIL);
  const privateKey = stripQuotes(process.env.FIREBASE_PRIVATE_KEY)?.replace(/\\n/g, "\n");

  if (!projectId || !clientEmail || !privateKey) {
    throw new Error("Missing Firebase Admin credentials in environment variables.");
  }

  adminApp = initializeApp({
    credential: cert({
      projectId,
      clientEmail,
      privateKey,
    }),
  });

  return adminApp;
};

export const adminAuth = () => getAuth(getAdminApp());
