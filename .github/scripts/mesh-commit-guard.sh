#!/usr/bin/env bash
# Verifies that commits starting with 'WP-' or 'mesh' do not touch the LiveKit contract set.
set -euo pipefail
BASE="mobile-app/app/src/main/java/com/ridervoice"
CONTRACT_PATHS=(
  "$BASE/network/LiveKitManager.kt"
  "$BASE/network/ConnectionState.kt"
  "$BASE/network/NetworkResilienceManager.kt"
  "$BASE/services/VoiceForegroundService.kt"
  "$BASE/ui/screens/RoomScreen.kt"
  "$BASE/ui/screens/room"
  "$BASE/audio"
  "docs/plan/OFFLINE_MESH_PLAN.md"
)
for p in "${CONTRACT_PATHS[@]}"; do
  test -e "$p" || { echo "ERROR: LiveKit contract path missing: $p"; exit 1; }
done
RANGE="${1:-}"
if [ -z "$RANGE" ]; then
  if git rev-parse --verify origin/main >/dev/null 2>&1; then
    RANGE="origin/main..HEAD"
  else
    RANGE="HEAD~1..HEAD"
  fi
fi
COMMITS=$(git log --format="%H" "$RANGE" 2>/dev/null || git rev-parse HEAD)
for sha in $COMMITS; do
  subj=$(git log -1 --format="%s" "$sha")
  if echo "$subj" | grep -qiE '^(WP-|mesh)'; then
    changed=$(git diff-tree --no-commit-id --name-only -r "$sha")
    for cp in "${CONTRACT_PATHS[@]}"; do
      if echo "$changed" | grep -q "^$cp"; then
        echo "VIOLATION: Commit $sha ('$subj') touched frozen LiveKit contract: $cp"
        exit 1
      fi
    done
  fi
done
echo "Mesh commit guard: all inspected commits adhere to LiveKit contract freeze."
