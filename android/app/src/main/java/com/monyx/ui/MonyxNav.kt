package com.monyx.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.monyx.DeepLink
import com.monyx.Destinations
import com.monyx.MonyxApp
import com.monyx.R
import com.monyx.sync.SyncWorker
import com.monyx.ui.add.AddScreen
import com.monyx.ui.add.AddViewModel
import com.monyx.ui.budget.BudgetScreen
import com.monyx.ui.onboarding.OnboardingScreen
import com.monyx.ui.overview.OverviewScreen
import com.monyx.ui.settings.SettingsScreen
import com.monyx.ui.theme.MonyxMark
import com.monyx.ui.theme.Palette
import com.monyx.ui.transactions.TransactionsScreen
import com.monyx.ui.update.UpdateDialog
import com.monyx.voice.EdgeNoteWriter
import com.monyx.voice.RepositoryVoiceLedger
import com.monyx.voice.SpeechListener
import com.monyx.voice.VoiceEntrySheet
import com.monyx.voice.VoiceEntryState
import com.monyx.voice.VoiceEntryViewModel
import com.monyx.voice.rememberRecordAudioPermission

private data class Tab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

/**
 * The height NavigationBar gives its own row, restated because the Add item is
 * drawn by hand and has to match the four items it stands beside.
 *
 * It cannot be fillMaxHeight(). NavigationBar constrains its row with
 * defaultMinSize, NOT a fixed height, so maxHeight arrives as the whole screen
 * and a child that fills it drags the bar up with it — the bar becomes the
 * screen, and the other four icons centre themselves in the middle of it. That
 * shipped once; the bounds were [441,0][640,2337] against [0,1064][200,1274]
 * for its neighbours.
 */
private val NavigationBarHeight = 80.dp

