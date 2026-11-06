# RiderVoice Offline Mesh Intercom Security Architecture & Threat Model

**Version:** 2.0.0  
**Target:** RiderVoice Offline Mesh Intercom (`com.ridervoice.mesh`)  
**Scope:** Local ad-hoc shared Wi-Fi / UDP mesh intercom (v1 transport = UDP over a Wi-Fi/hotspot network that all riders are already connected to; the app does not create or join that network)  

---

## 1. System Assets & Security Objectives

| Asset | Objective | Primary Countermeasure |
|---|---|---|
| **Voice Audio Stream** | Confidentiality against eavesdroppers | End-to-end AES-256-GCM encryption with unique IVs |
| **Packet Integrity** | Authenticity against tampering or spoofing | HMAC-SHA256 wire verification with constant-time check |
| **Stream Availability** | Protection against replay, flooding, DoS | 5-tuple DuplicatePacketCache, bounded queues, hop limits |
| **Pairing Credentials** | Resistance to brute-force dictionary attacks | PBKDF2WithHmacSHA256 with 100,000 iterations + salt |
| **Device Identity** | Non-clonability and isolation | Hardware-rooted UUID in secure storage (`allowBackup="false"`) |

---

## 2. Attacker Personas & Threat Capabilities

### Persona A: Passive Radio Eavesdropper
- **Capability:** Receives all 2.4GHz / 5GHz Wi-Fi frames and UDP broadcast/unicast datagrams on port 45454.
- **Goal:** Intercept and listen to intercom conversations.
- **Countermeasure:** Voice payloads are sealed using AES-256-GCM with a 256-bit `payloadKey`. Without the 16-symbol pairing code, brute force is computationally infeasible.

### Persona B: Active Packet Injector / Man-in-the-Middle
- **Capability:** Transmits arbitrary UDP packets, replays previously recorded frames, alters packet headers in transit.
- **Goals:** Inject offensive audio, hijack sessions, cause jitter buffer degradation, exhaust battery via relay amplification.
- **Countermeasures:**
  1. **Tampering:** Any modification to header or ciphertext fails HMAC-SHA256 verification (Step 4 of pipeline) and is discarded before parsing.
  2. **Replay:** The `DuplicatePacketCache` tracks `(sessionId, sourceId, epoch, kind, sequence)` with a 10s TTL. Replayed packets within 10s are dropped; older packets are rejected by the monotonic capture timestamp clock guard.
  3. **Relay Amplification (D26):** Forwarding engine suppresses transmission to radio neighbors that already directly hear the source or prior hop.

### Persona C: Compromised Convoy Member (Insider)
- **Capability:** Possesses the authentic pairing code and valid session keys.
- **Goals:** Impersonate other riders, transmit corrupt payloads, flood the mesh.
- **Countermeasures & Residual Limitations:**
  - **Payload Size Clamping (D21):** Over-sized frames (>1,024 bytes) are dropped prior to decoding.
  - **Device Cap:** Maximum 6 devices admitted per session. Attempts to introduce a 7th device return `FULL` and are rejected.
  - *Known Residual Risk:* Because the session uses a symmetric group key, a malicious node with the key can forge packets claiming another member's `sourceId`. True per-node non-repudiation would require an asymmetric public-key infrastructure (PKI) and per-packet digital signatures, which is incompatible with 20ms low-latency ad-hoc voice on low-power devices. This trade-off is accepted by design for closed convoys.

### Persona D: Local Physical Attacker / Compromised Device
- **Capability:** Possesses physical access to an unlocked Android phone or runs malicious apps on the device.
- **Goal:** Clone identity, extract session keys, compromise other convoy members.
- **Countermeasures:**
  - `AndroidManifest.xml` explicitly sets `android:allowBackup="false"`, preventing extraction of stored device identities or keys via `adb backup`.
  - Display names are sanitized against control characters, ASCII escapes, and zero-width spaces (`MeshIdentityRepository.cleanDisplayName`).

---

## 3. Cryptographic Key Separation

The architecture enforces strict separation between authentication and encryption keys derived from the master secret:

```
[16-Character Crockford Base32 Code] 
           │
           ▼
PBKDF2WithHmacSHA256 (100,000 iterations, salt "rvmesh/v2/session")
           │
           ▼ (80 bytes derived key)
 ┌─────────┼──────────┐
 │         │          │
 ▼         ▼          ▼
[0..15]  [16..47]   [48..79]
Session   authKey   payloadKey
 UUID   (HMAC-SHA) (AES-256-GCM)
```

- **`authKey`:** Used solely for wire datagram authentication (HMAC-SHA256). An intermediate relay node verifies this key to authenticate frames before forwarding, without needing to decrypt the audio.
- **`payloadKey`:** Used exclusively for payload encryption and decryption (AES-256-GCM). Relays never decrypt, re-encrypt, or alter the payload.

---

## 4. Verification & Audit History

