import { requireAdminPage } from "@/lib/auth/requireAdminPage";
import { supabaseAdmin } from "@/utils/supabase/admin";

export const dynamic = "force-dynamic";

export default async function AdminRoomsPage() {
  await requireAdminPage();
  const { data: rooms } = await supabaseAdmin
    .from("Room")
    .select("id, name, ownerId, createdAt")
    .order("createdAt", { ascending: false })
    .limit(20);

  const ownerIds = rooms?.map((room) => room.ownerId).filter(Boolean) ?? [];
  const { data: owners } = ownerIds.length
    ? await supabaseAdmin.from("User").select("id, displayName, email").in("id", ownerIds)
    : { data: [] };
  const ownerMap = new Map(owners?.map((owner) => [owner.id, owner]));

  return (
    <section className="admin-card">
      <span className="admin-section-pill">LiveKit</span>
      <h2>Rooms & audio streams</h2>
      <p>Monitor LiveKit rooms and keep audio quality within spec.</p>
      <div className="admin-grid" style={{ marginTop: "20px" }}>
        {rooms?.length ? (
          rooms.map((room) => {
            const owner = ownerMap.get(room.ownerId);
            return (
              <div key={room.id} className="admin-kpi">
                <span className="admin-tag">{room.name}</span>
                <strong>Owner: {owner?.displayName ?? owner?.email ?? room.ownerId}</strong>
                <span className="admin-muted">
                  Created {room.createdAt ? new Date(room.createdAt).toLocaleString() : "—"}
                </span>
              </div>
            );
          })
        ) : (
          <div className="admin-empty">No LiveKit rooms available yet.</div>
        )}
      </div>
    </section>
  );
}
