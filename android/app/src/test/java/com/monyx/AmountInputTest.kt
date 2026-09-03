package com.monyx

import com.monyx.ui.add.AmountInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * The keypad is the whole add flow, and money is an integer in minor
 * units — the one rule whose violation is a critical bug. Both are pure
 * logic, so both are tested here rather than through the UI.
 */
class AmountInputTest {

    private fun type(vararg keys: Char): AmountInput =
        keys.fold(AmountInput()) { acc, key ->
            when (key) {
                ',' -> acc.separator()
                '<' -> acc.backspace()
                '+', '-' -> acc.operator(key)
                '=' -> acc.evaluate()
                else -> acc.digit(key)
            }
        }

    @Test
    fun `starts at zero`() {
        assertEquals(0L, AmountInput().toMinor())
        assertEquals("0", AmountInput().text)
    }

    @Test
    fun `whole zloty become grosze`() {
        assertEquals(4500L, type('4', '5').toMinor())
    }

    @Test
    fun `decimals are grosze, not rounding`() {
        assertEquals(4599L, type('4', '5', ',', '9', '9').toMinor())
        assertEquals(4590L, type('4', '5', ',', '9').toMinor())
    }

    @Test
    fun `at most two decimal places are accepted`() {
        val input = type('1', ',', '2', '3', '4', '5')
        assertEquals("1,23", input.text)
        assertEquals(123L, input.toMinor())
    }

    @Test
    fun `only one separator`() {
        assertEquals("1,5", type('1', ',', ',', '5').text)
    }

    @Test
    fun `leading zero is replaced, not appended`() {
        assertEquals("5", type('5').text)
        assertEquals("50", type('5', '0').text)
    }

    @Test
    fun `backspace returns to zero rather than to an empty string`() {
        assertEquals("0", type('7', '<').text)
        assertEquals(0L, type('7', '<').toMinor())
    }

    @Test
    fun `the calculator adds`() {
        // 12,50 + 7,25 = 19,75
        val result = type('1', '2', ',', '5', '0', '+', '7', ',', '2', '5', '=')
        assertEquals(1975L, result.toMinor())
    }

    @Test
    fun `the calculator subtracts`() {
        val result = type('2', '0', '-', '5', '=')
        assertEquals(1500L, result.toMinor())
    }

    @Test
    fun `a subtraction below zero clamps rather than producing a negative amount`() {
        // Amounts are always positive; direction comes from kind.
        val result = type('5', '-', '9', '=')
        assertEquals(0L, result.toMinor())
    }

    @Test
    fun `a pending operation is visible to the UI so confirm can fold it first`() {
        val pending = type('5', '+')
        assertEquals(true, pending.hasPendingOperation)
        assertEquals("+", pending.operatorLabel)
        assertEquals(false, pending.evaluate().hasPendingOperation)
    }

    /**
     * The reported gap: "+" clears the big number to take the next operand, so
     * the amount typed so far was on screen nowhere at all until "=" folded it.
     */
    @Test
    fun `the running total stays readable while the second operand is typed`() {
        val pending = type('6', '0', '+')
        assertEquals("60", pending.pendingDisplay(Locale.forLanguageTag("pl-PL")))
        assertEquals("0", pending.display(Locale.forLanguageTag("pl-PL")))

        // Still the SAME left-hand side once digits of the right-hand side land.
        val typing = type('6', '0', '+', '4')
        assertEquals("60", typing.pendingDisplay(Locale.forLanguageTag("pl-PL")))
    }

    @Test
    fun `the running total is grouped and punctuated like every other amount`() {
        val pending = type('1', '2', '3', '4', ',', '5', '0', '+')
        assertEquals(
            "1 234,50",
            pending.pendingDisplay(Locale.forLanguageTag("pl-PL"))!!.replace('\u00A0', ' '),
        )
        assertEquals("1,234.50", pending.pendingDisplay(Locale.forLanguageTag("en-GB")))
    }

    @Test
    fun `with nothing pending there is no running total to show`() {
        assertNull(type('6', '0').pendingDisplay())
        assertNull(type('6', '0', '+', '4', '=').pendingDisplay())
    }

    @Test
    fun `chained operations fold left to right`() {
        // 10 + 5 + 2 = 17
        val result = type('1', '0', '+', '5', '+', '2', '=')
        assertEquals(1700L, result.toMinor())
    }

    /**
     * The grouping separator is not a plain space: Polish emits U+00A0 and
     * English a comma, and the keypad has to emit exactly what Money.format
     * emits or two screens showing the same amount disagree. Flattened here so
     * the expectation reads as the number it is rather than as a trap for the
     * next person.
     */
    @Test
    fun `display groups thousands the way the chosen language does`() {
        fun shown(input: AmountInput, tag: String) =
            input.display(Locale.forLanguageTag(tag)).replace('\u00A0', ' ')

        assertEquals("1 234", shown(type('1', '2', '3', '4'), "pl-PL"))
        assertEquals("1 234 567", shown(type('1', '2', '3', '4', '5', '6', '7'), "pl-PL"))
        assertEquals("1 234,50", shown(type('1', '2', '3', '4', ',', '5', '0'), "pl-PL"))

        assertEquals("1,234", shown(type('1', '2', '3', '4'), "en-GB"))
        assertEquals("1,234,567", shown(type('1', '2', '3', '4', '5', '6', '7'), "en-GB"))
        assertEquals("1,234.50", shown(type('1', '2', '3', '4', ',', '5', '0'), "en-GB"))
    }

    /**
     * The internal text always uses ',' as its decimal marker; that is an
     * encoding, and display() is what turns it into the locale's character.
     */
    @Test
    fun `the separator key agrees with the decimal point Money prints`() {
        for (tag in listOf("pl-PL", "en-GB")) {
            val locale = Locale.forLanguageTag(tag)
            val previous = Locale.getDefault()
            Locale.setDefault(locale)
            try {
                val fromKeypad = type('1', '2', '3', '4', ',', '5', '0').display(locale)
                val fromFormatter = com.monyx.data.Money.format(123450)
                assertEquals(fromFormatter, fromKeypad)
            } finally {
                Locale.setDefault(previous)
            }
        }
    }

    @Test
    fun `the digit count is bounded so the display cannot overflow`() {
        val many = type('1', '2', '3', '4', '5', '6', '7', '8', '9', '1', '2', '3')
        assertEquals(9, many.text.filter { it.isDigit() }.length)
    }

    /**
     * A dictated amount and a typed one have to be the same object from the
     * moment the keypad opens, or a voice prefill is a second kind of amount
     * with its own rounding.
     *
     * display(locale) is what is pinned, because it is the locale-sensitive
     * half: `text` uses ',' internally whatever the language is, so a round
     * trip through toMinor() alone proves less than it looks.
     */
    @Test
    fun `an amount handed over from a dictated sentence is an ordinary amount`() {
        assertEquals(20000L, AmountInput.ofMinor(20000).toMinor())
        assertEquals("200", AmountInput.ofMinor(20000).display(Locale.forLanguageTag("pl-PL")))

        val grosze = AmountInput.ofMinor(123450)
        assertEquals(123450L, grosze.toMinor())
        assertEquals(
            "1 234,50",
            grosze.display(Locale.forLanguageTag("pl-PL")).replace('\u00A0', ' '),
        )
        assertEquals("1,234.50", grosze.display(Locale.forLanguageTag("en-GB")))
    }

    @Test
    fun `clear resets everything`() {
        assertEquals(0L, type('9', '9', '9').clear().toMinor())
    }
}
