#!/usr/bin/env bash
# Verifies that commits and changes do not violate frozen paths against the baseline.
set -euo pipefail

FROZEN_BASE="${FROZEN_BASE:-a37ef09}"
RANGE="${1:-}"

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
  "docs/plan/OFFLINE_MESH_PLAN.md"
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

is_frozen() {
  local file="$1"
  for fp in "${FROZEN_PATHS[@]}"; do
    if [[ "$file" == "$fp"* ]] || [ "$file" = "$fp" ]; then
      return 0
    fi
  done
  return 1
}

if [ -n "$RANGE" ]; then
  CHANGED_FILES=$(git diff --name-only "$RANGE")
else
  # Check current tree against baseline FROZEN_BASE
  CHANGED_FILES=$(git diff --name-only "$FROZEN_BASE")
fi

violations=0
for file in $CHANGED_FILES; do
  if is_frozen "$file"; then
    if ! is_allowlisted "$file"; then
      echo "VIOLATION: Frozen path was modified: $file (differs from baseline $FROZEN_BASE)"
      violations=$((violations + 1))
    fi
  fi
done

# In addition, ensure docs/plan/OFFLINE_MESH_PLAN.md is not modified from canonical 4c6aa85
if git rev-parse --verify 4c6aa85 >/dev/null 2>&1; then
  if ! git diff --quiet 4c6aa85 HEAD -- docs/plan/OFFLINE_MESH_PLAN.md 2>/dev/null; then
    echo "VIOLATION: docs/plan/OFFLINE_MESH_PLAN.md was modified from canonical spec 4c6aa85"
    violations=$((violations + 1))
  fi
fi

if [ "$violations" -gt 0 ]; then
  echo "ERROR: $violations frozen path violation(s) detected."
  exit 1
fi

echo "Mesh commit guard: all inspected files adhere to frozen contract."
exit 0
