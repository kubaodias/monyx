package com.monyx.voice

import java.text.Normalizer
import java.util.Locale

/**
 * A spoken phrase against one household's own category names.
 *
 * The reason a table of rules beats a model here is that the target set is
 * tiny and self-chosen: ten to thirty names the family wrote themselves. What
 * it has to survive is Polish. The recogniser returns "spożywczy" where the
 * category reads "spożywcze", drops the diacritics on a bad connection, and
 * every noun after "na" arrives in a case the category name is not in.
 *
 * Four tiers, highest first, and every one of them is allowed to decline.
 * Whole-string Levenshtein is deliberately absent: it is the thing that
 * produces a confident "Auto" for "Autobus", and a confident wrong category in
 * a shared ledger is worse than a trip to the keypad.
 */
internal object CategoryMatcher {

    /**
     * One name matched, two names matched equally well, or nothing.
     *
     * A tie is its own answer rather than a silent pick. The first pass treats
     * it as a refusal; the summary sheet, which is already on screen and can
     * ask, offers the tied names as chips.
     */
    sealed interface Outcome {
        data class One(
            val category: VoiceCategory,
            /**
             * Whether the phrase named the WHOLE category or only a word of it.
             *
             * "zakupy" reaches `Zakupy spożywcze` on one of its two words, and
             * that is a good enough guess to file under — it is NOT good enough
             * to decide whether the row is money in or money out. See
             * [VoiceParser], where the difference is a flipped month sign.
             */
            val wholeName: Boolean,
            /**
             * How many names cleared the threshold at all. More than one, in a
             * sentence that also held more than one number, means two clauses
             * were said and neither was heard whole.
             */
            val contenders: Int,
            /**
             * Which of the phrase's words the name actually accounted for,
             * as indices into `phraseTokens`.
             *
             * The bookkeeping [SpokenAmount] has always done, for the same
             * reason: what is left over once the amount, the date and the name
             * are all spoken for is the note. Without it "150 zł na zakupy w
             * Biedronce" has no way to know that "Biedronce" was never used.
             */
            val matched: Set<Int>,
        ) : Outcome

        data class Tie(val candidates: List<VoiceCategory>) : Outcome
        data object None : Outcome
    }

    /** Tiers 1 to 3. What the first pass will act on without asking. */
    const val MIN_SCORE = 20

    /**
     * Tier 4 as well. Only the correction pass drops this low: the one reason
     * to hold the microphone over the summary is to name a category, so a
     * near-miss there is far more likely to be the right guess than it is in a
     * free-form sentence where the phrase might not be a category at all.
     */
    const val LENIENT_MIN_SCORE = 10

    /**
     * @param phraseTokens everything left after the amount and the date were
     *   taken out, filler words included — tiers 1 and 2 need them, because
     *   "Other income" is a category name whose second word is also an income
     *   keyword and stripping it would make the category unsayable.
     * @param contentIndices which of those are content, as indices: the same
     *   list with filler and kind keywords dropped. Tiers 3 and 4 read only
     *   these, which is what stops a bare "income" being taken as a request for
     *   "Other income". Indices rather than the words themselves so a match can
     *   report the span it used.
     */
    fun match(
        phraseTokens: List<String>,
        contentIndices: List<Int>,
        candidates: List<VoiceCategory>,
        minScore: Int = MIN_SCORE,
    ): Outcome {
        if (candidates.isEmpty()) return Outcome.None
        val scored = candidates
            .map { it to score(it, phraseTokens, contentIndices) }
            .filter { it.second.score >= minScore }
            .sortedByDescending { it.second.score }
        val best = scored.firstOrNull() ?: return Outcome.None
        val tied = scored.takeWhile { it.second.score == best.second.score }
        if (tied.size == 1) return best.one(scored.size)

        // A child beats its own parent on a draw. Both levels are real
        // destinations in the grid, and the more specific one is what somebody
        // naming a word the child owns meant. A draw between unrelated names is
        // still a refusal.
        val child = tied.firstOrNull { (candidate, _) -> tied.any { it.first.id == candidate.parentId } }
        return child?.one(scored.size) ?: Outcome.Tie(tied.map { it.first })
    }

