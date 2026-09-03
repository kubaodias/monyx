package com.monyx

import com.monyx.ui.add.EntryKind
import com.monyx.voice.VoiceAccount
import com.monyx.voice.VoiceCategory
import com.monyx.voice.VoiceCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * The correction grammar is closed on purpose: at the moment the summary is on
 * screen the sayable set is small and known, and that is exactly what lets an
 * unmatched utterance be reported as unmatched instead of guessed at. The
 * caller's contract for [com.monyx.voice.VoiceCorrection.isEmpty] is "do not
 * write", so every field is asserted absent rather than merely unequal.
 *
 * Same fixture as VoiceParserTest: the real seeded names, with diacritics.
 */
class VoiceCommandTest {

    private val today = LocalDate.of(2026, 3, 14)
    private val pl = Locale.forLanguageTag("pl-PL")
    private val en = Locale.forLanguageTag("en-GB")

    private val groceries = VoiceCategory("c-groceries", "Zakupy spożywcze", EntryKind.Expense)
    private val transport = VoiceCategory("c-transport", "Transport", EntryKind.Expense)
    private val home = VoiceCategory("c-home", "Dom", EntryKind.Expense)
    private val salary = VoiceCategory("c-salary", "Wypłata", EntryKind.Income)
    private val categories = listOf(groceries, transport, home, salary)

    private val cash = VoiceAccount("a-cash", "Gotówka")
    private val card = VoiceAccount("a-card", "Karta")
    private val accounts = listOf(cash, card)

    private fun say(
        text: String,
        locale: Locale = pl,
        categories: List<VoiceCategory> = this.categories,
    ) = VoiceCommands.parse(text, emptyList(), categories, accounts, locale, today)

    @Test
    fun `take it back`() {
        for (word in listOf("cofnij", "wycofaj", "anuluj", "usuń")) {
            assertTrue(word, say(word, pl).revert)
        }
        for (word in listOf("revert", "undo", "cancel", "delete")) {
            assertTrue(word, say(word, en).revert)
        }
        assertTrue(say("nie").revert)
        assertTrue(say("no", en).revert)
    }

    @Test
    fun `make it a different amount`() {
        assertEquals(25000L, say("ma być 250").amountMinor)
        assertEquals(25000L, say("make it 250", en).amountMinor)
        assertEquals(25000L, say("250").amountMinor)
    }

    @Test
    fun `put it in a different category`() {
        assertEquals(groceries.id, say("zmień kategorię na zakupy").categoryId)
        assertEquals(groceries.id, say("zakupy").categoryId)

        val english = listOf(VoiceCategory("c-gro-en", "Groceries", EntryKind.Expense))
        assertEquals("c-gro-en", say("change category to groceries", en, english).categoryId)
    }

    /** One utterance, two fields, one `copy(...)` and one write. */
    @Test
    fun `an amount and a category in the same breath`() {
        val correction = say("250 na zakupy")
        assertEquals(25000L, correction.amountMinor)
        assertEquals(groceries.id, correction.categoryId)
        assertFalse(correction.revert)
    }

    @Test
    fun `the day and the account`() {
        assertEquals(today.minusDays(1), say("wczoraj").date)
        // The last day word wins: this is somebody correcting themselves.
        assertEquals(today.minusDays(1), say("nie dzisiaj, wczoraj").date)
        assertEquals(cash.id, say("gotówka").accountId)
        assertEquals(card.id, say("karta").accountId)
    }

    @Test
    fun `the kind, which the tap-to-edit dialog deliberately will not change`() {
        assertEquals(EntryKind.Income, say("przychód").kind)
        assertEquals(EntryKind.Expense, say("wydatek").kind)
    }

    @Test
    fun `yes, that is right`() {
        assertTrue(say("ok").confirm)
        assertTrue(say("tak").confirm)
        assertTrue(say("done", en).confirm)
    }

    /**
     * An undo verb with something concrete beside it changes nothing at all.
     *
     * "nie anuluj, zmień na transport" — *do not cancel, change it to
     * transport* — is the second most natural thing anybody will say to this
     * sheet and it is the destructive one, and there is no telling it apart
     * from "nie, cofnij 250" by shape: tokenise drops the punctuation, so both
     * arrive as the same words with the same verb in the same place. So both
     * are refused, the row is left alone, and Revert is a button one tap away.
     */
    @Test
    fun `an undo verb beside a correction changes nothing`() {
        for (sentence in listOf("nie anuluj, zmień na transport", "nie, cofnij 250")) {
            val correction = say(sentence)
            assertTrue(sentence, correction.isEmpty)
            assertFalse(sentence, correction.revert)
            assertNull(sentence, correction.amountMinor)
            assertNull(sentence, correction.categoryId)
        }
    }

