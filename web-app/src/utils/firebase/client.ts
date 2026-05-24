import { initializeApp, getApps } from "firebase/app";
import { getAuth } from "firebase/auth";

const stripQuotes = (val?: string) => val?.replace(/^["'\s,]+|["'\s,]+$/g, '');

const firebaseConfig = {
  apiKey: stripQuotes(process.env.NEXT_PUBLIC_FIREBASE_API_KEY),
  authDomain: stripQuotes(process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN),
  projectId: stripQuotes(process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID),
  appId: stripQuotes(process.env.NEXT_PUBLIC_FIREBASE_APP_ID),
};

const hasConfig = Object.values(firebaseConfig).every(Boolean);

const app = hasConfig
  ? getApps()[0] ?? initializeApp(firebaseConfig)
  : null;

export const firebaseAuth = hasConfig && app ? getAuth(app) : null;
