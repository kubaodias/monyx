package com.monio

import androidx.compose.ui.graphics.Color
import com.monio.ui.overview.PieSlice
import com.monio.ui.overview.sliceIdAt
import com.monio.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The parts of the newly interactive UI that are decidable without a device.
 *
 * Tapping a pie slice and reading a subcategory's colour are both things an
 * emulator verifies badly — a mis-tap and a correct-but-wrong-slice hit look
 * identical in a screenshot — so the arithmetic behind them is tested here.
 */
class InteractionTest {

    private fun slice(id: String, minor: Long) = PieSlice(id, id, minor, Color.Black)

    // Three equal slices: each owns exactly 120 degrees, clockwise from twelve.
    private val thirds = listOf(slice("a", 100), slice("b", 100), slice("c", 100))

    @Test
    fun `twelve o'clock lands on the first slice`() {
        assertEquals("a", sliceIdAt(thirds, 300, 0f))
    }

    @Test
    fun `each third owns its own arc`() {
        assertEquals("a", sliceIdAt(thirds, 300, 119f))
        assertEquals("b", sliceIdAt(thirds, 300, 121f))
        assertEquals("c", sliceIdAt(thirds, 300, 241f))
    }

    @Test
    fun `a boundary belongs to the slice that starts there, not the one that ends`() {
        assertEquals("b", sliceIdAt(thirds, 300, 120f))
        assertEquals("c", sliceIdAt(thirds, 300, 240f))
    }

    @Test
    fun `the far end of the ring stays on the last slice rather than falling through`() {
        // Sweeps are floats and need not sum to exactly 360. Landing in that gap
        // must not return null: the ring is fully covered as far as the user is
        // concerned, and a dead pixel at 359.99 reads as a broken chart.
        assertEquals("c", sliceIdAt(thirds, 300, 359.999f))
    }

    @Test
    fun `a slice worth nothing is never the answer`() {
        val withEmpty = listOf(slice("real", 100), slice("empty", 0))
        assertEquals("real", sliceIdAt(withEmpty, 100, 0f))
        assertEquals("real", sliceIdAt(withEmpty, 100, 359.99f))
    }

    @Test
    fun `an empty chart is not tappable`() {
        assertNull(sliceIdAt(emptyList(), 0, 10f))
        assertNull(sliceIdAt(thirds, 0, 10f))
    }

    // ------------------------------------------------------------- colours

    @Test
    fun `a subcategory takes its parent's colour`() {
        val parent = Palette.colorForChild(childColor = "teal", parentColor = null, parentId = null, ownId = "p")
        val child = Palette.colorForChild(childColor = "coral", parentColor = "teal", parentId = "p", ownId = "c")
        assertEquals("a child must not keep a colour of its own", parent, child)
    }

    @Test
    fun `a root category keeps its own colour`() {
        assertEquals(
            Palette.color("violet"),
            Palette.colorForChild(childColor = "violet", parentColor = null, parentId = null, ownId = "p"),
        )
    }

    @Test
    fun `a parent with no colour still shares one deterministic colour with its children`() {
        // Both fall back to a hash, and the child must hash on the PARENT's id —
        // hashing on its own would split the family across two colours.
        val parent = Palette.colorForChild(null, null, null, "parent-id")
        val child = Palette.colorForChild(null, null, "parent-id", "child-id")
        assertEquals(parent, child)
    }

    @Test
    fun `different families still get different colours`() {
        val one = Palette.colorForChild("teal", null, null, "a")
        val two = Palette.colorForChild("coral", null, null, "b")
        assertNotEquals(one, two)
    }

    // ------------------------------------------------------------- keypad

    @Test
    fun `every icon key resolves to its own vector rather than the fallback`() {
        // A key written to a synced row must never stop resolving. "other" is
        // excluded on purpose: it IS the fallback vector, deliberately, so it is
        // the one key this check cannot distinguish.
        val fallback = Palette.icon("definitely-not-a-key")
        for ((key, _) in Palette.icons.filterNot { it.first == "other" }) {
            assertNotEquals("icon key '$key' silently fell back", fallback, Palette.icon(key))
        }
    }

    @Test
    fun `the icon set is distinct enough to be worth browsing`() {
        // The picker was widened from sixteen to fifty-odd; a duplicate vector
        // would be an invisible copy-paste slip in a long literal list.
        val vectors = Palette.icons.map { it.second }
        assertEquals("two keys share one icon", vectors.size, vectors.toSet().size)
    }

    @Test
    fun `icon and colour keys are unique`() {
        assertEquals(Palette.icons.size, Palette.icons.map { it.first }.toSet().size)
        assertEquals(Palette.colors.size, Palette.colors.map { it.first }.toSet().size)
    }
}