All security controls were tested and verified under adversarial conditions:
- **`AdversarialTest.kt`:** Verified rejection of tampered bytes, invalid HMAC, invalid magic, corrupted IV, bad sequence, and replayed packets.
- **`SessionKdfTest.kt`:** Verified slow-KDF timing, Crockford Base32 normalization, and key derivation parity.
- **Mutation Checks:** Verified that dropping HMAC checks, allowing replay in duplicate cache, or admitting beyond the 6-device cap fails automated unit test assertions.


---

## 5. Shared Secret Model & Remediation Decisions (Q4 & Q5)

### 5.1 Single Shared Secret Model (Q4)
- **Elimination of Room-Derived Secrets:** Deriving mesh encryption keys from online ride room names (which are observable over signaling networks or URLs) is insecure and has been completely removed (`SessionKdf.fromOnlineRoom` deprecated and eliminated).
- **Out-of-Band Master Secret:** All sessions strictly use a 16-character Crockford Base32 pairing code established out-of-band by convoy members.
- **Fail-Closed Fallback Invariant:** If an online ride experiences network loss, the automatic switch manager only transitions to offline mesh if an authentic, unexpired pre-shared backup session is loaded in `OfflineSessionHolder.backupSession` (`OfflineAvailability.AVAILABLE`). If none exists (`OfflineAvailability.NO_SHARED_SECRET`), the state machine remains in `AutoSwitchState.ONLINE` and emits the `"NO_SHARED_SECRET"` diagnostic without starting unauthenticated radio traffic.

### 5.2 Deterministic Nonce Construction (Q5)
- Voice payloads encrypted with AES-256-GCM / ChaCha20-Poly1305 utilize a 12-byte (96-bit) deterministic nonce:
  ```
  [0..3]   Epoch (sender reboot counter)
  [4..7]   Monotonic Sequence Number
  [8]      Packet Type Code (0x02 = VOICE)
  [9..11]  Source Device UUID prefix (first 3 bytes)
  ```
- Combined with monotonic sequence tracking and epoch increments on restart, this guarantees zero IV reuse across the session lifetime.

---


### 5.3 Shared-Key Forgery, Relay Decryption & Debug Attack Surface (WP-40/WP-41/WP-49)
- **Shared Group Key & Forgery (B26):** Because all members share the same group symmetric keys (`authKey`, `payloadKey`), any member possesses the capability to forge datagrams claiming another member's `sourceId`. True non-repudiation would require asymmetric per-packet digital signatures, which is omitted by design due to 20ms voice latency and processing constraints on low-power mobile devices.
- **Relay Decryption (B26):** Intermediary relay nodes possess `payloadKey` (derived from the shared pairing code) and can decrypt audio packets they forward. There is no hop-by-hop asymmetric re-encryption between relays.
- **Local-Join-Relative Expiry (B26):** Session expiry (`expiresAt = createdAt + 8h`) is calculated relative to the local node's join timestamp, not synchronized via global NTP or consensus. A node joining later will compute a later expiration threshold unless manually ended.
- **Code Reuse & Key Rotation (B26):** Reusing the same Crockford Base32 pairing code across rides recreates identical `authKey` and `payloadKey` derivations. Convoy members must generate and distribute a new code per ride to maintain forward secrecy and prevent cross-ride replay.
- **Encrypted Backup Session Persistence & Key Zeroization:** `OfflineSessionHolder.backupSession` persists pre-ride backup pairing metadata (`pairingCode`, `createdAt`, `expiresAt`) in `EncryptedPrefsBackupSessionStore` (`EncryptedSharedPreferences` with self-healing fallback), enforcing the 8-hour TTL on restore and wiping persisted metadata on `clearBackup()` or user sign-out. Derived `authKey` and `payloadKey` byte arrays in RAM are zeroized immediately on session or `ForwardingEngine` close (`AutoCloseable`).
- **Debug Receiver Guard (A20):** `MeshDebugReceiver` is compiled exclusively into debug source sets and guarded with `android:permission="android.permission.DUMP"`, which can be held by the `adb shell` but cannot be acquired by normal third-party apps. It supports `--es allow "prefix1,prefix2"` peer filtering and logs strictly truncated 8-character session IDs, never pairing codes or key material.

## 6. Verification Status

Automated unit tests verified cryptographic separation and secret hygiene in JVM:
- `SessionHygieneTest.kt`: Verified zero room-derived secret leaks, argument-free navigation, and clean session clearing.
- `AdversarialTest.kt`: Verified rejection of tampered bytes, invalid HMAC, invalid magic, corrupted IV, bad sequence, and replayed packets.
- `SessionKdfTest.kt`: Verified slow-KDF timing, Crockford Base32 normalization, and key derivation parity.

*Note: Multi-device RF and physical range testing are tracked in `docs/testing/PHYSICAL_TEST_CHECKLIST.md` as NOT EXECUTED.*
