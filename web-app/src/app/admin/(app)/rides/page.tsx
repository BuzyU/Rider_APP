import { requireAdminPage } from "@/lib/auth/requireAdminPage";
import { supabaseAdmin } from "@/utils/supabase/admin";

export const dynamic = "force-dynamic";

export default async function AdminRidesPage() {
  await requireAdminPage();
  const { data: rides } = await supabaseAdmin
    .from("RideSession")
    .select("id, riderId, startTime, endTime, distanceKm")
    .order("startTime", { ascending: false })
    .limit(25);

  const riderIds = rides?.map((ride) => ride.riderId).filter(Boolean) ?? [];
  const { data: riders } = riderIds.length
    ? await supabaseAdmin.from("User").select("id, displayName, email").in("id", riderIds)
    : { data: [] };
  const riderMap = new Map(riders?.map((rider) => [rider.id, rider]));

  return (
    <section className="admin-card">
      <span className="admin-section-pill">Fleet</span>
      <h2>Ride roster</h2>
      <p>Track convoy status, route discipline, and coordinator assignment.</p>
      <div style={{ marginTop: "20px" }}>
        {rides?.length ? (
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
              {rides.map((ride) => {
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
          <div className="admin-empty">No ride sessions recorded yet.</div>
        )}
      </div>
    </section>
  );
}
