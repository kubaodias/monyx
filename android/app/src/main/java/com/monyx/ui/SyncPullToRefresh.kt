package com.monyx.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long the spinner stays after the gesture, with no relation to the sync.
 *
 * [com.monyx.sync.SyncWorker] is fire-and-forget — enqueueing it returns
 * immediately and nothing composable can observe its result — so this is an
 * acknowledgement of the pull, not a progress bar. Rows and figures arrive on
 * their own when the pull lands, through the flows the screens already collect.
 *
 * Long enough to be seen and read as "heard you"; short enough that it is gone
 * before anyone waits on it. A spinner tied to the worker would be better and
 * is not available at this layer.
 */
private const val ACKNOWLEDGEMENT_MS = 1200L

/**
 * The pull-to-refresh gesture, identical on every screen that shows money.
 *
 * One composable rather than the same dozen lines three times. The three
 * screens differ in what they draw and not at all in what the gesture means,
 * and the timing constant above is exactly the kind of thing that gets tuned in
 * one copy and left in the other two — after which the same pull feels
 * different depending on which tab you are on, for no reason anybody can see.
 *
 * Wrap the scrolling content, not the whole screen: the month switcher stays
 * pinned above this on all three, and a switcher that slid down under a
 * spinner would be the one control on screen saying which month the figures
 * belong to, moving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncPullToRefresh(
    onSyncRequested: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    content: @Composable () -> Unit,
) {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            onSyncRequested()
            scope.launch {
                delay(ACKNOWLEDGEMENT_MS)
                refreshing = false
            }
        },
        modifier = modifier,
    ) {
        content()
    }
}
