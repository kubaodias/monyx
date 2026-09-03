package com.monyx

import com.monyx.ui.add.EntryKind
import com.monyx.voice.VoiceAccount
import com.monyx.voice.VoiceCategory
import com.monyx.voice.VoiceParse
import com.monyx.voice.VoiceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * The parser is a pure function of its arguments, which is the whole reason it
 * is written the way it is: `today` and `locale` come in as parameters and
 * nothing here reaches for Dates.today() or Locale.getDefault(). So the entire
 * grammar can be pinned on a JVM with no device, no database and no clock.
 *
 * The fixture is the REAL seeded household — server/seed/default-categories.sql
 * and scripts/new-household.mjs — diacritics included, because "zakupy
 * spożywcze" versus "Zakupy spozywcze" is not a cosmetic difference here, it is
 * the bug the normaliser exists to prevent.
 *
 * Paliwo, Remonty, Auto and Autobus are fixture-only. No subcategories are
 * seeded; they are here because the parser has to choose between a parent and
 * its child, and between two names that start the same way.
 */
class VoiceParserTest {

    private val today = LocalDate.of(2026, 3, 14)
    private val pl = Locale.forLanguageTag("pl-PL")
    private val en = Locale.forLanguageTag("en-GB")

    private val groceries = VoiceCategory("c-groceries", "Zakupy spożywcze", EntryKind.Expense)
    private val transport = VoiceCategory("c-transport", "Transport", EntryKind.Expense)
    private val home = VoiceCategory("c-home", "Dom", EntryKind.Expense)
    private val health = VoiceCategory("c-health", "Zdrowie", EntryKind.Expense)
    private val fun_ = VoiceCategory("c-fun", "Rozrywka", EntryKind.Expense)
    private val kids = VoiceCategory("c-kids", "Dzieci", EntryKind.Expense)
    private val salary = VoiceCategory("c-salary", "Wypłata", EntryKind.Income)
    private val otherIncome = VoiceCategory("c-other-inc", "Inne przychody", EntryKind.Income)
    private val fuel = VoiceCategory("c-fuel", "Paliwo", EntryKind.Expense, parentId = "c-transport")
    private val repairs = VoiceCategory("c-repairs", "Remonty", EntryKind.Expense, parentId = "c-home")

    private val categories = listOf(
        groceries, transport, home, health, fun_, kids, salary, otherIncome, fuel, repairs,
    )

    private val cash = VoiceAccount("a-cash", "Gotówka")
    private val card = VoiceAccount("a-card", "Karta")
    private val accounts = listOf(cash, card)

    private fun parse(
        text: String,
        alternatives: List<String> = emptyList(),
        locale: Locale = pl,
        categories: List<VoiceCategory> = this.categories,
        accounts: List<VoiceAccount> = this.accounts,
    ) = VoiceParser.parse(text, alternatives, categories, accounts, locale, today)

    private fun complete(text: String, locale: Locale = pl) =
        (parse(text, locale = locale) as VoiceParse.Complete).transaction

    // ------------------------------------------------------------ the row

    @Test
    fun `the owner's own sentence, in both languages, is the same row`() {
        val english = complete("Add 200 to Transport", en)
        assertEquals(20000L, english.amountMinor)
        assertEquals(transport.id, english.categoryId)
        assertEquals(EntryKind.Expense, english.kind)
        assertEquals(today, english.date)
        assertEquals(cash.id, english.accountId)

        val polish = complete("dodaj 200 na transport")
        assertEquals(english.amountMinor, polish.amountMinor)
        assertEquals(english.categoryId, polish.categoryId)
        assertEquals(english.accountId, polish.accountId)
    }

    @Test
    fun `a spelled amount and a subcategory`() {
        val spoken = complete("wydałem trzydzieści pięć złotych na paliwo")
        assertEquals(3500L, spoken.amountMinor)
        assertEquals(fuel.id, spoken.categoryId)
        assertEquals(EntryKind.Expense, spoken.kind)
    }

    @Test
    fun `no verb, and the category last`() {
        val spoken = complete("200 zł zakupy")
        assertEquals(20000L, spoken.amountMinor)
        assertEquals(groceries.id, spoken.categoryId)
    }

