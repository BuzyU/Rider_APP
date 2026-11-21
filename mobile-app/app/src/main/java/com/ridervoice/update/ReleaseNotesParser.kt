package com.ridervoice.update

/**
 * Structured, category-wise representation of release notes for clean UI presentation.
 */
enum class ReleaseNoteCategory {
    WHATS_NEW,
    IMPROVEMENTS,
    BUG_FIXES,
    RELIABILITY,
    NOTES
}

data class ReleaseNoteSection(
    val category: ReleaseNoteCategory,
    val items: List<String>
)

/**
 * Parses raw GitHub Markdown release notes (or pre-formatted bullet notes) into clean,
 * categorized sections with zero raw Markdown symbols (`#`, `-`, `*`, `` ` ``, pipes, URLs,
 * or internal work-package codes like `(WP-156, WP-164)`).
 */
object ReleaseNotesParser {

    private val markdownLinkRegex = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
    private val urlRegex = Regex("https?://\\S+")
    private val htmlTagRegex = Regex("<[^>]+>")
    private val internalTagRegex = Regex("\\s*\\((?:(?:WP|SEC|RM|F|RV)-\\d+[a-zA-Z]?|#[0-9]+)(?:\\s*,\\s*(?:(?:WP|SEC|RM|F|RV)-\\d+[a-zA-Z]?|#[0-9]+))*\\)")
    private val trailingPrNumberRegex = Regex("\\s*\\(#?\\d+\\)\\s*$")
    private val leadingBulletRegex = Regex("^[\\s#*•\\-–—+]+")
    private val numberedListRegex = Regex("^\\d+[.)]\\s+")
    private val multiSpaceRegex = Regex("\\s{2,}")

    fun parse(rawNotes: String?): List<ReleaseNoteSection> {
        if (rawNotes.isNullOrBlank()) return emptyList()

        val buckets = linkedMapOf<ReleaseNoteCategory, MutableList<String>>()
        var currentHeaderCategory: ReleaseNoteCategory? = null
        var inCodeBlock = false

        for (rawLine in rawNotes.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock
                continue
            }
            if (inCodeBlock || trimmed.isEmpty()) continue
            if (trimmed.startsWith("|") || trimmed.startsWith("---") || trimmed.startsWith("===")) continue
            if (trimmed.startsWith(">")) continue
            if (trimmed.startsWith("SHA-256", ignoreCase = true)) continue
            if (trimmed.startsWith("Full Changelog", ignoreCase = true) ||
                trimmed.startsWith("New Contributors", ignoreCase = true)
            ) continue

            // Detect explicit Markdown headings (## / ### / bold section headers)
            val isHeading = trimmed.startsWith("#") ||
                (trimmed.startsWith("**") && trimmed.endsWith("**") && trimmed.length < 60 && !trimmed.contains(":"))

            if (isHeading) {
                val headingText = cleanText(trimmed)
                val detected = detectHeaderCategory(headingText)
                if (detected != null) {
                    currentHeaderCategory = detected
                }
                // Always skip heading lines themselves from becoming bullet items
                continue
            }

            val cleaned = cleanText(trimmed)
            if (cleaned.isBlank()) continue
            if (isMetaBoilerplate(cleaned)) continue

            val category = currentHeaderCategory ?: classifyItem(cleaned)
            val list = buckets.getOrPut(category) { mutableListOf() }
            if (list.none { it.equals(cleaned, ignoreCase = true) }) {
                list.add(cleaned)
            }
        }

        val orderedCategories = listOf(
            ReleaseNoteCategory.WHATS_NEW,
            ReleaseNoteCategory.IMPROVEMENTS,
            ReleaseNoteCategory.BUG_FIXES,
            ReleaseNoteCategory.RELIABILITY,
            ReleaseNoteCategory.NOTES
        )
        return orderedCategories.mapNotNull { cat ->
            val items = buckets[cat]
            if (items.isNullOrEmpty()) null else ReleaseNoteSection(cat, items)
        }
    }

    internal fun cleanText(raw: String): String {
        var line = raw.trim()
        line = markdownLinkRegex.replace(line, "$1")
        line = urlRegex.replace(line, "")
        line = htmlTagRegex.replace(line, "")
        line = leadingBulletRegex.replace(line, "")
        line = numberedListRegex.replace(line, "")
        line = line.replace("**", "").replace("__", "")
        line = line.replace("#", "")
        line = line.replace("*", "")
        line = line.replace("`", "")
        line = line.replace("|", " ")
        line = line.replace("[", "").replace("]", "")
        line = internalTagRegex.replace(line, "")
        line = trailingPrNumberRegex.replace(line, "")
        // Strip common decorative emojis at start of headings/bullets
        line = line.replace(Regex("^[\\p{So}\\p{Cn}\\uFE0F\\s]+"), "")
        line = multiSpaceRegex.replace(line, " ").trim()
        line = line.trimEnd(';', ',')
        return line
    }

    private fun isMetaBoilerplate(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("ridervoice v") ||
            lower == "release highlights" ||
            lower == "overview" ||
            lower == "highlights" ||
            lower == "changelog" ||
            lower.contains("cryptographic checksum") ||
            lower.contains("release notes") ||
            lower == "key improvements and fixes" ||
            lower == "what's changed" ||
            lower == "whats changed"
    }

    private fun detectHeaderCategory(heading: String): ReleaseNoteCategory? {
        val lower = heading.lowercase()
        return when {
            lower.contains("fix") || lower.contains("bug") || lower.contains("resolved") ->
                ReleaseNoteCategory.BUG_FIXES
            lower.contains("new") || lower.contains("feature") || lower.contains("added") ->
                ReleaseNoteCategory.WHATS_NEW
            lower.contains("improv") || lower.contains("enhance") || lower.contains("ui") ||
                lower.contains("ux") || lower.contains("design") || lower.contains("perf") ->
                ReleaseNoteCategory.IMPROVEMENTS
            lower.contains("secur") || lower.contains("reliab") || lower.contains("stabil") ||
                lower.contains("mesh") || lower.contains("infra") || lower.contains("migration") ->
                ReleaseNoteCategory.RELIABILITY
            lower.contains("known") || lower.contains("note") || lower.contains("caveat") ->
                ReleaseNoteCategory.NOTES
            else -> null
        }
    }

    private fun classifyItem(text: String): ReleaseNoteCategory {
        val lower = text.lowercase()
        return when {
            lower.startsWith("not verified") || lower.contains("known limitation") || lower.startsWith("note:") ->
                ReleaseNoteCategory.NOTES
            lower.startsWith("fixed") || lower.startsWith("fix ") ||
                lower.startsWith("eliminated") || lower.startsWith("resolved") ||
                lower.startsWith("restored") || lower.startsWith("prevented") ||
                lower.contains("fixed ") || lower.contains("bug fix") ||
                lower.contains("crash") || lower.contains("leak") ->
                ReleaseNoteCategory.BUG_FIXES
            lower.startsWith("added") || lower.startsWith("new ") ||
                lower.startsWith("introduced") || lower.contains("is now enabled") ||
                lower.contains("supporting up to") || lower.startsWith("per-ride") ->
                ReleaseNoteCategory.WHATS_NEW
            lower.contains("security") || lower.contains("checksum") ||
                lower.contains("migration") || lower.contains("permission") ||
                lower.contains("isolation") || lower.contains("self-repair") ||
                lower.contains("play protect") || lower.contains("offline") ||
                lower.contains("backup") || lower.contains("safety") ->
                ReleaseNoteCategory.RELIABILITY
            else ->
                ReleaseNoteCategory.IMPROVEMENTS
        }
    }
}
