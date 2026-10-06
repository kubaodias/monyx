package com.monyx.ui.add

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The bottom bar's middle button, lent to the keypad while the keypad is up.
 *
 * The add screen used to carry two full-width controls stacked at the bottom of
 * the one screen in the app that is short of vertical space: its own 62dp save
 * bar, and directly below it the navigation bar with a green "Dodaj" button on
 * it. Two buttons, a few millimetres apart, about the same transaction — and
 * the lower one was the tab you were already on, so it did nothing at all. The
 * bar's button becomes the save button instead: a tick and "Zapisz" for as long
 * as the keypad is on screen, and the 62dp goes to the category grid.
 *
 * This is a holder rather than a parameter because the two ends are composed in
 * different places — [com.monyx.ui.MonyxNav] draws the bar, the NavHost draws
 * the keypad inside it — and the question "can this be saved yet" belongs to the
 * screen that knows what is missing, not to the bar. The nav owns the instance,
 * hands it down, and draws whatever it finds in it.
 *
 * [active] goes false whenever the keypad is not what is being looked at: a
 * different tab, or the repeating-rule editor open over it with a save button of
 * its own. The bar then goes back to being the Add tab, because a button
 * labelled "Zapisz" that commits the form behind an open editor is worse than
 * no button at all.
 */
@Stable
class AddSaveSlot {

    /** Whether the bar's middle button is currently the keypad's save button. */
    var active: Boolean by mutableStateOf(false)
        private set

    /**
     * Whether a tap would write anything. False is the ordinary state of a
     * freshly opened keypad — no amount typed, no category picked — and the bar
     * draws it greyed, the way the save bar it replaced was greyed.
     */
    var enabled: Boolean by mutableStateOf(false)
        private set

    /**
     * Not a state object. It is rewritten on every composition of the screen,
     * it is never read during one, and holding it as state would recompose the
     * bar on every keystroke for a lambda whose identity nothing cares about.
     */
    private var action: () -> Unit = {}

    /** Called by the keypad, from a SideEffect, with what the bar should do. */
    fun offer(enabled: Boolean, onSave: () -> Unit) {
        this.enabled = enabled
        action = onSave
        active = true
    }

    /** Called when the keypad leaves, or goes behind the rule editor. */
    fun withdraw() {
        active = false
        enabled = false
        action = {}
    }

    /**
     * The tap. Checked here as well as in the bar, because the bar reads two
     * states and runs a lambda between them: a tap that arrives in the frame
     * after a save has already reset the draft must not write a second row.
     */
    fun save() {
        if (active && enabled) action()
    }
}
