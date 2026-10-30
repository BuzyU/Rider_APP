import { vi } from "vitest";

export type Result = { data?: unknown; error?: unknown; count?: number | null };

/**
 * Chainable Supabase stub. Each `from(table)` chain records calls and resolves
 * with the next queued result for `${table}.${op}` (op: select|insert|update).
 */
export function createDb() {
  const queues = new Map<string, Result[]>();
  const calls: { table: string; op: string; args: unknown[] }[] = [];

  const push = (key: string, r: Result) => {
    queues.set(key, [...(queues.get(key) ?? []), r]);
  };

  const from = (table: string) => {
    let op = "select";
    const resolve = (): Result => {
      const q = queues.get(`${table}.${op}`);
      return q && q.length ? (q.length > 1 ? q.shift()! : q[0]) : { data: null, error: null };
    };
    const chain: Record<string, unknown> = {};
    const self = () => chain;
    for (const m of ["select", "eq", "in", "order", "limit"]) chain[m] = vi.fn(self);
    chain.insert = vi.fn((...a: unknown[]) => { op = "insert"; calls.push({ table, op, args: a }); return chain; });
    chain.update = vi.fn((...a: unknown[]) => { op = "update"; calls.push({ table, op, args: a }); return chain; });
    chain.maybeSingle = vi.fn(async () => resolve());
    chain.then = (ok: (r: Result) => unknown, bad?: (e: unknown) => unknown) =>
      Promise.resolve(resolve()).then(ok, bad);
    return chain;
  };

  return { from: vi.fn(from), push, calls };
}


export type Db = ReturnType<typeof createDb>;
