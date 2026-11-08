package com.ridervoice.mesh

import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Deterministic UUID generator for tests, replacing ad-hoc non-UUID test strings.
 */
object TestIds {
    fun uuid(name: String): String =
        UUID.nameUUIDFromBytes(name.toByteArray(StandardCharsets.UTF_8)).toString()
}

