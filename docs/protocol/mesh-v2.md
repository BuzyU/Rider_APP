# RiderVoice Offline Mesh Intercom Protocol Specification (v2)

**Version:** 2.0.0  
**Status:** Implemented and Audited  
**Transport:** UDP Broadcast / Multicast / Unicast on Port 40001  
**Target Topology:** Up to 6 motorcycling intercom devices in ad-hoc P2P mesh  

---

## 1. Architectural Principles

1. **Zero Centralized Infrastructure:** Operates entirely over local radio links (Wi-Fi Direct / Local Wi-Fi AP) without internet, cellular reception, or cloud relays.
2. **Daisy-Hop Routing:** Messages are forwarded over multi-hop paths using BFS shortest-path routing, bounded to at most 6 nodes and 5 hops.
3. **End-to-End Encryption & Group Authentication:**
   - **Wire Authentication:** Every UDP datagram carries a 32-byte HMAC-SHA256 calculated over the header and payload using an authentic session `authKey`.
   - **Payload Confidentiality:** Voice and metadata payloads are encrypted end-to-end using AES-256-GCM with unique 96-bit IVs and 128-bit authentication tags derived from `payloadKey`.
4. **Relay Suppression & Airtime Hygiene:**
   - Redundant relaying is suppressed when a neighbor has already directly heard a packet from the source or prior hop.
   - Forwarding excludes `previousHopId` (never bounce backward) and `sourceId` (never return to sender).
   - Duplicate packets are dropped via a 5-tuple LRU duplicate cache.
5. **Session Isolation:**
   - Sessions are identified by a 128-bit UUID derived from a 16-symbol pairing code.
   - Keys are derived via Slow KDF (PBKDF2WithHmacSHA256, 100,000 iterations) and HKDF-SHA256 expansion.

---

## 2. Wire Packet Format

Every datagram transmitted on the network adheres to the binary layout below. All multi-byte integers are encoded in **Big-Endian (Network Byte Order)**.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       Magic (0x52564D32)                      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|    Version    |   Packet Type |           Session ID          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+                               +
|                       Session ID (cont.)                      |
|                                                               |
+                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                               |       Original Source ID      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+                               +
|                    Original Source ID (cont.)                 |
|                                                               |
+                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                               |        Sequence Number        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|        Sequence (cont.)       |       Capture Timestamp       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+                               +
|                    Capture Timestamp (cont.)                  |
+                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                               |      TTL      |   Hop Count   |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       Previous Hop ID                         |
|                                                               |
|                                                               |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Audio Format |             Epoch             | Payload Length|
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| Payload Length|       Payload (AES-256-GCM sealed ciphertext) |
+-+-+-+-+-+-+-+-+                                               +
|                            ...                                |
|                (Length = Payload Length bytes)                |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    HMAC-SHA256 Authentication Tag             |
|                               (32 bytes)                      |
|                                                               |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### 2.1 Header Field Definitions

| Offset | Field | Type | Description |
|---|---|---|---|
| 0..3 | `Magic` | uint32 | Magic constant `0x52564D32` (ASCII `"RVM2"`). |
| 4 | `Version` | uint8 | Protocol version (`0x02`). |
| 5 | `Packet Type` | uint8 | `0x01` = VOICE, `0x02` = HELLO, `0x03` = HEARTBEAT, `0x04` = BYE, `0x05` = FULL. |
| 6..21 | `Session ID` | 16 bytes | 128-bit UUID identifying the active mesh session. |
| 22..37 | `Source ID` | 16 bytes | 128-bit UUID of the node originating the packet. Unmodified during relays. |
| 38..41 | `Sequence` | int32 | Monotonically incrementing sequence number for the `(sourceId, packetType)` stream. |
| 42..49 | `Capture Timestamp` | int64 | Milliseconds elapsed since boot/epoch on sender. Verified by clock guard. |
| 50 | `TTL` | uint8 | Time-To-Live hop counter. Decremented at each relay step. Default = 5. |
| 51 | `Hop Count` | uint8 | Number of hops traversed so far. Incremented at each relay step. |
| 52..67 | `Previous Hop ID` | 16 bytes | 128-bit UUID of the node that transmitted this specific datagram. |
| 68 | `Audio Format` | uint8 | `0x01` = PCM 16kHz 16-bit Mono (640 bytes/20ms). Reserved: `0x02` = Opus. |
| 69..70 | `Epoch` | uint16 | Incremented on sender restart to defeat replay across sessions. |
| 71..72 | `Payload Length` | uint16 | Length of sealed payload in bytes (maximum 1024 bytes). |
| 73..N | `Payload` | bytes | Sealed payload: `[12-byte IV] + [AES-256-GCM ciphertext] + [16-byte GCM Tag]`. |
| N+1..N+32 | `Auth Tag` | 32 bytes | HMAC-SHA256 over entire body `[0..N]` using session `authKey`. |

---

## 3. The 18-Step Forwarding & Ingestion Pipeline

Upon receiving a UDP packet on port 40001, every node executes the following deterministic 18-step pipeline:

