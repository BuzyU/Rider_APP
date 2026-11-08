#!/usr/bin/env bash
set -u
M=mobile-app/app/src/main/java/com/ridervoice
fail=0

check() {   # $1 = description, rest = command whose OUTPUT must be empty
  local desc="$1"; shift
  for arg in "$@"; do
    if [[ "$arg" == mobile-app* || "$arg" == docs* || "$arg" == .github* ]]; then
      if [ ! -e "$arg" ]; then
        echo "VIOLATION: Path does not exist in check '$desc': $arg"
        fail=1
        return
      fi
    fi
  done
  local out
  out="$("$@" 2>&1 || true)"
  if [ -n "$out" ]; then echo "VIOLATION: $desc"; echo "$out"; fail=1; fi
}

check "companionSession stub"             grep -rn "companionSession: OfflineSession? = null" mobile-app/app/src
check "catch Throwable in mesh"           grep -rnE "catch \((_|e|t): Throwable\)" $M/mesh $M/services/MeshForegroundService.kt
check "session DisposableEffect"          bash -c "grep -n 'DisposableEffect' $M/navigation/NavGraph.kt | grep -i session"
check "fromCode(sessionCode in nav"        grep -rn "fromCode(sessionCode" $M/navigation
check "removed symbols"                   grep -rnE "fromOnlineRoom|BOOTSTRAP_KEY|DISCOVERY_PORT|setPttHeld|AUDIO_PORT" $M
check "Thread.sleep in mesh tests"        grep -rn "Thread.sleep" mobile-app/app/src/test/java/com/ridervoice/mesh
check "pairing code in debug src"         grep -rn "pairingCode" mobile-app/app/src/debug
check "TTL 7 claims"                      bash -c "grep -rnE 'DEFAULT_TTL = 7|TTL.*= 7|Default = 7' docs README.md ARCHITECTURE.md CODE_DEPTH_STRUCTURE.md | grep -v CORRECTED"
check "no MeshConfig.* = assignments"     grep -rnE "\bMeshConfig\.[a-zA-Z0-9_]+\s*=" mobile-app/app/src
check "runCatching around suspend in mesh" bash -c "grep -rnE '\brunCatching\b.*(enableAudio|disableAudio|resume|pause|start\(|stop\(|setMicrophoneEnabled)' $M/mesh/transport; grep -rnE '\brunCatching\b.*(enableAudio|disableAudio|resume|pause|setMicrophoneEnabled)' $M/mesh/audio"
check "main AndroidManifest contains no MeshDebugReceiver" grep -n "MeshDebugReceiver" mobile-app/app/src/main/AndroidManifest.xml
check "PeerFilterHolder.port assignment only in debug" bash -c "grep -rn 'PeerFilterHolder\.port\s*=' mobile-app/app/src/main || true"
check "mesh code never references DEFAULT_MASTER_KEY_ALIAS" bash -c "grep -rn 'DEFAULT_MASTER_KEY_ALIAS' $M/mesh || true"

if [ -f "mobile-app/app/build/intermediates/merged_manifests/release/AndroidManifest.xml" ]; then
  check "release merged manifest contains no MeshDebugReceiver" grep -n "MeshDebugReceiver" mobile-app/app/build/intermediates/merged_manifests/release/AndroidManifest.xml
fi

grep -q "NET_CAPABILITY_VALIDATED" -r $M/mesh || { echo "VIOLATION: ValidatedInternetSource missing"; fail=1; }

bash .github/scripts/mesh-commit-guard.sh || { echo "VIOLATION: mesh-commit-guard failed"; fail=1; }

if [ ! -f ".github/test-census-floor" ]; then
  echo "VIOLATION: .github/test-census-floor missing"
  fail=1
elif [ -d "mobile-app/app/build/test-results/testDebugUnitTest" ]; then
  if ls mobile-app/app/build/test-results/testDebugUnitTest/*.xml >/dev/null 2>&1; then
    FLOOR=$(cat .github/test-census-floor | tr -d '[:space:]')
    TOTAL=$(grep -h -o 'tests="[0-9]*"' mobile-app/app/build/test-results/testDebugUnitTest/*.xml | awk -F'"' '{s+=$2} END {print s}')
    if [ -n "$TOTAL" ] && [ -n "$FLOOR" ] && [ "$TOTAL" -lt "$FLOOR" ]; then
      MSG=$(git log -1 --format=%B)
      if ! echo "$MSG" | grep -q "TEST-REMOVAL:"; then
        echo "VIOLATION: Unit test total $TOTAL is below census floor $FLOOR without TEST-REMOVAL: in commit message"
        fail=1
      fi
    fi
  fi
fi

exit $fail
