#!/usr/bin/env bash
#
# verify-release-apk.sh
# Validates release APK signing certificate against pinned EXPECTED_SIGNER_SHA256
# and strictly rejects debug-fallback / insecure signing / unauthorized signers.
#
set -euo pipefail

# 1. Collect APK paths from arguments or APK_PATH env var
APK_TARGETS=()
if [ "$#" -gt 0 ]; then
  APK_TARGETS=("$@")
elif [ -n "${APK_PATH:-}" ]; then
  APK_TARGETS=("$APK_PATH")
fi

if [ "${#APK_TARGETS[@]}" -eq 0 ]; then
  echo "::error::Release verification failed: No APK path specified." >&2
  exit 1
fi

# 2. Reject if insecure release build flag is enabled (case-insensitive, whitespace-trimmed)
INSECURE_FLAG="$(echo "${ALLOW_INSECURE_RELEASE_BUILD:-false}" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
if [ "$INSECURE_FLAG" != "false" ] && [ "$INSECURE_FLAG" != "0" ] && [ -n "$INSECURE_FLAG" ]; then
  echo "::error::Release verification failed: Insecure release signing is strictly prohibited for release builds. ALLOW_INSECURE_RELEASE_BUILD must be false or unset." >&2
  exit 1
fi

# 3. Require non-empty EXPECTED_SIGNER_SHA256
EXPECTED_RAW="${EXPECTED_SIGNER_SHA256:-}"
# Normalize: strip colons, whitespace, lowercase
EXPECTED_NORM="$(echo "$EXPECTED_RAW" | tr -d '[:space:]:' | tr '[:upper:]' '[:lower:]')"

if [ -z "$EXPECTED_NORM" ]; then
  echo "::error::Release verification failed: EXPECTED_SIGNER_SHA256 is required but absent or blank." >&2
  exit 1
fi

if [[ ! "$EXPECTED_NORM" =~ ^[0-9a-f]{64}$ ]]; then
  echo "::error::Release verification failed: EXPECTED_SIGNER_SHA256 must be a valid 64-character hex string." >&2
  exit 1
fi

# 4. Known debug-fallback certificate fingerprints to reject
# Matches mobile-app/app/signing/debug-fallback.keystore (CN=RiderVoice Debug)
DEBUG_FALLBACK_SHA256="5135015f0c926a50b4605c1ac65250a87c63509aee92daa46a2d2ea0cf377f46"

if [ "$EXPECTED_NORM" = "$DEBUG_FALLBACK_SHA256" ]; then
  echo "::error::Release verification failed: EXPECTED_SIGNER_SHA256 cannot be pinned to the known debug-fallback certificate." >&2
  exit 1
fi

# 5. Resolve apksigner binary (Linux/macOS or Windows build-tools)
APKSIGNER_CMD="${APKSIGNER_BIN:-}"
if [ -z "$APKSIGNER_CMD" ]; then
  if command -v apksigner >/dev/null 2>&1; then
    APKSIGNER_CMD="apksigner"
  elif command -v apksigner.bat >/dev/null 2>&1; then
    APKSIGNER_CMD="apksigner.bat"
  elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/build-tools" ]; then
    BUILD_TOOLS_DIR=$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -n 1)
    if [ -n "$BUILD_TOOLS_DIR" ]; then
      if [ -x "$BUILD_TOOLS_DIR/apksigner" ]; then
        APKSIGNER_CMD="$BUILD_TOOLS_DIR/apksigner"
      elif [ -f "$BUILD_TOOLS_DIR/apksigner.bat" ]; then
        APKSIGNER_CMD="$BUILD_TOOLS_DIR/apksigner.bat"
      fi
    fi
  fi
fi

if [ -z "$APKSIGNER_CMD" ]; then
  echo "::error::Release verification failed: apksigner tool not found on PATH or in ANDROID_HOME/build-tools." >&2
  exit 1
fi

# 6. Verify each target APK
for APK in "${APK_TARGETS[@]}"; do
  if [ -z "$APK" ]; then
    echo "::error::Release verification failed: Empty APK path specified." >&2
    exit 1
  fi

  if [ -d "$APK" ]; then
    echo "::error::Release verification failed: Target path is a directory, not an APK file: $APK" >&2
    exit 1
  fi

  if [ ! -f "$APK" ]; then
    echo "::error::Release verification failed: Target APK file not found at: $APK" >&2
    exit 1
  fi

  # Execute apksigner with error trapping
  set +e
  CERT_OUTPUT=$("$APKSIGNER_CMD" verify --print-certs "$APK" 2>&1)
  TOOL_EXIT=$?
  set -e

  if [ "$TOOL_EXIT" -ne 0 ]; then
    echo "::error::Release verification failed: apksigner verification failed for $APK (exit code: $TOOL_EXIT)." >&2
    exit 1
  fi

  # Check certificate DN for debug signer
  if echo "$CERT_OUTPUT" | grep -Eiq "Signer #[0-9]+ certificate DN:.*(CN=Android Debug|CN=RiderVoice Debug)"; then
    echo "::error::Release verification failed: APK $APK is signed with a debug certificate DN." >&2
    exit 1
  fi

  # Check signer count: exactly one authorized signer is permitted for RiderVoice releases
  SIGNER_COUNT=$(echo "$CERT_OUTPUT" | grep -c "Signer #[0-9]\+ certificate SHA-256 digest:" || true)
  if [ "$SIGNER_COUNT" -eq 0 ]; then
    echo "::error::Release verification failed: No signers found in certificate output for $APK." >&2
    exit 1
  fi

  if [ "$SIGNER_COUNT" -gt 1 ]; then
    echo "::error::Release verification failed: Multiple signers detected ($SIGNER_COUNT) in $APK. Release APK must have exactly one authorized signer." >&2
    exit 1
  fi

  # Extract and normalize actual SHA-256 digest (with || true to avoid unhandled pipefail exit)
  ACTUAL_DIGEST=$(echo "$CERT_OUTPUT" | grep "Signer #1 certificate SHA-256 digest:" | head -n 1 | awk '{print $NF}' || true)
  ACTUAL_NORM="$(echo "$ACTUAL_DIGEST" | tr -d '[:space:]:' | tr '[:upper:]' '[:lower:]')"

  if [ -z "$ACTUAL_NORM" ] || [[ ! "$ACTUAL_NORM" =~ ^[0-9a-f]{64}$ ]]; then
    echo "::error::Release verification failed: Could not extract valid SHA-256 certificate digest from $APK." >&2
    exit 1
  fi

  # Explicitly reject debug-fallback fingerprint
  if [ "$ACTUAL_NORM" = "$DEBUG_FALLBACK_SHA256" ]; then
    echo "::error::Release verification failed: APK $APK is signed with the committed debug-fallback certificate." >&2
    exit 1
  fi

  # Compare actual normalized fingerprint against expected pinned fingerprint
  if [ "$ACTUAL_NORM" != "$EXPECTED_NORM" ]; then
    echo "::error::Release verification failed: Signer SHA-256 fingerprint mismatch for $APK! APK certificate does not match pinned EXPECTED_SIGNER_SHA256." >&2
    exit 1
  fi

  echo "APK signing certificate successfully verified for $APK against pinned SHA-256 fingerprint: $ACTUAL_NORM"
done

exit 0