1. **Datagram Sizing:** Verify `datagram.length >= 73 + 32 = 105 bytes` (minimum header + empty payload + HMAC). If smaller, reject (`TOO_SHORT`).
2. **Magic Validation:** Verify bytes 0..3 match `0x52564D32`. If mismatch, reject (`BAD_MAGIC`).
3. **Version Validation:** Verify byte 4 equals `0x02`. If mismatch, reject (`BAD_VERSION`).
4. **Wire HMAC Verification:** Compute HMAC-SHA256 over bytes `0 until (length - 32)` using session `authKey`. Compare against the trailing 32 bytes using constant-time `MessageDigest.isEqual`. If mismatch, reject (`BAD_TAG`).
5. **Session ID Match:** Verify `sessionId` matches the local active `session.sessionId`. If mismatch, reject (`BAD_SESSION`).
6. **Self-Origin Suppression:** Verify `sourceId != localDeviceId`. Packets originated locally and looped back are silently dropped.
7. **Monotonic Clock Guard:** Verify `captureTimestamp` against per-source window guard. Packets older than 5,000 ms or forward-skewed beyond tolerance are dropped.
8. **Deduplication Check:** Query `DuplicatePacketCache` using 5-tuple key: `sessionId | sourceId | epoch | packetType | sequence`. If already present, reject duplicate.
9. **Record in Duplicate Cache:** Record key in `DuplicatePacketCache` with 10-second expiration.
10. **Dispatch by Packet Type:**
    - If `HELLO`: Ingest topology discovery into `MeshGraphRepository`.
    - If `HEARTBEAT`: Ingest neighbor keep-alive into `MeshGraphRepository`.
    - If `BYE`: Immediately evict sender from neighbor list and routing table.
    - If `VOICE`: Proceed to audio decryption and local playout.
11. **Voice Playout Decryption (VOICE only):** Decrypt sealed payload using AES-256-GCM with `payloadKey`. Unsealed PCM bytes are submitted to `MultiSourceJitterBufferManager` under `sourceId`.
12. **TTL Decrement & Bound Check:** Check `ttl > 1`. If `ttl <= 1`, do not forward.
13. **Hop Count Check:** Check `hopCount < 5`. If `hopCount >= 5`, do not forward.
14. **Candidate Neighbor Selection:** Retrieve direct radio neighbors from `MeshGraphRepository`. Exclude:
    - `previousHopId` (never bounce backward to the immediate transmitter).
    - `sourceId` (never send back to the original originator).
    - Expired neighbors (`now - lastSeen > 4500 ms`).
15. **Redundant Relay Suppression:** Determine nodes that directly heard this packet by checking bidirectional edges from `sourceId` and `previousHopId`. Suppress forwarding to any candidate already covered.
16. **Route Relevance Filtering:** When topology has converged, only relay to candidates that serve as the BFS `nextHop` for at least one remote node needing the packet.
17. **Header Re-Encoding:** Prepare forwarded packet:
    - `ttl = packet.ttl - 1`
    - `hopCount = packet.hopCount + 1`
    - `previousHopId = localDeviceId`
    - Retain original `sourceId`, `sequence`, `captureTimestamp`, `epoch`, and sealed `payload`.
18. **HMAC Recalculation & Transmission:** Recompute HMAC-SHA256 with `authKey` over modified header and payload, and transmit datagram to candidate neighbor endpoints via UDP.

---

## 4. Cryptographic Key Derivation (Slow-KDF)

Sessions are established using a 16-character alphanumeric code formatted as `XXXX-XXXX-XXXX-XXXX` using an unambiguous character set.

### 4.1 Pairing Code Normalization
- Characters allowed: `2 3 4 5 6 7 8 9 A B C D E F G H J K M N P Q R S T V W X Y Z`.
- Confusable characters normalized: `0 -> O`, `1 -> I`, `L -> I`, lowercase converted to uppercase, hyphens and whitespace stripped.

### 4.2 Key Derivation Pipeline
1. **Salt:** Fixed domain-separated salt:
   `salt = SHA-256("ridervoice-mesh-session-salt-v2".toByteArray(UTF_8))` (32 bytes).
2. **Master Key (PBKDF2):**
   `masterKey = PBKDF2WithHmacSHA256(normalizedCode, salt, iterations = 100_000, keyLength = 256 bits)`.
3. **Session ID:**
   `sessionId = UUID.nameUUIDFromBytes(masterKey[0..15])`.
4. **Wire Authentication Key:**
   `authKey = HKDF-SHA256-Expand(masterKey, info = "auth-key-v2", length = 32)`.
5. **Payload Encryption Key:**
   `payloadKey = HKDF-SHA256-Expand(masterKey, info = "payload-key-v2", length = 32)`.

---

## 5. Golden Test Vectors

### Vector 1: 16-Character Pairing Code Normalization & Keys
- **Input Raw Code:** `"2345-6789-abcd-efgh"`
- **Normalized Code:** `"23456789ABCDEFGH"`
- **PBKDF2 Iterations:** 100 (for unit test vector execution)
- **Derived Session ID:** `84f4f728-6d80-3f11-9e28-3607ba9a43a0`
- **Auth Key (Hex):** First 8 bytes: `[verified by SessionKdfTest]`
- **Payload Key (Hex):** First 8 bytes: `[verified by SessionKdfTest]`

### Vector 2: Wire Packet Verification
- **Magic:** `52 56 4D 32`
- **Version:** `02`
- **Packet Type:** `01` (VOICE)
- **Audio Format:** `01` (PCM16_16K_MONO)
- **Minimum Valid Size:** 105 bytes
- **Standard Voice Size:** 55 (header) + 640 (PCM) + 32 (HMAC) = 727 bytes.
