package com.monyx.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monyx.BuildConfig
import com.monyx.Locales
import com.monyx.MonyxApp
import com.monyx.R
import com.monyx.data.Dates
import com.monyx.data.MemberEntity
import com.monyx.ui.theme.Palette
import kotlinx.coroutines.launch

private const val BACKUP_STALE_MS = 3L * 24 * 60 * 60 * 1000

/**
 * What each tab holds, and the order they appear in.
 *
 * Settings had grown to eleven cards on one scroll — accounts, categories,
 * every repeating rule, the household, the invite code, language, sync, backup,
 * re-upload, build. Finding the invite code meant scrolling past the rent.
 *
 * Four groups, by what the person is there to do rather than by what the code
 * calls things: set the app up, describe the month's fixed shape, deal with
 * other people, and the levers pulled once and then left alone.
 *
 * Language has a tab of its own rather than a card under General. It is named
 * for what is actually in it: Region would be naming the tab for a currency
 * setting that [0004] says will not exist — the currency is fixed and the
 * timezone is the household's, neither of them a preference. If that decision
 * is ever reversed, renaming one string is the whole cost.
 */
private enum class SettingsTab(val labelRes: Int) {
    General(R.string.settings_tab_general),
    Recurring(R.string.settings_tab_recurring),
    People(R.string.settings_tab_people),
    Language(R.string.settings_tab_language),
    Advanced(R.string.settings_tab_advanced),
}

