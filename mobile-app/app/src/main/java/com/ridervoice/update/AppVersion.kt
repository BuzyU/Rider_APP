package com.ridervoice.update

data class AppVersion(
    val raw: String,
    val parts: List<Int>
) : Comparable<AppVersion> {

    companion object {
        fun parse(versionStr: String?): AppVersion {
            if (versionStr.isNullOrBlank()) {
                return AppVersion(raw = "0.0.0", parts = listOf(0, 0, 0))
            }

            val clean = versionStr.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore("-")
                .substringBefore("+")

            val components = clean.split(".")
                .mapNotNull { it.trim().toIntOrNull() }

            val effectiveParts = if (components.isEmpty()) listOf(0) else components
            return AppVersion(raw = versionStr.trim(), parts = effectiveParts)
        }
    }

    override fun compareTo(other: AppVersion): Int {
        val maxLen = maxOf(this.parts.size, other.parts.size)
        for (i in 0 until maxLen) {
            val partA = this.parts.getOrElse(i) { 0 }
            val partB = other.parts.getOrElse(i) { 0 }
            if (partA != partB) {
                return partA.compareTo(partB)
            }
        }
        return 0
    }

    fun isNewerThan(other: AppVersion): Boolean = this > other

    override fun toString(): String = raw
}
