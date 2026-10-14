#!/usr/bin/env bash
# ==============================================================================
# Network Diagnostics End-to-End Test Script
# Simulates two riders joining a convoy diagnostic run, posting batches
# (including duplicate retransmissions), stopping, and triggering ZIP export.
# ==============================================================================

set -e

BASE_URL="${BASE_URL:-http://localhost:3000}"
ADMIN_KEY="${DIAG_ADMIN_KEY:-test_admin_key_123}"
CONVOY_NAME="Alpha-Convoy-Test"

TOKEN_RIDER1="test_rider_alpha"
TOKEN_RIDER2="test_rider_bravo"

echo "========================================================"
echo " Starting Network Diagnostics E2E Test"
echo " Target: $BASE_URL"
echo "========================================================"

# Step 1: Initialize convoy room if needed via room token endpoint
echo "[1/7] Ensuring convoy '$CONVOY_NAME' exists..."
INIT_RESP=$(curl -s -X POST "$BASE_URL/api/rooms/room/token" \
  -H "Authorization: Bearer $TOKEN_RIDER1" \
  -H "Content-Type: application/json" \
  -d "{\"roomName\": \"$CONVOY_NAME\"}")
echo "Room response: $INIT_RESP"

# Step 2: Rider 1 joins diagnostic run
echo -e "\n[2/7] Rider 1 joining diagnostic run..."
JOIN_RESP1=$(curl -s -X POST "$BASE_URL/api/diagnostics/join" \
  -H "Authorization: Bearer $TOKEN_RIDER1" \
  -H "Content-Type: application/json" \
  -d "{\"convoyId\": \"$CONVOY_NAME\"}")
echo "Rider 1 join response: $JOIN_RESP1"

RUN_ID=$(echo "$JOIN_RESP1" | grep -o '"runId":"[^"]*' | cut -d'"' -f4)
TEST_NUM=$(echo "$JOIN_RESP1" | grep -o '"testNumber":[0-9]*' | cut -d':' -f2)

if [ -z "$RUN_ID" ]; then
  echo "Error: Failed to obtain RUN_ID from join response"
  exit 1
fi
echo "Active Run ID: $RUN_ID (Test #$TEST_NUM)"

# Step 3: Rider 2 joins the same diagnostic run
echo -e "\n[3/7] Rider 2 joining diagnostic run..."
JOIN_RESP2=$(curl -s -X POST "$BASE_URL/api/diagnostics/join" \
  -H "Authorization: Bearer $TOKEN_RIDER2" \
  -H "Content-Type: application/json" \
  -d "{\"convoyId\": \"$CONVOY_NAME\"}")
echo "Rider 2 join response: $JOIN_RESP2"

# Step 4: Rider 1 posts batch 1
echo -e "\n[4/7] Rider 1 posting Batch 1..."
BATCH1_RESP=$(curl -s -X POST "$BASE_URL/api/diagnostics/batches" \
  -H "Authorization: Bearer $TOKEN_RIDER1" \
  -H "Content-Type: application/json" \
  -d "{
    \"runId\": \"$RUN_ID\",
    \"sequenceNumber\": 1,
    \"payload\": {
      \"selfMetrics\": [
        {\"timestamp\": 1728900000000, \"rtt_ms\": 42.5, \"packets_sent\": 100, \"packets_received\": 98, \"packets_lost\": 2, \"jitter_ms\": 3.1, \"bytes_sent\": 16000, \"bytes_received\": 15600, \"network_type\": \"CELLULAR\", \"cell_operator\": \"Verizon\", \"cell_tech\": \"LTE\"}
      ],
      \"inboundMetrics\": [
        {\"timestamp\": 1728900000000, \"remote_rider\": \"Rider_test_rider_bravo\", \"track_sid\": \"TR_001\", \"packets_received\": 98, \"packets_lost\": 1, \"jitter_ms\": 2.8, \"bytes_received\": 15600, \"jitter_buffer_delay\": 12.0, \"concealed_samples\": 0}
      ],
      \"events\": [
        {\"timestamp\": 1728900000000, \"event_type\": \"HEALTH_CHANGE\", \"details\": \"CELLULAR_NORMAL\"}
      ]
    }
  }")