    @Test
    fun `both decimal separators land on the same grosze`() {
        assertEquals(1250L, complete("12,50 na dom").amountMinor)
        assertEquals(1250L, complete("12.50 na dom").amountMinor)
    }

    @Test
    fun `złote and grosze, said the long way and the short way`() {
        assertEquals(1250L, complete("dwanaście złotych pięćdziesiąt groszy na dom").amountMinor)
        assertEquals(30L, complete("30 groszy na dom").amountMinor)
    }

    /**
     * How a price is actually said in a shop: no currency word at all, and the
     * grosze simply follow the złoty. It works because a numeral run folds only
     * while it DESCENDS — 50 is not smaller than 12, so "dwanaście
     * pięćdziesiąt" is two numbers and a price, where "dwieście pięćdziesiąt"
     * is one number and 250.
     */
    @Test
    fun `a bare price is not a sum`() {
        assertEquals(1250L, complete("dwanaście pięćdziesiąt na dom").amountMinor)
        assertEquals(25000L, complete("dwieście pięćdziesiąt na dom").amountMinor)
    }

    @Test
    fun `income is decided by the category, and the expense list is never consulted`() {
        val polish = complete("wypłata 5000")
        assertEquals(EntryKind.Income, polish.kind)
        assertEquals(salary.id, polish.categoryId)

        val english = VoiceParser.parse(
            "salary 5000",
            emptyList(),
            listOf(VoiceCategory("c-salary-en", "Salary", EntryKind.Income)),
            accounts,
            en,
            today,
        )
        val spoken = (english as VoiceParse.Complete).transaction
        assertEquals(EntryKind.Income, spoken.kind)
        assertEquals("c-salary-en", spoken.categoryId)
    }

    /**
     * The trap that makes kind-after-category a correctness rule rather than a
     * tidiness one. "Wypłata" is the seeded INCOME category and "wypłaciłem" —
     * I withdrew cash — shares its stem, so deciding the kind from the keyword
     * first files a grocery run as income and flips the sign of the month.
     */
    @Test
    fun `withdrawing cash for the shopping is an expense`() {
        val spoken = complete("wypłaciłem 200 na zakupy")
        assertEquals(EntryKind.Expense, spoken.kind)
        assertEquals(groceries.id, spoken.categoryId)
    }

    /**
     * "zwrot" points both ways: a refund received is income, a returned jacket
     * is a credit against Zakupy. A wrong kind is worse than a wrong category,
     * so it is not a keyword at all and the sentence goes to the keypad.
     */
    @Test
    fun `a refund is not silently income`() {
        val parsed = parse("zwrot 200")
        assertTrue(parsed is VoiceParse.Partial)
        val partial = parsed as VoiceParse.Partial
        assertEquals(VoiceParse.Missing.Category, partial.missing)
        assertEquals(EntryKind.Expense, partial.transaction.kind)
    }

    /**
     * Kind keywords are consumed before the stem tier, which is what stops a
     * bare "income" being read as a request for the seeded "Other income". The
     * category is still perfectly sayable — see the test below it.
     */
    @Test
    fun `a bare kind word is not a category`() {
        val english = listOf(
            VoiceCategory("c-other-en", "Other income", EntryKind.Income),
            VoiceCategory("c-groceries-en", "Groceries", EntryKind.Expense),
        )
        val parsed = parse("income 5000", locale = en, categories = english)
        assertTrue(parsed is VoiceParse.Partial)
        val partial = parsed as VoiceParse.Partial
        assertEquals(VoiceParse.Missing.Category, partial.missing)
        assertEquals(EntryKind.Income, partial.transaction.kind)
        assertNull(partial.transaction.categoryId)

        val named = parse("other income 5000", locale = en, categories = english)
        assertEquals("c-other-en", (named as VoiceParse.Complete).transaction.categoryId)
    }

