package com.monyx.voice

import com.monyx.ui.add.EntryKind
import java.time.LocalDate
import java.util.Locale

/**
 * A category, as much of one as the grammar needs.
 *
 * Not [com.monyx.data.CategoryEntity]. The parser is a pure function of its
 * arguments, tested on a JVM with no device and no database, and coupling it to
 * a Room row would drag `seq`, `pending` and `rejected` into a file that has no
 * business knowing sync exists. The ViewModel maps at the boundary.
 */
data class VoiceCategory(
    val id: String,
    val name: String,
    val kind: EntryKind,
    val parentId: String? = null,
    /** Carried for the summary chip, never read by the grammar. Drawing the
     *  category the way the grid draws it is what makes a mis-parse obvious at
     *  a glance rather than after reading a word. */
    val icon: String? = null,
    val color: String? = null,
)

/** An account, likewise. Only the name is ever spoken. */
data class VoiceAccount(val id: String, val name: String)

/** Everything one utterance decided. Not yet a row. */
data class SpokenTransaction(
    val kind: EntryKind,
    val amountMinor: Long,
    val categoryId: String?,
    val accountId: String?,
    val date: LocalDate,
    /**
     * Only ever what somebody explicitly asked to write down — "…, notatka
     * bilet miesięczny". The sentence itself is never the note: putting "dodaj
     * dwieście na transport" in every row pollutes the ledger and the search
     * index with the phrasing rather than with anything about the purchase.
     */
    val note: String?,
    val transcript: String,
)

sealed interface VoiceParse {
    /** Enough to write a row. */
    data class Complete(val transaction: SpokenTransaction) : VoiceParse

    /** Something was understood, not enough to save. Goes to the keypad. */
    data class Partial(val transaction: SpokenTransaction, val missing: Missing) : VoiceParse

    /** Nothing usable. */
    data class Unrecognised(val transcript: String) : VoiceParse

    enum class Missing {
        Amount,
        Category,
        Both,

        /** Understood completely, and there is nowhere to file it — a phone
         *  enrolled into a household that has no account yet. */
        Account,
    }
}

/**
 * Turning "dodaj dwieście na transport" into a transaction, on the phone, with
 * no network and no model.
 *
 * The argument for a table of rules rather than an LLM is not that rules are
 * better at language. It is that the AddScreen header rule — two taps and under
 * five seconds, and "a network requirement is a bug, not a matter of taste" —
 * applies with full force here, and a round trip in the middle of a sentence is
 * exactly what it forbids. The space is also small: nearly every utterance is
 * <verb?> <number> <preposition?> <category>, and the target set is one
 * household's own names. The residue is handled by SHOWING what was understood
 * rather than by understanding better, because the ledger is shared and a wrong
 * row syncs to everybody.
 *
 * When it cannot reach a savable transaction it does not error and does not
 * save: it hands back what it did get and the keypad finishes the job. The
 * second engine is the existing UI, not a second parser.
 *
 * `today` and `locale` are parameters and never `Dates.today()` /
 * `Locale.getDefault()`, so the whole thing is a pure function of its arguments
 * — the same discipline AmountInput and Recurrence keep.
 */
object VoiceParser {

    fun parse(
        transcript: String,
        alternatives: List<String> = emptyList(),
        categories: List<VoiceCategory>,
        accounts: List<VoiceAccount>,
        locale: Locale,
        today: LocalDate,
    ): VoiceParse {
        val hypotheses = (listOf(transcript) + alternatives).filter { it.isNotBlank() }
        if (hypotheses.isEmpty()) return VoiceParse.Unrecognised(transcript)

        // The n-best walk, and it is an accuracy win for nothing: the top
        // hypothesis "dodaj 200 na tran sport" has no category in it and the
        // second one does. First Complete wins; failing that, whichever Partial
        // got furthest, so the keypad opens with as much filled in as possible.
        var best: VoiceParse.Partial? = null
        for (hypothesis in hypotheses) {
            when (val parsed = parseOne(hypothesis, categories, accounts, locale, today)) {
                is VoiceParse.Complete -> return parsed
                is VoiceParse.Partial -> if (best == null || rank(parsed) > rank(best)) best = parsed
                is VoiceParse.Unrecognised -> Unit
            }
        }
        return best ?: VoiceParse.Unrecognised(transcript)
    }

