package com.monio.notifications

import com.monio.R

/**
 * The key is resolved through an explicit map, never Resources.getIdentifier().
 *
 * A string referenced only by name from the server has no code reference, and
 * shrinkResources on the R8-minified release build would strip it — leaving a
 * notification that works in debug and is empty in release. This `when` IS that
 * code reference.
 */
object LocKeys {
    fun body(key: String?): Int? = when (key) {
        "budget_alert" -> R.string.budget_alert
        else -> null
    }

    fun title(key: String?): Int? = when (key) {
        "budget_alert_title" -> R.string.budget_alert_title
        else -> null
    }
}
