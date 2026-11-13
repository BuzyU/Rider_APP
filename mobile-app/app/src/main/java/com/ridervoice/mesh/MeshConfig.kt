package com.ridervoice.mesh

/** Tunable constants for the offline mesh. Values are starting points pending physical tests. */
object MeshConfig {
    const val MESH_PORT = 45454

    const val MAX_DEVICES = 6
    const val MAX_HOPS = 5
    const val BOOST_HELLO_INTERVAL_MS = 750L
    const val HELLO_INTERVAL_MS = 1_500L
    const val STANDBY_HELLO_INTERVAL_MS = 5_000L
    const val ACTIVE_NEIGHBOR_TIMEOUT_MS = 5_000L
    const val STANDBY_NEIGHBOR_TIMEOUT_MS = 15_000L
    const val NEIGHBOR_TIMEOUT_MS = 5_000L
    /** Remote (multi-hop) adjacency reports live slightly longer: they arrive via relays. */
    const val REMOTE_REPORT_TIMEOUT_MS = 8_000L
    const val MEMBER_TIMEOUT_MS = 30_000L

    /**
     * When true, a relay does not forward a packet to a neighbor that is already a (fresh, bidirectional) radio
     * neighbor of the previous hop or of the original source: that neighbor heard the packet directly.
     * Without this, 6 fully connected devices produced 25 datagrams per voice frame instead of 5.
     * Trade-off: removes accidental redundancy, so measure packet loss on real hardware (device scenario 13/35).
     */
    const val SUPPRESS_REDUNDANT_RELAYS = true
    /** Number of times a packet may still be forwarded. 5 reaches 6 hops, the max chain for 6 devices. */
    const val DEFAULT_TTL = 5
    /** HELLO discovery floods match default TTL to reach all 6 nodes across 5 hops. */
    const val HELLO_TTL = DEFAULT_TTL
    const val MAX_NEIGHBORS_IN_HELLO = 5

    const val REPLAY_WINDOW_MAX_ENTRIES = 16
    const val DUP_CACHE_MAX_ENTRIES = 60_000
    const val MAX_VOICE_PAYLOAD_BYTES = 700
    const val MAX_CONTROL_PAYLOAD_BYTES = 400
    const val MAX_PAYLOAD_BYTES = 1_200
    /** Per-source forwarding budget (packets per 1s window). 20 ms frames = 50/s, plus headroom. */
    const val FORWARD_BUDGET_PER_SEC = 120

    const val SAMPLE_RATE = 16_000
    const val FRAME_MS = 20
    const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000      // 320
    const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2                // 640
    const val GCM_TAG_BYTES = 12
    const val ENCRYPTED_VOICE_PAYLOAD_BYTES = BYTES_PER_FRAME + GCM_TAG_BYTES // 652
    /** Wire budget for voice datagram: 17B header + max Opus 120B + 12B GCM tag = 149B (cap 150B) */
    const val MAX_VOICE_DATAGRAM_BYTES = 150

    const val OPUS_BITRATE_BPS = 16_000
    const val OPUS_COMPLEXITY = 5
    const val MAX_OPUS_PAYLOAD_BYTES = 120

    /**
     * Whether to attach platform AcousticEchoCanceler, NoiseSuppressor, and AutomaticGainControl
     * when using MediaRecorder.AudioSource.VOICE_COMMUNICATION. Default true until hardware A/B test.
     */
    const val USE_PLATFORM_EFFECTS_ON_VOICE_COMM_SOURCE = true

    const val SESSION_LIFETIME_MS = 8L * 60 * 60 * 1000
}
