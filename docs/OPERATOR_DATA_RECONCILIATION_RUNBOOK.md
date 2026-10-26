# Operator Data Reconciliation Runbook: Duplicate Active Rooms, Join Codes & Invites

This runbook guides operators through manually identifying and reconciling duplicate active room names, join codes, and duplicate ride invites in production PostgreSQL databases.

> [!IMPORTANT]
> **Zero Automated Deletion Policy**
> The RiderVoice backend self-healing migration engine (`schemaMigration.js`) deliberately **never** deletes rows or auto-resolves conflicting records. If duplicate records exist, unique index creation is deferred, a warning is recorded in `/api/health`, and this runbook must be followed by an operator with database access.

---

## 1. Health Status Verification

Check system health status:
```bash
curl -s https://<backend-host>/api/health | jq .
```
- If `status == "warning"` with `dataWarnings` populated, the database structure is intact and write traffic is **not blocked**.
- If `dataWarnings` reports duplicates, proceed to the corresponding section below.

---

## 2. Duplicate Active Room Names

PostgreSQL partial unique index `Room_name_active_unique` enforces unique room names for non-ended rooms (`WHERE status != 'ENDED'`).

### Find Duplicate Active Room Names
```sql
SELECT lower("name") AS normalized_name, count(*)::int AS count, array_agg("id") AS room_ids, array_agg("status") AS statuses, array_agg("createdAt") AS created_timestamps
FROM "Room"
WHERE "status" != 'ENDED'
GROUP BY lower("name")
HAVING count(*) > 1;
```

### Inspect Details of the Conflicting Rooms
```sql
SELECT "id", "name", "status", "ownerId", "joinCode", "createdAt", "startedAt", "endedAt"
FROM "Room"
WHERE lower("name") = lower('<normalized_name>')
ORDER BY "createdAt" DESC;
```

### Resolution Strategy
1. **Determine the true active room**: Typically the most recent room with active riders or an ongoing session.
2. **Transition obsolete duplicates to 'ENDED'**:
```sql
-- Transition stale/abandoned duplicate room to ENDED
UPDATE "Room"
SET "status" = 'ENDED', "endedAt" = NOW()
WHERE "id" = '<stale_room_id>'
  AND "status" != 'ENDED';
```
3. **Verify duplicates are cleared**:
```sql
SELECT lower("name"), count(*)
FROM "Room"
WHERE "status" != 'ENDED' AND lower("name") = lower('<normalized_name>')
GROUP BY lower("name");
```

---

## 3. Duplicate Active Join Codes

PostgreSQL partial unique index `Room_joinCode_active_unique` enforces unique 6-character join codes across non-ended rooms (`WHERE status != 'ENDED' AND "joinCode" IS NOT NULL`).

### Find Duplicate Active Join Codes
```sql
SELECT "joinCode", count(*)::int AS count, array_agg("id") AS room_ids, array_agg("status") AS statuses
FROM "Room"
WHERE "status" != 'ENDED' AND "joinCode" IS NOT NULL
GROUP BY "joinCode"
HAVING count(*) > 1;
```

### Resolution Strategy
1. **Clear joinCode from the older/inactive room**:
```sql
-- Nullify join code on the inactive room so only the active room holds it
UPDATE "Room"
SET "joinCode" = NULL
WHERE "id" = '<stale_room_id>'
  AND "status" != 'ENDED';
```
2. Alternatively, if the older room is finished, mark it `ENDED`:
```sql
UPDATE "Room"
SET "status" = 'ENDED', "endedAt" = NOW(), "joinCode" = NULL
WHERE "id" = '<stale_room_id>';
```

---

## 4. Duplicate Ride Invites (`roomId`, `inviteeId`)

Constraint `RideInvite_roomId_inviteeId_key` enforces unique `(roomId, inviteeId)` pairs.

### Find Duplicate Ride Invites
```sql
SELECT "roomId", "inviteeId", count(*)::int AS count, array_agg("id") AS invite_ids, array_agg("status") AS statuses
FROM "RideInvite"
GROUP BY "roomId", "inviteeId"
HAVING count(*) > 1;
```

### Inspect the Conflicting Invites
```sql
SELECT "id", "roomId", "inviteeId", "status", "source", "createdAt", "updatedAt"
FROM "RideInvite"
WHERE "roomId" = '<roomId>' AND "inviteeId" = '<inviteeId>'
ORDER BY "updatedAt" DESC;
```

### Resolution Strategy
1. Keep the most recently updated or accepted invite row.
2. Clean up the duplicate older record:
```sql
-- Remove specifically identified stale invite ID after operator confirmation
DELETE FROM "RideInvite"
WHERE "id" = '<stale_invite_id>';
```

---

## 5. Post-Reconciliation Verification

After resolving data conflicts in the database:
1. Re-run index creation manually or trigger backend schema assertion:
```sql
CREATE UNIQUE INDEX IF NOT EXISTS "Room_name_active_unique" ON "Room"(lower("name")) WHERE "status" != 'ENDED';
CREATE UNIQUE INDEX IF NOT EXISTS "Room_joinCode_active_unique" ON "Room"("joinCode") WHERE "status" != 'ENDED' AND "joinCode" IS NOT NULL;
ALTER TABLE "RideInvite" ADD CONSTRAINT "RideInvite_roomId_inviteeId_key" UNIQUE ("roomId", "inviteeId");
```
2. Query `/api/health` to confirm `dataWarnings` is empty (`[]`) and `status` is `"ok"`.
