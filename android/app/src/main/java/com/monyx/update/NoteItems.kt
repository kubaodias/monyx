package com.monyx.update

/**
 * A release's notes as the bullets the dialog draws: one per line, any bullet
 * the author typed dropped, blank lines gone. scripts/release-version.mjs
 * noteItems stores them that way; this also reads the rows published before it.
 */
object NoteItems {
    private val typedBullet = Regex("""^\s*(?:[-*•]\s+)?""")

    fun of(notes: String): List<String> =
        notes.lines().map { it.replace(typedBullet, "").trim() }.filter { it.isNotEmpty() }
}
