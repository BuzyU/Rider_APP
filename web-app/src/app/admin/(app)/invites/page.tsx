import { requireAdminPage } from "@/lib/auth/requireAdminPage";
import { supabaseAdmin } from "@/utils/supabase/admin";

export const dynamic = "force-dynamic";

export default async function AdminInvitesPage() {
  await requireAdminPage();
  const { data: invites } = await supabaseAdmin
    .from("RideInvite")
    .select("id, inviterId, inviteeId, status, createdAt, roomId")
    .order("createdAt", { ascending: false })
    .limit(25);

  const userIds = invites
    ? Array.from(new Set(invites.flatMap((invite) => [invite.inviterId, invite.inviteeId])))
    : [];
  const { data: users } = userIds.length
    ? await supabaseAdmin.from("User").select("id, email, displayName").in("id", userIds)
    : { data: [] };
  const userMap = new Map(users?.map((user) => [user.id, user]));

  return (
    <section className="admin-card">
      <span className="admin-section-pill">Invitations</span>
      <h2>Invite pipeline</h2>
      <p>Track pending invites and keep onboarding clean.</p>
      <div style={{ marginTop: "20px" }}>
        {invites?.length ? (
          <table className="admin-table">
            <thead>
              <tr>
                <th>Invitee</th>
                <th>Inviter</th>
                <th>Status</th>
                <th>Sent</th>
              </tr>
            </thead>
            <tbody>
              {invites.map((invite) => {
                const inviter = userMap.get(invite.inviterId);
                const invitee = userMap.get(invite.inviteeId);
                return (
                  <tr key={invite.id}>
                    <td>{invitee?.displayName ?? invitee?.email ?? invite.inviteeId}</td>
                    <td>{inviter?.displayName ?? inviter?.email ?? invite.inviterId}</td>
                    <td>
                      <span className="admin-tag">{invite.status}</span>
                    </td>
                    <td>
                      {invite.createdAt
                        ? new Date(invite.createdAt).toLocaleString()
                        : "—"}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        ) : (
          <div className="admin-empty">No invites sent yet.</div>
        )}
      </div>
    </section>
  );
}
