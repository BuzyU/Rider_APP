import { supabaseAdmin } from "@/utils/supabase/admin";

export const dynamic = "force-dynamic";

export default async function AdminDashboardPage() {
  const [, roomsCount, usersCount, inviteCount, rides] = await Promise.all([
    supabaseAdmin.from("RideSession").select("id", { count: "exact", head: true }),
    supabaseAdmin.from("Room").select("id", { count: "exact", head: true }),
    supabaseAdmin.from("User").select("id", { count: "exact", head: true }),
    supabaseAdmin.from("RideInvite").select("id", { count: "exact", head: true }),
    supabaseAdmin
      .from("RideSession")
      .select("id, startTime, endTime, distanceKm, riderId")
      .order("startTime", { ascending: false })
      .limit(6),
  ]);

  const activeRides = await supabaseAdmin
    .from("RideSession")
    .select("id", { count: "exact", head: true })
    .is("endTime", null);

  const riderIds = rides.data?.map((ride) => ride.riderId).filter(Boolean) ?? [];
  const { data: riders } = riderIds.length
    ? await supabaseAdmin.from("User").select("id, displayName, email").in("id", riderIds)
    : { data: [] };
  const riderMap = new Map(riders?.map((rider) => [rider.id, rider]));

  return (
    <>
      <section className="admin-card">
        <span className="admin-section-pill">Live overview</span>
        <h2>Convoy command overview</h2>
        <p>
          A pulse on active rides, audio rooms, and incident resolution. Keep
          the most critical signals in view and respond fast.
        </p>
        <div className="admin-grid" style={{ marginTop: "20px" }}>
          {[
            {
              label: "Active rides",
              value: activeRides.count ?? 0,
              note: "Currently in progress",
            },
            {
              label: "Live rooms",
              value: roomsCount.count ?? 0,
              note: "Total rooms",
            },
            {
              label: "Total users",
              value: usersCount.count ?? 0,
              note: "Registered riders",
            },
            {
              label: "Ride invites",
              value: inviteCount.count ?? 0,
              note: "Total invites",
            },
          ].map((kpi) => (
            <div key={kpi.label} className="admin-kpi">
              <span className="admin-tag">{kpi.label}</span>
              <strong>{kpi.value}</strong>
              <span className="admin-muted">{kpi.note}</span>
            </div>
          ))}
        </div>
      </section>

      <section className="admin-split">
        <div className="admin-card">
          <h2>Live operations board</h2>
          {rides.data?.length ? (
            <table className="admin-table">
              <thead>
                <tr>
                  <th>Ride</th>
                  <th>Status</th>
                  <th>Lead</th>
                  <th>Distance (km)</th>
                </tr>
              </thead>
              <tbody>
                {rides.data.map((ride) => {
                  const rider = riderMap.get(ride.riderId);
                  const status = ride.endTime ? "Completed" : "Active";
                  return (
                    <tr key={ride.id}>
                      <td>{ride.id}</td>
                      <td>
                        <span className="admin-tag admin-tag--success">
                          {status}
                        </span>
                      </td>
                      <td>{rider?.displayName ?? rider?.email ?? ride.riderId}</td>
                      <td>{ride.distanceKm}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          ) : (
            <div className="admin-empty">No ride sessions available yet.</div>
          )}
        </div>
        <div className="admin-card">
          <h2>Signal health</h2>
          {rides.data?.length ? (
            <div className="admin-chart">
              {rides.data.map((ride, index) => {
                const height = Math.min(100, Math.max(8, ride.distanceKm ?? 0));
                return (
                  <div
                    key={`bar-${index}`}
                    className="admin-chart__bar"
                    style={{ height: `${height}%` }}
                  />
                );
              })}
            </div>
          ) : (
            <div className="admin-empty">No telemetry data available yet.</div>
          )}
        </div>
      </section>
    </>
  );
}
