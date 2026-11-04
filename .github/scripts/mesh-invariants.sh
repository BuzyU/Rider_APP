#!/usr/bin/env bash
set -u
M=mobile-app/app/src/main/java/com/ridervoice
fail=0
check() {   # $1 = description, rest = command whose OUTPUT must be empty
  local desc="$1"; shift
  local out; out="$("$@" 2>/dev/null || true)"
  if [ -n "$out" ]; then echo "VIOLATION: $desc"; echo "$out"; fail=1; fi
}
check "companionSession stub"      grep -rn "companionSession: OfflineSession? = null" mobile-app/app/src
check "catch Throwable in mesh"    grep -rnE "catch \((_|e|t): Throwable\)" $M/mesh $M/services/MeshForegroundService.kt
check "session DisposableEffect"   bash -c "grep -n 'DisposableEffect' $M/navigation/NavGraph.kt | grep -i session"
check "fromCode(sessionCode in nav" grep -rn "fromCode(sessionCode" $M/navigation
check "removed symbols"            grep -rnE "fromOnlineRoom|BOOTSTRAP_KEY|DISCOVERY_PORT|setPttHeld|AUDIO_PORT" $M
check "Thread.sleep in mesh tests" grep -rn "Thread.sleep" mobile-app/app/src/test/java/com/ridervoice/mesh
check "pairing code in debug src"  grep -rn "pairingCode" mobile-app/app/src/debug
check "TTL 7 claims"               bash -c "grep -rnE 'DEFAULT_TTL = 7|TTL.*= 7|Default = 7' docs *.md rider-app-offline-mesh-three-stage-plan.md | grep -v CORRECTED"
check "banned doc words"           bash -c "grep -rniE 'seamless|production-ready|fully verified|100%' docs README.md ARCHITECTURE.md | grep -v CORRECTED"
grep -q "NET_CAPABILITY_VALIDATED" -r $M/mesh || { echo "VIOLATION: ValidatedInternetSource missing"; fail=1; }
exit $fail
