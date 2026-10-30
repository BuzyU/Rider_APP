import { supabaseAdmin } from "@/utils/supabase/admin";

export type VerifiedClaims = {
  uid: string;
  email?: string;
  email_verified?: boolean;
};

/**
 * Resolve the stored role for a verified Firebase identity.
 *
 * Matching is done with two separate parameterized `.eq` queries (never an
 * interpolated `.or()` filter). The uid is the primary key; an email match is
 * only honoured when Firebase reports `email_verified === true`.
 * Returns null when no role can be established (or on any lookup error).
 */
export async function resolveRole(claims: VerifiedClaims): Promise<string | null> {
  const byId = await supabaseAdmin
    .from("User")
    .select("role")
    .eq("id", claims.uid)
    .maybeSingle();

  if (byId.error) return null;
  if (byId.data) return byId.data.role ?? null;

  if (claims.email && claims.email_verified === true) {
    const byEmail = await supabaseAdmin
      .from("User")
      .select("role")
      .eq("email", claims.email)
      .maybeSingle();
    if (byEmail.error || !byEmail.data) return null;
    return byEmail.data.role ?? null;
  }

  return null;
}

