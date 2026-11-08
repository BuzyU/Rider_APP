#!/usr/bin/env bash
# Tests that mesh-commit-guard.sh correctly rejects modifications to frozen paths in a temporary worktree.
set -euo pipefail

REPO_ROOT="$(pwd)"
tmp=$(mktemp -d)
cleanup() {
  git worktree remove --force "$tmp" 2>/dev/null || true
  rm -rf "$tmp" 2>/dev/null || true
}
trap cleanup EXIT

echo "Creating isolated detached worktree at $tmp..."
git worktree add --detach "$tmp" HEAD

# Test 1: Change to frozen LiveKitManager.kt must be rejected
echo "Test 1: Frozen file modification must be rejected..."
(
  cd "$tmp"
  echo "// guard test" >> "mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt"
  git -c user.name=ci -c user.email=ci@example.com commit -qam "temp: modify LiveKitManager"
  if bash "$REPO_ROOT/.github/scripts/mesh-commit-guard.sh" HEAD~1..HEAD >/dev/null 2>&1; then
    echo "FAIL: Expected mesh-commit-guard to reject LiveKitManager modification"
    exit 1
  fi
)

# Test 2: Change under backend/ must be rejected
echo "Test 2: backend/ modification must be rejected..."
(
  cd "$tmp"
  git reset --hard HEAD~1 -q
  echo "// guard test backend" >> "backend/package.json"
  git -c user.name=ci -c user.email=ci@example.com commit -qam "temp: modify backend"
  if bash "$REPO_ROOT/.github/scripts/mesh-commit-guard.sh" HEAD~1..HEAD >/dev/null 2>&1; then
    echo "FAIL: Expected mesh-commit-guard to reject backend/ modification"
    exit 1
  fi
)

# Test 3: Change under mesh/ must be ACCEPTED
echo "Test 3: mesh/ modification must be accepted..."
(
  cd "$tmp"
  git reset --hard HEAD~1 -q
  echo "// guard test mesh" >> "mobile-app/app/src/main/java/com/ridervoice/mesh/MeshConfig.kt"
  git -c user.name=ci -c user.email=ci@example.com commit -qam "temp: modify mesh"
  if ! bash "$REPO_ROOT/.github/scripts/mesh-commit-guard.sh" HEAD~1..HEAD >/dev/null 2>&1; then
    echo "FAIL: Expected mesh-commit-guard to accept mesh/ modification"
    exit 1
  fi
)

echo "PASS: mesh-commit-guard.test.sh passed all 3 isolation tests (frozen rejected, backend rejected, mesh accepted)."
exit 0
