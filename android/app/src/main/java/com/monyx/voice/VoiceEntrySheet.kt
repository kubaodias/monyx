package com.monyx.voice

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monyx.R
import com.monyx.data.Dates
import com.monyx.data.AccountEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.TransactionEntity
import com.monyx.data.Money
import com.monyx.ui.add.EntryKind
import com.monyx.ui.theme.Palette
import com.monyx.ui.transactions.EditTransactionDialog
import com.monyx.ui.transactions.TransactionEdit
import java.time.LocalDate

/**
 * What the microphone did, in the fields it decided.
 *
 * A sheet rather than a snackbar, and with no timeout. A timed message is a
 * race against reading it, and the whole reason the row is written before it is
 * confirmed is that the confirmation can then be read at leisure — with Revert
 * sitting next to it for as long as it takes.
 *
 * Every field the parser decided is on it, because that is what makes a
 * mis-parse diagnosable: the amount, the category drawn the way the grid draws
 * it, the account and the day, and underneath, quietly, the sentence that was
 * actually heard. When the category is wrong the transcript usually says why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceEntrySheet(
    state: VoiceEntryState,
    categories: List<VoiceCategory>,
    accounts: List<VoiceAccount>,
    editing: TransactionEntity?,
    editableCategories: List<CategoryEntity>,
    editableAccounts: List<AccountEntity>,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
    onGrantPermission: () -> Unit,
    onRevert: () -> Unit,
    onUndoRevert: () -> Unit,
    onCorrectionHoldStart: () -> Unit,
    onCorrectionHoldEnd: () -> Unit,
    onChooseCategory: (VoiceCategory) -> Unit,
    onBeginEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onEdit: (TransactionEdit) -> Unit,
) {
    if (state is VoiceEntryState.Hidden) return

    val sheetState = rememberModalBottomSheetState()

    // The eyes may not be on the screen — that is the entire point of the
    // gesture — so the write is reported by feel as well. One pulse started the
    // listen; this is the distinct one that says a row now exists.
    val haptics = LocalHapticFeedback.current
    val savedId = (state as? VoiceEntryState.Saved)?.summary?.transactionId
    LaunchedEffect(savedId) {
        if (savedId != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            when (state) {
                is VoiceEntryState.Hidden -> Unit

                is VoiceEntryState.NeedsPermission -> Message(
                    title = stringResource(R.string.voice_permission_needed),
                    action = stringResource(R.string.voice_permission_grant),
                    onAction = onGrantPermission,
                )

                is VoiceEntryState.Listening -> ListeningBody(
                    partial = state.partial,
                    onStop = onStop,
                )

                is VoiceEntryState.NotUnderstood -> Message(
                    title = stringResource(R.string.voice_not_understood),
                    heard = state.transcript,
                    action = stringResource(R.string.voice_done),
                    onAction = onDismiss,
                )

                is VoiceEntryState.Incomplete -> Message(
                    title = stringResource(R.string.voice_partial_finish),
                    heard = state.transcript,
                    action = stringResource(R.string.voice_done),
                    onAction = onDismiss,
                )

                is VoiceEntryState.Failed -> Message(
                    title = stringResource(failureMessage(state.reason)),
                    action = stringResource(R.string.voice_done),
                    onAction = onDismiss,
                )

                is VoiceEntryState.SaveFailed -> Message(
                    title = stringResource(R.string.voice_save_failed),
                    heard = state.transcript,
                    action = stringResource(R.string.voice_done),
                    onAction = onDismiss,
                )

                is VoiceEntryState.Reverted -> {
                    Header(stringResource(R.string.voice_reverted))
                    Spacer(Modifier.height(8.dp))
                    Amount(state.summary.amountMinor, state.summary.kind, faded = true)
                    Spacer(Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onUndoRevert, modifier = Modifier.weight(1f)) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.voice_undo_revert))
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.voice_done))
                        }
                    }
                }

                is VoiceEntryState.Saved -> SavedBody(
                    state = state,
                    categories = categories,
                    accounts = accounts,
                    onRevert = onRevert,
                    onChange = onBeginEdit,
                    onDone = onDismiss,
                    onStop = onStop,
                    onCorrectionHoldStart = onCorrectionHoldStart,
                    onCorrectionHoldEnd = onCorrectionHoldEnd,
                    onChooseCategory = onChooseCategory,
                )
            }
        }
    }

    // Change, by tap: the dialog the transactions list already uses. There is
    // deliberately no second editor.
    //
    // It handles amount, category, account, note and date, and it does NOT
    // handle the kind — which the spoken grammar does. The asymmetry is
    // deliberate on both sides: the dialog refuses the kind because switching
    // it invalidates the category already chosen, and the spoken correction
    // allows it because "przychód, wypłata" re-chooses both in one breath.
    //
    // It does not reopen the keypad prefilled. The row already exists;
    // prefilling the keypad would offer to write a second one.
    editing?.let { row ->
        EditTransactionDialog(
            original = row,
            categories = editableCategories,
            accounts = editableAccounts,
            onDismiss = onCancelEdit,
            onSave = onEdit,
        )
    }
}

@Composable
private fun ListeningBody(partial: String, onStop: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = stringResource(R.string.voice_listening),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    Spacer(Modifier.height(16.dp))
    Text(
        // Live text while the sentence is still being said, and an example
        // before there is any. An empty panel gives no clue what may be said.
        text = partial.ifBlank { stringResource(R.string.voice_say_example) },
        style = MaterialTheme.typography.bodyLarge,
        color = if (partial.isBlank()) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
    Spacer(Modifier.height(24.dp))
    // Not only for the finger that is holding the bar. TalkBack's long-press
    // action starts a listen that no finger will ever end, and this is what
    // ends it.
    Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.voice_stop))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedBody(
    state: VoiceEntryState.Saved,
    categories: List<VoiceCategory>,
    accounts: List<VoiceAccount>,
    onRevert: () -> Unit,
    onChange: () -> Unit,
    onDone: () -> Unit,
    onStop: () -> Unit,
    onCorrectionHoldStart: () -> Unit,
    onCorrectionHoldEnd: () -> Unit,
    onChooseCategory: (VoiceCategory) -> Unit,
) {
    val summary = state.summary
    val category = categories.firstOrNull { it.id == summary.categoryId }
    val listening = state.correction as? CorrectionState.Listening

    Header(stringResource(R.string.voice_saved))
    Spacer(Modifier.height(8.dp))
    Amount(summary.amountMinor, summary.kind, faded = false)

    if (category != null) {
        Spacer(Modifier.height(12.dp))
        CategoryChip(category = category, categories = categories, onClick = null)
    }

    Spacer(Modifier.height(10.dp))
    Text(
        text = "${accounts.firstOrNull { it.id == summary.accountId }?.name.orEmpty()} · ${dayLabel(summary.date)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(10.dp))
    Text(
        text = stringResource(R.string.voice_heard, summary.transcript),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    when (val correction = state.correction) {
        is CorrectionState.Idle -> if (state.note == SavedNote.Updated) {
            Note(stringResource(R.string.voice_updated))
        }
        is CorrectionState.Listening -> Note(
            correction.partial.ifBlank { stringResource(R.string.voice_listening) },
        )
        is CorrectionState.NotUnderstood -> Note(stringResource(R.string.voice_correction_not_understood))
        is CorrectionState.Ambiguous -> {
            Note(stringResource(R.string.voice_correction_which))
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                correction.candidates.forEach { candidate ->
                    CategoryChip(
                        category = candidate,
                        categories = categories,
                        onClick = { onChooseCategory(candidate) },
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onRevert, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.voice_revert))
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onChange, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.voice_change))
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onDone, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.voice_done))
        }
    }

    Spacer(Modifier.height(12.dp))
    // The same gesture as the one that started all this, on a button that is
    // here rather than a microphone that stayed open. A mic left hot after a
    // write costs battery on a screen somebody is already looking at, puts a
    // permanent recording indicator in the status bar, and is a claim about a
    // household's shopping that this app should not be making.
    CorrectionMic(
        listening = listening != null,
        onHoldStart = onCorrectionHoldStart,
        onHoldEnd = onCorrectionHoldEnd,
        onStop = onStop,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CorrectionMic(
    listening: Boolean,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onStop: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val label = stringResource(R.string.voice_correct_hold)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (listening) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .combinedClickable(
                onClick = { if (listening) onStop() },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onHoldStart()
                },
                onLongClickLabel = label,
            )
            .holdToTalk(enabled = true, onRelease = onHoldEnd)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = if (listening) stringResource(R.string.voice_stop) else label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Header(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Note(text: String) {
    Spacer(Modifier.height(10.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Amount(amountMinor: Long, kind: EntryKind, faded: Boolean) {
    Text(
        text = Money.formatWithCurrency(amountMinor),
        fontSize = 40.sp,
        fontWeight = FontWeight.Light,
        color = when {
            faded -> MaterialTheme.colorScheme.onSurfaceVariant
            kind == EntryKind.Income -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        },
    )
}

/** Drawn exactly as the keypad's grid draws it, colour inherited from the
 *  parent, so a family of categories reads as one group in both places. */
