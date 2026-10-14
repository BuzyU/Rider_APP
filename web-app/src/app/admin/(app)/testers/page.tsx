import { supabaseAdmin } from "@/utils/supabase/admin";

export const dynamic = "force-dynamic";

interface TesterApplicationRecord {
  id: string;
  name: string;
  email: string;
  phone: string | null;
  device_model: string | null;
  motorcycle_model: string | null;
  experience_notes: string | null;
  apk_version: string | null;
  download_count: number | null;
  created_at: string;
}

export default async function AdminTestersPage() {
  let applications: TesterApplicationRecord[] = [];
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
      applications = data as TesterApplicationRecord[];
    }
  } catch {
    tableMissing = true;
  }

  return (
    <section className="admin-card">
      <span className="admin-section-pill">Closed Beta</span>
      <h2>Tester Applications</h2>
      <p>
        Riders who completed the tester application form to download the Android APK.
      </p>

      {tableMissing && (
        <div style={{
          marginTop: "1.5rem",
          padding: "1rem 1.25rem",
          backgroundColor: "rgba(255, 85, 0, 0.08)",
          border: "1px solid rgba(255, 85, 0, 0.2)",
          borderRadius: "12px",
          color: "var(--text-primary)"
        }}>
          <strong>Note:</strong> The <code>tester_applications</code> table has not been created in Supabase yet.
          Run <code>backend/supabase_tester_applications.sql</code> in your Supabase SQL Editor to enable full database persistence.
        </div>
      )}

      <div style={{ marginTop: "1.5rem", overflowX: "auto" }}>
        {applications.length > 0 ? (
          <table className="admin-table">
            <thead>
              <tr>
                <th>Applicant</th>
                <th>Contact</th>
                <th>Motorcycle & Device</th>
                <th>Notes</th>
                <th>APK Version</th>
                <th>Downloads</th>
                <th>Applied Date</th>
              </tr>
            </thead>
            <tbody>
              {applications.map((app) => (
                <tr key={app.id}>
                  <td>
                    <strong>{app.name}</strong>
                  </td>
                  <td>
                    <div>{app.email}</div>
                    {app.phone && (
                      <div className="admin-muted" style={{ fontSize: "0.85rem" }}>
                        {app.phone}
                      </div>
                    )}
                  </td>
                  <td>
                    {app.motorcycle_model && <div>{app.motorcycle_model}</div>}
                    {app.device_model && (
                      <div className="admin-muted" style={{ fontSize: "0.85rem" }}>
                        {app.device_model}
                      </div>
                    )}
                    {!app.motorcycle_model && !app.device_model && (
                      <span className="admin-muted">—</span>
                    )}
                  </td>
                  <td style={{ maxWidth: "260px", wordBreak: "break-word" }}>
                    {app.experience_notes || <span className="admin-muted">—</span>}
                  </td>
                  <td>
                    <span className="admin-tag admin-tag--success">
                      {app.apk_version || "v0.0.3.7"}
                    </span>
                  </td>
                  <td>{app.download_count ?? 1}</td>
                  <td>{new Date(app.created_at).toLocaleDateString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          !tableMissing && (
            <div className="admin-empty">
              No tester applications submitted yet.
            </div>
          )
        )}
      </div>
    </section>
  );
}

