package com.monyx.ui.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monyx.MonyxApp
import com.monyx.R
import com.monyx.sync.AvailableUpdate
import com.monyx.update.NoteItems
import com.monyx.update.UpdateState
import java.util.Locale

/**
 * The only place an update is offered or followed. Settings opens this rather
 * than drawing a second copy of the progress, so there is one set of states to
 * get right.
 *
 * Modal over whatever screen is showing, like the voice sheet: an update is
 * offered at launch, and it is not the keypad's business or the summary's.
 */
@Composable
fun UpdateDialog() {
    val context = LocalContext.current
    val updater = (context.applicationContext as MonyxApp).updater
    val prompt by updater.prompt.collectAsStateWithLifecycle()
    val state by updater.state.collectAsStateWithLifecycle()

    // Back from "Install unknown apps": carry on without asking for a second tap.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updater.resumeIfPermitted() }

    if (!prompt) return
    val update = when (val s = state) {
        is UpdateState.Available -> s.update
        is UpdateState.NeedsPermission -> s.update
        is UpdateState.Downloading -> s.update
        is UpdateState.Installing -> s.update
        is UpdateState.Failed -> s.update
        else -> return
    }
    val busy = state is UpdateState.Downloading || state is UpdateState.Installing

    AlertDialog(
        // A tap outside during a download would look like cancelling it, and
        // it would not be.
        onDismissRequest = { if (!busy) updater.dismiss() },
        title = { Text(stringResource(R.string.update_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.settings_build, update.versionName) +
                        " · " + stringResource(R.string.update_size, megabytes(update.sizeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ReleaseNotes(update)
                Status(state, update)
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available -> TextButton(onClick = { updater.install(update) }) {
                    Text(stringResource(R.string.update_install))
                }
                is UpdateState.Failed -> TextButton(onClick = { updater.install(update) }) {
                    Text(stringResource(R.string.update_retry))
                }
                is UpdateState.NeedsPermission -> TextButton(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }) {
                    Text(stringResource(R.string.update_open_settings))
                }
                else -> Unit
            }
        },
        dismissButton = {
            if (!busy) {
                TextButton(onClick = updater::dismiss) { Text(stringResource(R.string.update_later)) }
            }
        },
    )
}

/**
 * Every skipped release's notes, newest first, one bullet per line. A heading
 * only when there is more than one release.
 */
@Composable
private fun ReleaseNotes(update: AvailableUpdate) {
    val notes = update.notes.map { it to NoteItems.of(it.notes) }.filter { (_, items) -> items.isNotEmpty() }
    if (notes.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        notes.forEach { (note, items) ->
            if (notes.size > 1) {
                Text(
                    stringResource(R.string.settings_build, note.versionName),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEach { item -> Bullet(item) }
            }
        }
    }
}

/** The text hangs off the bullet, so a wrapped line starts under the text, not under the dot. */
@Composable
private fun Bullet(text: String) {
    Row {
        Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Status(state: UpdateState, update: AvailableUpdate) {
    when (state) {
        is UpdateState.Downloading -> {
            val fraction = if (update.sizeBytes > 0) (state.bytes.toFloat() / update.sizeBytes).coerceIn(0f, 1f) else 0f
            Text(stringResource(R.string.update_downloading, (fraction * 100).toInt()), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        is UpdateState.Installing -> {
            Text(stringResource(R.string.update_installing), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is UpdateState.NeedsPermission ->
            Text(stringResource(R.string.update_permission), style = MaterialTheme.typography.bodySmall)
        is UpdateState.Failed -> Text(
            stringResource(
                when (state.reason) {
                    UpdateState.Reason.OFFLINE -> R.string.update_error_offline
                    UpdateState.Reason.UNAVAILABLE -> R.string.update_error_unavailable
                    UpdateState.Reason.CORRUPT -> R.string.update_error_corrupt
                    UpdateState.Reason.NOT_THIS_APP -> R.string.update_error_not_this_app
                    UpdateState.Reason.CANCELLED -> R.string.update_error_cancelled
                    UpdateState.Reason.INSTALL_FAILED -> R.string.update_error_install_failed
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        else -> Unit
    }
}

private fun megabytes(bytes: Long): String = String.format(Locale.getDefault(), "%.1f", bytes / 1_048_576.0)
