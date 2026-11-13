#!/usr/bin/env bash
# Verifies that commits and changes do not violate frozen paths against the baseline.
set -euo pipefail

FROZEN_BASE="${FROZEN_BASE:-HEAD}"
RANGE="${1:-}"

if ! git rev-parse --verify "${FROZEN_BASE}^{commit}" >/dev/null 2>&1; then
  echo "ERROR: FROZEN_BASE '$FROZEN_BASE' is not a valid commit in this repository."
  exit 1
fi

FROZEN_PATHS=(
  "mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt"
  "mobile-app/app/src/main/java/com/ridervoice/network/ConnectionState.kt"
  "mobile-app/app/src/main/java/com/ridervoice/network/NetworkResilienceManager.kt"
  "mobile-app/app/src/main/java/com/ridervoice/state"
  "mobile-app/app/src/main/java/com/ridervoice/services/VoiceForegroundService.kt"
  "mobile-app/app/src/main/java/com/ridervoice/audio"
  "mobile-app/app/src/main/java/com/ridervoice/diagnostics"
  "mobile-app/app/src/main/java/com/ridervoice/ui/screens/room"
  "mobile-app/app/src/main/java/com/ridervoice/ui/screens/RoomScreen.kt"
  "backend"
  "web-app"
  "livekit-server"
)

# Read allowlist
ALLOWLIST=()
if [ -f ".github/frozen-allowlist" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line=$(echo "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
    [ -z "$line" ] && continue
    [[ "$line" =~ ^# ]] && continue
    ALLOWLIST+=("$line")
  done < ".github/frozen-allowlist"
fi

is_allowlisted() {
  local target="$1"
  for allowed in "${ALLOWLIST[@]}"; do
    if [ "$target" = "$allowed" ]; then
      return 0
    fi
  done
  return 1
}

BASE="${RANGE:-$FROZEN_BASE}"
violations=0

while IFS= read -r -d '' file; do
  [ -z "$file" ] && continue
  if ! is_allowlisted "$file"; then
    echo "VIOLATION: Frozen path was modified: $file (differs from baseline $FROZEN_BASE)"
    violations=$((violations + 1))
  fi
done < <(git diff --name-only -z "$BASE" -- "${FROZEN_PATHS[@]}")

# In addition, ensure docs/plan/OFFLINE_MESH_PLAN.md is not modified from canonical 4c6aa85
CANONICAL_PLAN_REF="4c6aa85"
if ! git rev-parse --verify "$CANONICAL_PLAN_REF" >/dev/null 2>&1; then
  echo "VIOLATION: Canonical plan ref $CANONICAL_PLAN_REF is missing from git history"
  violations=$((violations + 1))
else
  if ! git diff --quiet "$CANONICAL_PLAN_REF" HEAD -- docs/plan/OFFLINE_MESH_PLAN.md 2>/dev/null; then
    echo "VIOLATION: docs/plan/OFFLINE_MESH_PLAN.md was modified from canonical spec $CANONICAL_PLAN_REF"
    violations=$((violations + 1))
  fi
fi

if [ "$violations" -gt 0 ]; then
  echo "ERROR: $violations frozen path violation(s) detected."
  exit 1
fi

echo "Mesh commit guard: all inspected files adhere to frozen contract."
exit 0
