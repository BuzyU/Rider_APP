import { NextResponse } from "next/server";
import { supabaseAdmin } from "@/utils/supabase/admin";
import { requireAdmin } from "@/lib/auth/requireAdmin";

export async function GET() {
  const authResult = await requireAdmin();
  if (authResult instanceof NextResponse) {
    return authResult;
  }

  const { data, error } = await supabaseAdmin
    .from("admin_settings")
    .select("settings")
    .eq("id", "default")
    .maybeSingle();

  if (error) {
    console.error("[admin api] db error:", error);
    return NextResponse.json({ error: "Internal server error" }, { status: 500 });
  }

  return NextResponse.json({ settings: data?.settings ?? null });
}
