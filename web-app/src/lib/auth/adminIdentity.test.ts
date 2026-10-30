import { describe, it, expect, vi, beforeEach } from "vitest";

const h = vi.hoisted(() => ({ db: null as unknown as Db }));
vi.mock("@/utils/supabase/admin", () => ({ supabaseAdmin: { from: (t: string) => h.db.from(t) } }));

import { createDb, type Db } from "@/test/mockDb";
import { resolveRole } from "./adminIdentity";

beforeEach(() => { h.db = createDb(); });

describe("resolveRole", () => {
  it("uid match returns role", async () => {
    h.db.push("User.select", { data: { role: "ADMIN" }, error: null });
    expect(await resolveRole({ uid: "u", email: "a@x.com", email_verified: false })).toBe("ADMIN");
  });
  it("unverified email never matches by email", async () => {
    h.db.push("User.select", { data: null, error: null });
    expect(await resolveRole({ uid: "u", email: "admin@x.com", email_verified: false })).toBeNull();
    expect(h.db.from).toHaveBeenCalledTimes(1); // uid lookup only
  });
  it("verified email falls back to email match", async () => {
    h.db.push("User.select", { data: null, error: null });
    h.db.push("User.select", { data: { role: "ADMIN" }, error: null });
    expect(await resolveRole({ uid: "u", email: "admin@x.com", email_verified: true })).toBe("ADMIN");
  });
  it("lookup error yields null; special characters are never interpolated", async () => {
    h.db.push("User.select", { data: null, error: { message: "x" } });
    expect(await resolveRole({ uid: "u", email: "a,role.eq.ADMIN@x.com", email_verified: true })).toBeNull();
  });
});

