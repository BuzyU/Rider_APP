import { requireAdminPage } from "@/lib/auth/requireAdminPage";
import SettingsForm from "./SettingsForm";
import { supabaseAdmin } from "@/utils/supabase/admin";

export const dynamic = "force-dynamic";

export default async function AdminSettingsPage() {
  await requireAdminPage();
  const { data } = await supabaseAdmin
    .from("admin_settings")
    .select("settings")
    .eq("id", "default")
    .maybeSingle();

  return (
    <section className="admin-card">
      <span className="admin-section-pill">Preferences</span>
      <h2>Operational settings</h2>
      <p>Adjust alerts, room behavior, and service targets.</p>
      <SettingsForm initialSettings={data?.settings ?? null} />
    </section>
  );
}
