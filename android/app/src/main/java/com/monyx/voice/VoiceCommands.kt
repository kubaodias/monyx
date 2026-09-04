package com.monyx.voice

import com.monyx.ui.add.EntryKind
import java.time.LocalDate
import java.util.Locale

/**
 * What one spoken correction changes. Nulls change nothing.
 *
 * One record rather than a sealed set of commands, for one reason: "250 na
 * zakupy" is a single utterance that sets two fields, and a record of nullable
 * fields applies as one `copy(...)` and one write. The sync protocol carries
 * whole rows, so a half-applied correction has nowhere to live anyway.
 *
 * [revert] short-circuits the rest, which is why "nie, cofnij 250" takes the
 * row back and does not first set it to 250.
 */
data class VoiceCorrection(
    val revert: Boolean = false,
    val confirm: Boolean = false,
    val amountMinor: Long? = null,
    val categoryId: String? = null,
    val kind: EntryKind? = null,
    val date: LocalDate? = null,
    val accountId: String? = null,
    /**
     * Replaces whatever note the row has, rather than appending to it.
     *
     * This grammar is only ever reached seconds after the row was written, so
     * there is almost never a note to preserve — and when there is, it is one
     * the same person just dictated. A second attempt at it is a correction of
     * the first, not a second sentence: appending would quietly keep a
     * mis-heard "te zakupy były w lidzie" in front of the fix. The field is on
     * the sheet and one tap opens it, which is where adding TO a note belongs.
     */
    val note: String? = null,
    /** Two category names scored the same. The sheet is on screen and can ask. */
    val ambiguous: List<VoiceCategory> = emptyList(),
) {
    val isEmpty: Boolean
        get() = !revert && !confirm && amountMinor == null && categoryId == null &&
            kind == null && date == null && accountId == null && note == null &&
            ambiguous.isEmpty()
}

/**
 * The second, narrower pass: what may be said to the summary sheet.
 *
 * A closed grammar, not another run of the free-form parse. At this moment the
 * sayable set is small and known — take it back, make it 250, put it in
 * groceries, that was yesterday — and that is exactly what makes it reliable
 * enough to act on without a second confirmation. An utterance that matches
 * nothing is reported as matching nothing and the row is left alone, which a
 * free-form parser cannot honestly say.
 *
 * It reuses [SpokenAmount] and [CategoryMatcher] with the category threshold
 * lowered, because the one reason to hold the microphone over a summary is to
 * name a category.
 *
 * The kind is in this grammar and is NOT in [com.monyx.ui.transactions.EditTransactionSheet],
 * deliberately. The dialog refuses it because switching an expense to income
 * invalidates the category already chosen; here the category is being re-chosen
 * in the same breath, so there is nothing left to invalidate.
 */
object VoiceCommands {

    fun parse(
        transcript: String,
        alternatives: List<String> = emptyList(),
        categories: List<VoiceCategory>,
        accounts: List<VoiceAccount>,
        locale: Locale,
        today: LocalDate,
    ): VoiceCorrection {
        val hypotheses = (listOf(transcript) + alternatives).filter { it.isNotBlank() }
        for (hypothesis in hypotheses) {
            val correction = parseOne(hypothesis, categories, accounts, locale, today)
            if (!correction.isEmpty) return correction
        }
        return VoiceCorrection()
    }

