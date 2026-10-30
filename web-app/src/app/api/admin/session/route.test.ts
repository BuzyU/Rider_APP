import { describe, it, expect, vi, beforeEach } from "vitest";

const h = vi.hoisted(() => ({
  db: null as unknown as import("@/test/mockDb").Db,
  verifyIdToken: vi.fn(),
  createSessionCookie: vi.fn(),
  set: vi.fn(),
}));

vi.mock("next/headers", () => ({ cookies: async () => ({ set: h.set }) }));
vi.mock("@/utils/firebase/admin", () => ({
  adminAuth: () => ({ verifyIdToken: h.verifyIdToken, createSessionCookie: h.createSessionCookie }),
}));
vi.mock("@/utils/supabase/admin", () => ({ supabaseAdmin: { from: (t: string) => h.db.from(t) } }));

import { createDb } from "@/test/mockDb";
import { POST } from "./route";

const call = (body: unknown = { idToken: "tok" }) =>
  POST(new Request("http://x/api/admin/session", { method: "POST", body: JSON.stringify(body) }));

beforeEach(() => {
  h.db = createDb();
  h.set.mockReset();
  h.verifyIdToken.mockReset();
  h.createSessionCookie.mockReset();
  h.createSessionCookie.mockResolvedValue("session-cookie-value");
  h.verifyIdToken.mockResolvedValue({ uid: "u1", email: "admin@example.com", email_verified: true });
});

const cookiesSet = () => h.set.mock.calls.map((c) => c[0]);

describe("POST /api/admin/session", () => {
  it("valid admin by uid: 200 and sets both cookies", async () => {
    h.db.push("User.select", { data: { role: "ADMIN" }, error: null });
    const r = await call();
    expect(r.status).toBe(200);
    expect(cookiesSet()).toEqual(["__session", "rv_admin"]);
    expect(h.createSessionCookie).toHaveBeenCalledTimes(1);
  });

  it("verified email can match admin when uid has no row", async () => {
    h.db.push("User.select", { data: null, error: null });
    h.db.push("User.select", { data: { role: "ADMIN" }, error: null });
    expect((await call()).status).toBe(200);
    expect(cookiesSet()).toContain("__session");
  });

  it("unverified email cannot gain admin via email match: 403, no cookies, no email lookup", async () => {
    h.verifyIdToken.mockResolvedValue({ uid: "attacker", email: "admin@example.com", email_verified: false });
    h.db.push("User.select", { data: null, error: null });
    h.db.push("User.select", { data: { role: "ADMIN" }, error: null }); // would match if queried
    const r = await call();
    expect(r.status).toBe(403);
    expect(h.set).not.toHaveBeenCalled();
    expect(h.createSessionCookie).not.toHaveBeenCalled();
    expect(h.db.from).toHaveBeenCalledTimes(1); // uid lookup only
  });

  it("non-admin role: 403 and no cookies", async () => {
    h.db.push("User.select", { data: { role: "USER" }, error: null });
    expect((await call()).status).toBe(403);
    expect(h.set).not.toHaveBeenCalled();
  });

  it("missing or non-string idToken: 400, Firebase never called", async () => {
    expect((await call({})).status).toBe(400);
    expect((await call({ idToken: 123 })).status).toBe(400);
    expect(h.verifyIdToken).not.toHaveBeenCalled();
  });

  it("invalid token: 401 with generic body", async () => {
    h.verifyIdToken.mockRejectedValue(new Error("Firebase ID token has expired project-secret-123"));
    const r = await call();
    expect(r.status).toBe(401);
    const text = JSON.stringify(await r.json());
    expect(text).not.toContain("project-secret");
    expect(text).not.toContain("expired");
    expect(h.set).not.toHaveBeenCalled();
  });

  it("role lookup DB error: denied, generic, no cookies, no DB text", async () => {
    h.db.push("User.select", { data: null, error: { message: 'relation "User" secret_column failed' } });
    const r = await call();
    expect(r.status).toBe(403);
    expect(JSON.stringify(await r.json())).not.toContain("secret_column");
    expect(h.set).not.toHaveBeenCalled();
  });

  it("thrown lookup failure: 401 generic, no cookies", async () => {
    h.db.from = vi.fn(() => {
      throw new Error("connection string postgres://user:pw@host");
    }) as never;
    const r = await call();
    expect(r.status).toBe(401);
    expect(JSON.stringify(await r.json())).not.toContain("postgres://");
    expect(h.set).not.toHaveBeenCalled();
  });
});