    /**
     * Bare means bare. "nie" turns up in the middle of sentences that are not
     * an undo at all, and "nie, zostaw" — *no, leave it* — means the exact
     * opposite of what a naive reading takes it for.
     */
    @Test
    fun `a negation with a word of its own beside it is not an undo`() {
        for (sentence in listOf("to nie było anulowane", "nie, zostaw")) {
            val correction = say(sentence)
            assertTrue(sentence, correction.isEmpty)
            assertFalse(sentence, correction.revert)
        }
    }

    /**
     * "nie" is Polish's ordinary negation particle, and this is the single most
     * natural correction anybody will ever say to this sheet. It must change
     * the category, not delete the row.
     */
    @Test
    fun `bare negation reverts and negation with a correction corrects`() {
        val correction = say("nie na zakupy, na transport")
        assertFalse(correction.revert)
        assertEquals(transport.id, correction.categoryId)
    }

    // ------------------------------------------------------------- notes

    /**
     * The owner's own sentence, and the reason the note rule exists.
     *
     * "te zakupy były w lidlu" contains a category name and is plainly not a
     * category correction — "zakupy" is only being used to point at the row.
     * Reading a category out of the middle of it would move the transaction to
     * Zakupy spożywcze and say nothing about where they actually were.
     */
    @Test
    fun `a sentence about the transaction is a note, not a category`() {
        val correction = say("te zakupy były w lidlu")
        assertEquals("te zakupy były w lidlu", correction.note)
        assertNull(correction.categoryId)
        assertNull(correction.amountMinor)
        assertFalse(correction.revert)
    }

    /** ...and the bare name still is one. */
    @Test
    fun `a bare category name is still a category`() {
        val correction = say("zakupy")
        assertEquals(groceries.id, correction.categoryId)
        assertNull(correction.note)
    }

    @Test
    fun `an explicit marker takes everything after it, verbatim`() {
        val correction = say("notatka bilet miesięczny")
        assertEquals("bilet miesięczny", correction.note)
        assertNull(correction.amountMinor)
        assertNull(correction.categoryId)
        assertNull(correction.date)
    }

    /**
     * The marker wins over the undo verbs, which are otherwise the one thing in
     * this grammar that short-circuits everything. Somebody who says "notatka"
     * has already told you which half of the sentence is an instruction.
     */
    @Test
    fun `a note is not swallowed by the revert or confirm words inside it`() {
        val cancelled = say("notatka anulowane zamówienie")
        assertEquals("anulowane zamówienie", cancelled.note)
        assertFalse(cancelled.revert)

        val agreed = say("notatka ok było tanio")
        assertEquals("ok było tanio", agreed.note)
        assertFalse(agreed.confirm)
    }

    /** Naming a field is never a note, however much else is said around it. */
    @Test
    fun `an utterance that names a field stays a field correction`() {
        val both = say("250 na zakupy")
        assertEquals(25000L, both.amountMinor)
        assertEquals(groceries.id, both.categoryId)
        assertNull(both.note)

        assertNull(say("zmień kategorię na zakupy").note)
        assertNull(say("wczoraj").note)
        assertNull(say("gotówka").note)
    }

    /** A sentence whose only signal is a negation is somebody saying no, not
     *  somebody describing a purchase. */
    @Test
    fun `a negated sentence is refused rather than written down`() {
        val correction = say("to nie było anulowane")
        assertTrue(correction.isEmpty)
        assertNull(correction.note)
    }

    @Test
    fun `two names that score the same are offered rather than guessed`() {
        val tied = listOf(
            VoiceCategory("c-a", "Zakupy spożywcze", EntryKind.Expense),
            VoiceCategory("c-b", "Zakupy domowe", EntryKind.Expense),
        )
        val correction = say("zakupy", categories = tied)
        assertNull(correction.categoryId)
        assertEquals(setOf("c-a", "c-b"), correction.ambiguous.map { it.id }.toSet())
    }

    @Test
    fun `nothing understood changes nothing`() {
        val correction = say("asdf")
        assertTrue(correction.isEmpty)
        assertFalse(correction.revert)
        assertFalse(correction.confirm)
        assertNull(correction.amountMinor)
        assertNull(correction.categoryId)
        assertNull(correction.kind)
        assertNull(correction.date)
        assertNull(correction.accountId)
        assertNull(correction.note)
        assertTrue(correction.ambiguous.isEmpty())
    }
}