    private fun rank(partial: VoiceParse.Partial): Int = when (partial.missing) {
        VoiceParse.Missing.Account -> 3
        VoiceParse.Missing.Amount, VoiceParse.Missing.Category -> 2
        VoiceParse.Missing.Both -> 1
    }

    private fun parseOne(
        text: String,
        categories: List<VoiceCategory>,
        accounts: List<VoiceAccount>,
        locale: Locale,
        today: LocalDate,
    ): VoiceParse {
        // The note comes off first, and everything in it is out of the grammar's
        // reach from here on. "notatka" is a promise about the rest of the
        // sentence, so a number or a category name inside the note is text and
        // not an instruction.
        val (instruction, dictatedNote) = CategoryMatcher.splitOnNoteMarker(text)
        val words = CategoryMatcher.words(instruction)
        val tokens = words.map { it.token }

        // Which words are spoken for. Everything the grammar recognises marks
        // its own span here, and whatever is left at the end of it is the note
        // — see [leftoverNote].
        val consumed = BooleanArray(tokens.size)

        val amount = SpokenAmount.find(tokens, locale)
        amount?.tokens?.forEach { consumed[it] = true }

        val afterAmount = tokens.indices.filterNot { consumed[it] }
        val spokenDate = VoiceDates.read(afterAmount.map { tokens[it] }, today)
        spokenDate?.tokens?.forEach { consumed[afterAmount[it]] = true }

        val phraseIndices = tokens.indices.filterNot { consumed[it] }
        val phraseTokens = phraseIndices.map { tokens[it] }

        val contentIndices = phraseTokens.indices.filterNot { index ->
            val token = phraseTokens[index]
            val stem = VoiceWords.stem(token)
            // A kind keyword is spoken for wherever it lands: "wydałem" is an
            // instruction about the row, never a word about the shop.
            val keyword = stem in VoiceWords.incomeStems || stem in VoiceWords.expenseStems
            if (keyword) consumed[phraseIndices[index]] = true
            token in VoiceWords.filler || keyword
        }

        // The category is matched across BOTH kind lists first, and only THEN
        // is the kind decided. Reading a keyword first is a correctness bug
        // rather than an inefficiency: "wypłata" is the seeded income category
        // and "wypłaciłem 200 na zakupy" — I withdrew two hundred for the
        // shopping — shares its stem, so a keyword-first rule files a grocery
        // run as income and flips the sign of the month.
        val matched = CategoryMatcher.match(phraseTokens, contentIndices, categories)
            as? CategoryMatcher.Outcome.One
        val keywordKind = keywordKind(phraseTokens)

        // Which of the two speaks for the kind, and it is not simply "the
        // category". A category matched on only PART of its name is a good
        // enough guess to file under and nowhere near good enough to decide
        // which way the money went: "dodaj 200 na inne wydatki" reaches the
        // seeded INCOME category `Inne przychody` on the single word "inne",
        // and writing an income row for a sentence whose other word is
        // *wydatki* is the same flipped sign, arrived at through the scorer
        // instead of through the keyword list.
        //
        // So: a name said in full speaks for the kind. A name half-matched does
        // not — but a half-matched EXPENSE category still overrules an income
        // keyword, because income has to be asserted and expense is the
        // default, and that is what keeps "wypłaciłem 200 na zakupy" an
        // expense filed under Zakupy.
        val kind = when {
            matched != null && matched.wholeName -> matched.category.kind
            matched != null && matched.category.kind == EntryKind.Expense -> EntryKind.Expense
            else -> keywordKind ?: EntryKind.Expense
        }

        // A half-matched category that disagrees with the kind is not filed at
        // all. Guessing at the name was survivable; guessing at the name AND
        // contradicting the sentence about the direction is not.
        val category = matched?.category?.takeIf { it.kind == kind }

        // Two clauses in one breath: "dodaj 200 na zdrowie i 100 na dom". The
        // amount reader takes the last number and the matcher takes the
        // best-scoring name, and neither knows the other exists — so the row
        // is 100 filed under Zdrowie, which is neither thing that was said,
        // and it is Complete, so it is written and synced. One number and one
        // name is the shape this grammar handles; two of each is a sentence it
        // has no business finishing.
        val splitUtterance = (amount?.groups ?: 0) > 1 && (matched?.contenders ?: 0) > 1

        // Only the name that was actually FILED under is spoken for. A match
        // this sentence then refused (the kind disagreed, or two clauses were
        // heard) leaves its words unclaimed, which is right: they were not used.
        if (category != null) matched?.matched?.forEach { consumed[phraseIndices[it]] = true }

        val heardSomething = amount != null && !splitUtterance || category != null
        val note = dictatedNote ?: if (heardSomething) leftoverNote(words, consumed) else null

        val spoken = SpokenTransaction(
            kind = kind,
            amountMinor = if (splitUtterance) 0 else amount?.minor ?: 0,
            categoryId = if (splitUtterance) null else category?.id,
            // Nobody names the account in a shop, and offering to would double
            // the ambiguity surface of a field that is right by default. The
            // correction pass can set it, which is where it is actually used.
            accountId = accounts.firstOrNull()?.id,
            date = spokenDate?.date ?: today,
            note = note,
            transcript = text,
        )
        if (splitUtterance) return VoiceParse.Partial(spoken, VoiceParse.Missing.Both)

        return when {
            amount != null && category != null && spoken.accountId != null -> VoiceParse.Complete(spoken)
            amount != null && category != null -> VoiceParse.Partial(spoken, VoiceParse.Missing.Account)
            amount != null -> VoiceParse.Partial(spoken, VoiceParse.Missing.Category)
            category != null -> VoiceParse.Partial(spoken, VoiceParse.Missing.Amount)
            keywordKind != null || spokenDate != null -> VoiceParse.Partial(spoken, VoiceParse.Missing.Both)
            else -> VoiceParse.Unrecognised(text)
        }
    }

