import { requireAdminPage } from "@/lib/auth/requireAdminPage";
import { supabaseAdmin } from "@/utils/supabase/admin";
import TestersTable, { TesterApplicationRow } from "./TestersTable";

export const dynamic = "force-dynamic";

export default async function AdminTestersPage() {
  await requireAdminPage();
  let applications: TesterApplicationRow[] = [];
  let tableMissing = false;

  try {
    const { data, error } = await supabaseAdmin
      .from("tester_applications")
      .select("*")
      .order("created_at", { ascending: false });

    if (error) {
      if (error.code === "42P01") {
        tableMissing = true;
      }
    } else if (data) {
      applications = data as TesterApplicationRow[];
    }
  } catch {
    tableMissing = true;
  }

  return (
    <section className="admin-card">
      <span className="admin-section-pill">Early Access</span>
      <h2>Tester Applications</h2>
      <p>
        Review rider applications, approve or reject tester access, and monitor APK test deployments.
      </p>

      {tableMissing && (
        <div
          style={{
            marginTop: "1.5rem",
            padding: "1rem 1.25rem",
            backgroundColor: "rgba(255, 85, 0, 0.08)",
            border: "1px solid rgba(255, 85, 0, 0.2)",
            borderRadius: "12px",
            color: "var(--text-primary)",
          }}
        >
          <strong>Note:</strong> The <code>tester_applications</code> table has not been created in Supabase yet.
          Run <code>backend/supabase_tester_applications.sql</code> in your Supabase SQL Editor to enable full database persistence.
        </div>
      )}

      <div style={{ marginTop: "1.5rem", overflowX: "auto" }}>
        <TestersTable initialApplications={applications} />
      </div>
    </section>
  );
}
