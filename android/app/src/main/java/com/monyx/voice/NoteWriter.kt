package com.monyx.voice

/**
 * What was said, and what the rules made of it. Everything the model is told.
 *
 * Deliberately not the transcript alone: the amount and the category are what
 * the sentence has already been mined for, and saying so is what stops the
 * model offering them back as a note.
 */
data class NoteRequest(
    val transcript: String,
    val amountMinor: Long,
    val categoryName: String?,
    /** What [VoiceParser]'s suffix table produced. Never blank — the model is
     *  not asked to invent a note where the rules declined to. */
    val note: String,
)

/**
 * A second opinion on the note, from a model, when there happens to be one.
 *
 * An interface for the same reason [Recogniser] is one: what the ViewModel does
 * with the answer is worth testing on a JVM, and AICore cannot be reached from
 * one. [None] is the default everywhere — in every unit test, and on every
 * phone AICore does not serve, which is most phones in the world.
 *
 * The contract is deliberately weak, and that is the point. It may return null
 * for any reason at all and the caller must be identical either way: the row is
 * already written, with a note, and the summary is already on screen.
 */
interface NoteWriter {
    /** A better note, or null for "leave it alone". Never throws. */
    suspend fun improve(request: NoteRequest): String?

    companion object {
        val None: NoteWriter = object : NoteWriter {
            override suspend fun improve(request: NoteRequest): String? = null
        }
    }
}

/**
 * The prompt, and the gate the answer has to get through.
 *
 * Pure, and in one place, because both halves are the whole feature: a prompt
 * nobody can find is a prompt nobody will fix, and a model answer taken on
 * trust is a worse note than the six-line table it replaced.
 */
internal object NotePrompt {

    /**
     * What the model is asked.
     *
     * Three things it has to be told, and the third is the one that matters:
     *
     *  - what was said, and what has already been taken out of it, so the
     *    amount and the category are not offered back as a label;
     *  - that a place is written the way it is written on the shop, which is
     *    the entire job — Polish inflects it after "w" and a label should not
     *    be inflected;
     *  - that returning NOTHING is a correct answer. A model that always finds
     *    a label is worse than the table, because the table's failure is to
     *    leave a note alone and a model's is to invent one.
     *
     * The examples are the owner's own sentences. They are worth more than the
     * instructions above them and should stay real rather than becoming tidy
     * illustrations of a rule.
     */
    fun of(request: NoteRequest): String = buildString {
        appendLine("You label one expense in a household budget app. Polish and English.")
        appendLine("Reply with ONLY the label: at most four words, no quotes, no explanation.")
        appendLine("If the sentence names no place or thing worth labelling, reply with: -")
        appendLine("Write a place the way it is written on the shop, not the way it was said.")
        appendLine("Polish inflects a name after a preposition; the label is not inflected.")
        appendLine()
        appendLine("Said: 150 zl na zakupy w Biedronce -> Biedronka")
        appendLine("Said: wydalem 35 na paliwo -> -")
        appendLine("Said: 20 na transport bilet miesieczny -> Bilet miesieczny")
        appendLine("Said: sto zlotych na dom w Castoramie przy dworcu -> Castorama przy dworcu")
        appendLine()
        appendLine("Said: ${request.transcript}")
        request.categoryName?.let { appendLine("Already filed under: $it") }
        appendLine("Already read as the amount: ${request.amountMinor / 100}")
        appendLine("Current label: ${request.note}")
        append("Label:")
    }

    /** Four words of a shop name is generous; anything longer is a sentence,
     *  and a sentence is the model explaining rather than labelling. */
    private const val MAX_WORDS = 4
    private const val MAX_CHARS = 40

    /** The shortest prefix two forms of one name have to share. "Biedronce"
     *  and "Biedronka" share seven; four is loose enough for any inflection
     *  and far too tight to reach a word that was never said. */
    private const val GROUNDED_PREFIX = 4

    /**
     * A model answer is taken only when it is plausibly BETTER, which here
     * means: short, single-line, not a refusal, not the amount read back — and
     * grounded, meaning every word of it is a word that was actually said, or
     * one inflection away from one.
     *
     * Grounding is the load-bearing rule and it is what makes this safe to ship
     * on a cosmetic field. The model cannot introduce a shop that was never
     * mentioned, cannot summarise, and cannot editorialise; the most it can do
     * is re-render words the sentence already contained. A model having a bad
     * day degrades to "no change", never to a wrong note.
     */
    fun accept(answer: String?, request: NoteRequest): String? {
        val cleaned = answer?.trim()?.trim('"', '\'', '`', '.', ':')?.trim() ?: return null
        if (cleaned.isEmpty() || cleaned == "-") return null
        if ('\n' in cleaned || '\r' in cleaned) return null
        if (cleaned.length > MAX_CHARS) return null

        val words = CategoryMatcher.words(cleaned)
        if (words.isEmpty() || words.size > MAX_WORDS) return null

        // The amount, offered back as a label. "150" is not a note.
        val major = (request.amountMinor / 100).toString()
        if (words.any { it.token == major || it.token == request.amountMinor.toString() }) return null

        // A refusal or an explanation. Every one of these is a word no shop is
        // called and no label needs.
        if (words.any { it.token in REFUSALS }) return null

        val said = CategoryMatcher.words(request.transcript).map { it.token }
        if (!words.all { word -> said.any { grounded(word.token, it) } }) return null

        return CategoryMatcher.asNote(cleaned)?.takeIf { it != request.note }
    }

    private fun grounded(answer: String, said: String): Boolean {
        if (answer == said) return true
        val shared = answer.commonPrefixWith(said).length
        return shared >= GROUNDED_PREFIX && shared >= minOf(answer.length, said.length) - 3
    }

    private val REFUSALS = setOf(
        "sorry", "cannot", "can", "unable", "unfortunately", "sure", "here",
        "label", "note", "answer", "przepraszam", "niestety", "nie", "etykieta",
        "notatka", "oto", "brak",
    )
}