    private fun parseOne(
        text: String,
        categories: List<VoiceCategory>,
        accounts: List<VoiceAccount>,
        locale: Locale,
        today: LocalDate,
    ): VoiceCorrection {
        // "notatka …" wins over everything, including the undo verbs below it.
        // Somebody who says the word has already told you which half of the
        // sentence is an instruction, so "notatka anulowane zamówienie" writes
        // those two words down and does not delete anything.
        val (spoken, marked) = CategoryMatcher.splitOnNoteMarker(text)
        if (marked != null) return VoiceCorrection(note = marked)

        val tokens = CategoryMatcher.tokenise(spoken)
        val revertSpoken = tokens.any { VoiceWords.stem(it) in VoiceWords.revertStems }

        val amount = SpokenAmount.find(tokens, locale)
        val afterAmount = tokens.filterIndexed { index, _ -> amount == null || index !in amount.tokens }

        val spokenDate = VoiceDates.read(afterAmount, today)
        val afterDate = spokenDate?.let { day ->
            afterAmount.filterIndexed { index, _ -> index !in day.tokens }
        } ?: afterAmount

        // Accounts before categories, and only on the name said in full. The
        // seeded names are Gotówka and Karta, which nothing in a category list
        // is going to be confused with — and matching them loosely would put
        // every unmatched word one typo away from moving the money.
        val account = matchAccount(afterDate, accounts)
        val phraseTokens = account?.let { matched ->
            afterDate.filterIndexed { index, _ -> index !in matched.tokens }
        } ?: afterDate

        val kind = phraseTokens.firstNotNullOfOrNull { VoiceWords.kindByName[it] }
        val contentIndices = phraseTokens.indices.filterNot { index ->
            val token = phraseTokens[index]
            token in VoiceWords.filler ||
                token in VoiceWords.kindByName ||
                token in VoiceWords.negations(locale) ||
                VoiceWords.stem(token) in VoiceWords.confirmStems
        }

        val outcome = CategoryMatcher.match(
            phraseTokens = phraseTokens,
            contentIndices = contentIndices,
            candidates = categories,
            minScore = CategoryMatcher.LENIENT_MIN_SCORE,
        )

        val correction = VoiceCorrection(
            amountMinor = amount?.minor,
            categoryId = (outcome as? CategoryMatcher.Outcome.One)?.category?.id,
            kind = kind,
            date = spokenDate?.date,
            accountId = account?.id,
            ambiguous = (outcome as? CategoryMatcher.Outcome.Tie)?.candidates.orEmpty(),
        )

        // A sentence ABOUT the transaction, rather than the name of one of its
        // fields. This is the owner's own case and the reason the rule exists:
        // "te zakupy były w lidlu" contains a category name and is plainly not
        // a category correction, and filing it as one would move the row to
        // Zakupy spożywcze because of a word that was only pointing at it.
        //
        // The rule, stated so it can be argued with: an utterance is a note when
        // it carries a demonstrative or a copula — [VoiceWords.statementWords],
        // a closed list — that is not itself part of the name that matched, AND
        // the only thing it otherwise produced was a category, or nothing at
        // all. An amount, a date, an account or a kind means somebody named a
        // field, and naming a field is never a note.
        //
        // "zakupy" has no such word, so it stays a category. "zmień kategorię
        // na zakupy" has none either. "250 na zakupy" set an amount, so the
        // rule does not look. Negations are excluded because a sentence whose
        // only signal is "nie" is somebody saying no, not describing a
        // purchase — "to nie było anulowane" is refused rather than written
        // down.
        //
        // The failure mode this accepts, deliberately: an unintended note. It
        // is one line of text on a sheet the user is already reading, next to a
        // field that opens an editor on one tap. A wrong category or a wrong
        // amount is neither visible nor cheap — it moves money between columns
        // and syncs to the other phone. Between the two readings, the note is
        // the safer landing.
        val statement = looksLikeAStatement(tokens, locale, correction, categories)
        if (statement) return VoiceCorrection(note = CategoryMatcher.asNote(text))

        // An undo verb AND something concrete in the same breath is a refusal,
        // not a revert.
        //
        // "nie anuluj, zmień na transport" is the second most natural thing
        // anybody will say to this sheet and it is the destructive one, and
        // there is no way to tell it from "nie, cofnij 250" by shape: tokenise
        // drops the punctuation, so the two arrive as the same list of words
        // with the same verb in the same place. Rather than pick, this refuses
        // both and leaves the row alone — the sheet says nothing changed and
        // Revert is a button, right there, one tap away and unambiguous.
        if (revertSpoken) {
            return if (correction.isEmpty) VoiceCorrection(revert = true) else VoiceCorrection()
        }
        if (!correction.isEmpty) return correction

        // Bare words, and only bare, which here means literally nothing else
        // was said. "nie" is Polish's ordinary negation particle: it turns up
        // in the middle of "nie na zakupy, na transport" (already returned
        // above as a category change), in "to nie było anulowane", and in "nie,
        // zostaw" — which means the opposite of what it would be taken for.
        // Anything with a word of its own left in it after the fillers is not
        // an answer this grammar can read, and an unreadable answer changes
        // nothing.
        val spare = tokens.filterNot { token ->
            token in VoiceWords.filler ||
                token in VoiceWords.negations(locale) ||
                VoiceWords.stem(token) in VoiceWords.confirmStems
        }
        if (spare.isNotEmpty()) return VoiceCorrection()

        val stems = tokens.map(VoiceWords::stem)
        return when {
            tokens.any { it in VoiceWords.negations(locale) } -> VoiceCorrection(revert = true)
            stems.any { it in VoiceWords.confirmStems } -> VoiceCorrection(confirm = true)
            else -> VoiceCorrection()
        }
    }

    /** See the comment at the call site; this is only the arithmetic of it. */
    private fun looksLikeAStatement(
        tokens: List<String>,
        locale: Locale,
        correction: VoiceCorrection,
        categories: List<VoiceCategory>,
    ): Boolean {
        val namedAField = correction.amountMinor != null || correction.date != null ||
            correction.accountId != null || correction.kind != null ||
            correction.ambiguous.isNotEmpty()
        if (namedAField) return false
        if (tokens.any { it in VoiceWords.negations(locale) }) return false
        if (tokens.any { VoiceWords.stem(it) in VoiceWords.revertStems }) return false

        // A statement word that is part of the matched name is the name, not a
        // sentence — a household with a category called "To i owo" should not
        // find every mention of it turning into a note.
        val ownWords = categories.firstOrNull { it.id == correction.categoryId }
            ?.let { CategoryMatcher.tokenise(it.name) }
            .orEmpty()
        return tokens.any { it in VoiceWords.statementWords && it !in ownWords }
    }

    private data class MatchedAccount(val id: String, val tokens: IntRange)

    private fun matchAccount(tokens: List<String>, accounts: List<VoiceAccount>): MatchedAccount? {
        for (account in accounts) {
            val name = CategoryMatcher.tokenise(account.name)
            if (name.isEmpty() || name.size > tokens.size) continue
            for (start in 0..tokens.size - name.size) {
                if (tokens.subList(start, start + name.size) == name) {
                    return MatchedAccount(account.id, start..start + name.size - 1)
                }
            }
        }
        return null
    }
}
