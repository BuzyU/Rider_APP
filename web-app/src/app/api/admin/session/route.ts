import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { adminAuth } from "@/utils/firebase/admin";
import { supabaseAdmin } from "@/utils/supabase/admin";

export async function POST(request: Request) {
  const { idToken } = await request.json();

  if (!idToken || typeof idToken !== "string") {
    return NextResponse.json({ error: "Missing idToken" }, { status: 400 });
  }

  const expiresIn = 1000 * 60 * 60 * 24 * 5;

  try {
    const decoded = await adminAuth().verifyIdToken(idToken);
    const { data: user, error } = await supabaseAdmin
      .from("User")
      .select("role, email")
      .or(`id.eq.${decoded.uid},email.eq.${decoded.email ?? ""}`)
      .maybeSingle();

    if (error || !user || user.role !== "ADMIN") {
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
    const message = error instanceof Error ? error.message : "Unknown error";
    return NextResponse.json(
      { error: `Auth error: ${message}` },
      { status: 401 }
    );
  }
}
