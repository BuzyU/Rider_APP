# RiderVoice Offline Mesh Intercom Protocol Specification (v2)

**Version:** 2.0.0  
**Status:** Implemented and Audited  
**Transport:** UDP Broadcast / Multicast / Unicast on Port 45454 (`MeshConfig.AUDIO_PORT`)  
**Target Topology:** Up to 6 motorcycling intercom devices in ad-hoc P2P mesh  

---

## 1. Architectural Principles

1. **Zero Centralized Infrastructure:** Operates entirely over local radio links without internet, cellular reception, or cloud relays. (v1 transport = UDP over a Wi-Fi/hotspot network that all riders are already connected to. The app does not create or join that network.)
2. **Daisy-Hop Routing:** Messages are forwarded over multi-hop paths using BFS shortest-path routing, bounded to at most 6 nodes and 5 hops.
3. **End-to-End Encryption & Hop Authentication:**
   - **Hop Authentication:** Every UDP datagram carries a 16-byte outer authentication tag (truncated HMAC-SHA256) calculated over the header and sealed payload using the session `authKey`.
   - **Payload Confidentiality:** Voice payloads are encrypted end-to-end using AES-256-GCM with a 12-byte deterministic nonce (`epoch(4) + sequence(4) + typeCode(1) + sourceIdPrefix(3)`), 16-byte GCM tag, and AAD binding (`sessionId + sourceId + typeCode + epoch + sequence + captureTimestamp + audioFormat`) derived from `payloadKey`.
4. **Relay Suppression & Airtime Hygiene:**
   - Redundant relaying is suppressed when a neighbor has already directly heard a packet from the source or prior hop.
   - Forwarding excludes `previousHopId` (never bounce backward) and `sourceId` (never return to sender).
   - Duplicate packets are dropped via a sliding-window duplicate packet cache.
5. **Session Isolation:**
   - Sessions are identified by a 128-bit UUID derived from a 16-symbol pairing code.
   - Keys are derived via Slow KDF (`PBKDF2WithHmacSHA256`, 120,000 iterations, salt `"rvmesh/v2/session"`, 80-byte output).

---

## 2. Wire Packet Format

Every datagram transmitted on the network adheres to the binary big-endian layout below. All multi-byte integers are encoded in **Big-Endian (Network Byte Order)**.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       Magic (0x52564D31)                      |
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
|                       Capture Timestamp                       |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|      TTL      |   Hop Count   |        Previous Hop ID        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+                               +
|                     Previous Hop ID (cont.)                   |
|                                                               |
+                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                               |  Audio Format |     Epoch     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+               +
|         Epoch (cont.)         |         Payload Length        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       Payload (AES-256-GCM sealed ciphertext + 16B GCM tag)   |
|                            ...                                |
|                (Length = Payload Length bytes)                |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|              Outer HMAC-SHA256 Tag (Truncated to 16B)         |
|                            (16 bytes)                         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### 2.1 Header Field Definitions (75 Bytes Total)

| Offset | Field | Type | Description |
|---|---|---|---|
| 0..3 | `Magic` | uint32 | Magic constant `0x52564D31` (ASCII `"RVM1"`). |
| 4 | `Version` | uint8 | Protocol version (`0x02`). |
| 5 | `Packet Type` | uint8 | `1` = HELLO, `2` = VOICE, `3` = BYE. |
| 6..21 | `Session ID` | 16 bytes | 128-bit UUID identifying the active mesh session. |
| 22..37 | `Source ID` | 16 bytes | 128-bit UUID of the node originating the packet. Unmodified during relays. |
| 38..41 | `Sequence` | int32 | Monotonically incrementing sequence number for the `(sourceId, packetType)` stream. |
| 42..49 | `Capture Timestamp` | int64 | Milliseconds elapsed since Unix epoch on sender. Verified by clock guard. |
| 50 | `TTL` | uint8 | Time-To-Live hop counter. Decremented at each relay step. Default = 5 (`MeshConfig.DEFAULT_TTL`). |
| 51 | `Hop Count` | uint8 | Number of hops traversed so far. Incremented at each relay step. Sanity bound = 7 (`MeshConfig.MAX_HOPS`). |
| 52..67 | `Previous Hop ID` | 16 bytes | 128-bit UUID of the node that transmitted this specific datagram. |
| 68 | `Audio Format` | uint8 | `0x01` = PCM 16kHz 16-bit Mono (640 bytes / 20ms). |
| 69..72 | `Epoch` | int32 | Incremented on sender restart to defeat replay across sessions. |
| 73..74 | `Payload Length` | uint16 | Length of sealed payload in bytes (maximum 1,200 bytes). |
| 75..N | `Payload` | bytes | Sealed payload: `[AES-256-GCM ciphertext]` (640 bytes PCM) + `[16-byte GCM Tag]` = 656 bytes for VOICE. |
| N+1..N+16 | `Auth Tag` | 16 bytes | Truncated HMAC-SHA256 over entire body `[0..N]` using session `authKey`. |

**Total Voice Datagram Size:**  
`75 (Header) + 640 (PCM) + 16 (GCM Tag) + 16 (Outer Tag) = 747 bytes`.

---

## 3. The Forwarding & Ingestion Pipeline

Upon receiving a UDP packet on port 45454, every node executes the following deterministic pipeline:

