package com.monio

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The language picker, in one place.
 *
 * Selection goes through AppCompatDelegate rather than a preference of our own.
 * On API 33+ that forwards to the framework LocaleManager, so the choice also
 * shows up under Settings › System › Languages and survives a reinstall; below
 * 33 AppCompat stores it itself, which is what the autoStoreLocales meta-data
 * in the manifest switches on. Keeping our own copy in DataStore would give two
 * sources of truth that drift the first time someone changes it from system
 * settings instead of from inside the app.
 *
 * Setting a locale recreates the Activity — that is AppCompat's doing, and it
 * is what makes every already-composed string re-read.
 */
object Locales {
    /**
     * Every tag with a values-<tag>/ directory. Adding a language means adding
     * it here, in res/xml/locales_config.xml, and as res/values-<tag>/ — miss
     * the first and it cannot be picked, miss the second and the system
     * language list will not show it.
     */
    val SUPPORTED = listOf("en", "pl")

    /** The chosen language tag, or null when following the system. */
    fun current(): String? =
        AppCompatDelegate.getApplicationLocales()
            .toLanguageTags()
            .takeIf { it.isNotEmpty() }
            ?.substringBefore(',')
            ?.substringBefore('-')

    /** Pass null to hand the choice back to the system. */
    fun apply(tag: String?) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag),
        )
    }
}
