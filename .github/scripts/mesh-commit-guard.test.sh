#!/usr/bin/env bash
# Tests that mesh-commit-guard.sh correctly rejects any commit modifying frozen files.
set -euo pipefail

TARGET="mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt"
test -f "$TARGET" || { echo "Target file not found: $TARGET"; exit 1; }

echo "Testing mesh-commit-guard rejection..."
# Append a comment to the target frozen file
echo "// Guard test temporary change" >> "$TARGET"
git commit --only "$TARGET" -m "temp: test commit modifying LiveKitManager" --no-verify

set +e
bash .github/scripts/mesh-commit-guard.sh HEAD~1..HEAD >/dev/null 2>&1
status=$?
set -e

# Clean up only the test commit and restore the target file
git reset HEAD~1 >/dev/null 2>&1
git checkout -- "$TARGET"

if [ "$status" -ne 1 ]; then
  echo "FAIL: Expected mesh-commit-guard to exit with code 1, got $status"
  exit 1
fi

echo "PASS: mesh-commit-guard correctly rejected changes to $TARGET (exit code 1)."
exit 0