1. **Datagram Sizing:** Verify `datagram.length >= 75 + 16 = 91 bytes` (header + minimum tag). If smaller, reject (`TOO_SHORT`).
2. **Magic Validation:** Verify bytes 0..3 match `0x52564D31`. If mismatch, reject (`BAD_MAGIC`).
3. **Version Validation:** Verify byte 4 equals `0x02`. If mismatch, reject (`BAD_VERSION`).
4. **Packet Type Validation:** Verify byte 5 matches known type (`HELLO`, `VOICE`, `BYE`). If invalid, reject (`BAD_TYPE`).
5. **Length Bounds Validation:** Verify `payloadLength <= 1200` and `datagram.length == 75 + payloadLength + 16`. If mismatch, reject (`BAD_LENGTH`).
6. **Wire HMAC Verification:** Compute HMAC-SHA256 over bytes `0 until (length - 16)` using session `authKey`. Compare the first 16 bytes against the trailing 16 bytes using constant-time `MessageDigest.isEqual`. If mismatch, reject (`BAD_TAG`).
7. **Session ID Match:** Verify `sessionId` matches the local active `session.sessionId`. If mismatch, reject.
8. **Self-Origin Suppression:** Verify `sourceId != localDeviceId`. Packets originated locally are dropped.
9. **Monotonic Clock Guard:** Verify `captureTimestamp` against clock skew guard (`abs(now - ts) <= MAX_CLOCK_SKEW_MS`).
10. **Deduplication Check:** Query `DuplicatePacketCache` using 5-tuple key: `sessionId | sourceId | epoch | packetType | sequence`. If already present, reject duplicate.
11. **Record in Duplicate Cache:** Record key in `DuplicatePacketCache` with expiration.
12. **Dispatch by Packet Type:**
    - If `HELLO`: Ingest topology discovery into `MeshGraphRepository`.
    - If `BYE`: Immediately evict sender from neighbor list and routing table.
    - If `VOICE`: Proceed to audio decryption and local playout.
13. **Voice Playout Decryption (VOICE only):** Decrypt sealed payload using AES-256-GCM with `payloadKey` and deterministic nonce. Unsealed PCM bytes are submitted to `MultiSourceJitterBufferManager` under `sourceId`.
14. **TTL & Hop Count Policy Enforcement:** 
    - Invariant: Drop and reject packet immediately if `ttl <= 0`, `hopCount > MAX_HOPS` (7), or `ttl + hopCount > DEFAULT_TTL` (5).
    - Forwarding Stop Condition: If `ttl <= 1` or `hopCount >= MAX_HOPS` (7), do not relay further.
15. **Candidate Neighbor Selection:** Retrieve direct radio neighbors from `MeshGraphRepository`. Exclude:
    - `previousHopId` (never bounce backward to the immediate transmitter).
    - `sourceId` (never send back to the original originator).
    - Expired neighbors.
16. **Redundant Relay Suppression:** Determine nodes that directly heard this packet by checking bidirectional edges from `sourceId` and `previousHopId`. Suppress forwarding to any candidate already covered.
17. **Header Re-Encoding & HMAC Recalculation:** Prepare forwarded packet:
    - `ttl = packet.ttl - 1`
    - `hopCount = packet.hopCount + 1`
    - `previousHopId = localDeviceId`
    - Retain original `sourceId`, `sequence`, `captureTimestamp`, `epoch`, and sealed `payload`.
    - Recompute outer truncated HMAC-SHA256 with `authKey` over modified header and payload, and transmit datagram to candidate neighbor endpoints via UDP unified port 45454 (`MeshConfig.MESH_PORT`).
18. **Endpoint Learning Invariant (D22):** Inbound unauthenticated packets are NEVER learned into the active endpoint table. Endpoints are registered strictly upon successful wire HMAC-SHA256 verification or explicit verified registration.
19. **Thread-Safe Routing Table:** `RoutingTable` employs `@Synchronized recompute` and atomic immutable snapshot swapping (`AtomicReference<RoutingSnapshot>`) so concurrent lookups and background graph updates never race or corrupt route states.

---

## 4. Cryptographic Key Derivation

Sessions are established using a 16-character alphanumeric code formatted as `XXXX-XXXX-XXXX-XXXX` using Crockford's Base32 alphabet.

### 4.1 Pairing Code Normalization
- Alphabet: `0123456789ABCDEFGHJKMNPQRSTVWXYZ` (32 characters, excluding I, L, O, U).
- Normalization: `i/I -> 1`, `l/L -> 1`, `o/O -> 0`, lowercase converted to uppercase, hyphens and whitespace stripped.

### 4.2 Key Derivation Pipeline
1. **Salt:** Fixed domain-separated salt:
   `salt = "rvmesh/v2/session".toByteArray(UTF_8)`.
2. **PBKDF2 Output (80 bytes):**
   `derivedBytes = PBKDF2WithHmacSHA256(normalizedCode, salt, iterations = 120_000, keyLength = 640 bits = 80 bytes)`.
3. **Session ID (UUID):**
   `sessionId = UUID from derivedBytes[0..15]`.
4. **Wire Authentication Key:**
   `authKey = derivedBytes[16..47]` (32 bytes).
5. **Payload Encryption Key:**
   `payloadKey = derivedBytes[48..79]` (32 bytes).
