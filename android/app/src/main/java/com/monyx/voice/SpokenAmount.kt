package com.monyx.voice

import com.monyx.ui.add.AmountInput
import java.util.Locale

/**
 * The number in a spoken sentence, in minor units.
 *
 * Deliberately NOT [com.monyx.data.Money.parseToMinor]. That one reads
 * Locale.getDefault() and exists to make sense of a field somebody typed, with
 * grouping separators and a locale-dependent argument about which of "," and
 * "." is the decimal point. Speech has no grouping separators at all, and the
 * recogniser emits whichever separator it feels like regardless of language, so
 * the two have opposite problems and sharing an implementation would mean one
 * of them carrying rules that are wrong for it.
 *
 * What this has instead is the way people actually say prices out loud:
 * "trzydzieści pięć złotych", "dwanaście pięćdziesiąt", "30 groszy".
 */
internal object SpokenAmount {

    /**
     * The number, the tokens it used up so the rest can be read as words, and
     * how many numbers the sentence held altogether.
     *
     * [groups] is not bookkeeping. One sentence with two numbers in it is
     * either a count in front of a price ("2 x 12 zł") or two transactions
     * said in one breath, and only the caller — which can also see how many
     * category names scored — can tell those apart.
     */
    data class Match(val minor: Long, val tokens: IntRange, val groups: Int)

    /**
     * The keypad's own ceiling, derived from it rather than restated.
     *
     * MAX_DIGITS counts digits typed BEFORE the separator, so this is
     * 999 999 999 złoty. Anything above it is a mis-hearing rather than a
     * purchase, and a mis-hearing must produce NO amount — a truncated one
     * would be a wrong row that looks perfectly right. Written this way so the
     * two ceilings cannot drift apart: a dictated amount the keypad would
     * refuse must be refused here too, or the handoff hands over something the
     * keypad cannot hold.
     */
    private val MAX_MINOR: Long = "9".repeat(AmountInput.MAX_DIGITS).toLong() * 100

    private val WHOLE = Regex("""^\d{1,9}$""")
    private val DECIMAL = Regex("""^\d{1,9}[.,]\d{1,2}$""")

    fun find(tokens: List<String>, locale: Locale): Match? {
        val groups = collect(tokens, locale)
        if (groups.isEmpty()) return null
        // "2 x 12 zł" and "dwa chleby za dwanaście złotych" both put a count in
        // front of a price. The one wearing a currency word is the price; with
        // none of them wearing one the last is the better guess, because a
        // sentence ends on its number far more often than it starts on one.
        val chosen = groups.lastOrNull { it.currency } ?: groups.last()
        return Match(chosen.minor, chosen.range, groups.size)
    }

    private data class Group(val minor: Long, val range: IntRange, val currency: Boolean)

    private data class Run(val value: Long, val end: Int, val spelled: Boolean, val decimal: Boolean) {
        /** Minor units, on the reading that the number was złoty. */
        fun asMinor(): Long = if (decimal) value else value * 100
    }

    private fun collect(tokens: List<String>, locale: Locale): List<Group> {
        val numerals = VoiceWords.numerals(locale)
        val multipliers = VoiceWords.multipliers(locale)
        val groups = mutableListOf<Group>()
        var i = 0
        while (i < tokens.size) {
            val run = readNumber(tokens, i, numerals, multipliers)
            if (run == null) {
                i++
                continue
            }
            var end = run.end
            var minor: Long
            val next = tokens.getOrNull(end).orEmpty()
            var currency = false
            when {
                next in VoiceWords.grosze -> {
                    // "30 groszy" is thirty MINOR units. The one place a bare
                    // number is not złoty.
                    minor = run.value
                    currency = true
                    end++
                }
                next in VoiceWords.currency -> {
                    currency = true
                    end++
                    minor = run.asMinor()
                    // "dwanaście złotych pięćdziesiąt groszy".
                    val fraction = readNumber(tokens, end, numerals, multipliers)
                    if (fraction != null && fraction.value in 0..99 &&
                        tokens.getOrNull(fraction.end).orEmpty() in VoiceWords.grosze
                    ) {
                        minor += fraction.value
                        end = fraction.end + 1
                    }
                }
                else -> {
                    minor = run.asMinor()
                    // "dwanaście pięćdziesiąt" — how a price is said in a shop,
                    // with the currency left out entirely. Only for spelled
                    // numerals: digits come back from the recogniser as "12,50"
                    // already, and "2 12" is two counts, not twelve fifty.
                    val fraction = if (run.spelled) readNumber(tokens, end, numerals, multipliers) else null
                    if (fraction != null && fraction.spelled && fraction.value in 1..99) {
                        minor += fraction.value
                        end = fraction.end
                    }
                }
            }
            if (minor in 1..MAX_MINOR) groups += Group(minor, i until end, currency)
            i = maxOf(end, i + 1)
        }
        return groups
    }

    /**
     * One number starting at [from], digits or words, or null if there is not
     * one there.
     *
     * Spelled numerals fold additively while they DESCEND, which is the whole
     * trick: "dwieście pięćdziesiąt" is 250 because 50 is smaller than 200, and
     * "dwanaście pięćdziesiąt" is two numbers because 50 is not smaller than 12.
     * That single rule is what separates a sum from a price said aloud, and it
     * is the reason this is not a lookup loop with a running total.
     */
    private fun readNumber(
        tokens: List<String>,
        from: Int,
        numerals: Map<String, Long>,
        multipliers: Map<String, Long>,
    ): Run? {
        val first = tokens.getOrNull(from) ?: return null
        if (DECIMAL.matches(first)) {
            val parts = first.split(',', '.')
            val minor = parts[0].toLong() * 100 + parts[1].padEnd(2, '0').toLong()
            return Run(minor, from + 1, spelled = false, decimal = true)
        }
        if (WHOLE.matches(first)) return Run(first.toLong(), from + 1, spelled = false, decimal = false)

        var total = 0L
        var current = 0L
        var previous = Long.MAX_VALUE
        var end = from
        while (end < tokens.size) {
            val token = tokens[end]
            val multiplier = multipliers[token]
            if (multiplier != null) {
                if (end == from && multiplier == 100L) break
                current = (if (current == 0L) 1L else current) * multiplier
                if (multiplier >= 1000L) {
                    total += current
                    current = 0L
                }
                // A multiplier restarts the descent: "dwa tysiące pięćset".
                previous = Long.MAX_VALUE
                end++
                continue
            }
            val value = numerals[token] ?: break
            if (end > from && value >= previous) break
            current += value
            previous = value
            end++
        }
        if (end == from) return null
        return Run(total + current, end, spelled = true, decimal = false)
    }
}