private val TABS = listOf(
    Tab(Destinations.OVERVIEW, R.string.nav_overview, MonyxMark),
    Tab(Destinations.TRANSACTIONS, R.string.nav_transactions, Icons.AutoMirrored.Filled.List),
    Tab(Destinations.ADD, R.string.nav_add, Icons.Filled.Add),
    Tab(Destinations.BUDGET, R.string.nav_budget, Icons.Filled.DonutLarge),
    Tab(Destinations.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
fun MonyxAppRoot(
    app: MonyxApp,
    enrolled: Boolean?,
    memberId: String?,
    startDestination: String?,
    deepLink: DeepLink?,
    onDeepLinkHandled: () -> Unit,
) {
    // null means "still reading DataStore" — showing nothing for one frame beats
    // flashing the onboarding screen at an already-enrolled family.
    if (enrolled == null) {
        Box(Modifier.fillMaxSize())
        return
    }

    var isEnrolled by remember(enrolled) { mutableStateOf(enrolled) }

    if (!isEnrolled) {
        OnboardingScreen(
            onEnrolled = {
                isEnrolled = true
                // The Activity read isEnrolled() ONCE into a produceState, so the
                // flag it is watching never flips and its LaunchedEffect never
                // re-fires. Scheduling here is what stops a freshly joined phone
                // from sitting empty until someone force-closes the app.
                SyncWorker.onEnrolled(app)
            },
        )
        return
    }

    MainScaffold(
        app = app,
        memberId = memberId,
        startDestination = startDestination,
        deepLink = deepLink,
        onDeepLinkHandled = onDeepLinkHandled,
    )
}

/**
 * Switching tabs, everywhere. Jumping from Budget into a filtered Transactions
 * list has to use this too.
 *
 * A bare navigate() would PUSH transactions onto the budget tab's own back
 * stack, and `restoreState` would then faithfully restore [budget, transactions]
 * the next time the budget tab was tapped — landing the user on transactions
 * every time, which is exactly the reported bug.
 */
private fun NavController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun MainScaffold(
    app: MonyxApp,
    memberId: String?,
    startDestination: String?,
    deepLink: DeepLink?,
    onDeepLinkHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination
    val route = currentRoute?.route
    val context = LocalContext.current

    // Hoisted out of the nav graph on purpose. Scoped to the back stack entry it
    // would be destroyed and recreated at times nobody can predict from the
    // screen — here its lifetime is the app's, and the ONE thing that clears it
    // is the explicit discard below.
    val addViewModel: AddViewModel = viewModel(
        factory = viewModelFactory {
            initializer { AddViewModel(app.repository) }
        },
    )

    // Hoisted for the same reason, and one more: the gesture that drives it is
    // on the bottom bar, which outlives every destination in the graph.
    //
    // No Context is handed to it. It cannot enqueue sync and must not learn
    // how — that arrives as the same lambda AddScreen and TransactionsScreen
    // are given, from here, where the application already is.
    val voiceViewModel: VoiceEntryViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                VoiceEntryViewModel(
                    ledger = RepositoryVoiceLedger(app.repository),
                    recogniser = SpeechListener(app),
                    onSyncRequested = { SyncWorker.enqueue(app) },
                    // Asked after the row is written and only while the summary
                    // is on screen. Offline it answers null on the first connect
                    // attempt and nothing downstream notices.
                    noteWriter = EdgeNoteWriter(app.session),
                )
            }
        },
    )
    val voiceState by voiceViewModel.state.collectAsStateWithLifecycle()
    val voiceCategories by voiceViewModel.categories.collectAsStateWithLifecycle()
    val voiceAccounts by voiceViewModel.accounts.collectAsStateWithLifecycle()
    val voiceEditing by voiceViewModel.editing.collectAsStateWithLifecycle()
    val editableCategories by voiceViewModel.editableCategories.collectAsStateWithLifecycle()
    val editableAccounts by voiceViewModel.editableAccounts.collectAsStateWithLifecycle()
    val microphone = rememberRecordAudioPermission()

    // Granted from the system dialog the long press raised, or from settings.
    // The sheet is asking for something the app now has, so it has nothing left
    // to say — closing it is more honest than leaving a stale request on screen.
    LaunchedEffect(microphone.granted, voiceState) {
        if (microphone.granted && voiceState is VoiceEntryState.NeedsPermission) {
            voiceViewModel.dismiss()
        }
    }

    // The recogniser is asked for the interface language, and the grammar reads
    // the same one — so a phone switched to English understands "add 200 to
    // transport" and nothing has to guess.
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()

    // False on a phone with no recognition service at all, and then the gesture
    // simply is not there: no explanation, no dead long press. memberId is the
    // other half — a row needs an author, and the session has not been read for
    // the first frame or two after launch.
    val recognitionAvailable = remember { SpeechListener.isAvailable(app) }

    // Deep-link state for the budget screen, set by a notification tap.
    var budgetCategoryId by remember { mutableStateOf<String?>(null) }
    var budgetPeriod by remember { mutableStateOf<String?>(null) }

    // The filter the transactions screen should be showing. The screen applies
    // it reactively, so the tab may keep its ViewModel across a switch and still
    // pick up a new filter — or have it cleared when the tab is tapped directly.
    var txCategoryId by remember { mutableStateOf<String?>(null) }
    var txPeriod by remember { mutableStateOf<String?>(null) }

    // Which tab drilled INTO the transactions list, if any. Tapping a pie slice
    // is a question asked from the overview, so back has to answer it by
    // returning there — not by falling through to the start destination, which
    // is what a bottom bar does by default and is the reported bug: back from a
    // slice landed on the keypad.
    var txOrigin by remember { mutableStateOf<String?>(null) }

    // Every path that selects a tab goes through here, the long press
    // included. Two copies of the filter reset will diverge, and the way it
    // shows is a bottom-bar tap that lands on a list still filtered to one
    // category from a budget row somebody tapped ten minutes ago.
    fun selectTab(route: String) {
        if (route == Destinations.TRANSACTIONS) {
            txCategoryId = null
            txPeriod = null
            txOrigin = null
        }
        navController.switchTab(route)
    }

    fun openTransactions(categoryId: String?, period: String?) {
        txCategoryId = categoryId
        txPeriod = period
        // Asked of the controller, not read from `route` above. The graph's
        // destination lambdas are remembered from an early composition, so a
        // captured `route` is whatever was current when the graph was built —
        // "add", typically — and back then returned to the keypad, which is the
        // very bug this is here to fix. currentDestination is read at the tap.
        txOrigin = navController.currentDestination?.route
            ?.takeIf { it != Destinations.TRANSACTIONS }
        navController.switchTab(Destinations.TRANSACTIONS)
    }

    // A half-typed expense belongs to the visit that started it. Anywhere but
    // the keypad and it is gone, so the next visit starts at zero.
    LaunchedEffect(route) {
        if (route != null && route != Destinations.ADD) {
            addViewModel.discardDraft()
        }
    }

    // A sentence the parser could not finish. It writes nothing and hands over
    // what it did get; the keypad finishes the job by hand.
    //
    // The order of these two lines does not matter, and it is worth knowing
    // why: discardDraft() runs on ARRIVAL at a route that is not Add, so
    // navigating TO the keypad never throws a prefill away.
    LaunchedEffect(voiceViewModel) {
        voiceViewModel.handoff.collect { spoken ->
            addViewModel.prefillFromVoice(spoken)
            selectTab(Destinations.ADD)
        }
    }

    LaunchedEffect(deepLink) {
        when (deepLink) {
            is DeepLink.Budget -> {
                budgetCategoryId = deepLink.categoryId
                budgetPeriod = deepLink.period
                navController.switchTab(Destinations.BUDGET)
                onDeepLinkHandled()
            }
            null -> Unit
        }
    }

    Scaffold(
        // Everywhere except the keypad, where the bottom right is already the
        // save button and a second circle over it would be a mis-tap waiting
        // to happen. Hides itself entirely when no number is configured.
        floatingActionButton = {
            if (route != Destinations.ADD) CallAssistantButton()
        },
        bottomBar = {
            NavigationBar {
                TABS.forEach { tab ->
                    val selected = currentRoute?.hierarchy?.any { it.route == tab.route } == true
                    val isAdd = tab.route == Destinations.ADD
                    if (isAdd) {
                        AddTabItem(
                            selected = selected,
                            voiceEnabled = recognitionAvailable && memberId != null,
                            onClick = { selectTab(tab.route) },
                            onHoldStart = {
                                if (microphone.granted) {
                                    voiceViewModel.startListening(languageTag, memberId)
                                } else {
                                    voiceViewModel.permissionRequired()
                                    // Raised once, automatically, at the exact
                                    // moment somebody reached for the feature.
                                    // After that the sheet asks in words, and
                                    // its button goes to system settings —
                                    // a second automatic launch is a dialog
                                    // Android will not draw.
                                    if (!microphone.askedBefore) microphone.ask()
                                }
                            },
                        )
                        return@forEach
                    }
                    // The logo, and it stays the logo's own weight of black
                    // whether or not the tab is selected — the mark is an
                    // identity, not a state. onSurface rather than a literal
                    // black so it is still there in the dark theme, where a
                    // hard-coded #000 would be a hole in the bar.
                    val isMark = tab.route == Destinations.OVERVIEW
                    NavigationBarItem(
                        selected = selected,
                        onClick = { selectTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                        colors = when {
                            isMark -> NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurface,
                            )
                            else -> NavigationBarItemDefaults.colors()
                        },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            // Launching the app lands on the summary: where the money went
            // this month, what is left, what was entered last. Opening a ledger
            // is a question far more often than it is an entry, and the keypad
            // answered the rarer one by putting a blank amount in front of
            // somebody who came to look something up.
            //
            // Entering is still one tap — the middle of the bottom bar — and
            // zero from the home screen: the launcher long-press shortcut
            // passes start=add, which is what `startDestination` carries here.
            startDestination = startDestination ?: Destinations.OVERVIEW,
            // consumeWindowInsets, THEN imePadding. The window is edge to edge,
            // so `adjustResize` in the manifest does nothing on API 30+ and the
            // keyboard simply draws over the bottom of the screen — which is
            // where the note field and the save button live. imePadding lifts
            // them; consumeWindowInsets(padding) is what stops it adding the
            // navigation bar's height a second time, since the Scaffold has
            // already accounted for it in `padding`.
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            composable(Destinations.ADD) {
                AddScreen(
                    viewModel = addViewModel,
                    memberId = memberId,
                    // Straight to the ledger, unfiltered, so the row just
                    // entered is on screen as proof it landed. Both saves come
                    // through here: a repeating rule materialises its first
                    // occurrence before calling back, so there is a row to see.
                    onSaved = {
                        SyncWorker.enqueue(app)
                        selectTab(Destinations.TRANSACTIONS)
                    },
                )
            }
            composable(Destinations.OVERVIEW) {
                OverviewScreen(
                    onOpenTransactions = { categoryId, period ->
                        openTransactions(categoryId, period)
                    },
                    onSyncRequested = { SyncWorker.enqueue(app) },
                )
            }
            composable(Destinations.TRANSACTIONS) {
                // Declared INSIDE the destination, not beside the Scaffold. The
                // back dispatcher runs the most recently registered enabled
                // callback first, and NavHost registers its own when the host
                // composes — a handler declared above it is added earlier and
                // never sees the press. That is why the first attempt at this
                // still landed on the keypad.
                BackHandler(enabled = txOrigin != null) {
                    val origin = txOrigin
                    txOrigin = null
                    txCategoryId = null
                    txPeriod = null
                    origin?.let { navController.switchTab(it) }
                }
                TransactionsScreen(
                    filterCategoryId = txCategoryId,
                    filterPeriod = txPeriod,
                    onSyncRequested = { SyncWorker.syncNow(context) },
                )
            }
            composable(Destinations.BUDGET) {
                BudgetScreen(
                    initialCategoryId = budgetCategoryId,
                    initialPeriod = budgetPeriod,
                    onOpenCategoryTransactions = { categoryId, period ->
                        openTransactions(categoryId, period)
                    },
                )
            }
            composable(Destinations.SETTINGS) { SettingsScreen() }
        }
    }

    // Beside the Scaffold, not inside a destination: a long press from the
    // overview should not yank anybody to the keypad, so the sheet is modal
    // over whatever screen they were already on.
    VoiceEntrySheet(
        state = voiceState,
        categories = voiceCategories,
        accounts = voiceAccounts,
        editing = voiceEditing,
        editableCategories = editableCategories,
        editableAccounts = editableAccounts,
        onDismiss = voiceViewModel::dismiss,
        onStop = voiceViewModel::stopListening,
        onGrantPermission = microphone.ask,
        onRevert = voiceViewModel::revert,
        onCorrectionHoldStart = { voiceViewModel.startCorrecting(languageTag) },
        onChooseCategory = voiceViewModel::chooseCategory,
        onBeginEdit = voiceViewModel::beginEdit,
        onCancelEdit = voiceViewModel::cancelEdit,
        onEdit = { edit ->
            voiceViewModel.applyEdit(
                amountMinor = edit.amountMinor,
                categoryId = edit.categoryId,
                accountId = edit.accountId,
                note = edit.note,
                occurredAtMs = edit.occurredAtMs,
            )
        },
    )

    // Last, so it draws above the voice sheet if both are ever up at once.
    UpdateDialog()
}

/**
 * The Add tab, drawn by hand rather than by NavigationBarItem.
 *
 * The gesture is the reason. NavigationBarItem has no long press and cannot be
 * given one from outside: Material applies its own Modifier.selectable to the
 * item's root, INSIDE whatever modifier is passed in, so an outer gesture sits
 * above the node that actually consumes the press. Hanging the gesture on
 * AddIcon's pill instead would work and would be worse — the pill is 64x32dp
 * inside a touch target a fifth of the screen wide and the full height of the
 * bar, so a long press on the label, or on the dead space above and below the
 * pill, would silently just select the tab. For a one-handed gesture in a shop
 * that is a target far smaller than the finger believes it is pressing.
 *
 * Almost nothing of NavigationBarItem was being used here anyway: this item
 * already overrode its colours and drew its own indicator. combinedClickable
 * brings the tap, the ripple, the long press and its TalkBack action with it.
 *
 * The long press STARTS a listen and nothing here ends one. The release used to,
 * and it was wrong: a phone half out of a pocket is not held still for the
 * length of a sentence, and a thumb that shifts is not somebody saying they
 * have finished. The recogniser's own endpointing ends it, or one of the two
 * clocks in VoiceEntryViewModel does, or the sheet's Stop button — which is
 * also the only thing that can end a listen TalkBack started, since its
 * long-click action has no release either.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.AddTabItem(
    selected: Boolean,
    voiceEnabled: Boolean,
    onClick: () -> Unit,
    onHoldStart: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val holdLabel = stringResource(R.string.voice_hold_to_talk)
    Box(
        modifier = Modifier
            .weight(1f)
            .height(NavigationBarHeight)
            .combinedClickable(
                role = Role.Tab,
                onLongClickLabel = holdLabel.takeIf { voiceEnabled },
                onLongClick = if (!voiceEnabled) {
                    null
                } else {
                    {
                        // The one signal that the microphone is live, and it
                        // has to be felt rather than seen: the phone is half
                        // out of a pocket and the eyes are on the shelf.
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onHoldStart()
                    }
                },
                onClick = onClick,
            )
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AddIcon()
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.nav_add),
                color = ADD_ACCENT,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * The Add tab's icon, in a filled green pill that does not wait to be selected.
 *
 * A green glyph on the bar was still a glyph among four other glyphs — the same
 * size, the same weight, one hue apart. This is the tab the app exists for and
 * it is now the only filled shape down there, which is the difference between
 * "coloured differently" and "visible".
 *
 * 64x32 is Material's own active-indicator size, so the pill sits exactly where
 * the other tabs' selection indicators sit and the row of five reads as one row.
 */
@Composable
private fun AddIcon() {
    Box(
        modifier = Modifier
            .width(64.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ADD_ACCENT),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = ADD_ON_ACCENT)
    }
}

private val ADD_ACCENT = Palette.color("green")
private val ADD_ON_ACCENT = Color.White
