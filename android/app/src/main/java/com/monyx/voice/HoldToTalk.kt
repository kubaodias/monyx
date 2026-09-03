package com.monyx.voice

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Reports the lift, which is the half of a long press `clickable` does not have.
 *
 * `combinedClickable` gives the gesture its ripple, its haptic and its TalkBack
 * long-click action, and it is deliberately left in place — but `onLongClick`
 * fires while the finger is still down and nothing in `clickable` ever reports
 * the finger coming off. Recording has to stop somewhere, and "somewhere" is
 * the lift, because that is the only unambiguous "I have finished speaking" a
 * person gives you without looking at the screen.
 *
 * It is a pointer-up and nothing more, so it fires on an ORDINARY TAP too —
 * this modifier cannot see whether a long press ever happened. Deciding
 * whether a lift ends anything belongs to whoever knows if a listen is running,
 * and `VoiceEntryViewModel.stopListening` is where that guard lives.
 *
 * So this watches the same pointer stream and consumes nothing at all: it takes
 * the down with requireUnconsumed = false on the Initial pass, then waits on
 * the Final pass until no pointer is left pressed. The button underneath goes
 * on behaving exactly as Material wrote it, tap included.
 *
 * Dragging off the item is NOT a cancel, and that is a decision rather than an
 * omission. A thumb resting on the bottom bar for three seconds while somebody
 * speaks WILL drift, and `tryAwaitRelease()` returning false would throw the
 * sentence away with no sound, no buzz and nothing on screen. Leaving the item
 * stops the recording and delivers it, the same as lifting; Revert on the
 * summary is the undo, and it can be seen.
 */
fun Modifier.holdToTalk(enabled: Boolean, onRelease: () -> Unit): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
            } while (event.changes.any { it.pressed })
            onRelease()
        }
    }
