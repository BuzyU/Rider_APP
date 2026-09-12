import Link from "next/link";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { Cormorant_Garamond, IBM_Plex_Sans } from "next/font/google";
import { adminAuth } from "@/utils/firebase/admin";
import { supabaseAdmin } from "@/utils/supabase/admin";
import "../admin.css";

const adminSerif = Cormorant_Garamond({
  subsets: ["latin"],
  weight: ["400", "600", "700"],
  variable: "--font-admin-serif",
});

const adminSans = IBM_Plex_Sans({
  subsets: ["latin"],
  weight: ["300", "400", "500", "600", "700"],
  variable: "--font-admin-sans",
});

export const dynamic = "force-dynamic";

export default async function AdminAppLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const cookieStore = await cookies();
  const sessionCookie = cookieStore.get("__session")?.value;

  if (!sessionCookie) {
    redirect("/admin/login");
  }

  try {
    await adminAuth().verifySessionCookie(sessionCookie, true);
  } catch {
    redirect("/admin/login");
  }

  let userCount: number | null = null;
  let rideCount: number | null = null;

  try {
    const [{ count: uCount }, { count: rCount }] = await Promise.all([
      supabaseAdmin.from("User").select("*", { count: "exact", head: true }),
      supabaseAdmin.from("RideSession").select("*", { count: "exact", head: true }),
    ]);
    userCount = uCount;
    rideCount = rCount;
  } catch {
  }

  const navItems = [
    { label: "Overview", href: "/admin" },
    { label: "All Users", href: "/admin/users", badge: userCount !== null ? String(userCount) : undefined },
    { label: "Roles", href: "/admin/roles" },
    { label: "Rides", href: "/admin/rides", badge: rideCount !== null ? String(rideCount) : undefined },
    { label: "Rooms", href: "/admin/rooms" },
    { label: "Invites", href: "/admin/invites" },
    { label: "Reports", href: "/admin/reports" },
    { label: "Settings", href: "/admin/settings" },
  ];

  return (
    <div className={`admin-shell ${adminSerif.variable} ${adminSans.variable}`}>
      <div className="admin-shell__backdrop" aria-hidden="true" />
      <aside className="admin-sidebar">
        <div className="admin-brand">
          <div className="admin-brand__crest">RV</div>
          <div>
            <div className="admin-brand__title">Rider Voice</div>
            <div className="admin-brand__subtitle">Command Console</div>
          </div>
        </div>
        <nav className="admin-nav">
          {navItems.map((item) => (
            <Link key={item.href} href={item.href}>
              <span>{item.label}</span>
              {item.badge ? <span className="admin-pill">{item.badge}</span> : null}
            </Link>
          ))}
        </nav>
        <div className="admin-sidebar__footer">
          <div className="admin-status">
            <span className="admin-status__dot" />
            Control channel live
          </div>
          LiveKit hub stable, 3 alerts pending
        </div>
      </aside>
      <div className="admin-main">
        <header className="admin-topbar">
          <div className="admin-topbar__title">
            <span className="admin-section-pill">Operations</span>
            <h1>Admin Control</h1>
          </div>
          <div className="admin-topbar__actions">
            <div className="admin-search">
              <input
                type="text"
                placeholder="Search rides, users, rooms"
                aria-label="Search rides, users, rooms"
              />
            </div>
            <button className="admin-button admin-button--ghost" type="button">
              Create alert
            </button>
            <button className="admin-button admin-button--primary" type="button">
              New ride
            </button>
          </div>
        </header>
        <main className="admin-content">{children}</main>
      </div>
    </div>
  );
}