    /**
     * The same flipped sign as the test above it, reached through the SCORER
     * rather than through the keyword list.
     *
     * "inne wydatki" is *other expenses*. It scores against the seeded INCOME
     * category `Inne przychody` on the single shared word "inne" — one stem of
     * two, which is a good enough guess to file under and nowhere near good
     * enough to decide which way the money went. A category matched on half its
     * name does not speak for the kind, and one that then contradicts the kind
     * is not filed at all.
     */
    @Test
    fun `half a name may not decide which way the money went`() {
        for (sentence in listOf("dodaj 200 na inne wydatki", "dodaj 200 na inne")) {
            val parsed = parse(sentence)
            assertTrue(sentence, parsed is VoiceParse.Partial)
            val partial = parsed as VoiceParse.Partial
            assertEquals(sentence, VoiceParse.Missing.Category, partial.missing)
            assertEquals(sentence, EntryKind.Expense, partial.transaction.kind)
            assertNull(sentence, partial.transaction.categoryId)
        }

        val english = listOf(
            VoiceCategory("c-other-en", "Other income", EntryKind.Income),
            VoiceCategory("c-groceries-en", "Groceries", EntryKind.Expense),
        )
        val parsed = parse("add 20 for other stuff", locale = en, categories = english) as VoiceParse.Partial
        assertEquals(VoiceParse.Missing.Category, parsed.missing)
        assertEquals(EntryKind.Expense, parsed.transaction.kind)
    }

    /**
     * Two clauses in one breath. The amount reader takes the last number and
     * the matcher takes the best-scoring name, and neither knows the other
     * exists — so without this the row is 100,00 filed under Zdrowie, which is
     * neither thing that was said, and it is Complete, so it is written and it
     * syncs.
     */
    @Test
    fun `two transactions in one breath are refused rather than blended`() {
        val parsed = parse("dodaj 200 na zdrowie i 100 na dom")
        assertTrue(parsed is VoiceParse.Partial)
        val partial = parsed as VoiceParse.Partial
        assertEquals(VoiceParse.Missing.Both, partial.missing)
        assertEquals(0L, partial.transaction.amountMinor)
        assertNull(partial.transaction.categoryId)
    }

    /** Dom is three letters, so there is no ending to cut off it and Polish can
     *  only add one. The app's own seeded category, in its commonest form. */
    @Test
    fun `a short category name survives being inflected`() {
        val spoken = complete("dodaj 200 do domu")
        assertEquals(home.id, spoken.categoryId)
        assertEquals(20000L, spoken.amountMinor)
    }

    /**
     * A note dictated in the same breath as the transaction. The marker takes
     * everything after it, so a number or a category name inside the note is
     * text and not an instruction.
     */
    @Test
    fun `a note said with the transaction is carried, not parsed`() {
        val spoken = complete("dodaj 200 na transport, notatka bilet miesięczny")
        assertEquals(20000L, spoken.amountMinor)
        assertEquals(transport.id, spoken.categoryId)
        assertEquals("Bilet miesięczny", spoken.note)

        // Nothing said after the marker is somebody who stopped talking.
        assertNull(complete("dodaj 200 na transport notatka").note)
        // And with no marker there is no note: the sentence is not the note.
        assertNull(complete("dodaj 200 na transport").note)
    }

    /**
     * The owner's own sentence. Nothing here is a rule about shops: the amount,
     * the category and the fillers each claim their words, and the two nobody
     * claimed are what the sentence was for.
     */
    @Test
    fun `words the grammar had no field for become the note`() {
        val spoken = complete("150 zł na zakupy w Biedronce")
        assertEquals(15000L, spoken.amountMinor)
        assertEquals(groceries.id, spoken.categoryId)
        // "W", not just "Biedronce": one preposition in front of the first real
        // word belongs to the phrase, and the capital is applied where the note
        // is built rather than where it is drawn.
        assertEquals("W Biedronce", spoken.note)
    }

    /**
     * The case this must never get wrong. A sentence that is nothing but an
     * instruction leaves only fillers behind, and a note reading "Dodaj na"
     * would be worse than not having the feature.
     */
    @Test
    fun `a sentence with nothing left over gets no note`() {
        assertNull(complete("dodaj 200 na transport").note)
        assertNull(complete("Add 200 to Transport", en).note)
        assertNull(complete("wydałem trzydzieści pięć złotych na paliwo").note)
        assertNull(complete("200 zł zakupy").note)
        assertNull(complete("wczoraj 20 na transport").note)
    }

