package com.monio.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.monio.DeepLink
import com.monio.Destinations
import com.monio.MonioApp
import com.monio.R
import com.monio.sync.SyncWorker
import com.monio.ui.add.AddScreen
import com.monio.ui.add.AddViewModel
import com.monio.ui.budget.BudgetScreen
import com.monio.ui.onboarding.OnboardingScreen
import com.monio.ui.overview.OverviewScreen
import com.monio.ui.settings.SettingsScreen
import com.monio.ui.theme.Palette
import com.monio.ui.transactions.TransactionsScreen

private data class Tab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

private val TABS = listOf(
    Tab(Destinations.OVERVIEW, R.string.nav_overview, Icons.Filled.PieChart),
    Tab(Destinations.TRANSACTIONS, R.string.nav_transactions, Icons.AutoMirrored.Filled.List),
    Tab(Destinations.ADD, R.string.nav_add, Icons.Filled.Add),
    Tab(Destinations.BUDGET, R.string.nav_budget, Icons.Filled.DonutLarge),
    Tab(Destinations.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
fun MonioAppRoot(
    app: MonioApp,
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
    app: MonioApp,
    memberId: String?,
    startDestination: String?,
    deepLink: DeepLink?,
    onDeepLinkHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination
    val context = LocalContext.current

    // Deep-link state for the budget screen, set by a notification tap.
    var budgetCategoryId by remember { mutableStateOf<String?>(null) }
    var budgetPeriod by remember { mutableStateOf<String?>(null) }

    // The filter the transactions screen should be showing. The screen applies
    // it reactively, so the tab may keep its ViewModel across a switch and still
    // pick up a new filter — or have it cleared when the tab is tapped directly.
    var txCategoryId by remember { mutableStateOf<String?>(null) }
    var txPeriod by remember { mutableStateOf<String?>(null) }

    fun openTransactions(categoryId: String?, period: String?) {
        txCategoryId = categoryId
        txPeriod = period
        navController.switchTab(Destinations.TRANSACTIONS)
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
        bottomBar = {
            NavigationBar {
                TABS.forEach { tab ->
                    val selected = currentRoute?.hierarchy?.any { it.route == tab.route } == true
                    val isAdd = tab.route == Destinations.ADD
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            // Tapping the tab itself means "all transactions",
                            // not whatever a budget row filtered to earlier.
                            if (tab.route == Destinations.TRANSACTIONS) {
                                txCategoryId = null
                                txPeriod = null
                            }
                            navController.switchTab(tab.route)
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                        // Add is the one thing the app exists to do, so it is the
                        // one item that is coloured rather than monochrome.
                        colors = if (isAdd) {
                            NavigationBarItemDefaults.colors(
                                selectedIconColor = ADD_ON_ACCENT,
                                selectedTextColor = ADD_ACCENT,
                                unselectedIconColor = ADD_ACCENT,
                                unselectedTextColor = ADD_ACCENT,
                                indicatorColor = ADD_ACCENT,
                            )
                        } else {
                            NavigationBarItemDefaults.colors()
                        },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            // Launching the app lands directly on the numeric keypad. This
            // is the single thing that determines whether the family is still
            // using this in a month.
            startDestination = startDestination ?: Destinations.ADD,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destinations.ADD) {
                val addViewModel: AddViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { AddViewModel(app.repository) }
                    },
                )
                AddScreen(
                    viewModel = addViewModel,
                    memberId = memberId,
                    onSaved = { SyncWorker.enqueue(app) },
                )
            }
            composable(Destinations.OVERVIEW) {
                OverviewScreen(
                    onOpenTransactions = { categoryId, period ->
                        openTransactions(categoryId, period)
                    },
                )
            }
            composable(Destinations.TRANSACTIONS) {
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
}

private val ADD_ACCENT = Palette.color("green")
private val ADD_ON_ACCENT = androidx.compose.ui.graphics.Color.White