@Composable
private fun CategoryChip(
    category: VoiceCategory,
    categories: List<VoiceCategory>,
    onClick: (() -> Unit)?,
) {
    val parent = categories.firstOrNull { it.id == category.parentId }
    val tint = Palette.colorForChild(category.color, parent?.color, category.parentId, category.id)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(tint.copy(alpha = 0.16f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Palette.icon(category.icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = category.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Message(
    title: String,
    heard: String? = null,
    action: String,
    onAction: () -> Unit,
) {
    Text(text = title, style = MaterialTheme.typography.titleMedium)
    if (!heard.isNullOrBlank()) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.voice_heard, heard),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onAction,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(action)
    }
}

private fun failureMessage(reason: ListenFailure): Int = when (reason) {
    ListenFailure.NoService -> R.string.voice_no_service
    ListenFailure.NoPermission -> R.string.voice_permission_needed
    ListenFailure.NoSpeech -> R.string.voice_no_speech
    ListenFailure.Network -> R.string.voice_offline
    ListenFailure.LanguageUnavailable -> R.string.voice_language_unavailable
    // Busy, Audio and the codes nobody can act on: the microphone is somebody
    // else's for the moment, and the honest thing to say is that nothing was
    // heard rather than to name a framework error.
    ListenFailure.Busy, ListenFailure.Audio, ListenFailure.Other -> R.string.voice_no_speech
}

@Composable
private fun dayLabel(date: LocalDate): String = when (date) {
    Dates.today() -> stringResource(R.string.add_today)
    Dates.today().minusDays(1) -> stringResource(R.string.add_yesterday)
    else -> Dates.dayLabel(date.toString())
}
