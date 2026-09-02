package com.monyx.ui.settings

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import kotlin.math.abs

/**
 * A column whose rows can be dragged into a different order.
 *
 * Long press to pick a row up, drag, let go. No handle: the rows already carry
 * three icon buttons each, and a fourth grip would take width from the name to
 * offer something a long press already offers. Long press is also what stops a
 * reorder from competing with the tap that opens the editor.
 *
 * Rows swap under the finger rather than at the end, so what is on screen
 * during the drag is the order that will be saved. [onReorder] fires ONCE, when
 * the finger lifts — a write per swap would put a dozen rows through Room and
 * the sync queue for one gesture.
 *
 * Deliberately not a `LazyColumn`: these lists are a handful of rows inside a
 * card that is itself inside a scrolling page, and a lazy list nested in a
 * scroll has no height to measure against.
 *
 * @param keyOf stable identity per item, so the drag survives the reordering
 *   of the list underneath it.
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    keyOf: (T) -> String,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (item: T, dragging: Boolean) -> Unit,
) {
    // What a drag left behind, as KEYS — never as items.
    //
    // Remembering the items themselves is what made an edit invisible: a
    // remembered copy only refreshes when the identities change, and adding a
    // subcategory does not change the list of root ids, so the roots on screen
    // kept the children they were built with and the new row never appeared.
    // The same held for a rename, a recoloured icon and a balance that moved.
    // Keys carry the order; the content always comes from [items].
    //
    // Null for all but a second or two: the order comes from the database
    // except between the finger lifting and Room catching up, which is the one
    // moment the list would otherwise snap back to where it started.
    var draggedOrder by remember { mutableStateOf<List<String>?>(null) }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Heights by position, filled in as the rows are measured. A row is
    // exchanged with its neighbour once it has travelled half of that
    // neighbour's height, which is the point at which the two have visually
    // crossed.
    val heights = remember { mutableStateListOf<Int>() }

    // The gesture reads these long after the composition that started it: the
    // pointerInput block is keyed by identity alone, so it does not restart
    // when the list underneath changes.
    val latest by rememberUpdatedState(items)

    // Once the database agrees with the finger, stop overriding it — otherwise
    // an order arriving from another phone could never win.
    LaunchedEffect(items) {
        if (draggedOrder == items.map(keyOf)) draggedOrder = null
    }

    fun ordered(): List<T> {
        val fresh = latest
        val order = draggedOrder ?: return fresh
        val byKey = fresh.associateBy(keyOf)
        // A row added or removed while the override stands makes it meaningless
        // — better the database's order with everything in it than a stale one
        // missing a row.
        if (byKey.keys != order.toSet()) return fresh
        return order.mapNotNull(byKey::get)
    }

    val display = ordered()

    Column(modifier = modifier) {
        display.forEachIndexed { index, item ->
            val key = keyOf(item)
            val dragging = key == draggingKey
            // key(), or the whole thing silently fails to save.
            //
            // Without it a Column identifies its children by POSITION, so a
            // swap hands the node at index 0 a different item — and with it a
            // different pointerInput key, which cancels the gesture coroutine
            // mid-drag. onDragEnd never runs, so the rows visibly reorder on
            // screen and nothing is ever written. With key() the node moves
            // with its item and the gesture survives the swap that caused it.
            key(key) {
                Box(
                    modifier = Modifier
                        // Above its neighbours while it is in the air, or the row
                        // it is passing paints over the top of it.
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) offsetY else 0f }
                        .onSizeChanged { size ->
                            while (heights.size <= index) heights.add(0)
                            heights[index] = size.height
                        }
                        // Keyed by identity, NOT by index: keyed by index, every
                        // swap would restart the gesture detector and drop the
                        // finger that was mid-drag.
                        .pointerInput(key) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingKey = key
                                    offsetY = 0f
                                },
                                onDragEnd = {
                                    draggingKey = null
                                    offsetY = 0f
                                    onReorder(ordered())
                                },
                                onDragCancel = {
                                    draggingKey = null
                                    offsetY = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    offsetY += amount.y
                                    val now = ordered()
                                    val from = now.indexOfFirst { keyOf(it) == key }
                                    if (from < 0) return@detectDragGesturesAfterLongPress
                                    val to = if (offsetY > 0) from + 1 else from - 1
                                    if (to !in now.indices) return@detectDragGesturesAfterLongPress
                                    val neighbour = heights.getOrNull(to)?.takeIf { it > 0 }
                                        ?: return@detectDragGesturesAfterLongPress
                                    if (abs(offsetY) < neighbour / 2f) {
                                        return@detectDragGesturesAfterLongPress
                                    }
                                    draggedOrder = now.map(keyOf)
                                        .toMutableList()
                                        .apply { add(to, removeAt(from)) }
                                    // The row has moved a whole neighbour up or
                                    // down, so the finger is that much less ahead
                                    // of it than it was.
                                    offsetY -= if (to > from) neighbour.toFloat() else -neighbour.toFloat()
                                },
                            )
                        },
                ) {
                    row(item, dragging)
                }
            }
        }
    }
}
