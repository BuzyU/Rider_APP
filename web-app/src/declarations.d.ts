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

declare module 'next' {
  export type Metadata = any;
  export type NextPage<P = {}, IP = P> = any;
  export type NextConfig = any;
}

declare module 'next/link' {
  const content: any;
  export default content;
}

declare module 'next/navigation' {
  export const useRouter: any;
  export const usePathname: any;
  export const useSearchParams: any;
  export const redirect: any;
}

declare module 'next/headers' {
  export const cookies: any;
  export const headers: any;
}

declare module 'next/server' {
  export class NextRequest {
    cookies: any;
    headers: any;
    nextUrl: any;
    url: string;
  }
  export class NextResponse {
    static json(body: any, init?: any): any;
    static redirect(url: string | URL, status?: number): any;
    static next(init?: any): any;
  }
}

declare module 'next/font/google';
declare module 'next/types.js';
declare module 'next/server.js';
