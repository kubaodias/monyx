package com.monyx.voice

import com.monyx.ui.add.EntryKind
import java.util.Locale

/**
 * Every word the grammar knows, in one place, already normalised.
 *
 * Normalised means lower case with the diacritics stripped and ł folded to l,
 * because that is the shape a transcript arrives in after [CategoryMatcher.normalise]
 * and comparing anything else against "złotych" is comparing against a string
 * that never turns up. Nothing here is ever matched against raw speech.
 *
 * The tables are Polish and English together rather than one or the other. The
 * recogniser is asked for the interface language, so an English phone hearing
 * "trzydzieści pięć" is not the common case — but it is exactly what happens the
 * first time somebody switches the app to English and carries on talking to it
 * in Polish, and there is nothing to be gained by failing that. Where a word
 * means one thing in one language and something else in the other, the
 * interface language decides; [negations] is the only place that currently
 * bites, and it bites hard enough to be worth the parameter.
 */
internal object VoiceWords {

    /**
     * Words that end an amount. "PLN" is here because the recogniser hands back
     * the abbreviation as often as the word when a price is dictated.
     */
    val currency = setOf("zl", "zlote", "zlotych", "zloty", "zlotego", "zlotymi", "pln")

    /** The fractional half of a price: "dwanaście złotych pięćdziesiąt GROSZY". */
    val grosze = setOf("gr", "grosz", "grosza", "grosze", "groszy")

    private val polishNumerals = mapOf(
        "zero" to 0L, "jeden" to 1L, "jedna" to 1L, "jedno" to 1L,
        "dwa" to 2L, "dwie" to 2L, "trzy" to 3L, "cztery" to 4L, "piec" to 5L,
        "szesc" to 6L, "siedem" to 7L, "osiem" to 8L, "dziewiec" to 9L,
        "dziesiec" to 10L, "jedenascie" to 11L, "dwanascie" to 12L,
        "trzynascie" to 13L, "czternascie" to 14L, "pietnascie" to 15L,
        "szesnascie" to 16L, "siedemnascie" to 17L, "osiemnascie" to 18L,
        "dziewietnascie" to 19L,
        "dwadziescia" to 20L, "trzydziesci" to 30L, "czterdziesci" to 40L,
        "piecdziesiat" to 50L, "szescdziesiat" to 60L, "siedemdziesiat" to 70L,
        "osiemdziesiat" to 80L, "dziewiecdziesiat" to 90L,
        "sto" to 100L, "dwiescie" to 200L, "trzysta" to 300L, "czterysta" to 400L,
        "piecset" to 500L, "szescset" to 600L, "siedemset" to 700L,
        "osiemset" to 800L, "dziewiecset" to 900L,
    )

    private val englishNumerals = mapOf(
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L,
        "five" to 5L, "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L,
        "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L,
        "fourteen" to 14L, "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L,
        "eighteen" to 18L, "nineteen" to 19L,
        "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L,
        "sixty" to 60L, "seventy" to 70L, "eighty" to 80L, "ninety" to 90L,
    )

    /**
     * Numerals that multiply what came before them rather than adding to it.
     * Polish has no multiplicative hundred — "dwieście" is its own word — so
     * only the thousands are here on that side.
     */
    private val polishMultipliers = mapOf("tysiac" to 1000L, "tysiace" to 1000L, "tysiecy" to 1000L, "tys" to 1000L)

    private val englishMultipliers = mapOf("hundred" to 100L, "thousand" to 1000L)

    fun numerals(locale: Locale): Map<String, Long> = ordered(locale, polishNumerals, englishNumerals)

    fun multipliers(locale: Locale): Map<String, Long> = ordered(locale, polishMultipliers, englishMultipliers)

    /** The interface language's table wins where the two disagree. */
    private fun ordered(locale: Locale, polish: Map<String, Long>, english: Map<String, Long>): Map<String, Long> =
        if (locale.language == "pl") english + polish else polish + english

    /**
     * Verbs and prepositions that carry no information. They are dropped before
     * the stem tier of category matching so "dodaj 200 na transport" is matched
     * on "transport" alone — otherwise "na" and "dodaj" are two more chances to
     * collide with a five-letter stem.
     */
    val filler = setOf(
        "dodaj", "dodac", "dopisz", "zapisz", "wpisz", "prosze", "na", "za", "do",
        "od", "w", "we", "o", "i", "to", "jest", "mam", "sie", "juz", "tez",
        "add", "put", "log", "note", "record", "to", "on", "for", "of", "a", "an",
        "the", "in", "at", "and", "it", "is", "was", "that", "please", "some",
    )

    /**
     * Words that mean the row is money coming IN.
     *
     * Matched by stem, which is what makes Polish inflection affordable — and
     * also what puts the trap in: "wypłata" is the seeded income category and
     * "wypłaciłem" (I withdrew cash) shares its stem. That is why the kind is
     * decided from the CATEGORY first and only falls back to these words; see
     * [VoiceParser].
     *
     * "zwrot" is deliberately absent, and so is "refund". It points both ways —
     * a refund received is income, a returned jacket is a credit against
     * Zakupy — and a wrong kind flips the sign of the month, which is worse
     * than a wrong category. Anything genuinely ambiguous is refused and handed
     * to the keypad.
     */
    val incomeStems = stemsOf(
        "przychod", "przychody", "wplata", "wplate", "wplaty", "wplynelo",
        "wplynely", "dostalem", "dostalam", "otrzymalem", "otrzymalam",
        "zarobilem", "zarobilam", "wyplata", "wyplate", "wyplaty", "pensja",
        "income", "salary", "earned", "received", "wages", "paycheck",
    )

