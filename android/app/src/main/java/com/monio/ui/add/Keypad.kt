package com.monio.ui.add

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
import androidx.compose.material.icons.filled.Check
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
import com.monio.R

/** What a key press means. The screen owns the interpretation. */
sealed interface KeyAction {
    data class Digit(val value: Char) : KeyAction
    /** The "00" key — two grosze zeros in one tap, which is most of the time. */
    data object DoubleZero : KeyAction
    data object Separator : KeyAction
    data object Backspace : KeyAction
    data class Operator(val op: Char) : KeyAction
    data object Confirm : KeyAction
}

private data class Key(
    val label: String,
    val action: KeyAction,
    val emphasis: Emphasis = Emphasis.Digit,
)

private enum class Emphasis { Digit, Function, Confirm }

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
    confirmEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    // Read through LocalConfiguration, not Locale.getDefault(), so switching
    // language recomposes the key instead of leaving a stale comma behind.
    val separator = AmountInput.decimalSeparator(LocalConfiguration.current.locales[0])

    val rows = listOf(
        listOf(
            Key("7", KeyAction.Digit('7')),
            Key("8", KeyAction.Digit('8')),
            Key("9", KeyAction.Digit('9')),
            Key("−", KeyAction.Operator('-'), Emphasis.Function),
        ),
        listOf(
            Key("4", KeyAction.Digit('4')),
            Key("5", KeyAction.Digit('5')),
            Key("6", KeyAction.Digit('6')),
            Key("+", KeyAction.Operator('+'), Emphasis.Function),
        ),
        listOf(
            Key("1", KeyAction.Digit('1')),
            Key("2", KeyAction.Digit('2')),
            Key("3", KeyAction.Digit('3')),
            Key("⌫", KeyAction.Backspace, Emphasis.Function),
        ),
        listOf(
            Key(separator.toString(), KeyAction.Separator),
            Key("0", KeyAction.Digit('0')),
            Key("00", KeyAction.DoubleZero),
            Key("✓", KeyAction.Confirm, Emphasis.Confirm),
        ),
    )

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { key ->
                    KeyButton(
                        key = key,
                        enabled = key.action != KeyAction.Confirm || confirmEnabled,
                        onClick = { onKey(key.action) },
                        modifier = Modifier.weight(1f).fillMaxSize(),
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
        Emphasis.Confirm -> if (enabled) scheme.primary else scheme.surfaceVariant
    }
    val foreground = when (key.emphasis) {
        Emphasis.Digit -> scheme.onSurface
        Emphasis.Function -> scheme.onSurfaceVariant
        Emphasis.Confirm -> if (enabled) scheme.onPrimary else scheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
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
            is KeyAction.Confirm -> Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.add_save),
                tint = foreground,
            )
            else -> Text(
                text = key.label,
                color = foreground,
                fontSize = 26.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
