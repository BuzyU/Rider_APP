import { requireAdminPage } from "@/lib/auth/requireAdminPage";
import { supabaseAdmin } from "@/utils/supabase/admin";

export const dynamic = "force-dynamic";

export default async function AdminRolesPage() {
  await requireAdminPage();
  const [adminCount, customerCount] = await Promise.all([
    supabaseAdmin
      .from("User")
      .select("id", { count: "exact", head: true })
      .eq("role", "ADMIN"),
    supabaseAdmin
      .from("User")
      .select("id", { count: "exact", head: true })
      .eq("role", "CUSTOMER"),
  ]);

  return (
    <section className="admin-card">
      <span className="admin-section-pill">Policy</span>
      <h2>Role control matrix</h2>
      <p>Define what each role can access across live rides and operations.</p>
      <div className="admin-grid" style={{ marginTop: "20px" }}>
        <div className="admin-kpi">
          <span className="admin-tag">Admin</span>
          <strong>{adminCount.count ?? 0} admins</strong>
          <span className="admin-muted">
            Can manage rides, rooms, alerts, and assign roles.
          </span>
        </div>
        <div className="admin-kpi">
          <span className="admin-tag">Customer</span>
          <strong>{customerCount.count ?? 0} customers</strong>
          <span className="admin-muted">
            Can join rides, manage profile, and view personal stats.
          </span>
        </div>
      </div>
    </section>
  );
}
