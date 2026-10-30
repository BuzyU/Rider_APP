#!/usr/bin/env bash
#
# verify-release-apk.test.sh
# Tests all 22 required scenarios from Task E Phase F
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_SCRIPT="$SCRIPT_DIR/verify-release-apk.sh"

TMP_DIR="$(mktemp -d 2>/dev/null || mktemp -d -t 'release-test')"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

DUMMY_APK="$TMP_DIR/dummy.apk"
touch "$DUMMY_APK"

DUMMY_APK_2="$TMP_DIR/dummy-canonical.apk"
touch "$DUMMY_APK_2"

DIR_WITH_SPACES="$TMP_DIR/path with spaces"
mkdir -p "$DIR_WITH_SPACES"
DUMMY_APK_SPACES="$DIR_WITH_SPACES/release app.apk"
touch "$DUMMY_APK_SPACES"

TEST_DIRECTORY_PATH="$TMP_DIR/a_directory"
mkdir -p "$TEST_DIRECTORY_PATH"

MOCK_APKSIGNER="$TMP_DIR/mock-apksigner.sh"
cat << 'EOF' > "$MOCK_APKSIGNER"
#!/usr/bin/env bash
MODE="${MOCK_MODE:-valid_release}"
case "$MODE" in
  valid_release)
    echo "Signer #1 certificate DN: CN=RiderVoice Production Release, O=RiderVoice, C=US"
    echo "Signer #1 certificate SHA-256 digest: a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    ;;
  debug_fallback)
    echo "Signer #1 certificate DN: CN=RiderVoice Debug, O=RiderVoice, C=US"
    echo "Signer #1 certificate SHA-256 digest: 5135015f0c926a50b4605c1ac65250a87c63509aee92daa46a2d2ea0cf377f46"
    ;;
  debug_dn_only)
    echo "Signer #1 certificate DN: CN=Android Debug, O=Android, C=US"
    echo "Signer #1 certificate SHA-256 digest: 1111111111111111111111111111111111111111111111111111111111111111"
    ;;
  multiple_signers)
    echo "Signer #1 certificate DN: CN=RiderVoice Production Release, O=RiderVoice, C=US"
    echo "Signer #1 certificate SHA-256 digest: a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    echo "Signer #2 certificate DN: CN=Attacker Key, O=Evil Corp, C=US"
    echo "Signer #2 certificate SHA-256 digest: 2222222222222222222222222222222222222222222222222222222222222222"
    ;;
  missing_fingerprint)
    echo "Signer #1 certificate DN: CN=RiderVoice Production Release, O=RiderVoice, C=US"
    echo "Warning: no digest generated"
    ;;
  malformed_digest)
    echo "Signer #1 certificate DN: CN=RiderVoice Production Release, O=RiderVoice, C=US"
    echo "Signer #1 certificate SHA-256 digest: NOT_A_VALID_HEX_DIGEST"
    ;;
  error)
    echo "apksigner: parse error" >&2
    exit 2
    ;;
  *)
    echo "Unknown mock mode: $MODE" >&2
    exit 3
    ;;
esac
exit 0
EOF
chmod +x "$MOCK_APKSIGNER"

VALID_RELEASE_SHA="A1:B2:C3:D4:E5:F6:07:18:29:3A:4B:5C:6D:7E:8F:90:A1:B2:C3:D4:E5:F6:07:18:29:3A:4B:5C:6D:7E:8F:90"
DEBUG_SHA="51:35:01:5F:0C:92:6A:50:B4:60:5C:1A:C6:52:50:A8:7C:63:50:9A:EE:92:DA:A4:6A:2D:2E:A0:CF:37:7F:46"
WRONG_SHA="00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00"

PASSED_TESTS=0
FAILED_TESTS=0

run_test() {
  local test_name="$1"
  local expected_exit="$2"
  shift 2

  echo "Running test: $test_name..."
  set +e
  "$@" > "$TMP_DIR/out.log" 2>&1
  local actual_exit=$?
  set -e

  if [ "$actual_exit" -eq "$expected_exit" ]; then
    echo "  [PASS] $test_name (exit code: $actual_exit)"
    PASSED_TESTS=$((PASSED_TESTS + 1))
  else
    echo "  [FAIL] $test_name - Expected exit $expected_exit, got $actual_exit"
    cat "$TMP_DIR/out.log"
    FAILED_TESTS=$((FAILED_TESTS + 1))
  fi
}

echo "=== TASK E COMPREHENSIVE SIGNING & ARTIFACT VERIFICATION TESTS ==="

# 1. Missing expected signer fingerprint
run_test "1. Missing expected signer fingerprint" 1 \
  env -u EXPECTED_SIGNER_SHA256 APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 2. Empty expected fingerprint
run_test "2. Empty expected signer fingerprint" 1 \
  env EXPECTED_SIGNER_SHA256="" APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 3. Whitespace-only fingerprint
run_test "3. Whitespace-only expected signer fingerprint" 1 \
  env EXPECTED_SIGNER_SHA256="     " APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 4a. Malformed fingerprint (non-hex characters)
run_test "4a. Malformed fingerprint (non-hex characters)" 1 \
  env EXPECTED_SIGNER_SHA256="Z1:B2:C3:D4:E5:F6:07:18:29:3A:4B:5C:6D:7E:8F:90:A1:B2:C3:D4:E5:F6:07:18:29:3A:4B:5C:6D:7E:8F:90" \
  APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 4b. Malformed fingerprint (invalid length - 63 hex chars)
run_test "4b. Malformed fingerprint (too short - 63 chars)" 1 \
  env EXPECTED_SIGNER_SHA256="a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9" \
  APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 5. Correct uppercase fingerprint with colons
