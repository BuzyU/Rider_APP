import { NextResponse } from "next/server";
import { supabaseAdmin } from "@/utils/supabase/admin";
import { requireAdmin } from "@/lib/auth/requireAdmin";

export async function POST(request: Request) {
  const authResult = await requireAdmin();
  if (authResult instanceof NextResponse) {
    return authResult;
  }

  const body = await request.json();
  const payload = {
    alertThreshold: typeof body.alertThreshold === "number" ? body.alertThreshold : null,
    latencyTargetMs: typeof body.latencyTargetMs === "number" ? body.latencyTargetMs : null,
    policyNote: typeof body.policyNote === "string" ? body.policyNote : "",
  };

  const { error } = await supabaseAdmin
    .from("admin_settings")
    .upsert({ id: "default", settings: payload }, { onConflict: "id" });

  if (error) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }

  return NextResponse.json({ ok: true });
}
