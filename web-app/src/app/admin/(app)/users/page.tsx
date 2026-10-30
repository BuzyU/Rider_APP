import { requireAdminPage } from "@/lib/auth/requireAdminPage";
import UsersTable from "./UsersTable";
import { supabaseAdmin } from "@/utils/supabase/admin";

export const dynamic = "force-dynamic";

export default async function AdminUsersPage() {
  await requireAdminPage();
  const { data: users } = await supabaseAdmin
    .from("User")
    .select("id, email, handle, displayName, role, createdAt")
    .order("createdAt", { ascending: false });

  return (
    <section className="admin-card">
      <span className="admin-section-pill">People</span>
      <h2>All users</h2>
      <p>Switch roles instantly to move riders between Customer and Admin.</p>
      <div style={{ marginTop: "20px" }}>
        <UsersTable initialUsers={users ?? []} />
      </div>
    </section>
  );
}
