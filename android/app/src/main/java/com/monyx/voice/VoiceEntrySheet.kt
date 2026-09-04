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
import com.monyx.ui.transactions.EditTransactionSheet
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
    onCorrectionHoldStart: () -> Unit,
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

                // The four that take themselves away. No button on any of
                // them: a control that vanishes under a thumb reaching for it
                // is worse than no control, and none of these four is asking
                // anything anyway.
                is VoiceEntryState.NotUnderstood ->
                    Message(stringResource(R.string.voice_not_understood))

                is VoiceEntryState.Incomplete ->
                    Message(stringResource(R.string.voice_partial_finish))

                is VoiceEntryState.Failed ->
                    Message(stringResource(failureMessage(state.reason)))

                is VoiceEntryState.SaveFailed ->
                    Message(stringResource(R.string.voice_save_failed))

                is VoiceEntryState.Saved -> SavedBody(
                    state = state,
                    categories = categories,
                    accounts = accounts,
                    onRevert = onRevert,
                    onEditField = onBeginEdit,
                    onDone = onDismiss,
                    onStop = onStop,
                    onCorrectionHoldStart = onCorrectionHoldStart,
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
        EditTransactionSheet(
            original = row,
            categories = editableCategories,
            accounts = editableAccounts,
            onDismiss = onCancelEdit,
            onSave = onEdit,
            // Deleting from the editor and tapping Revert are the same write —
            // a tombstone on the row this sheet is describing — so they land in
            // the same place, and that place closes the sheet. Anything else
            // would leave the summary describing a row that no longer exists.
            onDelete = { onRevert() },
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
    // Live text while the sentence is still being said, and NOTHING before
    // there is any. There used to be a worked example here — a whole sentence
    // to read at the one moment the person is already talking, teaching a
    // phrasing the parser does not actually require. It taught it once and then
    // sat there for every listen afterwards.
    if (partial.isNotBlank()) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = partial,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(24.dp))
    // The listen does not end when the finger comes off the bar — it ends when
    // the recogniser hears the sentence finish, or when one of the two clocks
    // runs out. This is how somebody says "I am done" before either, and it is
    // the ONLY way to end a listen TalkBack started, whose long-click action
    // has no release at all.
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
    onEditField: () -> Unit,
    onDone: () -> Unit,
    onStop: () -> Unit,
    onCorrectionHoldStart: () -> Unit,
    onChooseCategory: (VoiceCategory) -> Unit,
) {
    val summary = state.summary
    val category = categories.firstOrNull { it.id == summary.categoryId }
    val listening = state.correction as? CorrectionState.Listening

    // Every field on this sheet opens the editor, and the whole row is the
    // target. There is no heading above them and no pencil beside them: the
    // sheet only ever appears because something was just written, so "Zapisano"
    // said nothing the figures underneath it were not already saying, and five
    // pencils down the right-hand side turned a summary into a form. This is the entire "change it" affordance now: a Popraw button
    // beside Cofnij and Gotowe said only "something here is wrong" and then
    // made you find it again in a dialog, where the value that is wrong is
    // already on screen and already the thing being looked at.
    //
    // The dialog opens on all five fields rather than on the one that was
    // tapped. EditTransactionDialog is an AlertDialog with a scrolling Column
    // and no focus plumbing, and threading a target field through it would mean
    // reshaping a dialog two other screens depend on for a convenience.
    Spacer(Modifier.height(4.dp))
    EditableField(label = stringResource(R.string.voice_edit_amount), onClick = onEditField) {
        Amount(summary.amountMinor, summary.kind, faded = false)
    }

    EditableField(label = stringResource(R.string.voice_edit_category), onClick = onEditField) {
        if (category != null) {
            CategoryChip(category = category, categories = categories, onClick = null)
        } else {
            FieldText(stringResource(R.string.add_needs_category), muted = true)
        }
    }

    EditableField(label = stringResource(R.string.voice_edit_account), onClick = onEditField) {
        FieldText(
            "${accounts.firstOrNull { it.id == summary.accountId }?.name.orEmpty()} · ${dayLabel(summary.date)}",
            muted = false,
        )
    }

    // Tappable with nothing in it, because adding one is the main reason to
    // reach for this field at all.
    EditableField(
        label = if (summary.note.isBlank()) {
            stringResource(R.string.voice_add_note)
        } else {
            stringResource(R.string.voice_edit_note)
        },
        onClick = onEditField,
    ) {
        FieldText(
            summary.note.ifBlank { stringResource(R.string.voice_add_note) },
            muted = summary.note.isBlank(),
        )
    }

    when (val correction = state.correction) {
        is CorrectionState.Idle -> Unit
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
        Spacer(Modifier.width(12.dp))
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
        onStop = onStop,
    )
}

/**
 * One value on the summary, and the whole width of it is the way into the
 * editor.
 *
 * Nothing marks it as pressable, which is deliberate: a summary of four lines
 * with a pencil on every one of them reads as a form to fill in rather than as
 * a receipt to glance at, and the receipt is what this is. The affordance is
 * that everything on the sheet behaves the same way.
 *
 * The label is not drawn either — the values say what they are at a glance
 * ("200,00 zł", a category chip, "Gotówka · Dziś"). It is carried as
 * `onClickLabel`, so it is still exactly what TalkBack announces the row's
 * action as, and losing the glyph costs the screen reader nothing.
 */
@Composable
private fun EditableField(
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick, onClickLabel = label)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun FieldText(text: String, muted: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (muted) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CorrectionMic(
    listening: Boolean,
    onHoldStart: () -> Unit,
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
            // Hold to start, tap to stop. The release is not an end here for
            // the same reason it is not one on the bar: a thumb resting on a
            // sheet while somebody speaks drifts, and dropping the correction
            // silently is worse than waiting for the endpointer.
            .combinedClickable(
                onClick = { if (listening) onStop() },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onHoldStart()
                },
                onLongClickLabel = label,
            )
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

/**
 * A message, and a button only when there is something to decide.
 *
 * With [action] left out this is the whole of a dead end: the text appears, it
 * is read, and VoiceEntryViewModel takes the sheet away a couple of seconds
 * later. Nothing to tap, and nothing left on screen to tap at.
 */
@Composable
private fun Message(
    title: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Text(text = title, style = MaterialTheme.typography.titleMedium)
    if (action != null) {
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
            Text(action)
        }
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