    private fun Pair<VoiceCategory, Scored>.one(contenders: Int) =
        Outcome.One(first, second.wholeName, contenders, second.matched)

    /**
     * The four tiers, as scores, and the gaps between them are the point.
     *
     * They are far apart on purpose: a tier is a claim about how much of the
     * name was actually said, and no amount of arithmetic inside one tier may
     * ever carry a candidate into the one above. A name said in full has to
     * beat a name half-guessed at, whatever the two are — that is what stops
     * "Dom" outranking "Dom > Remonty" when the sentence held the child's own
     * word, and what keeps NEAR (a single typo, and the only tier the first
     * pass will not act on at all) below [MIN_SCORE].
     */
    private const val EXACT = 100
    private const val NAMED = 60
    private const val STEM = 20
    private const val NEAR = 10

    /** How much of the name was said, alongside how well. The two are separate
     *  answers: the score picks a category, and [Scored.wholeName] decides
     *  whether that category is allowed to speak for the KIND as well. */
    private data class Scored(val score: Int, val wholeName: Boolean, val matched: Set<Int>)

    private val NOTHING = Scored(0, wholeName = false, matched = emptySet())

    private fun score(
        category: VoiceCategory,
        phraseTokens: List<String>,
        contentIndices: List<Int>,
    ): Scored {
        val nameTokens = tokenise(category.name)
        if (nameTokens.isEmpty()) return NOTHING

        if (phraseTokens == nameTokens) {
            return Scored(EXACT, wholeName = true, matched = phraseTokens.indices.toSet())
        }
        // The whole name, in order, somewhere in the sentence. Within the tier,
        // scored by length so a longer name outranks a shorter one it contains.
        runStart(phraseTokens, nameTokens)?.let { start ->
            return Scored(
                score = NAMED + nameTokens.sumOf { it.length },
                wholeName = true,
                matched = (start until start + nameTokens.size).toSet(),
            )
        }

        // Stems, which is Polish inflection bought cheaply: "spożywcze" and
        // "spożywczy" share five letters, so do "zakupy" and "zakupów", and
        // English plurals come along free.
        val nameStems = nameTokens.map(VoiceWords::stem).toSet()
        val hitBy = { spokenStem: String -> nameStems.any { stemsMatch(it, spokenStem) } }
        val used = contentIndices.filter { hitBy(VoiceWords.stem(phraseTokens[it])) }.toSet()
        val hits = nameStems.count { nameStem ->
            used.any { stemsMatch(nameStem, VoiceWords.stem(phraseTokens[it])) }
        }
        if (hits > 0) {
            // Within the tier: more words of the name matched is better, and
            // covering ALL of a short name beats covering half of a long one.
            // The whole tier still spans 28..38, so it cannot reach NAMED.
            return Scored(
                score = STEM + hits * 8 + hits * 10 / nameStems.size,
                wholeName = hits == nameStems.size,
                matched = used,
            )
        }

        // One typo, in a word long enough for one typo to still leave a word.
        val close = contentIndices.filter { index ->
            val spoken = phraseTokens[index]
            spoken.length >= 4 && nameTokens.any { it.length >= 4 && withinOneEdit(it, spoken) }
        }.toSet()
        val near = nameTokens.count { name ->
            name.length >= 4 && close.any { withinOneEdit(name, phraseTokens[it]) }
        }
        return if (near > 0) Scored(NEAR + near, wholeName = false, matched = close) else NOTHING
    }

    /**
     * One word of a name against one word that was said, both already stemmed.
     *
     * A name token at or over the stem window has already had its ending cut
     * off, so an exact comparison is the whole job. A SHORTER one has nothing
     * to cut, and Polish can then only add to it — "Dom" is heard as "domu",
     * "domem", "domach" — so those endings have to be allowed back on. Only for
     * names that short, and that limit is load-bearing in the other direction:
     * letting a prefix reach any name would make "tran" a match for
     * "Transport", which is exactly the half-word guess the tiers exist to
     * refuse.
     */
    private fun stemsMatch(nameStem: String, spokenStem: String): Boolean =
        if (nameStem.length >= VoiceWords.STEM_LENGTH) {
            nameStem == spokenStem
        } else {
            spokenStem.startsWith(nameStem)
        }

