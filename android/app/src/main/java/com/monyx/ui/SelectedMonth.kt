package com.monyx.ui

import com.monyx.data.Dates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one month the whole app is looking at.
 *
 * Overview, Transactions and Budget used to hold a month each, so stepping back
 * to July on the overview and then opening the budget showed September — three
 * controls that look identical, sit in the same place and disagree. They are not
 * three questions about three months; they are three views of one month, and
 * this is where that month lives.
 *
 * Scoped to the application, not to a ViewModel or a back stack entry: tab
 * switches destroy and rebuild those, and a shared selection has to outlive
 * every one of them. It is deliberately NOT persisted — a fresh launch starts
 * on the current month, because the first thing anyone wants after opening a
 * budgeting app tomorrow morning is tomorrow's budget, not the month they were
 * auditing last week.
 */
class SelectedMonth(initial: String = Dates.currentPeriod()) {

    private val _period = MutableStateFlow(initial)
    val period: StateFlow<String> = _period.asStateFlow()

    /** Ignores blanks: callers pass a deep link's period straight through. */
    fun set(period: String?) {
        if (!period.isNullOrBlank()) _period.value = period
    }

    fun shift(months: Long) {
        _period.value = Dates.shiftPeriod(_period.value, months)
    }
}
