# RiderVoice Offline Mesh Intercom Protocol Specification (v4)

**Version:** 4.0.0  
**Status:** Implemented and Audited  
**Transport:** UDP Broadcast / Multicast / Unicast on Port 45454 (`MeshConfig.MESH_PORT`)  
**Target Topology:** Up to 6 motorcycling intercom devices in ad-hoc P2P mesh  

---

## 1. Architectural Principles

1. **Zero Centralized Infrastructure:** Operates entirely over local radio links without internet, cellular reception, or cloud relays. (v1 transport = UDP over a Wi-Fi/hotspot network that all riders are already connected to. The app does not create or join that network.)
2. **Daisy-Hop Routing:** Messages are forwarded over multi-hop paths using BFS shortest-path routing, bounded to at most 6 nodes and 5 hops.
3. **Single Authenticated Encryption Tag & Zero-AES Mutable Forwarding:**
   - **Single AEAD Tag:** Double authentication (outer HMAC + inner GCM) has been replaced by a single 96-bit (12-byte) AES-256-GCM authentication tag.
   - **Subkey Derivation:** Per-source, per-epoch subkeys are derived via HKDF-SHA256 (`salt = "rvmesh/v4/src"`, `info = srcShort(4) || epoch(4)`).
   - **Deterministic Nonce (12 bytes):** `epoch(4) || srcShort(4) || seq(3) || typeCode(1)`.
   - **AAD Binding (12 bytes):** `vtf(1) || srcShort(4) || epoch(4) || seq(3)`.
   - **Zero-AES Forwarding:** Mutable hop fields (`ttlHop` and `prevShort`) are outside AAD, permitting intermediate relays to decrement TTL, increment hop count, and rewrite previous hop in-place without re-encrypting or running AES.
4. **Airtime Hygiene & Relay Suppression:**
   - Redundant relay suppression is implemented in `ForwardingEngine` (`MeshConfig.SUPPRESS_REDUNDANT_RELAYS`) to eliminate duplicate multi-path transmissions.
   - Voice datagram target size is ≤ 100 bytes (Opus 16 kbit/s = 69 bytes total).
5. **Replay Protection without Wall Clocks:**
   - Wall clocks are eliminated from wire packets, preventing desync drops due to clock drift.
   - Replay is defeated by a 128-bit bitmap replay window (`ReplayWindow`) per `(srcShort, epoch, type)` stream, tracking sequence numbers modulo 2^24.
   - Epoch pinning: on receiving the first valid authenticated packet from `srcShort`, receiver pins the epoch; packets with differing epochs are rejected.
6. **Short Node Identifiers:**
   - 4-byte `srcShort` and `prevShort` identifiers replace 16-byte UUIDs on the wire.
   - `ShortIdDirectory` associates `srcShort` with full 16-byte UUID advertised in `HELLO` messages (whose payload begins with 16-byte full UUID).
7. **Session Isolation:**
   - Sessions are identified by a 128-bit UUID derived from a 16-symbol pairing code.
   - Keys are derived via Slow KDF (`PBKDF2WithHmacSHA256`, 120,000 iterations, salt `"rvmesh/v2/session"`, 80-byte output).

---

## 2. Wire Packet Format

Every datagram transmitted on the network adheres to the binary big-endian layout below. All multi-byte integers are encoded in **Big-Endian (Network Byte Order)**.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|      vtf      |     ttlHop    |           srcShort            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|   srcShort    |           prevShort           |     epoch     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|         epoch (cont.)         |             seq               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       seq     |       Ciphertext ...                          |
+-+-+-+-+-+-+-+-+                                               +
|                                                               |
+                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                               |       GCM Tag (12 bytes)      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+                               +
|                       GCM Tag (cont.)                         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### 2.1 Header Field Definitions (17 Bytes Total)

| Offset | Field | Type | Description |
|---|---|---|---|
| 0 | `vtf` | uint8 | Version (3 bits) = 4 (`0x04`), Type (2 bits: 0=VOICE, 1=HELLO, 2=BYE), Audio Format (3 bits: 0=NONE, 1=PCM, 2=OPUS16). |
| 1 | `ttlHop` | uint8 | TTL (high 4 bits) \| Hop Count (low 4 bits). Mutable during forwarding. |
| 2..5 | `srcShort` | uint32 | First 4 bytes of originator's UUID. |
| 6..9 | `prevShort` | uint32 | First 4 bytes of transmitting node's UUID. Mutable during forwarding. |
| 10..13 | `epoch` | uint32 | Transport epoch counter generated at startup. |
| 14..16 | `seq` | uint24 | Monotonically incrementing 24-bit sequence counter. |
| 17..N | `Ciphertext` | bytes | Sealed AES-256-GCM ciphertext (`datagram.length - 17 - 12`). |
| N..N+12 | `GCM Tag` | 12 bytes | 96-bit AES-GCM authentication tag. |