    /** Where [name] appears as a contiguous run of [tokens], or null. Whole
     *  words only — a substring test matches "dom" inside "domowe" and files
     *  the wrong row. */
    private fun runStart(tokens: List<String>, name: List<String>): Int? {
        if (name.isEmpty() || name.size > tokens.size) return null
        for (start in 0..tokens.size - name.size) {
            if (tokens.subList(start, start + name.size) == name) return start
        }
        return null
    }

    /** Levenshtein, bounded at one and abandoned early. Two words that differ by
     *  more than a letter are different words, not a mis-hearing. */
    private fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        val (shorter, longer) = if (a.length <= b.length) a to b else b to a
        var i = 0
        var j = 0
        var edited = false
        while (i < shorter.length && j < longer.length) {
            if (shorter[i] == longer[j]) {
                i++
                j++
                continue
            }
            if (edited) return false
            edited = true
            if (shorter.length == longer.length) i++
            j++
        }
        return true
    }

    /**
     * Lower case, diacritics gone, ł folded to l.
     *
     * lowercase() with no argument is root-locale in Kotlin, which keeps the
     * Turkish dotless I out of a Polish household's category names. Ł is the
     * one letter here that does NOT decompose into a base plus a combining
     * mark, so stripping marks leaves it standing and "Łazienka" never matches
     * the "lazienka" a recogniser without diacritics returns. It is mapped by
     * hand, and forgetting to is the classic version of this bug.
     */
    fun normalise(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        return buildString(decomposed.length) {
            for (ch in decomposed) when {
                ch == 'ł' || ch == 'Ł' -> append('l')
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> Unit
                else -> append(ch)
            }
        }
    }

    /**
     * Normalised words. Everything that is not a letter or a digit becomes a
     * gap, except the decimal separators, which survive inside a token because
     * "12,50" is one word to the amount reader and would be two to everything
     * else. A separator left dangling at either end is punctuation and goes.
     */
    /**
     * Splits a sentence at an explicit note marker: what to parse, and what to
     * write down verbatim.
     *
     * Nothing after the marker means no note: "dodaj 200 na transport notatka"
     * is somebody who stopped talking, not a request for an empty one.
     */
    fun splitOnNoteMarker(value: String): Pair<String, String?> {
        val spoken = words(value)
        val marker = spoken.indexOfFirst { it.token in VoiceWords.noteMarkers }
        if (marker < 0) return value to null
        val note = spoken.drop(marker + 1).joinToString(" ") { it.source }
        return spoken.take(marker).joinToString(" ") { it.source } to asNote(note)
    }

    /**
     * A note as it will be read, which is by a person and not by this file.
     *
     * One capital at the front, applied here rather than at each render so
     * every route to a note — dictated after "notatka", left over at the end of
     * a sentence, or a whole sentence taken as one — arrives in the same shape,
     * and so the value in Room matches the value on screen.
     */
    fun asNote(value: String): String? = value
        .trim(' ', ',', '.', ':', ';')
        .takeIf { it.isNotEmpty() }
        ?.replaceFirstChar { it.titlecase(Locale.ROOT) }

    /**
     * One word, twice: as it was said and as this file compares it.
     *
     * A note is quoted back to the household, so it has to keep the diacritics
     * and the capitals that [normalise] exists to throw away — "w Biedronce",
     * not "w biedronce". Splitting once and carrying both is what keeps the two
     * halves aligned; normalising the whole string first and trying to map back
     * afterwards is the version of this that goes wrong on the first
     * apostrophe.
     */
    data class Word(val source: String, val token: String)

    fun words(value: String): List<Word> {
        val out = mutableListOf<Word>()
        val current = StringBuilder()
        fun flush() {
            val raw = current.toString().trim(',', '.')
            current.setLength(0)
            if (raw.isEmpty()) return
            val token = normalise(raw)
            if (token.isNotEmpty()) out += Word(raw, token)
        }
        for (ch in value) {
            // The separators survive inside a token because "12,50" is one word
            // to the amount reader and would be two to everything else.
            if (ch.isLetterOrDigit() || ch == ',' || ch == '.') current.append(ch) else flush()
        }
        flush()
        return out
    }

    fun tokenise(value: String): List<String> = words(value).map { it.token }
}