    /**
     * What the sentence said that the grammar had no field for.
     *
     * "150 zł na zakupy w Biedronce" is one amount, one category and two words
     * nothing asked for, and those two words are the whole reason anybody would
     * say the sentence that way. So the rule is not a list of shops: once the
     * amount, the date, the category and the keywords have each claimed their
     * words, what is left over IS the note.
     *
     * Leading and trailing fillers go, because "dodaj 200 na transport" leaves
     * "dodaj" and "na" behind and a note reading "Dodaj na" would be worse than
     * no feature at all. That is the case this must never get wrong. Everything
     * between is kept, gaps included: those words are already known not to be
     * an instruction, and a phrase reads worse with holes in it than with a
     * stray "i".
     *
     * A preposition of place goes with them, and takes the case ending of the
     * word after it — "w Biedronce" is written down as "Biedronka", because a
     * note is a label on a row and not a sentence about a trip. Only that one
     * governed word is touched; "w Biedronce przy dworcu" keeps its second
     * half exactly as it was said. See [nominative] for what that costs.
     */
    private fun leftoverNote(words: List<CategoryMatcher.Word>, consumed: BooleanArray): String? {
        val spare = words.indices.filterNot { consumed[it] }
        val firstReal = spare.firstOrNull { words[it].token !in VoiceWords.filler } ?: return null

        // Was this word governed by a preposition of place? Walk back over the
        // little words immediately in front of it — "w", or "at the" — and stop
        // at the first thing the grammar already claimed.
        var back = firstReal - 1
        var governed = false
        while (back >= 0 && !consumed[back] && words[back].token in VoiceWords.filler + VoiceWords.placePrepositions) {
            if (words[back].token in VoiceWords.placePrepositions) governed = true
            back--
        }

        val tail = spare.filter { it >= firstReal }
            .dropLastWhile { words[it].token in VoiceWords.filler }
        if (tail.isEmpty()) return null

        val head = words[tail.first()]
        val rest = tail.drop(1).map { words[it].source }
        val name = if (governed) nominative(head.source) else head.source
        return CategoryMatcher.asNote((listOf(name) + rest).joinToString(" "))
    }

