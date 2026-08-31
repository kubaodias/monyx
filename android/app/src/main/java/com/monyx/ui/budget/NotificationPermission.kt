package com.monyx.ui.budget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.monyx.R

private const val PREFS_NAME = "notification_permission"
private const val KEY_ASKED = "asked_post_notifications"

private fun hasPostNotificationsPermission(context: Context): Boolean {
    // Below API 33 the permission does not exist; notifications just work.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

/**
 * Requests POST_NOTIFICATIONS on first visit to the Budget screen, Android 13+
 * only. This must NEVER be called from the Add screen — the
 * five-second rule forbids a permission dialog in that path. Without this
 * permission every push is dropped silently: the server sees a successful
 * delivery and the phone shows nothing.
 *
 * Asks once automatically on first visit; after that, a small rationale card
 * offers an explicit "enable" action instead of re-prompting with the system
 * dialog on every visit.
 */
@Composable
fun NotificationPermissionGate(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var granted by remember { mutableStateOf(hasPostNotificationsPermission(context)) }
    var alreadyAsked by remember { mutableStateOf(prefs.getBoolean(KEY_ASKED, false)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        granted = isGranted
        alreadyAsked = true
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
    }

    // Ask exactly once, automatically, on first visit.
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted && !alreadyAsked) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // If the user grants it from system Settings and comes back, notice on resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = hasPostNotificationsPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val showRationale by derivedStateOf {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted
    }

    if (showRationale) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.NotificationsNone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.notifications_permission_rationale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text(stringResource(R.string.notifications_enable))
                }
            }
        }
    }
}