run_test "5. Correct uppercase fingerprint with colons" 0 \
  env EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 6. Correct lowercase fingerprint without colons
run_test "6. Correct lowercase fingerprint without colons" 0 \
  env EXPECTED_SIGNER_SHA256="a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90" \
  APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 7. Correct fingerprint with permitted whitespace
run_test "7. Correct fingerprint with permitted whitespace" 0 \
  env EXPECTED_SIGNER_SHA256="   A1:b2:C3:d4:E5:f6:07:18:29:3A:4B:5c:6D:7e:8F:90:A1:B2:C3:D4:E5:F6:07:18:29:3a:4b:5c:6d:7e:8f:90   " \
  APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 8. Incorrect valid-format fingerprint
run_test "8. Incorrect valid-format fingerprint" 1 \
  env EXPECTED_SIGNER_SHA256="$WRONG_SHA" APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 9. Known debug-fallback certificate rejected
run_test "9. Known debug-fallback certificate rejected" 1 \
  env EXPECTED_SIGNER_SHA256="$DEBUG_SHA" APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="debug_fallback" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 10. Debug certificate distinguished name rejected
run_test "10. Debug certificate distinguished name rejected" 1 \
  env EXPECTED_SIGNER_SHA256="11:11:11:11:11:11:11:11:11:11:11:11:11:11:11:11:11:11:11:11:11:11:11:11:11:11:11:11:11:11:11:11" \
  APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="debug_dn_only" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 11a. Insecure release flag enabled (lowercase true)
run_test "11a. Insecure release flag enabled (true)" 1 \
  env ALLOW_INSECURE_RELEASE_BUILD="true" EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" \
  APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 11b. Insecure release flag enabled (capitalized True)
run_test "11b. Insecure release flag enabled (True)" 1 \
  env ALLOW_INSECURE_RELEASE_BUILD="True" EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" \
  APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 11c. Insecure release flag enabled (uppercase TRUE)
run_test "11c. Insecure release flag enabled (TRUE)" 1 \
  env ALLOW_INSECURE_RELEASE_BUILD="TRUE" EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" \
  APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 11d. Insecure release flag enabled (truthy 1)
run_test "11d. Insecure release flag enabled (1)" 1 \
  env ALLOW_INSECURE_RELEASE_BUILD="1" EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" \
  APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 11e. Insecure release flag with whitespace ("  true  ")
run_test "11e. Insecure release flag with whitespace" 1 \
  env ALLOW_INSECURE_RELEASE_BUILD="  true  " EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" \
  APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 11f. Insecure release flag explicitly disabled (false)
run_test "11f. Insecure release flag explicitly disabled (false)" 0 \
  env ALLOW_INSECURE_RELEASE_BUILD="false" EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" \
  APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 11g. Insecure release flag truthy variant (yes)
run_test "11g. Insecure release flag truthy variant (yes)" 1 \
  env ALLOW_INSECURE_RELEASE_BUILD="yes" EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" \
  APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 11h. Insecure release flag numeric disabled (0)
run_test "11h. Insecure release flag numeric disabled (0)" 0 \
  env ALLOW_INSECURE_RELEASE_BUILD="0" EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" \
  APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 11i. Insecure release flag unset
run_test "11i. Insecure release flag unset" 0 \
  env -u ALLOW_INSECURE_RELEASE_BUILD EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" \
  APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 12a. Missing APK file
run_test "12a. Missing APK file path" 1 \
  env EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$TMP_DIR/nonexistent.apk"

# 12b. Directory specified instead of APK file
run_test "12b. Directory specified instead of APK file" 1 \
  env EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$TEST_DIRECTORY_PATH"

# 13. Missing signing tool
run_test "13. Missing signing tool binary" 1 \
  env EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" APKSIGNER_BIN="$TMP_DIR/nonexistent_apksigner" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 14. Signing-tool execution failure
run_test "14. Signing-tool execution failure (corrupt APK)" 1 \
  env EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="error" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 15. Missing signer fingerprint in tool output
run_test "15. Missing signer fingerprint in tool output" 1 \
  env EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="missing_fingerprint" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 16. Malformed signer output from tool
run_test "16. Malformed signer output from tool" 1 \
  env EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="malformed_digest" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 17. Unexpected signer fingerprint
run_test "17. Unexpected signer fingerprint" 1 \
  env EXPECTED_SIGNER_SHA256="ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" \
  APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 18. Correct simulated signer accepted
run_test "18. Correct simulated signer accepted" 0 \
  env EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 19. Paths containing spaces
run_test "19. Target APK path containing spaces" 0 \
  env EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK_SPACES"

# 20. Pipeline failure handling (no unhandled bash crash on empty grep)
run_test "20. Pipeline failure handling on empty grep" 1 \
  env EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="missing_fingerprint" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 21. Additional signer or ambiguous signer output
run_test "21. Additional signer or ambiguous signer output rejected" 1 \
  env EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="multiple_signers" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK"

# 22a. Multiple release assets: all valid
run_test "22a. Multiple release assets: all valid" 0 \
  env EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK" "$DUMMY_APK_2"

# 22b. Multiple release assets: one invalid/missing
run_test "22b. Multiple release assets: one invalid/missing" 1 \
  env EXPECTED_SIGNER_SHA256="$VALID_RELEASE_SHA" APKSIGNER_BIN="$MOCK_APKSIGNER" MOCK_MODE="valid_release" \
  bash "$TARGET_SCRIPT" "$DUMMY_APK" "$TMP_DIR/missing.apk"

echo ""
echo "=== SUMMARY: $PASSED_TESTS passed, $FAILED_TESTS failed ==="
if [ "$FAILED_TESTS" -ne 0 ]; then
  exit 1
fi
exit 0
