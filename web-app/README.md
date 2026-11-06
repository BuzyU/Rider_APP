# RiderVoice Web Application & Admin Console

Next.js 16 (App Router + TypeScript) web application powering the RiderVoice public landing site, deep-link convoy join gateway (`/j/[token]`), waitlist intake (`/api/waitlist`), APK download redirect (`/download`), and authenticated operational admin console (`/admin`).

---

## 1. Local Development

```bash
cd web-app
npm install
npm run dev
```

Open `http://localhost:3000` in your browser.

### Verification Commands

```bash
npm run typecheck
npm run build
```

---

## 2. Application Structure

- `src/app/page.tsx`: Public marketing and waitlist landing page.
- `src/app/j/[token]/page.tsx`: Convoy invite deep-link landing page with one-tap Android app handoff (`ridervoice://join/<token>`) and APK fallback.
- `src/app/download/route.ts`: Direct redirect to the latest signed RiderVoice release APK.
- `src/app/privacy/page.tsx` & `src/app/terms/page.tsx`: Privacy policy and terms of service.
- `src/app/admin/(auth)/login/page.tsx`: Firebase-authenticated admin sign-in.
- `src/app/admin/(app)/`: Operational console for users, rooms, invites, rides, reports, roles, and platform settings.

---

## 3. Admin Console Deployment (Vercel)

The admin UI lives under `/admin` and uses Firebase Admin to protect routes.

### Vercel Project Settings
1. Import this repo and set **Root Directory** to `web-app`.
2. Framework preset: **Next.js**.
3. Build command: `next build` (default).
4. Deploy.

### Required Environment Variables
Set these in Vercel Project Settings → Environment Variables:

- `FIREBASE_PROJECT_ID`
- `FIREBASE_CLIENT_EMAIL`
- `FIREBASE_PRIVATE_KEY` (paste with `\n` for line breaks)
- `NEXT_PUBLIC_FIREBASE_API_KEY`
- `NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN`
- `NEXT_PUBLIC_FIREBASE_PROJECT_ID`
- `NEXT_PUBLIC_FIREBASE_APP_ID`

### Session Cookie Flow
The admin layout expects a Firebase session cookie named `__session`.
Create the cookie by sending an ID token to:

- `POST /api/admin/session` with JSON `{ "idToken": "<firebase-id-token>" }`

Clear the cookie with:

- `POST /api/admin/logout`
