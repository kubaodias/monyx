package com.monyx.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private const val PREFS_NAME = "voice_permission"
private const val KEY_ASKED = "asked_record_audio"

private fun hasRecordAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

@Immutable
class RecordAudioPermission internal constructor(
    val granted: Boolean,
    /** Whether the system dialog has been raised once already. It is what
     *  stops a second automatic launch, and what turns [ask] from a prompt
     *  into a route to system settings. */
    val askedBefore: Boolean,
    /**
     * What the sheet's one button does.
     *
     * The first time, the system dialog. After that, the app's own settings
     * page — because Android stops drawing that dialog after a refusal or two
     * and a button that silently does nothing, forever, is worse than no
     * button. There is always somewhere for the answer "no, but actually yes"
     * to go.
     */
    val ask: () -> Unit,
)

/**
 * RECORD_AUDIO, asked on the first long press and never before it.
 *
 * NotificationPermissionGate says in the source that a permission dialog must
 * NEVER be raised from the Add screen, because the five-second rule forbids one
 * in that path — and this gesture lives on the Add tab. The rule is about an
 * UNPROMPTED dialog in front of somebody who came to type a number: it costs
 * them a decision they did not ask to make, in the seconds the whole screen
 * exists to protect. Nothing here fires until a finger is held on the bar for
 * half a second, which is a request for the microphone in as many words. The
 * keypad path is untouched: type, tap, save, no dialog, ever.
 *
 * The in-app rationale is a state of the voice sheet rather than a card on a
 * screen, because there is no screen — the gesture works from wherever you are.
 * Its button raises the system dialog the first time and opens system settings
 * after that; see [RecordAudioPermission.ask].
 */
@Composable
fun rememberRecordAudioPermission(): RecordAudioPermission {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var granted by remember { mutableStateOf(hasRecordAudioPermission(context)) }
    var asked by remember { mutableStateOf(prefs.getBoolean(KEY_ASKED, false)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        granted = isGranted
        asked = true
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
    }

    // Granted from system settings and then brought back — notice on resume,
    // otherwise the sheet keeps asking for something the app already has.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasRecordAudioPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return remember(granted, asked, launcher, context) {
        RecordAudioPermission(
            granted = granted,
            askedBefore = asked,
            ask = {
                if (asked) openAppSettings(context) else launcher.launch(Manifest.permission.RECORD_AUDIO)
            },
        )
    }
}

/**
 * The app's own page in system settings, where the microphone toggle lives.
 *
 * FLAG_ACTIVITY_NEW_TASK because this may be started from a composable holding
 * an application context. A device with the screen missing entirely is a tablet
 * ROM problem and not worth a dialog — the sheet's message still stands.
 */
private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