echo "Rider 1 Batch 1 response: $BATCH1_RESP"

# Test Idempotency: Re-transmit batch 1
echo -e "\n[4b/7] Retransmitting Batch 1 (Testing Idempotency)..."
BATCH1_DUP=$(curl -s -X POST "$BASE_URL/api/diagnostics/batches" \
  -H "Authorization: Bearer $TOKEN_RIDER1" \
  -H "Content-Type: application/json" \
  -d "{
    \"runId\": \"$RUN_ID\",
    \"sequenceNumber\": 1,
    \"payload\": {
      \"selfMetrics\": [{\"timestamp\": 1728900000000, \"rtt_ms\": 42.5}]
    }
  }")
echo "Rider 1 Batch 1 duplicate response: $BATCH1_DUP"

# Step 5: Rider 2 posts batch 1
echo -e "\n[5/7] Rider 2 posting Batch 1..."
BATCH2_RESP=$(curl -s -X POST "$BASE_URL/api/diagnostics/batches" \
  -H "Authorization: Bearer $TOKEN_RIDER2" \
  -H "Content-Type: application/json" \
  -d "{
    \"runId\": \"$RUN_ID\",
    \"sequenceNumber\": 1,
    \"payload\": {
      \"selfMetrics\": [
        {\"timestamp\": 1728900002000, \"rtt_ms\": 55.0, \"packets_sent\": 95, \"packets_received\": 90, \"packets_lost\": 5, \"jitter_ms\": 4.5, \"bytes_sent\": 15200, \"bytes_received\": 14400, \"network_type\": \"WIFI\"}
      ],
      \"inboundMetrics\": [
        {\"timestamp\": 1728900002000, \"remote_rider\": \"Rider_test_rider_alpha\", \"track_sid\": \"TR_002\", \"packets_received\": 90, \"packets_lost\": 3, \"jitter_ms\": 3.5, \"bytes_received\": 14400}
      ],
      \"events\": []
    }
  }")
echo "Rider 2 Batch 1 response: $BATCH2_RESP"

# Step 6: Rider 1 stops recording
echo -e "\n[6/7] Rider 1 stopping recording..."
STOP1_RESP=$(curl -s -X POST "$BASE_URL/api/diagnostics/stop" \
  -H "Authorization: Bearer $TOKEN_RIDER1" \
  -H "Content-Type: application/json" \
  -d "{\"runId\": \"$RUN_ID\"}")
echo "Rider 1 stop response: $STOP1_RESP (runClosed should be false since Rider 2 is still active)"

# Rider 2 stops recording -> triggers run close + ZIP export + email
echo -e "\n[6b/7] Rider 2 stopping recording (triggers run closure)..."
STOP2_RESP=$(curl -s -X POST "$BASE_URL/api/diagnostics/stop" \
  -H "Authorization: Bearer $TOKEN_RIDER2" \
  -H "Content-Type: application/json" \
  -d "{\"runId\": \"$RUN_ID\"}")
echo "Rider 2 stop response: $STOP2_RESP (runClosed should be true)"

# Step 7: Export ZIP archive via admin endpoint
echo -e "\n[7/7] Exporting diagnostic ZIP archive (admin key)..."
EXPORT_OUT="diagnostic_export_${RUN_ID}.zip"
HTTP_STATUS=$(curl -s -w "%{http_code}" -o "$EXPORT_OUT" \
  -H "x-diag-admin-key: $ADMIN_KEY" \
  "$BASE_URL/api/diagnostics/runs/$RUN_ID/export")

echo "Export HTTP Status: $HTTP_STATUS"
if [ "$HTTP_STATUS" -eq 200 ]; then
  ZIP_SIZE=$(stat -c%s "$EXPORT_OUT" 2>/dev/null || wc -c < "$EXPORT_OUT")
  echo "SUCCESS! Exported ZIP archive ($ZIP_SIZE bytes) to $EXPORT_OUT"
else
  echo "Error downloading export. HTTP Status: $HTTP_STATUS"
  cat "$EXPORT_OUT"
  exit 1
fi

echo -e "\n========================================================"
echo " Network Diagnostics E2E Test Completed Successfully!"
echo "========================================================"
