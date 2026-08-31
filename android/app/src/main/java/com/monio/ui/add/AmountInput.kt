package com.monio.ui.add

import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The amount field is NOT a TextField.
 *
 * This is the highest-leverage decision in the Android half of the project: it
 * removes the entire class of "focus the field at first composition" flakiness —
 * FocusRequester not yet attached, the window not yet focused mid-transition,
 * Gboard's own 300-800 ms cold start — and it delivers the calculator this
 * screen wants anyway. There is no system keyboard in the add flow at all.
 *
 * This class is the pure state behind the hand-drawn grid: digits in, a Long in
 * minor units out. It is deliberately free of Compose so it can be reasoned
 * about (and unit-tested) on its own.
 *
 * `text` uses ',' as its decimal marker internally whatever the language is.
 * That is an encoding, not a display: display() substitutes whatever the
 * locale actually uses.
 */
class AmountInput private constructor(
    val text: String,
    private val pendingOperand: Long?,
    private val pendingOperator: Char?,
) {
    constructor() : this("0", null, null)

    companion object {
        private const val MAX_DIGITS = 9

        /**
         * The decimal key's label, and the character display() puts back. Read
         * from the locale rather than fixed to a comma: an English interface
         * expects a full stop, and a keypad whose separator key disagrees with
         * the amount above it looks broken.
         */
        fun decimalSeparator(locale: Locale = Locale.getDefault()): Char =
            DecimalFormatSymbols.getInstance(locale).decimalSeparator
    }

    val hasPendingOperation: Boolean get() = pendingOperator != null

    val operatorLabel: String? get() = pendingOperator?.toString()

    /** The entered value in minor units (grosze). Money is an integer, always. */
    fun toMinor(): Long {
        val parts = text.split(',')
        val whole = parts[0].filter { it.isDigit() }.ifEmpty { "0" }
        val fraction = parts.getOrNull(1).orEmpty().filter { it.isDigit() }.take(2).padEnd(2, '0')
        return whole.toLong() * 100 + fraction.toLong()
    }

    private fun withText(next: String) = AmountInput(next, pendingOperand, pendingOperator)

    fun digit(d: Char): AmountInput {
        val parts = text.split(',')
        if (parts.size == 2) {
            // At most two decimal places — grosze, and no more.
            if (parts[1].length >= 2) return this
            return withText("$text$d")
        }
        if (text.filter { it.isDigit() }.length >= MAX_DIGITS) return this
        val next = if (text == "0") d.toString() else "$text$d"
        return withText(next)
    }

    fun separator(): AmountInput = if (text.contains(',')) this else withText("$text,")

    fun backspace(): AmountInput {
        val dropped = text.dropLast(1)
        return withText(if (dropped.isEmpty() || dropped == "-") "0" else dropped)
    }

    fun clear(): AmountInput = AmountInput()

    /** Start a calculator operation, folding any operation already pending. */
    fun operator(op: Char): AmountInput {
        val folded = evaluate()
        return AmountInput("0", folded.toMinor(), op)
    }

    /** Apply the pending operation, if any. Never produces a negative amount. */
    fun evaluate(): AmountInput {
        val left = pendingOperand ?: return this
        val op = pendingOperator ?: return this
        val right = toMinor()
        val result = when (op) {
            '+' -> left + right
            '-' -> left - right
            else -> right
        }.coerceAtLeast(0)
        return AmountInput(minorToText(result), null, null)
    }

    private fun minorToText(minor: Long): String {
        val whole = minor / 100
        val fraction = (minor % 100).toInt()
        return if (fraction == 0) whole.toString() else "$whole,${fraction.toString().padStart(2, '0')}"
    }

    /**
     * Display form, grouped the way the interface language groups: "1 234,50"
     * in Polish, "1,234.50" in English. Both separators come from the same
     * DecimalFormatSymbols that NumberFormat uses inside Money, so the keypad
     * and every formatted amount elsewhere agree character for character —
     * Polish groups with a NON-BREAKING space, and a plain one here would be a
     * mismatch nobody notices until they compare two screens.
     */
    fun display(locale: Locale = Locale.getDefault()): String {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val parts = text.split(',')
        val grouped = parts[0].reversed().chunked(3)
            .joinToString(symbols.groupingSeparator.toString()).reversed()
        return if (parts.size == 2) "$grouped${symbols.decimalSeparator}${parts[1]}" else grouped
    }
}
