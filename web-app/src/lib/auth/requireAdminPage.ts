import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { adminAuth } from "@/utils/firebase/admin";
import { resolveRole } from "@/lib/auth/adminIdentity";

/**
 * Page/loader-level admin guard. Call at the top of every admin server page
 * and server action before touching `supabaseAdmin`. Middleware cookie checks
 * are only a redirect convenience; this is the real boundary.
 */
export async function requireAdminPage(): Promise<{ uid: string; email?: string }> {
  const cookieStore = await cookies();
  const sessionCookie = cookieStore.get("__session")?.value;
  if (!sessionCookie) redirect("/admin/login");

  let decoded;
  try {
    decoded = await adminAuth().verifySessionCookie(sessionCookie, true);
  } catch {
    redirect("/admin/login");
  }

  const role = await resolveRole({
    uid: decoded.uid,
    email: decoded.email,
    email_verified: decoded.email_verified,
  });
  if (role !== "ADMIN") redirect("/admin/login?error=forbidden");

  return { uid: decoded.uid, email: decoded.email };
}

