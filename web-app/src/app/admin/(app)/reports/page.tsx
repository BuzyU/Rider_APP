import { requireAdminPage } from "@/lib/auth/requireAdminPage";
import { supabaseAdmin } from "@/utils/supabase/admin";

export const dynamic = "force-dynamic";

export default async function AdminReportsPage() {
  await requireAdminPage();
  const now = new Date();
  const thirtyDaysAgo = new Date(now);
  thirtyDaysAgo.setDate(now.getDate() - 30);

  const [ridesCount, roomsCount, inviteCount, totalDistance] = await Promise.all([
    supabaseAdmin.from("RideSession").select("id", { count: "exact", head: true }),
    supabaseAdmin.from("Room").select("id", { count: "exact", head: true }),
    supabaseAdmin.from("RideInvite").select("id", { count: "exact", head: true }),
    supabaseAdmin.from("RideSession").select("distanceKm"),
  ]);

  const { data: recentRides } = await supabaseAdmin
    .from("RideSession")
    .select("id, distanceKm, startTime")
    .gte("startTime", thirtyDaysAgo.toISOString())
    .order("startTime", { ascending: false })
    .limit(6);

  const totalDistanceValue = totalDistance.data
    ? totalDistance.data.reduce((sum, ride) => sum + (ride.distanceKm ?? 0), 0)
    : 0;

  return (
    <section className="admin-card">
      <span className="admin-section-pill">Reports</span>
      <h2>Signal and safety reports</h2>
      <p>Operational summaries for the last 30 days.</p>
      <div className="admin-split" style={{ marginTop: "20px" }}>
        <div className="admin-kpi">
          <span className="admin-tag">Total rides</span>
          <strong>{ridesCount.count ?? 0}</strong>
          <span className="admin-muted">All time</span>
        </div>
        <div className="admin-kpi">
          <span className="admin-tag">Total distance</span>
          <strong>{totalDistanceValue.toFixed(1)} km</strong>
          <span className="admin-muted">Across all rides</span>
        </div>
        <div className="admin-kpi">
          <span className="admin-tag">Rooms / Invites</span>
          <strong>{roomsCount.count ?? 0} / {inviteCount.count ?? 0}</strong>
          <span className="admin-muted">Total created</span>
        </div>
      </div>
      <div className="admin-card" style={{ marginTop: "20px" }}>
        <h2>Recent ride distances</h2>
        {recentRides?.length ? (
          <div className="admin-chart">
            {recentRides.map((ride, index) => {
              const height = Math.min(100, Math.max(8, ride.distanceKm ?? 0));
              return (
                <div
                  key={`report-bar-${index}`}
                  className="admin-chart__bar"
                  style={{ height: `${height}%` }}
                />
              );
            })}
          </div>
        ) : (
          <div className="admin-empty">No recent rides in the last 30 days.</div>
        )}
      </div>
    </section>
  );
}
