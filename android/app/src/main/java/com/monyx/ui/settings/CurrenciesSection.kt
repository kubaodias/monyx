package com.monyx.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.monyx.R
import com.monyx.data.Currencies
import com.monyx.data.Currency

/**
 * Which currencies this phone deals in.
 *
 * A list of the household's own, not a switch per currency the app knows about.
 * Nine switches was a list of hypotheticals — six of them Scandinavian — and
 * two of its three lines of text were explaining why a row could not be
 * switched off. This is złoty, the euro, and whatever has been added since; the
 * ones that cannot go have no remove button, which says the same thing in no
 * words at all.
 *
 * Anything can be added, not only the nine with NBP rates. A code and the text
 * to print after a figure is all a currency is here. The dialog says which ones
 * have a rate and what it means when one does not, because that is the part
 * nobody can guess: an unrated currency keeps its own figures perfectly and
 * contributes nothing to a złoty total.
 *
 * Nothing on this screen changes a stored row, a balance or a total. Removing
 * the krona does not make a krona row unreadable; it stops the krona being one
 * more thing to scroll past on the way to the euro.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CurrenciesSection(
    /** The codes the household's accounts are denominated in. */
    inUse: Set<String>,
    /** The stored entries, or null when nobody has chosen. See [Currencies]. */
    chosen: Set<String>?,
    onAdd: (String, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val offered = Currencies.offered(chosen, inUse)
    var adding by remember { mutableStateOf(false) }

    SectionCard(
        title = stringResource(R.string.settings_currencies),
        icon = Icons.Filled.Payments,
        trailing = {
            IconButton(onClick = { adding = true }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.settings_currency_add),
                )
            }
        },
    ) {
        Text(
            stringResource(R.string.settings_currencies_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        offered.forEach { currency ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = currency.code,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                // The symbol, where it is not the code again. It is what the row
                // on the ledger will actually read.
                if (currency.suffix != currency.code) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = currency.suffix,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                // Złoty and anything an account holds stay. See
                // [Currencies.locked] — the row says so by having nothing to
                // tap, which is how the rest of Settings says it too.
                if (!Currencies.locked(currency, inUse)) {
                    IconButton(onClick = { onRemove(currency.code) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.settings_delete),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        AddCurrencyDialog(
            already = offered.map { it.code }.toSet(),
            onAdd = { code, suffix ->
                onAdd(code, suffix)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

/**
 * Typing in a currency: three capitals, and whatever should follow a figure in
 * it.
 *
 * The seven with rates that are not on the list yet are offered as chips, which
 * is both a shortcut and the only place in the app that says which currencies
 * convert. Everything else is the two fields, and the symbol field is optional:
 * left empty, the code itself goes after the figure, which is what the krona and
 * the franc do anyway.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddCurrencyDialog(
    already: Set<String>,
    onAdd: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }
    val clean = code.trim().uppercase()
    val shaped = Currency.isValidCode(clean)
    val duplicate = clean in already
    val rated = remember(already) { Currency.KNOWN.filterNot { it.code in already } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_currency_add)) },
        text = {
            Column {
                if (rated.isNotEmpty()) {
                    Text(
                        stringResource(R.string.settings_currency_rated),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        rated.forEach { currency ->
                            AssistChip(
                                onClick = { onAdd(currency.code, currency.suffix) },
                                label = { Text(currencyLabel(currency)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Row(verticalAlignment = Alignment.Top) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.take(3) },
                        label = { Text(stringResource(R.string.settings_currency_code)) },
                        singleLine = true,
                        isError = code.isNotBlank() && (!shaped || duplicate),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = suffix,
                        onValueChange = { suffix = it.take(4) },
                        label = { Text(stringResource(R.string.settings_currency_symbol)) },
                        // Empty is a real answer: the code goes after the
                        // figure, the way SEK and CHF already do.
                        placeholder = { Text(clean.ifBlank { "฿" }) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        when {
                            duplicate -> R.string.settings_currency_exists
                            clean.isNotBlank() && !shaped -> R.string.settings_currency_bad_code
                            // What a currency outside the NBP list does, said
                            // before it is added rather than discovered later on
                            // a summary sheet that does not add up.
                            shaped && clean !in Currency.RATED -> R.string.settings_currency_no_rate
                            else -> R.string.settings_currency_code_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (duplicate || (clean.isNotBlank() && !shaped)) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(clean, suffix) },
                enabled = shaped && !duplicate,
            ) {
                Text(stringResource(R.string.settings_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}