    /** Confirmation only: expense is already the default. */
    val expenseStems = stemsOf(
        "wydalem", "wydalam", "wydalismy", "wydatek", "zaplacilem", "zaplacilam",
        "kupilem", "kupilam", "kupilismy", "spent", "paid", "bought", "expense",
    )

    /** The two kinds said by name, for the correction grammar. */
    val kindByName = mapOf(
        "przychod" to EntryKind.Income,
        "wplyw" to EntryKind.Income,
        "income" to EntryKind.Income,
        "wydatek" to EntryKind.Expense,
        "expense" to EntryKind.Expense,
    )

    /** How many days back, from a word. Nothing beyond the day before yesterday. */
    val daysBack = mapOf(
        "dzis" to 0L, "dzisiaj" to 0L, "today" to 0L,
        "wczoraj" to 1L, "yesterday" to 1L,
        "przedwczoraj" to 2L,
    )

    /**
     * Undo, said unambiguously. Every one of these can only mean "take that
     * back", which is why they short-circuit the rest of the correction
     * grammar — "nie, cofnij 250" is a revert and the 250 is not an amount.
     */
    val revertStems = stemsOf(
        "cofnij", "cofnac", "wycofaj", "anuluj", "usun", "skasuj",
        "revert", "undo", "cancel", "delete", "remove",
    )

    /** Yes, that is right, close the sheet. */
    val confirmStems = stemsOf(
        "ok", "okej", "tak", "dobrze", "dobra", "zgadza", "gotowe", "koniec",
        "yes", "yeah", "done", "correct", "right", "good", "close",
    )

    /**
     * Bare negation, which is a revert only when nothing else was said.
     *
     * "nie" is Polish's ordinary negation particle, so "nie na zakupy, na
     * transport" is the most natural correction anybody will ever say to this
     * sheet and must not delete the row. English "no" is a plain refusal and is
     * safe — but Polish "no" is an affirmative filler ("no dobrze"), so it is
     * only a negation when the interface is English. This is the one place the
     * locale changes an answer.
     */
    fun negations(locale: Locale): Set<String> =
        if (locale.language == "en") setOf("no", "nope", "nie") else setOf("nie")

    /**
     * The prepositions that put a place after them.
     *
     * They are what turns the tail of "150 zł na zakupy w Biedronce" into an
     * answer to *where*, and they are the signal that the word after them is a
     * shop name standing in a grammatical case rather than a word somebody
     * meant to write down. Every one of them is dropped from a note; see
     * VoiceParser, which also undoes what "w" did to the noun.
     *
     * "from" is here and not in [filler] because it is only ever a place word;
     * the rest already are fillers and are listed again so the rule can be read
     * in one place.
     */
    val placePrepositions = setOf("w", "we", "na", "przy", "u", "at", "in", "from")

    /**
     * "…and write this down with it." Everything after one of these is the
     * note, whatever it contains — a category name, a number, the word
     * "cofnij". An explicit marker is the one thing in this grammar that is
     * never second-guessed, because somebody who says "notatka" has already
     * told you which half of the sentence is which.
     */
    val noteMarkers = setOf("notatka", "notatke", "notatki", "dopisz", "opis", "uwaga", "note", "memo")

    /**
     * Words that turn a phrase into a sentence ABOUT the transaction.
     *
     * A demonstrative or a copula. "zakupy" is a category; "te zakupy były w
     * lidlu" is somebody saying where they were, and reading a category out of
     * the middle of that files the row against a name they were only using to
     * point at it. These are what tell the two apart, and [VoiceCommands] is
     * the only place that decision is made.
     */
    val statementWords = setOf(
        "te", "ta", "ten", "tamte", "tamten", "tamta",
        "bylo", "byly", "byl", "byla", "bylem", "bylam", "bylismy", "jest", "sa",
        "this", "that", "these", "those", "was", "were", "are",
    )

    /**
     * How much of a word survives before its ending is cut off. Five.
     *
     * It is a compromise between two failures that pull opposite ways, and both
     * of them are real in the seeded household. Cut too little and half a word
     * becomes a match: at four, "inne" would reach past `Inne przychody` and
     * "tran" would reach `Transport`, which is how a guess at a name turns into
     * a wrong row. Cut too much and inflection stops being absorbed:
     * "spożywcze" and "spożywczy" have to land on the same stem, and so do
     * "zakupy" and "zakupów".
     *
     * Five is where those two stop fighting for the names this app ships with.
     * It does nothing at all for a name SHORTER than five — `Dom` heard as
     * "domu" has no ending to cut — which is handled separately, and
     * deliberately, in [CategoryMatcher].
     */
    const val STEM_LENGTH = 5

    fun stem(token: String): String = token.take(STEM_LENGTH)

    private fun stemsOf(vararg words: String): Set<String> = words.map(::stem).toSet()
}
