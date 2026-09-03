package com.monyx

import com.monyx.voice.NotePrompt
import com.monyx.voice.NoteRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate a model answer has to get through before it is allowed to replace a
 * note the rules already wrote.
 *
 * The bar is "plausibly better", not "different", and the load-bearing rule is
 * grounding: every word of the answer has to be a word that was actually said,
 * or one inflection away from one. A model cannot introduce a shop nobody
 * mentioned, cannot summarise and cannot editorialise — the most it can do is
 * re-render what the sentence already contained. That is what makes a model
 * having a bad day degrade to "no change" rather than to a wrong note.
 */
class NotePromptTest {

    private val request = NoteRequest(
        transcript = "150 zł na zakupy w Rossmanie",
        amountMinor = 15000,
        categoryName = "Zakupy spożywcze",
        note = "Rossman",
    )

    private fun accept(answer: String?) = NotePrompt.accept(answer, request)

    @Test
    fun `a grounded, short, better answer is taken`() {
        assertEquals("Rossmann", accept("Rossmann"))
        assertEquals("Rossmann", accept("  \"Rossmann\"  "))
    }

    @Test
    fun `saying nothing is a valid answer and changes nothing`() {
        assertNull(accept("-"))
        assertNull(accept(""))
        assertNull(accept("   "))
        assertNull(accept(null))
    }

    @Test
    fun `an answer that matches what the rules already wrote is not a change`() {
        assertNull(accept("Rossman"))
    }

    /** The rule that stops invention. "Tesco" was never said. */
    @Test
    fun `a word that was never said is refused`() {
        assertNull(accept("Tesco"))
        assertNull(accept("Rossmann Tesco"))
    }

    @Test
    fun `every word has to be grounded, not just the first`() {
        val atStation = request.copy(transcript = "150 zł na zakupy w Rossmanie przy dworcu")
        assertEquals("Rossmann przy dworcu", NotePrompt.accept("Rossmann przy dworcu", atStation))
        assertNull(NotePrompt.accept("Rossmann przy lotnisku", atStation))
    }

    @Test
    fun `the amount read back is not a label`() {
        assertNull(accept("150"))
        assertNull(NotePrompt.accept("15000", request.copy(note = "x")))
    }

    @Test
    fun `an explanation is not a label`() {
        assertNull(accept("Sorry, I cannot help with that request"))
        assertNull(accept("The label is Rossmann"))
        assertNull(accept("Rossmann\nRossmann"))
    }

    @Test
    fun `a sentence is not a label`() {
        assertNull(accept("Rossmann zakupy Rossmann zakupy Rossmann"))
        assertNull(accept("R".repeat(41)))
    }

    /** The examples in the prompt are the owner's own sentences, and one of
     *  them has to teach that returning nothing is correct. */
    @Test
    fun `the prompt carries the sentence, the fields and permission to say nothing`() {
        val prompt = NotePrompt.of(request)
        assertTrue(prompt, "150 zł na zakupy w Rossmanie" in prompt)
        assertTrue(prompt, "Zakupy spożywcze" in prompt)
        assertTrue(prompt, "150" in prompt)
        assertTrue(prompt, "Rossman" in prompt)
        assertTrue(prompt, "reply with: -" in prompt)
        assertTrue(prompt, "wydalem 35 na paliwo -> -" in prompt)
    }
}