    /**
     * Polish locative, undone. A table of endings, not morphology, and it will
     * sometimes be wrong.
     *
     * "w Biedronce" is where somebody was; "Biedronka" is what they would have
     * written down. The gap between those two is a case ending, and undoing a
     * case ending properly needs a dictionary and a gender — neither of which
     * is going anywhere near this app. What is here is the handful of endings
     * that actually turn up on the front of a shop, applied to one word.
     *
     * So it has a known failure: a name whose nominative genuinely ends in one
     * of these strings gets cut, and "-cie" is ambiguous in the language itself
     * (*markecie* is from "market", *gazecie* is from "gazeta"); this picks the
     * consonant, which is what shop names are. That is acceptable here and
     * would not be anywhere else in this app, for one reason: a note is free
     * text on a row whose numbers it cannot touch, it is on screen the moment
     * it is written, and one tap opens the editor. It is a cosmetic guess, made
     * where a wrong guess is visible and costs a tap. Anything it does not
     * recognise it leaves exactly as it was heard — wrong-but-unchanged beats
     * wrong-and-mangled, which is the same instinct as refusing a category.
     */
    private fun nominative(word: String): String {
        // Five characters, because the shortest chain names in the locative are
        // exactly that long ("Lidlu", "Żabce"), and below it the odds swing
        // hard the other way: a four-letter word ending in -u is far likelier
        // to be an ordinary Polish word than a shop, and "menu" becoming "men"
        // is the kind of wrong somebody notices.
        if (word.length < 5) return word
        val lower = word.lowercase()
        for ((ending, nominative) in LOCATIVE) {
            if (lower.endsWith(ending)) return word.dropLast(ending.length) + nominative
        }
        return word
    }

    /** Longest ending first: every one of "-dzie", "-nie", "-mie" and "-cie"
     *  would also be caught by a shorter suffix if it were tested first. */
    private val LOCATIVE = listOf(
        "dzie" to "d",   // Kauflandzie -> Kaufland
        "nie" to "n",    // Rossmannie  -> Rossmann
        "mie" to "ma",   // Castoramie  -> Castorama
        "cie" to "t",    // markecie    -> market
        "ce" to "ka",    // Biedronce   -> Biedronka, Żabce -> Żabka
        "u" to "",       // Lidlu       -> Lidl, Empiku -> Empik
    )

    /** Expense is the default, the same as [com.monyx.ui.add.AddUiState]. Income
     *  has to be said. Explicit expense words are recognised as confirmation and
     *  change nothing. */
    private fun keywordKind(tokens: List<String>): EntryKind? {
        val stems = tokens.map(VoiceWords::stem)
        return when {
            stems.any { it in VoiceWords.incomeStems } -> EntryKind.Income
            stems.any { it in VoiceWords.expenseStems } -> EntryKind.Expense
            else -> null
        }
    }
}

/**
 * "wczoraj" and nothing more adventurous.
 *
 * Today, yesterday, the day before. Free-form dates ("last Tuesday", "on the
 * third") are refused for the same reason a tie between two categories is
 * refused: the cost of getting one wrong is a row filed in the wrong month,
 * and the keypad already has a date picker two taps away.
 */
internal object VoiceDates {

    data class Spoken(val date: LocalDate, val tokens: IntRange)

    /**
     * The LAST day word wins, not the first. "nie dzisiaj, wczoraj" is somebody
     * correcting themselves mid-sentence, and reading the word they withdrew
     * files the row in the wrong day — quietly, because the summary shows a
     * date that looks perfectly plausible.
     *
     * "day before yesterday" is consumed whole, so its own last word is never
     * read again as a plain "yesterday".
     */
    fun read(tokens: List<String>, today: LocalDate): Spoken? {
        var found: Spoken? = null
        var index = 0
        while (index < tokens.size) {
            if (index + 2 < tokens.size &&
                tokens[index] == "day" && tokens[index + 1] == "before" && tokens[index + 2] == "yesterday"
            ) {
                found = Spoken(today.minusDays(2), index..index + 2)
                index += 3
                continue
            }
            val back = VoiceWords.daysBack[tokens[index]]
            if (back != null) found = Spoken(today.minusDays(back), index..index)
            index++
        }
        return found
    }
}
