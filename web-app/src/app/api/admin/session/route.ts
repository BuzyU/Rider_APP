import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { adminAuth } from "@/utils/firebase/admin";
import { resolveRole } from "@/lib/auth/adminIdentity";

export async function POST(request: Request) {
  const { idToken } = await request.json();

  if (!idToken || typeof idToken !== "string") {
    return NextResponse.json({ error: "Missing idToken" }, { status: 400 });
  }

  const expiresIn = 1000 * 60 * 60 * 24 * 5;

  try {
    const decoded = await adminAuth().verifyIdToken(idToken);
    const role = await resolveRole({ uid: decoded.uid, email: decoded.email, email_verified: decoded.email_verified });

    if (role !== "ADMIN") {
      return NextResponse.json({ error: "Admin access required" }, { status: 403 });
    }

    const sessionCookie = await adminAuth().createSessionCookie(idToken, {
      expiresIn,
    });

    const cookieStore = await cookies();
    cookieStore.set("__session", sessionCookie, {
      httpOnly: true,
      secure: process.env.NODE_ENV === "production",
      sameSite: "lax",
      path: "/",
      maxAge: expiresIn / 1000,
    });
    cookieStore.set("rv_admin", "1", {
      httpOnly: true,
      secure: process.env.NODE_ENV === "production",
      sameSite: "lax",
      path: "/",
      maxAge: expiresIn / 1000,
    });

    return NextResponse.json({ ok: true });
  } catch (error: unknown) {
    console.warn("[admin/session] auth failed:", error);
    return NextResponse.json(
      { error: "Authentication failed" },
      { status: 401 }
    );
  }
}
