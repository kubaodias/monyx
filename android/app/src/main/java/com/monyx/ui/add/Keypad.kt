package com.monyx.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monyx.R

/** What a key press means. The screen owns the interpretation. */
sealed interface KeyAction {
    data class Digit(val value: Char) : KeyAction
    data object Separator : KeyAction
    data object Backspace : KeyAction
    data class Operator(val op: Char) : KeyAction

    /** Folds a pending sum into one number. It does NOT save — see SaveBar. */
    data object Equals : KeyAction
}

private data class Key(
    val label: String,
    val action: KeyAction,
    val emphasis: Emphasis = Emphasis.Digit,
    /** How many of the row's four columns this key occupies. */
    val span: Float = 1f,
)

private enum class Emphasis { Digit, Function }

/**
 * A hand-drawn grid, roughly forty lines of layout. No system keyboard is ever
 * shown in the add flow.
 *
 * Compose recomposition is not a risk here: a digit tap redrawing a number and a
 * twenty-cell grid is nothing. Do not spend an evening on derivedStateOf.
 */
@Composable
fun Keypad(
    onKey: (KeyAction) -> Unit,
    equalsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    // Read through LocalConfiguration, not Locale.getDefault(), so switching
    // language recomposes the key instead of leaving a stale comma behind.
    val separator = AmountInput.decimalSeparator(LocalConfiguration.current.locales[0])

    // Phone order — 1 2 3 on top — not calculator order. This is a phone, and
    // the layout every other number on it is typed into ascends downward; a
    // household entering an expense is dialling a figure, not operating a till.
    //
    // The right-hand column does NOT flip with them. Those are three unrelated
    // controls that happen to share a column, and the only thing moving them
    // would achieve is putting backspace somewhere new.
    val rows = listOf(
        listOf(
            Key("1", KeyAction.Digit('1')),
            Key("2", KeyAction.Digit('2')),
            Key("3", KeyAction.Digit('3')),
            Key("−", KeyAction.Operator('-'), Emphasis.Function),
        ),
        listOf(
            Key("4", KeyAction.Digit('4')),
            Key("5", KeyAction.Digit('5')),
            Key("6", KeyAction.Digit('6')),
            Key("+", KeyAction.Operator('+'), Emphasis.Function),
        ),
        listOf(
            Key("7", KeyAction.Digit('7')),
            Key("8", KeyAction.Digit('8')),
            Key("9", KeyAction.Digit('9')),
            Key("⌫", KeyAction.Backspace, Emphasis.Function),
        ),
        // Zero takes the width the "00" key used to have. Two zeros in one tap
        // saved a tap on round amounts and cost a mis-tap on every other one.
        listOf(
            Key(separator.toString(), KeyAction.Separator),
            Key("0", KeyAction.Digit('0'), span = 2f),
            // "=" and nothing more. This key used to be a filled green tick,
            // which is the shape and the colour of a commit button — so the one
            // control that looked like it finished the expense actually only
            // finished the arithmetic. Saving now has a button of its own.
            Key("=", KeyAction.Equals, Emphasis.Function),
        ),
    )

    Column(
        // A little air under the bottom row. The save bar used to provide it and
        // is not always there any more, which left "0" sitting on the tab bar.
        modifier = modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { key ->
                    KeyButton(
                        key = key,
                        enabled = key.action != KeyAction.Equals || equalsEnabled,
                        onClick = { onKey(key.action) },
                        modifier = Modifier.weight(key.span).fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyButton(
    key: Key,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val background = when (key.emphasis) {
        Emphasis.Digit -> scheme.surface
        Emphasis.Function -> scheme.surfaceVariant
    }
    val foreground = when (key.emphasis) {
        Emphasis.Digit -> scheme.onSurface
        Emphasis.Function -> scheme.onSurfaceVariant
    }.let { if (enabled) it else it.copy(alpha = 0.4f) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (key.action) {
            is KeyAction.Backspace -> Icon(
                Icons.AutoMirrored.Filled.Backspace,
                contentDescription = stringResource(R.string.add_backspace),
                tint = foreground,
            )
            else -> Text(
                text = key.label,
                color = foreground,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