    @Test
    fun `the leftover note survives a date at the end of the sentence`() {
        val spoken = complete("wczoraj 150 zł na zakupy w Biedronce")
        assertEquals(today.minusDays(1), spoken.date)
        assertEquals("W Biedronce", spoken.note)
    }

    @Test
    fun `a leftover with no preposition in front of it stands on its own`() {
        val spoken = complete("dodaj 200 na transport bilet miesięczny")
        assertEquals(transport.id, spoken.categoryId)
        assertEquals("Bilet miesięczny", spoken.note)
    }

    /** An explicit marker still wins: what follows it is the note whole, and
     *  the leftover rule never gets to look. */
    @Test
    fun `a dictated note beats a leftover one`() {
        assertEquals(
            "Bilet miesięczny",
            complete("150 zł na zakupy w Biedronce, notatka bilet miesięczny").note,
        )
    }

    @Test
    fun `what is inside a note is text, not an instruction`() {
        val spoken = complete("dodaj 200 na transport, notatka zakupy za 500 wczoraj")
        assertEquals(20000L, spoken.amountMinor)
        assertEquals(transport.id, spoken.categoryId)
        assertEquals(today, spoken.date)
        assertEquals("Zakupy za 500 wczoraj", spoken.note)
    }

    @Test
    fun `yesterday, in both languages`() {
        assertEquals(today.minusDays(1), complete("wczoraj 20 na transport").date)
        assertEquals(today.minusDays(1), complete("yesterday 20 on transport", en).date)
        assertEquals(today.minusDays(2), complete("przedwczoraj 20 na transport").date)
        assertEquals(today.minusDays(2), complete("day before yesterday 20 on transport", en).date)
    }

    /** Somebody correcting themselves mid-sentence. The word they withdrew must
     *  not be the one that files the row. */
    @Test
    fun `the last day word wins, not the first`() {
        assertEquals(today.minusDays(1), complete("nie dzisiaj, wczoraj 20 na transport").date)
    }

    // -------------------------------------------------------- the matching

    /**
     * Recognisers drop diacritics. Ł is the letter that makes this more than a
     * one-liner: it does not decompose into a base plus a combining mark, so
     * stripping marks leaves it standing and "Łazienka" never matches.
     */
    @Test
    fun `a transcript with no diacritics still matches the household's names`() {
        assertEquals(groceries.id, complete("200 na zakupy spozywcze").categoryId)

        // Ł is the one that makes this more than a call to a normaliser: it
        // does not decompose, so stripping combining marks leaves it standing.
        val bathroom = VoiceCategory("c-bath", "Łazienka", EntryKind.Expense)
        val parsed = parse("200 na lazienka", categories = listOf(bathroom))
        assertEquals("c-bath", (parsed as VoiceParse.Complete).transaction.categoryId)
    }

    @Test
    fun `a parent and its child are both reachable`() {
        assertEquals(repairs.id, complete("200 na remonty").categoryId)
        assertEquals(home.id, complete("200 na dom").categoryId)
    }

    @Test
    fun `an exact name beats one that merely starts the same way`() {
        val cars = listOf(
            VoiceCategory("c-auto", "Auto", EntryKind.Expense),
            VoiceCategory("c-bus", "Autobus", EntryKind.Expense),
        )
        assertEquals(
            "c-auto",
            ((parse("200 na auto", categories = cars)) as VoiceParse.Complete).transaction.categoryId,
        )
    }

    /** Half a word is not a category. A confident wrong one in a shared ledger
     *  is worse than a trip to the keypad. */
    @Test
    fun `half a name is refused rather than guessed`() {
        val cars = listOf(
            VoiceCategory("c-auto", "Auto", EntryKind.Expense),
            VoiceCategory("c-bus", "Autobus", EntryKind.Expense),
        )
        val parsed = parse("200 na aut", categories = cars)
        assertEquals(VoiceParse.Missing.Category, (parsed as VoiceParse.Partial).missing)
        assertNull(parsed.transaction.categoryId)
    }

    @Test
    fun `two names that score the same are a refusal, not a coin toss`() {
        val tied = listOf(
            VoiceCategory("c-a", "Zakupy spożywcze", EntryKind.Expense),
            VoiceCategory("c-b", "Zakupy domowe", EntryKind.Expense),
        )
        val parsed = parse("200 na zakupy", categories = tied)
        assertEquals(VoiceParse.Missing.Category, (parsed as VoiceParse.Partial).missing)
    }

