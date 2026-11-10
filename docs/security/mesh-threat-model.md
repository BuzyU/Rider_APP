# RiderVoice Offline Mesh Intercom Security Architecture & Threat Model

**Version:** 4.0.0  
**Target:** RiderVoice Offline Mesh Intercom (`com.ridervoice.mesh`)  
**Scope:** Local ad-hoc shared Wi-Fi / UDP mesh intercom (v1 transport = UDP over a Wi-Fi/hotspot network that all riders are already connected to; the app does not create or join that network)  

---

## 1. System Assets & Security Objectives

| Asset | Objective | Primary Countermeasure |
|---|---|---|
| **Voice Audio Stream** | Confidentiality against eavesdroppers | End-to-end AES-256-GCM encryption with unique IVs |
| **Packet Integrity** | Authenticity against tampering or spoofing | 96-bit AES-GCM AEAD tag with AAD binding immutable fields |
| **Stream Availability** | Protection against replay, flooding, DoS | 128-bit bitmap replay window (`ReplayWindow`), epoch pinning, bounded queues, hop limits |
| **Pairing Credentials** | Resistance to brute-force dictionary attacks | PBKDF2WithHmacSHA256 with 120,000 iterations + salt |
| **Device Identity** | Non-clonability and isolation | Hardware-rooted UUID in secure storage (`allowBackup="false"`), short-id collision detection |

---

## 2. Attacker Personas & Threat Capabilities

### Persona A: Passive Radio Eavesdropper
- **Capability:** Receives all 2.4GHz / 5GHz Wi-Fi frames and UDP broadcast/unicast datagrams on port 45454.
- **Goal:** Intercept and listen to intercom conversations.
- **Countermeasure:** Voice payloads are sealed using AES-256-GCM with a 256-bit `payloadKey`. Without the 16-symbol pairing code, brute force is computationally infeasible.

### Persona B: Active Packet Injector / Man-in-the-Middle
- **Capability:** Transmits arbitrary UDP packets, replays previously recorded frames, alters packet headers in transit.
- **Goals:** Inject offensive audio, hijack sessions, cause jitter buffer degradation, exhaust battery via relay amplification.
- **Countermeasures & Security Analysis:**
  1. **Single AEAD Tag & Tampering:** Any modification to immutable header fields (`vtf`, `srcShort`, `epoch`, `seq`), ciphertext, or tag fails AES-256-GCM verification and is discarded before decoding.
  2. **Replay Protection (Zero Wall Clock):** The bitmap replay window (`ReplayWindow`) tracks sequence numbers per `(srcShort, epoch, type)` stream across a 128-bit window with 24-bit wrap-around diff. Zero reliance on wall clocks avoids clock desync vulnerabilities.
  3. **Mutable Hop Fields & DoS by TTL Tampering:** `ttlHop` and `prevShort` are excluded from AAD to allow intermediate relays to forward packets with zero AES overhead. An on-path adversary can alter TTL to cause early packet expiration; however, they cannot modify packet contents or impersonate members without invalidating the 96-bit GCM tag.
  4. **Epoch Pinning & Residual Risk:** Receivers pin the sender's epoch on the first valid authenticated packet. Packets with mismatched epochs are dropped. Residual risk: an adversary cannot forge epochs without `payloadKey`, preventing cross-epoch replay.
  5. **Short ID Collision Handling:** `ShortIdDirectory` associates 4-byte `srcShort` with full 16-byte UUID from authenticated `HELLO` frames. If a collision is detected, `idCollision` is raised and conflicting frames are dropped.
  6. **Relay Amplification:** Forwarding engine suppresses transmission to radio neighbors that already directly hear the source or prior hop.

### Persona C: Compromised Convoy Member (Insider)
- **Capability:** Possesses the authentic pairing code and valid session keys.
- **Goals:** Impersonate other riders, transmit corrupt payloads, flood the mesh.
- **Countermeasures & Residual Limitations:**
  - **Payload Size Clamping:** Over-sized frames (>100 bytes for Opus voice, >1,200 bytes total) are dropped prior to decoding.
  - **Device Cap:** Maximum 6 devices admitted per session. Attempts to introduce a 7th device return `FULL` and are rejected.
  - *Known Residual Risk:* Because the session uses a symmetric group key, a malicious node with the key can forge packets claiming another member's `srcShort`. True per-node non-repudiation would require an asymmetric public-key infrastructure (PKI) and per-packet digital signatures, which is incompatible with 20ms low-latency ad-hoc voice on low-power devices. This trade-off is accepted by design for closed convoys.

### Persona D: Local Physical Attacker / Compromised Device
- **Capability:** Possesses physical access to an unlocked Android phone or runs malicious apps on the device.
- **Goal:** Clone identity, extract session keys, compromise other convoy members.
- **Countermeasures:**
  - `AndroidManifest.xml` explicitly sets `android:allowBackup="false"`, preventing extraction of stored device identities or keys via `adb backup`.
  - Display names are sanitized against control characters, ASCII escapes, and zero-width spaces (`MeshIdentityRepository.cleanDisplayName`).

---

## 3. Cryptographic Key Separation

The architecture enforces strict key separation derived from the master secret:

```
[16-Character Crockford Base32 Code] 
           │
           ▼
PBKDF2WithHmacSHA256 (120,000 iterations, salt "rvmesh/v2/session")
           │
           ▼ (80 bytes derived key)
 ┌─────────┼──────────┐
 │         │          │
 ▼         ▼          ▼
[0..15]  [16..47]   [48..79]
Session   authKey   payloadKey
 UUID                (AES-256)
                       │
                       ▼ HKDF-SHA256 (salt "rvmesh/v4/src", info srcShort||epoch)
                  sourceKeyV4 (32 bytes per source/epoch)
```

- **`payloadKey`:** Master session symmetric key used to derive per-source subkeys.
- **`sourceKeyV4`:** HKDF-derived 256-bit subkey unique to each `(srcShort, epoch)` pair, ensuring complete IV separation across senders and restarts.

---

## 4. Verification & Audit History

All security controls were tested and verified under adversarial conditions:
- **`AdversarialTest.kt`:** Verified rejection of tampered bytes, invalid GCM tag, bad sequence, and replayed packets.
- **`SessionKdfTest.kt`:** Verified slow-KDF timing, Crockford Base32 normalization, and key derivation parity.
- **`V4RoundTripTest.kt` & `V4TagTamperTest.kt`:** Verified AEAD integrity, tamper detection, and bit-exact payload round-trips.
- **`ReplayWindowTest.kt`:** Verified 128-bit bitmap replay window under out-of-order delivery, duplicate rejection, and wrap-around.
- **`NoWallClockTest.kt`:** Verified independent operation under arbitrary clock skew.

---

## 5. Shared Secret Model & Remediation Decisions

### 5.1 Single Shared Secret Model
- **Elimination of Room-Derived Secrets:** Deriving mesh encryption keys from online ride room names is insecure and has been completely removed.
- **Out-of-Band Master Secret:** All sessions strictly use a 16-character Crockford Base32 pairing code established out-of-band by convoy members.
- **Fail-Closed Fallback Invariant:** If an online ride experiences network loss, the automatic switch manager only transitions to offline mesh if an authentic, unexpired pre-shared backup session is loaded in `OfflineSessionHolder.backupSession` (`OfflineAvailability.AVAILABLE`). If none exists (`OfflineAvailability.NO_SHARED_SECRET`), the state machine remains in `AutoSwitchState.ONLINE` and emits the `"NO_SHARED_SECRET"` diagnostic without starting unauthenticated radio traffic.

### 5.2 Deterministic Nonce & Per-Source Subkey Construction (Wire v4)
- Payloads encrypted with AES-256-GCM utilize a 12-byte (96-bit) deterministic nonce:
  ```
  [0..3]   Epoch (sender reboot counter)
  [4..7]   srcShort (first 4 bytes of source UUID)
  [8..10]  Monotonic Sequence Number (24-bit)
  [11]     Packet Type Code (0x00 = VOICE, 0x01 = HELLO, 0x02 = BYE)
  ```
- Combined with monotonic sequence tracking, epoch increments on restart, and independent per-source subkeys derived via HKDF-SHA256 (`PayloadCrypto.sourceKeyV4`), this guarantees zero IV reuse across the session lifetime.

---

### 5.3 Shared-Key Forgery, Relay Decryption & Topology Mitigations
- **Shared Group Key & Member Forgery:** All session members share keys derived from the convoy pairing code (`payloadKey`). Consequently, any authenticated session member can technically forge datagrams claiming any `srcShort`, inject arbitrary `HELLO` announcements, or send forged `BYE` teardowns.
- **Direct-Heard BYE Teardown Mitigation:** To prevent distant nodes or rogue relays from falsely tearing down active direct radio links, `ForwardingEngine` strictly ignores any `BYE` packet with `hopCount > 0` for any device that has been heard directly within the last `NEIGHBOR_TIMEOUT_MS`.
- **Relay Processing:** Intermediary relay nodes possess `payloadKey` and derive per-source subkeys to decrypt payloads for local delivery. For forwarding, relays mutate mutable hop fields in-place without re-encrypting.
- **Local-Join-Relative Expiry:** Session expiry (`expiresAt = createdAt + 8h`) is calculated relative to the local node's join timestamp.
- **Code Reuse & Key Rotation:** Reusing the same Crockford Base32 pairing code across rides recreates identical keys. Convoy members must generate a new code per ride to maintain forward secrecy.
- **Encrypted Backup Session Persistence & Key Zeroization:** `OfflineSessionHolder.backupSession` persists pre-ride backup pairing metadata in `EncryptedPrefsBackupSessionStore` backed by `rvmesh_backup_master_key`. Derived keys in RAM are zeroized on session close (`AutoCloseable`).
- **Debug Receiver Guard:** `MeshDebugReceiver` is compiled exclusively into debug source sets and guarded with `android:permission="android.permission.DUMP"`.

---

## 6. Verification Status

Automated unit tests verified cryptographic separation and secret hygiene in JVM:
- `SessionHygieneTest.kt`: Verified zero room-derived secret leaks, argument-free navigation, and clean session clearing.
- `AdversarialTest.kt`: Verified rejection of tampered bytes, invalid tag, corrupted IV, bad sequence, and replayed packets.
- `SessionKdfTest.kt`: Verified slow-KDF timing, Crockford Base32 normalization, and key derivation parity.

*Note: Multi-device RF and physical range testing are tracked in `docs/testing/PHYSICAL_TEST_CHECKLIST.md` as NOT EXECUTED.*