**Total Datagram Sizes:**  
- **Opus 16k Voice:** `17 (Header) + 40 (Opus) + 12 (GCM Tag) = 69 bytes`.
- **PCM Voice (fallback):** `17 (Header) + 640 (PCM) + 12 (GCM Tag) = 669 bytes`.
- **BYE:** `17 (Header) + 0 + 12 (GCM Tag) = 29 bytes`.

---

## 3. The Forwarding & Ingestion Pipeline

Upon receiving a UDP packet on port 45454, every node executes the following deterministic pipeline:

1. **Datagram Sizing:** Verify `datagram.length >= 17 + 12 = 29 bytes` (header + tag). If smaller, reject (`TOO_SHORT`).
2. **Version Validation:** Extract `version` from high 3 bits of byte 0 (`vtf ushr 5`). Must equal `0x04`. If mismatch, reject (`BAD_VERSION`).
3. **Packet Type Validation:** Extract `type` from middle 2 bits of byte 0. Must be 0 (VOICE), 1 (HELLO), or 2 (BYE).
4. **Length & Budget Bounds:** For voice packets with Opus format, verify size <= 100 bytes (`MeshConfig.MAX_VOICE_DATAGRAM_BYTES`). Oversized packets are dropped.
5. **Self-Origin Suppression:** Verify `srcShort != localShortId`. Packets originated locally are dropped.
6. **Short ID & Epoch Pinning:**
   - If packet is not `HELLO`, verify `srcShort` is known in `ShortIdDirectory`. Unknown sources are dropped.
   - Verify `epoch` matches pinned epoch for `srcShort`. Mismatched epochs are dropped.
7. **Replay Window Check:** Query bitmap replay window (`ReplayWindow`) for `(srcShort, epoch, type)`. If duplicate or out of window, reject (`DUPLICATE`).
8. **AEAD Decryption & Verification:**
   - Derive source subkey `K_src = HKDF-SHA256(payloadKey, salt="rvmesh/v4/src", info=srcShort||epoch)`.
   - Reconstruct 12-byte Nonce: `epoch(4) || srcShort(4) || seq(3) || typeCode(1)`.
   - Reconstruct 12-byte AAD: `vtf(1) || srcShort(4) || epoch(4) || seq(3)`.
   - Decrypt and verify 96-bit AES-GCM tag. If tag verification fails, reject (`BAD_TAG`).
9. **Commit Replay Sequence:** On successful AEAD verification, commit sequence into `ReplayWindow`.
10. **Dispatch by Packet Type:**
    - If `HELLO`: Payload begins with 16-byte full `deviceId`. Register `srcShort -> fullDeviceId` in `ShortIdDirectory`. Decode remaining topology discovery into `MeshGraphRepository`.
    - If `BYE`: Evict sender from active routing table.
    - If `VOICE`: Submit decrypted audio frame to jitter buffer.
11. **TTL & Hop Count Policy Enforcement:**
    - Reject if `ttl <= 0`, `hopCount > MAX_HOPS` (5), or `ttl + hopCount > DEFAULT_TTL` (5).
    - If `ttl <= 1` or `hopCount >= MAX_HOPS` (5), do not forward.
12. **Zero-AES Mutable Forwarding:**
    - Candidate neighbor selection: Direct radio neighbors excluding `prevShort` and `srcShort`.
    - Mutate byte 1: `newTtl = ttl - 1`, `newHop = hopCount + 1`, `ttlHop = (newTtl shl 4) or newHop`.
    - Mutate bytes 6..9: `prevShort = localShortId`.
    - Forward exact byte buffer directly to candidates with zero AES encryption.

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
6. **Per-Source Subkey (Wire v4):**
   `sourceKey = HKDF-SHA256(payloadKey, salt = "rvmesh/v4/src", info = srcShort || epoch)` (32 bytes).

---

## 5. Specification Test Vectors

Inputs:
- Master `payloadKey`: `0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20`
- Device ID: `11111111-1111-4111-8111-111111111111` (`srcShort = 0x11111111`)
- Epoch: `1`
- Sequence: `1`
- TTL: `5`, Hop Count: `0` (`ttlHop = 0x50`)

### 5.1 Voice Packet (Opus 16k, 40-byte audio payload, 69 bytes total)
```
8250111111111111111100000001000001fd1eab64dcd0c2490875ea8fb3ffc59284c325110d750f0cb032114907e9d5ad0328ac0db646c6937501d53308b213b05cdd5298
```

### 5.2 Hello Packet (57 bytes total)
```
8850111111111111111100000001000001e35d3186fa192364b2f517c95ac4a6c9d2056296e12a707d1e9dc2dc588febe4aba0dfa967f9eb734974323657
```

### 5.3 Bye Packet (29 bytes total)
```
90501111111111111111000000010000017f49d34882e571ae898a49d6
```
