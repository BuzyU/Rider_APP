# RiderVoice Offline Mesh Package Architecture

**Package:** `com.ridervoice.mesh`  
**Purpose:** Zero-infrastructure local radio voice intercom and automatic network switching fallback.  
**Transport Truth:** v1 transport = UDP over a Wi-Fi/hotspot network that all riders are already connected to. The app does not create or join that network.

---

## 1. Package Directory Map

```
com.ridervoice.mesh/
├── audio/
│   ├── JitterBuffer.kt                # SourceJitterBuffer (wrap-safe, PLC, delay bounds) & MultiSourceJitterBufferManager
│   ├── MeshVoxBridge.kt               # Bridge between PTT/VOX audio engine and offline mesh transport
│   ├── OfflineAudioCapture.kt         # AudioRecord PCM capture with atomic watchdog and loopback protection
│   └── OfflineAudioPlayback.kt        # AudioTrack PCM playback with 16-bit saturating mixing
├── forwarding/
│   ├── ForwardingEngine.kt            # 18-step ingestion pipeline, daisy-hop forwarding, relay suppression (D26)
│   └── TtlPolicy.kt                   # Hop count limits and TTL management
├── graph/
│   ├── MeshGraphRepository.kt         # Topology graph, bidirectional link tracking, neighbor expiration, monotonic updates
│   └── DirectNeighbor.kt              # Direct radio neighbor descriptor
├── identity/
│   ├── MeshIdentity.kt                # Identity model and validation
│   └── MeshIdentityRepository.kt      # Hardware-rooted UUID storage, display name hygiene
├── link/
│   ├── MeshEndpoint.kt                # Socket address abstraction (IP:Port)
│   ├── MeshFrame.kt                   # Inbound raw packet wrapper
│   ├── MeshLink.kt                    # Transport interface abstraction
│   └── UdpMeshLink.kt                 # Non-blocking UDP socket over shared Wi-Fi/hotspot network, subnet broadcast
├── protocol/
│   ├── DuplicatePacketCache.kt        # 5-tuple LRU duplicate filter with TTL
│   ├── HelloCodec.kt                  # Binary encoder/decoder for HELLO packets
│   ├── MeshPacket.kt                  # Wire packet header definition, encoder, decoder, HMAC-SHA256
│   └── PayloadCrypto.kt               # AES-256-GCM encryption/decryption
├── routing/
│   └── RouteCalculator.kt             # BFS shortest-path routing with deterministic lexicographic tie-breaking
├── session/
│   ├── OfflineSession.kt              # Session model, pairing code formatting, SessionManager (6-device cap)
│   └── OfflineSession.kt              # Session model, pairing code formatting, PBKDF2 key derivation
├── switching/
│   ├── AutomaticSwitchStateMachine.kt # 8-state finite state machine governing automatic failover between LiveKit and Mesh
│   ├── SwitchingModels.kt             # Health signal models (internet, livekit, routes, probe)
│   └── LocalHealthSignalsProvider.kt  # Polling and reactive sensor sampling health signals for state machine
├── transport/
│   ├── AudioOwnershipArbiter.kt       # Mutex-protected arbiter enforcing exclusive microphone hardware ownership
│   ├── OfflineMeshVoiceTransport.kt   # High-level transport orchestrator managing links, engines, and background services
│   ├── VoiceSessionCoordinator.kt     # Option B coordinator handling switching between LiveKit and Offline Mesh
│   └── VoiceTransport.kt              # Common voice transport interface
└── ui/
    ├── OfflineMeshLobbyScreen.kt      # Compose UI for creating/joining offline mesh sessions
    └── OfflineRoomScreen.kt           # Compose UI HUD for active offline mesh intercom with tactical diagnostics
```

---

## 2. End-to-End Data Flow

```
[Local Microphone]
        │
        ▼ (AudioRecord)
[OfflineAudioCapture] (Atomic watchdog, 20ms frames: 640 bytes PCM)
        │
        ▼
[PayloadCrypto.seal] (AES-256-GCM encryption with session payloadKey)
        │
        ▼
[ForwardingEngine.sendVoice] (Prepares MeshPacket with monotonic seq, captureTs, TTL=5)
        │
        ▼
[MeshPacketCodec.encode] (Packs 75-byte header, appends sealed payload, appends truncated HMAC-SHA256)
        │
        ▼
[UdpMeshLink.send] (UDP broadcast/unicast datagram on port 45454)
        │
       ~~~ Radio Link (Shared Wi-Fi / Hotspot Subnet) ~~~
        │
        ▼
[UdpMeshLink.incomingFrames] (Receives UDP datagram)
        │
        ▼
[ForwardingEngine.onFrame] (18-step pipeline: HMAC check, dedup, session match)
        ├─────────────────────────────────────────────────┐
        ▼ (Local Playout)                                 ▼ (Multi-Hop Relay)
[PayloadCrypto.open]                               [Forward Candidate Selection]
        │                                          (Exclude prevHop, source, covered)
        ▼                                                 │
[SourceJitterBuffer.enqueue]                              ▼
(Wrap-safe ordering, 60ms target delay)            [Header Re-encode & HMAC Update]
        │                                          (TTL--, HopCount++, prevHop=self)
        ▼                                                 │
[MultiSourceJitterBufferManager.mixNextFrame]             ▼
(16-bit saturating additive mixing)                [UdpMeshLink.send] (Relay datagram)
        │
        ▼ (AudioTrack)
[OfflineAudioPlayback] -> [Headset / Speaker]
```

---

## 3. Threading and Coroutine Model

1. **Socket I/O:** `UdpMeshLink` executes datagram send/receive operations on `Dispatchers.IO` using non-blocking buffers.
2. **Audio Capture & Playout:** `OfflineAudioCapture` and `OfflineAudioPlayback` run on dedicated background threads with high priority (`Process.THREAD_PRIORITY_URGENT_AUDIO`).
3. **Audio Hardware Arbiter:** `AudioOwnershipArbiter` protects ownership claims using a `kotlinx.coroutines.sync.Mutex`, guaranteeing that LiveKit and Offline Mesh never contend for the Android `AudioRecord` simultaneously.
4. **State Machine:** `AutomaticSwitchStateMachine` runs evaluation loops within structured coroutine scopes, emitting state updates via immutable `StateFlow`.
