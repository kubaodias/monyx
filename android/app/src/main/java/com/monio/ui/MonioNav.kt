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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.monio.DeepLink
import com.monio.Destinations
import com.monio.MonioApp
import com.monio.R
import com.monio.ui.add.AddScreen
import com.monio.ui.add.AddViewModel
import com.monio.ui.budget.BudgetScreen
import com.monio.ui.onboarding.OnboardingScreen
import com.monio.ui.overview.OverviewScreen
import com.monio.ui.settings.SettingsScreen
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
        OnboardingScreen(onEnrolled = { isEnrolled = true })
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

    // Deep-link state for the budget screen, set by a notification tap (§8).
    var budgetCategoryId by remember { mutableStateOf<String?>(null) }
    var budgetPeriod by remember { mutableStateOf<String?>(null) }
    var txCategoryId by remember { mutableStateOf<String?>(null) }
    var txPeriod by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(deepLink) {
        when (deepLink) {
            is DeepLink.Budget -> {
                budgetCategoryId = deepLink.categoryId
                budgetPeriod = deepLink.period
                navController.navigate(Destinations.BUDGET) { launchSingleTop = true }
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
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            // Launching the app lands directly on the numeric keypad (§9). This
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
                    onSaved = { com.monio.sync.SyncWorker.enqueue(app) },
                )
            }
            composable(Destinations.OVERVIEW) {
                OverviewScreen(
                    onOpenTransaction = { navController.navigate(Destinations.TRANSACTIONS) },
                    onSeeAllTransactions = {
                        txCategoryId = null
                        txPeriod = null
                        navController.navigate(Destinations.TRANSACTIONS)
                    },
                )
            }
            composable(Destinations.TRANSACTIONS) {
                TransactionsScreen(
                    initialCategoryId = txCategoryId,
                    initialPeriod = txPeriod,
                )
            }
            composable(Destinations.BUDGET) {
                BudgetScreen(
                    initialCategoryId = budgetCategoryId,
                    initialPeriod = budgetPeriod,
                    onOpenCategoryTransactions = { categoryId, period ->
                        txCategoryId = categoryId
                        txPeriod = period
                        navController.navigate(Destinations.TRANSACTIONS)
                    },
                )
            }
            composable(Destinations.SETTINGS) { SettingsScreen() }
        }
    }
}
