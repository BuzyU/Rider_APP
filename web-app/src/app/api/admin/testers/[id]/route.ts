import { NextResponse } from "next/server";
import { requireAdmin } from "@/lib/auth/requireAdmin";
import { supabaseAdmin } from "@/utils/supabase/admin";

const STATUSES = ["PENDING", "APPROVED", "REJECTED"];

export async function PATCH(
  request: Request,
  context: { params: Promise<{ id: string }> }
) {
  const admin = await requireAdmin();
  if (admin instanceof NextResponse) return admin;

  const { id } = await context.params;

  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON" }, { status: 400 });
  }
  const status = (body as { status?: unknown } | null)?.status;

  if (!id || id.length > 64 || typeof status !== "string" || !STATUSES.includes(status)) {
    return NextResponse.json(
      { error: "Valid id and status (PENDING, APPROVED, REJECTED) are required." },
      { status: 400 }
    );
  }

  try {
    const { data, error } = await supabaseAdmin
      .from("tester_applications")
      .update({ status, updated_at: new Date().toISOString() })
      .eq("id", id)
      .select("id");

    if (error) {
      console.error("[admin/testers] update failed:", error);
      return NextResponse.json({ error: "Internal server error" }, { status: 500 });
    }
    if (!data || data.length === 0) {
      return NextResponse.json({ error: "Application not found" }, { status: 404 });
    }
    return NextResponse.json({ success: true, id, status });
  } catch (err) {
    console.error("[admin/testers] unexpected error:", err);
    return NextResponse.json({ error: "Internal server error" }, { status: 500 });
  }
}

