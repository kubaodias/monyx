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
    /** Two category names scored the same. The sheet is on screen and can ask. */
    val ambiguous: List<VoiceCategory> = emptyList(),
) {
    val isEmpty: Boolean
        get() = !revert && !confirm && amountMinor == null && categoryId == null &&
            kind == null && date == null && accountId == null && ambiguous.isEmpty()
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
 * The kind is in this grammar and is NOT in [com.monyx.ui.transactions.EditTransactionDialog],
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
        val tokens = CategoryMatcher.tokenise(text)
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
        val contentTokens = phraseTokens.filterNot { token ->
            val stem = VoiceWords.stem(token)
            token in VoiceWords.filler ||
                token in VoiceWords.kindByName ||
                token in VoiceWords.negations(locale) ||
                stem in VoiceWords.confirmStems
        }

        val outcome = CategoryMatcher.match(
            phraseTokens = phraseTokens,
            contentTokens = contentTokens,
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