/**
 * Settings holds accounts, categories, repeating rules, members, invite code
 * and sync status, plus the three items that make the design operable: the
 * count of rejected rows, last_backup_at from the server, and re-upload
 * everything. They are split across [SettingsTab].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as MonyxApp
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))

    val accounts by viewModel.accountRows.collectAsStateWithLifecycle(initialValue = emptyList())
    val categoryGroups by viewModel.categoryGroups.collectAsStateWithLifecycle(initialValue = emptyList())
    val members by viewModel.members.collectAsStateWithLifecycle(initialValue = emptyList())
    val allCategories by viewModel.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val recurringRules by viewModel.recurringRules.collectAsStateWithLifecycle(initialValue = emptyList())
    val syncState by viewModel.syncState.collectAsStateWithLifecycle(initialValue = null)
    val rejectedCount by viewModel.rejectedCount.collectAsStateWithLifecycle(initialValue = 0)
    val inviteState by viewModel.inviteState.collectAsStateWithLifecycle()
    val reuploadRequested by viewModel.reuploadRequested.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    var showReuploadConfirm by remember { mutableStateOf(false) }
    val copiedLabel = stringResource(R.string.settings_invite_copied)
    val reuploadDoneLabel = stringResource(R.string.settings_reupload_done)

    LaunchedEffect(reuploadRequested) {
        if (reuploadRequested) {
            snackbarHostState.showSnackbar(reuploadDoneLabel)
            viewModel.acknowledgeReupload()
        }
    }

    var tab by rememberSaveable { mutableStateOf(SettingsTab.General) }
    // Non-null while the repeating-rule editor is open. A seed rather than a
    // nullable rule, because "add" and "edit rule X" are both open states and
    // only one of them has a rule behind it.
    var editor by remember { mutableStateOf<RuleSeed?>(null) }

    editor?.let { seed ->
        RecurringEditor(
            seed = seed,
            accounts = accounts.filter { it.entity.archived == 0 }.map { it.entity },
            categories = allCategories,
            onDismiss = { editor = null },
            onSave = { draft ->
                val ruleId = seed.ruleId
                if (ruleId == null) {
                    viewModel.addRecurringRule(draft)
                } else {
                    viewModel.updateRecurringRule(ruleId, draft)
                }
                editor = null
            },
        )
        return
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(stringResource(R.string.nav_settings)) })
                // Scrollable rather than fixed: four Polish labels do not fit
                // four equal columns on a narrow phone, and a fixed TabRow
                // answers that by shrinking the text until it wraps mid-word.
                ScrollableTabRow(
                    selectedTabIndex = tab.ordinal,
                    edgePadding = 12.dp,
                    divider = {},
                ) {
                    SettingsTab.entries.forEach { option ->
                        Tab(
                            selected = tab == option,
                            onClick = { tab = option },
                            text = { Text(stringResource(option.labelRes), maxLines = 1) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (tab) {
                SettingsTab.General -> {
                    item {
                        AccountsSection(
                            accounts = accounts,
                            onAdd = viewModel::addAccount,
                            onUpdate = viewModel::updateAccount,
                            onArchive = viewModel::setAccountArchived,
                            onDelete = viewModel::deleteAccount,
                            onReorder = viewModel::reorderAccounts,
                        )
                    }
                    item {
                        CategoriesSection(
                            groups = categoryGroups,
                            onAdd = viewModel::addCategory,
                            onUpdate = viewModel::updateCategory,
                            onDelete = viewModel::deleteCategory,
                            onReorder = viewModel::reorderCategories,
                        )
                    }
                }

                SettingsTab.Recurring -> item {
                    RecurringSection(
                        rules = recurringRules,
                        hasAccounts = accounts.any { it.entity.archived == 0 },
                        onOpen = { editor = it?.let(RuleSeed::of) ?: RuleSeed() },
                        onDelete = viewModel::deleteRecurringRule,
                    )
                }

                SettingsTab.People -> {
                    item { MembersSection(members = members) }
                    item {
                        InviteSection(
                            state = inviteState,
                            onGenerate = viewModel::createInvite,
                            onCopy = { code ->
                                clipboardManager.setText(AnnotatedString(code))
                                coroutineScope.launch { snackbarHostState.showSnackbar(copiedLabel) }
                            },
                        )
                    }
                }

                SettingsTab.Language -> item { LanguageSection() }

                SettingsTab.Advanced -> {
                    item {
                        SyncSection(
                            lastSyncAt = syncState?.lastSyncAt ?: 0,
                            rejectedCount = rejectedCount,
                            onSyncNow = viewModel::syncNow,
                        )
                    }
                    item { BackupSection(lastBackupAt = syncState?.lastBackupAt ?: 0) }
                    item { ReuploadSection(onReupload = { showReuploadConfirm = true }) }
                    item { BuildIdentitySection() }
                }
            }
        }
    }

    if (showReuploadConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_reupload_confirm_title),
            message = stringResource(R.string.settings_reupload_description),
            confirmLabel = stringResource(R.string.settings_reupload),
            onConfirm = {
                showReuploadConfirm = false
                viewModel.reuploadEverything()
            },
            onDismiss = { showReuploadConfirm = false },
        )
    }
}

@Composable
private fun MembersSection(members: List<MemberEntity>) {
    SectionCard(title = stringResource(R.string.settings_members), icon = Icons.Filled.Groups) {
        if (members.isEmpty()) {
            Text(
                stringResource(R.string.settings_no_members),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            members.forEach { member ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(member.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(
                            R.string.settings_member_since,
                            Dates.dayLabel(Dates.localDate(member.createdAt)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun InviteSection(
    state: InviteUiState,
    onGenerate: () -> Unit,
    onCopy: (String) -> Unit,
) {
    SectionCard(title = stringResource(R.string.settings_invite), icon = Icons.Filled.PersonAdd) {
        Button(onClick = onGenerate, enabled = state !is InviteUiState.Loading) {
            Text(stringResource(R.string.settings_invite_create))
        }
        when (state) {
            is InviteUiState.Loading -> {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_invite_loading))
                }
            }
            is InviteUiState.Error -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_invite_error),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            is InviteUiState.Success -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_invite_code),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.code,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { onCopy(state.code) }) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.settings_invite_copy),
                        )
                    }
                }
                Text(
                    stringResource(R.string.settings_invite_expires),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            InviteUiState.Idle -> Unit
        }
    }
}

@Composable
private fun SyncSection(lastSyncAt: Long, rejectedCount: Int, onSyncNow: () -> Unit) {
    SectionCard(title = stringResource(R.string.settings_sync), icon = Icons.Filled.Sync) {
        Text(
            if (lastSyncAt > 0) {
                stringResource(R.string.settings_last_sync, Dates.dayLabel(Dates.localDate(lastSyncAt)))
            } else {
                stringResource(R.string.settings_never_synced)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (rejectedCount > 0) {
            Text(
                stringResource(R.string.settings_rejected_rows, rejectedCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSyncNow) {
            Text(stringResource(R.string.settings_sync_now))
        }
    }
}

@Composable
private fun BackupSection(lastBackupAt: Long) {
    val stale = lastBackupAt == 0L || System.currentTimeMillis() - lastBackupAt > BACKUP_STALE_MS
    SectionCard(title = stringResource(R.string.settings_backup), icon = Icons.Filled.CloudUpload) {
        Text(
            if (lastBackupAt > 0) {
                stringResource(R.string.settings_last_backup, Dates.dayLabel(Dates.localDate(lastBackupAt)))
            } else {
                stringResource(R.string.settings_backup_unknown)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (stale) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.settings_backup_stale),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ReuploadSection(onReupload: () -> Unit) {
    SectionCard(title = stringResource(R.string.settings_reupload)) {
        Text(
            stringResource(R.string.settings_reupload_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onReupload) {
            Text(stringResource(R.string.settings_reupload))
        }
    }
}

@Composable
private fun LanguageSection() {
    // AppCompatDelegate is the single source of truth and is read fresh on each
    // composition rather than mirrored into state: selecting a language
    // recreates the Activity, so a remembered copy would only ever be the value
    // that was already replaced.
    val selected = Locales.current()

    SectionCard(title = stringResource(R.string.settings_language), icon = Icons.Filled.Language) {
        LanguageRow(
            label = stringResource(R.string.settings_language_system),
            selected = selected == null,
            onSelect = { Locales.apply(null) },
        )
        Locales.SUPPORTED.forEach { tag ->
            // An explicit map, never Resources.getIdentifier(): a string reached
            // only by a name built at runtime has no code reference, and
            // shrinkResources strips it from the release build.
            val label = when (tag) {
                "pl" -> stringResource(R.string.language_pl)
                else -> stringResource(R.string.language_en)
            }
            LanguageRow(
                label = label,
                selected = selected == tag,
                onSelect = { Locales.apply(tag) },
            )
        }
    }
}

/** selectable() carries the single-choice semantics; the tick is only paint. */
@Composable
private fun LanguageRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun BuildIdentitySection() {
    SectionCard(title = stringResource(R.string.settings_app_version)) {
        Text(
            stringResource(R.string.settings_build, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A calm, card-based section with a title and optional leading icon, in the
 *  spirit of 1Money: rounded surfaces, generous whitespace (top-level rule). */
@Composable
internal fun SectionCard(
    title: String,
    icon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                trailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

/**
 * A scrollable grid, not a side-scrolling strip.
 *
 * With fifty-odd icons a single row is unusable: the ones past the fourth are
 * invisible, and nothing about the strip suggests there are forty more. A grid
 * shows most of them at once and scrolls vertically like the rest of the dialog.
 */
@Composable
internal fun IconSwatchRow(selected: String?, onSelect: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 48.dp),
        // Bounded because this lives inside an AlertDialog, which offers a
        // child infinite height and would crash a lazy grid asked to fill it.
        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(Palette.icons, key = { it.first }) { (key, vector) ->
            val isSelected = key == selected
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    )
                    .border(
                        width = if (isSelected) 2.dp else 0.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    )
                    .clickable { onSelect(key) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    vector,
                    contentDescription = key,
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
internal fun ColorSwatchRow(selected: String?, onSelect: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 44.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = 104.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(Palette.colors, key = { it.first }) { (key, color) ->
            val isSelected = key == selected
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color, CircleShape)
                    .border(
                        width = if (isSelected) 3.dp else 0.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape,
                    )
                    .clickable { onSelect(key) },
            )
        }
    }
}