    /** Except between a parent and its own child, where the specific one is
     *  what somebody naming a word the child owns meant. */
    @Test
    fun `a child beats its own parent on a draw`() {
        val family = listOf(
            VoiceCategory("c-a", "Zakupy spożywcze", EntryKind.Expense),
            VoiceCategory("c-b", "Zakupy domowe", EntryKind.Expense, parentId = "c-a"),
        )
        val spoken = (parse("200 na zakupy", categories = family) as VoiceParse.Complete).transaction
        assertEquals("c-b", spoken.categoryId)
    }

    // --------------------------------------------------- falling short

    @Test
    fun `a category with no amount goes to the keypad carrying the category`() {
        val parsed = parse("dodaj na transport") as VoiceParse.Partial
        assertEquals(VoiceParse.Missing.Amount, parsed.missing)
        assertEquals(transport.id, parsed.transaction.categoryId)
        assertEquals(0L, parsed.transaction.amountMinor)
    }

    @Test
    fun `an amount with no category goes to the keypad carrying the amount`() {
        val parsed = parse("dodaj 200") as VoiceParse.Partial
        assertEquals(VoiceParse.Missing.Category, parsed.missing)
        assertEquals(20000L, parsed.transaction.amountMinor)
    }

    @Test
    fun `nonsense is nonsense`() {
        assertTrue(parse("asdf qwerty") is VoiceParse.Unrecognised)
    }

    /**
     * The n-best walk, which is free accuracy: the top hypothesis has no
     * category in it and the second one does.
     */
    @Test
    fun `the second hypothesis is tried when the first falls short`() {
        val parsed = parse("dodaj 200 na tran sport", listOf("dodaj 200 na transport"))
        val spoken = (parsed as VoiceParse.Complete).transaction
        assertEquals(transport.id, spoken.categoryId)
        assertEquals(20000L, spoken.amountMinor)
    }

    /**
     * The keypad stops at nine digits of złoty, so anything longer is a
     * mis-hearing rather than a purchase. It must produce NO amount: a
     * truncation would be a wrong row that looks perfectly right.
     */
    @Test
    fun `an amount past the keypad's own ceiling is not truncated`() {
        val parsed = parse("dodaj 1234567890 na transport") as VoiceParse.Partial
        assertEquals(VoiceParse.Missing.Amount, parsed.missing)
        assertEquals(0L, parsed.transaction.amountMinor)
        assertEquals(transport.id, parsed.transaction.categoryId)
    }

    /**
     * "2 x 12 zł" and "dwa chleby za dwanaście złotych" both put a count in
     * front of a price. The group wearing a currency word is the price.
     */
    @Test
    fun `two numbers in one sentence resolve to the one wearing a currency`() {
        assertEquals(1200L, complete("2 x 12 zł na zakupy").amountMinor)
        assertEquals(1200L, complete("dwa chleby za dwanaście złotych na zakupy").amountMinor)
    }

    // ------------------------------------------------- an empty household

    @Test
    fun `a household with no categories yet refuses rather than throwing`() {
        val parsed = parse("dodaj 200 na transport", categories = emptyList())
        assertEquals(VoiceParse.Missing.Category, (parsed as VoiceParse.Partial).missing)
    }

    /** A freshly enrolled phone whose accounts have not arrived. accounts.first()
     *  would throw; the keypad already knows how to say "add an account first". */
    @Test
    fun `a household with no accounts yet refuses rather than throwing`() {
        val parsed = parse("dodaj 200 na transport", accounts = emptyList())
        assertEquals(VoiceParse.Missing.Account, (parsed as VoiceParse.Partial).missing)
        assertNull(parsed.transaction.accountId)
    }

    @Test
    fun `the date comes from the argument and nowhere else`() {
        val other = LocalDate.of(2019, 12, 31)
        val parsed = VoiceParser.parse("dodaj 200 na transport", emptyList(), categories, accounts, pl, other)
        assertEquals(other, (parsed as VoiceParse.Complete).transaction.date)
    }
}
