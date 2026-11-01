package com.ridervoice.mesh

/** Tunable constants for the offline mesh. Values are starting points pending physical tests. */
object MeshConfig {
    const val DISCOVERY_PORT = 45455
    const val AUDIO_PORT = 45454

    const val MAX_DEVICES = 6
    const val HELLO_INTERVAL_MS = 1_500L
    const val NEIGHBOR_TIMEOUT_MS = 5_000L
    /** Remote (multi-hop) adjacency reports live slightly longer: they arrive via relays. */
    const val REMOTE_REPORT_TIMEOUT_MS = 8_000L
    const val MEMBER_TIMEOUT_MS = 30_000L

    /** Number of times a packet may still be forwarded. 5 reaches 6 hops, the max chain for 6 devices. */
    /**
     * When true, a relay does not forward a packet to a neighbor that is already a (fresh, bidirectional) radio
     * neighbor of the previous hop or of the original source: that neighbor heard the packet directly.
     * Without this, 6 fully connected devices produced 25 datagrams per voice frame instead of 5.
     * Trade-off: removes accidental redundancy, so measure packet loss on real hardware (device scenario 13/35).
     */
    const val SUPPRESS_REDUNDANT_RELAYS = true
    const val DEFAULT_TTL = 5
    /** HELLO floods are kept shorter than audio. */
    const val HELLO_TTL = 4
    const val MAX_NEIGHBORS_IN_HELLO = 5

    /** Sanity bound only: offline phones may have skewed clocks, so this is deliberately loose. */
    const val MAX_CLOCK_SKEW_MS = 60_000L
    const val DUP_CACHE_MAX_ENTRIES = 32_000
    const val DUP_CACHE_TTL_MS = 2 * MAX_CLOCK_SKEW_MS + 5_000L
    const val MAX_PAYLOAD_BYTES = 1_200
    /** Per-source forwarding budget (packets per 1s window). 20 ms frames = 50/s, plus headroom. */
    const val FORWARD_BUDGET_PER_SEC = 120

    const val SAMPLE_RATE = 16_000
    const val FRAME_MS = 20
    const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000      // 320
    const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2                // 640
    const val GCM_TAG_BYTES = 16
    const val ENCRYPTED_VOICE_PAYLOAD_BYTES = BYTES_PER_FRAME + GCM_TAG_BYTES // 656

    const val SESSION_LIFETIME_MS = 8L * 60 * 60 * 1000

    /** Debug peer filter for test hooks / partition testing. Null by default. */
    @Volatile var debugPeerFilter: ((String) -> Boolean)? = null
}

