package com.monyx.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.monyx.BuildConfig
import com.monyx.R

/**
 * Telnyx green. Bright enough that the icon on it has to be dark — the mark
 * belongs to the platform the assistant runs on, so it is a literal rather
 * than a theme colour, and it stays itself in the dark theme.
 */
private val TelnyxGreen = Color(0xFF00E3AA)
private val OnTelnyxGreen = Color(0xFF10201B)

/**
 * Calls the voice assistant.
 *
 * ACTION_DIAL, not ACTION_CALL: dialling opens the phone app with the number
 * filled in and lets the person press the green button themselves. ACTION_CALL
 * would place the call from under their thumb and needs the CALL_PHONE
 * permission to do it — a permission prompt and an unasked-for outgoing call,
 * to save one tap.
 *
 * Renders nothing when [BuildConfig.ASSISTANT_NUMBER] is empty. The number
 * lives in an untracked `voice.properties`, so a clean checkout has no number
 * and therefore no button, rather than a button that dials nowhere.
 */
@Composable
fun CallAssistantButton() {
    val number = BuildConfig.ASSISTANT_NUMBER
    if (number.isEmpty()) return

    val context = LocalContext.current
    FloatingActionButton(
        onClick = {
            val intent = Intent(Intent.ACTION_DIAL, "tel:$number".toUri())
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                // A tablet with no dialler. Nothing useful to say about it that
                // is worth a dialog, and nothing to crash over.
            }
        },
        containerColor = TelnyxGreen,
        contentColor = OnTelnyxGreen,
    ) {
        Icon(
            Icons.Filled.Call,
            contentDescription = stringResource(R.string.call_assistant),
        )
    }
}
