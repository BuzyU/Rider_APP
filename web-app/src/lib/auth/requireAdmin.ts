import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { adminAuth } from "@/utils/firebase/admin";
import { supabaseAdmin } from "@/utils/supabase/admin";

export type AdminUser = {
  uid: string;
  email?: string;
  role: string;
};

export async function requireAdmin(): Promise<AdminUser | NextResponse> {
  const cookieStore = await cookies();
  const sessionCookie = cookieStore.get("__session")?.value;

  if (!sessionCookie) {
    return NextResponse.json({ error: "Unauthorized: Missing session" }, { status: 401 });
  }

  let decoded;
  try {
    decoded = await adminAuth().verifySessionCookie(sessionCookie, true);
  } catch (err: any) {
    return NextResponse.json(
      { error: `Unauthorized: Invalid session (${err?.message || "verification failed"})` },
      { status: 401 }
    );
  }

  const { data: user, error } = await supabaseAdmin
    .from("User")
    .select("role, email")
    .or(`id.eq.${decoded.uid},email.eq.${decoded.email ?? ""}`)
    .maybeSingle();

  if (error || !user || user.role !== "ADMIN") {
    return NextResponse.json(
      { error: "Forbidden: Admin access required" },
      { status: 403 }
    );
  }

  return {
    uid: decoded.uid,
    email: decoded.email,
    role: user.role,
  };
}
