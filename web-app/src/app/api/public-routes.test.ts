import { describe, it, expect, vi, beforeEach } from "vitest";

const h = vi.hoisted(() => ({ db: null as unknown as Db, mail: vi.fn() }));
vi.mock("@/utils/supabase/admin", () => ({ supabaseAdmin: { from: (t: string) => h.db.from(t) } }));
vi.mock("@/lib/email/smtpClient", () => ({ sendSmtpMail: h.mail }));

import { createDb, type Db } from "@/test/mockDb";
import { resetRateLimits } from "@/lib/rateLimit";
import * as tester from "./tester-application/route";
import * as waitlist from "./waitlist/route";

const post = (mod: { POST: (r: Request) => Promise<Response> }, body: unknown, ip = "1.1.1.1") =>
  mod.POST(new Request("http://x", { method: "POST", headers: { "x-forwarded-for": ip }, body: typeof body === "string" ? body : JSON.stringify(body) }));
const goodTester = { name: "Rider", email: "r@example.com" };

beforeEach(() => {
  h.db = createDb();
  h.mail.mockReset();
  h.mail.mockResolvedValue({ success: true, messageId: "m" });
  resetRateLimits();
  vi.stubGlobal("fetch", vi.fn(async () => ({ ok: false })));
});

describe("tester-application POST", () => {
  it("old public PATCH is gone", () => {
    expect("PATCH" in tester).toBe(false);
  });
  it("success inserts and sends one email", async () => {
    const r = await post(tester, goodTester);
    expect(r.status).toBe(200);
    expect(h.db.calls.filter((c: { op: string }) => c.op === "insert")).toHaveLength(1);
    expect(h.mail).toHaveBeenCalledTimes(1);
  });
  it.each([
    ["select", "tester_applications.select"],
    ["insert", "tester_applications.insert"],
  ])("503 and no email when %s errors", async (_n, key) => {
    h.db.push(key, { data: null, error: { code: "XX", message: "pg internal detail" } });
    const r = await post(tester, goodTester);
    expect(r.status).toBe(503);
    expect(h.mail).not.toHaveBeenCalled();
    expect(JSON.stringify(await r.json())).not.toContain("pg internal");
  });
  it("503 and no email when update errors", async () => {
    h.db.push("tester_applications.select", { data: { id: "1" }, error: null });
    h.db.push("tester_applications.update", { data: null, error: { message: "x" } });
    expect((await post(tester, goodTester)).status).toBe(503);
    expect(h.mail).not.toHaveBeenCalled();
  });
  it("duplicate 23505 is idempotent 200 with no email", async () => {
    h.db.push("tester_applications.insert", { data: null, error: { code: "23505" } });
    expect((await post(tester, goodTester)).status).toBe(200);
    expect(h.mail).not.toHaveBeenCalled();
  });
  it("400 for non-string/oversize fields and bad email", async () => {
    expect((await post(tester, { ...goodTester, phone: 123 })).status).toBe(400);
    expect((await post(tester, { ...goodTester, experienceNotes: "x".repeat(2001) })).status).toBe(400);
    expect((await post(tester, { name: "a", email: "nope" })).status).toBe(400);
    expect((await post(tester, "{bad json")).status).toBe(400);
  });
  it("rejects multi-recipient, delimiter, and header injection attempts without sending mail", async () => {
    const maliciousEmails = [
      "a..b@example.com",
      ".a@example.com",
      "a.@example.com",
      "user@-example.com",
      "user@example-.com",
      "user@domain..com",
      "victim@example.com, attacker@example.com",
      "victim@example.com;attacker@example.com",
      "<victim@example.com>",
      '"attacker"@example.com',
      "victim(comment)@example.com",
      "victim@example.com\r\nBcc:attacker@example.com",
      "user@domain,another@domain.com",
    ];
    let idx = 0;
    for (const badEmail of maliciousEmails) {
      idx++;
      const res = await post(tester, { name: "Test", email: badEmail }, `10.0.0.${idx}`);
      expect(res.status).toBe(400);
      expect(h.mail).not.toHaveBeenCalled();
    }
  });
  it("429 after too many requests from one IP", async () => {
    let last = 0;
    for (let i = 0; i < 7; i++) last = (await post(tester, { name: "n", email: `u${i}@example.com` }, "9.9.9.9")).status;
    expect(last).toBe(429);
  });
});

describe("waitlist", () => {
  it("success inserts and emails", async () => {
    expect((await post(waitlist, { email: "w@example.com" })).status).toBe(200);
    expect(h.mail).toHaveBeenCalledTimes(1);
  });
  it("rejects multi-recipient and invalid email formats without sending mail", async () => {
    const maliciousEmails = [
      "a..b@example.com",
      ".a@example.com",
      "a.@example.com",
      "user@-example.com",
      "user@example-.com",
      "user@domain..com",
      "w1@example.com, w2@example.com",
      "w1@example.com;w2@example.com",
      "<w@example.com>",
      '"quoted"@example.com',
      "w(comment)@example.com",
      "w@example.com\r\nCc:extra@example.com",
    ];
    let idx = 0;
    for (const badEmail of maliciousEmails) {
      idx++;
      const res = await post(waitlist, { email: badEmail }, `10.0.1.${idx}`);
      expect(res.status).toBe(400);
      expect(h.mail).not.toHaveBeenCalled();
    }
  });
  it("503 and no email on select error", async () => {
    h.db.push("waitlist.select", { data: null, error: { message: "d" } });
    expect((await post(waitlist, { email: "w@example.com" })).status).toBe(503);
    expect(h.mail).not.toHaveBeenCalled();
  });
  it("503 and no email on non-23505 insert error", async () => {
    h.db.push("waitlist.insert", { data: null, error: { code: "42P01", message: "d" } });
    expect((await post(waitlist, { email: "w@example.com" })).status).toBe(503);
    expect(h.mail).not.toHaveBeenCalled();
  });
  it("existing and 23505 are idempotent with no email", async () => {
    h.db.push("waitlist.select", { data: { id: 1 }, error: null });
    expect((await post(waitlist, { email: "a@example.com" })).status).toBe(200);
    h.db = createDb();
    h.db.push("waitlist.insert", { data: null, error: { code: "23505" } });
    expect((await post(waitlist, { email: "b@example.com" })).status).toBe(200);
    expect(h.mail).not.toHaveBeenCalled();
  });
  it("GET returns only the waitlist count, 503 on error", async () => {
    h.db.push("waitlist.select", { count: 7, error: null });
    const r = await waitlist.GET();
    expect((await r.json()).count).toBe(7);
    h.db = createDb();
    h.db.push("waitlist.select", { count: null, error: { message: "x" } });
    expect((await waitlist.GET()).status).toBe(503);
  });
});

