import { describe, it, expect, vi, beforeEach } from "vitest";

const h = vi.hoisted(() => ({
  db: null as unknown as Db,
  cookie: undefined as string | undefined,
  verify: vi.fn(),
}));

vi.mock("next/headers", () => ({
  cookies: async () => ({ get: (n: string) => (n === "__session" && h.cookie ? { value: h.cookie } : undefined) }),
}));
vi.mock("@/utils/firebase/admin", () => ({ adminAuth: () => ({ verifySessionCookie: h.verify }) }));
vi.mock("@/utils/supabase/admin", () => ({ supabaseAdmin: { from: (t: string) => h.db.from(t) } }));

import { createDb, type Db } from "@/test/mockDb";
import { PATCH } from "./route";

const call = (status: unknown, id = "abc") =>
  PATCH(
    new Request("http://x/api/admin/testers/" + id, { method: "PATCH", body: JSON.stringify({ status }) }),
    { params: Promise.resolve({ id }) }
  );

beforeEach(() => {
  h.db = createDb();
  h.cookie = "sess";
  h.verify.mockReset();
  h.verify.mockResolvedValue({ uid: "u1", email: "a@x.com", email_verified: true });
  h.db.push("User.select", { data: { role: "ADMIN" }, error: null });
});

const updates = () => h.db.calls.filter((c: { op: string }) => c.op === "update");

describe("PATCH /api/admin/testers/[id]", () => {
  it("401 without cookie, no update", async () => {
    h.cookie = undefined;
    expect((await call("APPROVED")).status).toBe(401);
    expect(updates()).toHaveLength(0);
  });
  it("401 on invalid session", async () => {
    h.verify.mockRejectedValue(new Error("boom secret"));
    const r = await call("APPROVED");
    expect(r.status).toBe(401);
    expect(JSON.stringify(await r.json())).not.toContain("boom");
  });
  it("403 for non-admin", async () => {
    h.db = createDb();
    h.db.push("User.select", { data: { role: "USER" }, error: null });
    expect((await call("APPROVED")).status).toBe(403);
    expect(updates()).toHaveLength(0);
  });
  it("400 on bad status", async () => {
    expect((await call("HACKED")).status).toBe(400);
    expect(updates()).toHaveLength(0);
  });
  it("200 with exactly one update", async () => {
    h.db.push("tester_applications.update", { data: [{ id: "abc" }], error: null });
    expect((await call("APPROVED")).status).toBe(200);
    expect(updates()).toHaveLength(1);
  });
  it("404 when no rows", async () => {
    h.db.push("tester_applications.update", { data: [], error: null });
    expect((await call("APPROVED")).status).toBe(404);
  });
  it("500 generic on db error", async () => {
    h.db.push("tester_applications.update", { data: null, error: { message: "relation secret_table" } });
    const r = await call("APPROVED");
    expect(r.status).toBe(500);
    expect(JSON.stringify(await r.json())).not.toContain("secret_table");
  });
});

