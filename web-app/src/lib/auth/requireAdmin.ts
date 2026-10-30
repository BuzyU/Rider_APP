import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { adminAuth } from "@/utils/firebase/admin";
import { resolveRole } from "@/lib/auth/adminIdentity";

export type AdminUser = {
  uid: string;
  email?: string;
  role: string;
};

export async function requireAdmin(): Promise<AdminUser | NextResponse> {
  const cookieStore = await cookies();
  const sessionCookie = cookieStore.get("__session")?.value;

  if (!sessionCookie) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  let decoded;
  try {
    decoded = await adminAuth().verifySessionCookie(sessionCookie, true);
  } catch (err: unknown) {
    console.warn("[requireAdmin] session verification failed:", err);
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  const role = await resolveRole({
    uid: decoded.uid,
    email: decoded.email,
    email_verified: decoded.email_verified,
  });

  if (role !== "ADMIN") {
    return NextResponse.json(
      { error: "Forbidden: Admin access required" },
      { status: 403 }
    );
  }

  return { uid: decoded.uid, email: decoded.email, role };
}
