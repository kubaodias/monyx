package com.monyx

import com.monyx.data.CategoryEntity
import com.monyx.ui.transactions.CategoryDrill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One level of categories at a time, in the editor.
 *
 * The seeded set with two subcategories hung off it — no household ships with
 * any, so Paliwo and Remonty are fixture-only, but they are the whole reason
 * this exists. Sort order is deliberately not the list order here: the roots
 * come back in the order somebody dragged them into, which is a thing the flat
 * list used to get right by accident and this has to get right on purpose.
 */
class CategoryDrillTest {

    private fun category(id: String, name: String, parent: String? = null, order: Int = 0) =
        CategoryEntity(id = id, parentId = parent, name = name, kind = "expense", sortOrder = order)

    private val groceries = category("c-groceries", "Zakupy spożywcze", order = 0)
    private val transport = category("c-transport", "Transport", order = 1)
    private val home = category("c-home", "Dom", order = 2)
    private val fuel = category("c-fuel", "Paliwo", parent = "c-transport", order = 1)
    private val tickets = category("c-tickets", "Bilety", parent = "c-transport", order = 0)
    private val repairs = category("c-repairs", "Remonty", parent = "c-home", order = 0)

    private val ofKind = listOf(home, transport, groceries, fuel, repairs, tickets)

    @Test
    fun `the top level is roots only, in the order they were dragged into`() {
        assertEquals(
            listOf("c-groceries", "c-transport", "c-home"),
            CategoryDrill.shown(ofKind, null).map { it.id },
        )
    }

    /**
     * The parent leads its own family. A parent with children is still
     * somewhere people file to — "Dom" as well as "Dom > Remonty" — and
     * leaving it out would make it unreachable the moment it grew its first
     * child.
     */
    @Test
    fun `a family leads with the parent itself`() {
        assertEquals(
            listOf("c-transport", "c-tickets", "c-fuel"),
            CategoryDrill.shown(ofKind, "c-transport").map { it.id },
        )
    }

    @Test
    fun `only a parent with children drills`() {
        assertTrue(CategoryDrill.hasChildren(ofKind, "c-transport"))
        assertTrue(CategoryDrill.hasChildren(ofKind, "c-home"))
        assertFalse(CategoryDrill.hasChildren(ofKind, "c-groceries"))
        assertFalse(CategoryDrill.hasChildren(ofKind, "c-fuel"))
    }

    /** Editing a row already filed under Dom > Remonty opens on Dom, so the
     *  answer to "where is this?" is the first thing on screen. */
    @Test
    fun `the dialog opens on the family the row is already in`() {
        assertEquals("c-home", CategoryDrill.openOn(ofKind, "c-repairs"))
        assertEquals("c-transport", CategoryDrill.openOn(ofKind, "c-fuel"))
    }

    @Test
    fun `a row filed at the top level opens at the top level`() {
        assertNull(CategoryDrill.openOn(ofKind, "c-home"))
        assertNull(CategoryDrill.openOn(ofKind, "c-groceries"))
        assertNull(CategoryDrill.openOn(ofKind, null))
    }

    /** A category that has since been deleted or renamed out of this kind's
     *  list must not leave the row showing an empty family. */
    @Test
    fun `an unknown selection falls back rather than showing nothing`() {
        assertNull(CategoryDrill.openOn(ofKind, "c-gone"))
        assertEquals(
            CategoryDrill.shown(ofKind, null).map { it.id },
            CategoryDrill.shown(ofKind, "c-gone").map { it.id },
        )
    }
}
