package com.monyx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monyx.MonyxApp
import com.monyx.data.Currencies
import com.monyx.data.Currency

/**
 * The currencies this phone offers, for whichever picker is asking.
 *
 * One line at each call site, because there are four of them — the keypad, the
 * edit sheet, the account editor and the rule editor — and threading a preference
 * flow down through each screen's ViewModel would be four copies of the same
 * plumbing for a value that is not about any of them. It reads the preference
 * directly, the way a composable reads the theme.
 *
 * @param inUse codes that must be offered whatever the preference says: the
 *   currencies the household's accounts are denominated in, plus whatever the
 *   form being drawn has currently chosen. See [Currencies.offered].
 */
@Composable
internal fun rememberOfferedCurrencies(inUse: Collection<String?>): List<Currency> {
    val app = LocalContext.current.applicationContext as MonyxApp
    val chosen by app.currencyPreferences.chosen.collectAsStateWithLifecycle(initialValue = null)
    // The first frame reads null, which is "nobody has chosen" — the same answer
    // a fresh install gives, and the same list. A phone that HAS hidden things
    // shows them for one frame of a dialog opening; cheap, and the alternative
    // is an empty picker while DataStore reads from disk.
    val keep = inUse.filterNotNullTo(HashSet())
    return remember(chosen, keep) { Currencies.offered(chosen, keep) }
}
