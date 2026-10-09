package com.monyx.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monyx.R
import com.monyx.data.Currencies
import com.monyx.data.Currency

/**
 * Which currencies this phone is willing to be asked about.
 *
 * Every currency the app knows is listed; the switch says whether it turns up in
 * the pickers. Nothing here changes a stored row, a balance or a total — hiding
 * the Swedish krona does not make a krona row unreadable, it stops the krona
 * being one of nine things to scroll past on the way to the euro.
 *
 * Two rows cannot be switched off, and they say why rather than simply refusing:
 *
 *  - **złoty**, which every total is in. See ADR 0004 and 0022.
 *  - **any currency an account holds**, because that is a fact already written
 *    down. Switch the account to something else first, and this row unlocks.
 *
 * A switch per row rather than a multi-select of chips: this is a list of
 * independent yes/no answers, which is what a switch means, and the household
 * reads down it once and then never comes back.
 */
@Composable
fun CurrenciesSection(
    /** The codes the household's accounts are denominated in. */
    inUse: Set<String>,
    shown: Set<String>?,
    onSetShown: (String, Boolean) -> Unit,
) {
    val offered = Currencies.offered(shown, inUse).map { it.code }.toSet()
    SectionCard(title = stringResource(R.string.settings_currencies), icon = Icons.Filled.Payments) {
        Text(
            stringResource(R.string.settings_currencies_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Currency.entries.forEach { currency ->
            val locked = Currencies.locked(currency, inUse)
            val on = currency.code in offered
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = on,
                        enabled = !locked,
                        role = Role.Switch,
                        onValueChange = { onSetShown(currency.code, it) },
                    )
                    .padding(vertical = 6.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = currency.code,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        // The symbol, where it is not the code again. It is what
                        // the row on the ledger will actually read.
                        if (currency.suffix != currency.code) {
                            Text(
                                text = currency.suffix,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Why this one cannot be turned off, on the row itself.
                    if (locked) {
                        Text(
                            stringResource(
                                if (currency.isReporting) {
                                    R.string.settings_currency_reporting
                                } else {
                                    R.string.settings_currency_in_use
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = on, enabled = !locked, onCheckedChange = null)
            }
        }
    }
}
