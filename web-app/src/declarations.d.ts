declare module '*.module.css' {
  const classes: { [key: string]: string };
  export default classes;
}

declare module 'lucide-react';

declare module 'framer-motion' {
  export type Variants = any;
  export const motion: any;
}

declare module 'firebase/app' {
  export class FirebaseError extends Error {
    code: string;
  }
  export const initializeApp: any;
  export const getApps: any;
  export const getApp: any;
}

declare module 'firebase/auth' {
  export const getAuth: any;
  export const signInWithEmailAndPassword: any;
}

