package com.monyx

import androidx.compose.ui.graphics.Color
import com.monyx.data.MonyxRepository
import com.monyx.sync.Rejection
import com.monyx.sync.SyncEngine
import com.monyx.ui.budget.PlanState
import com.monyx.ui.overview.PieSlice
import com.monyx.ui.overview.sliceIdAt
import com.monyx.ui.theme.Palette
import com.monyx.ui.transactions.steppedPeriod
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

    // -------------------------------------------------- account filter

    @Test
    fun `no accounts picked means every account, not none`() {
        assertEquals(
            "an empty selection must mean no filter, not an empty overview",
            1,
            MonyxRepository.allAccounts(emptySet()),
        )
    }

    @Test
    fun `picking accounts turns the filter on`() {
        assertEquals(0, MonyxRepository.allAccounts(setOf("a")))
        assertEquals(0, MonyxRepository.allAccounts(setOf("a", "b")))
    }

    // ---------------------------------------------------- the monthly plan

    private fun plan(planned: Long, assigned: Long, spent: Long = 0) =
        PlanState("2026-08", plannedMinor = planned, hasPlan = true, assignedMinor = assigned, spentMinor = spent)

    @Test
    fun `left to assign is what the plan has not handed out yet`() {
        assertEquals(200_00L, plan(planned = 600_00, assigned = 400_00).leftToAssignMinor)
    }

    @Test
    fun `a fully assigned month is finished, not over`() {
        // Zero is the goal of this screen, so it must not read as an error.
        val finished = plan(planned = 600_00, assigned = 600_00)
        assertEquals(0L, finished.leftToAssignMinor)
        assertEquals("zero assigned-to-spare is not over-assigning", false, finished.overAssigned)
    }

    @Test
    fun `handing out more than exists is over-assigned`() {
        val over = plan(planned = 600_00, assigned = 650_00)
        assertEquals(-50_00L, over.leftToAssignMinor)
        assertEquals(true, over.overAssigned)
    }

    @Test
    fun `left to spend counts every expense, budgeted or not`() {
        // The distinction that matters: 500 was handed out, but 550 was spent —
        // 50 of it in categories with no budget at all. Left to spend must see
        // that, or the headline figure quietly lies.
        val state = plan(planned = 600_00, assigned = 500_00, spent = 550_00)
        assertEquals(100_00L, state.leftToAssignMinor)
        assertEquals(50_00L, state.leftToSpendMinor)
    }

    @Test
    fun `overspending the month goes negative rather than clamping`() {
        assertEquals(-25_00L, plan(planned = 600_00, assigned = 600_00, spent = 625_00).leftToSpendMinor)
    }

    @Test
    fun `an unplanned month has no answer, rather than a wrong one`() {
        // Caught on screen: categories carry their limits forward, so a month
        // nobody has planned yet already has thousands assigned against a total
        // of nothing. Subtracting produced a large red negative on a card whose
        // own subtitle said "no plan yet".
        val carriedForward = PlanState("2026-08", hasPlan = false, assignedMinor = 425_000, spentMinor = 520_300)
        assertNull("no plan means no figure at all", carriedForward.leftToAssignMinor)
        assertNull(carriedForward.leftToSpendMinor)
        assertEquals("an unplanned month must not look over-assigned", false, carriedForward.overAssigned)
    }

    // ------------------------------------------------- what the server kept

    private fun rejection(id: String?, reason: String) = Rejection("month_plans", id, reason)

    @Test
    fun `rows the server did not refuse are accepted`() {
        assertEquals(
            listOf("a", "c"),
            SyncEngine.acceptedIds(listOf("a", "b", "c"), listOf(rejection("b", "bad_period"))),
        )
    }

    @Test
    fun `a refusal that names no row accepts nothing from that table`() {
        // The one that cost a month plan. "unknown_table" carries id = null,
        // because the server never parsed a row to have an id for. Subtracting
        // it by id removes nothing, so the whole batch read as accepted, pending
        // was cleared, and the row existed on exactly one phone forever.
        assertEquals(
            emptyList<String>(),
            SyncEngine.acceptedIds(listOf("a", "b"), listOf(rejection(null, "unknown_table"))),
        )
    }

    @Test
    fun `a table nobody refused is accepted whole`() {
        assertEquals(listOf("a", "b"), SyncEngine.acceptedIds(listOf("a", "b"), emptyList()))
    }

    @Test
    fun `a row sent without an id is not reported back as accepted`() {
        // Nothing can clear a flag on a row that has no id to clear it by.
        assertEquals(listOf("a"), SyncEngine.acceptedIds(listOf("a", null), emptyList()))
    }

    @Test
    fun `icon and colour keys are unique`() {
        assertEquals(Palette.icons.size, Palette.icons.map { it.first }.toSet().size)
        assertEquals(Palette.colors.size, Palette.colors.map { it.first }.toSet().size)
    }

    // The Transactions month switcher. The dropdown it replaced offered the
    // current month and five behind it and nothing else, so a receipt from last
    // spring could not be filtered to at all.

    @Test
    fun `an arrow pressed on all time lands on the month it falls back to`() {
        // Either arrow. There is no month to move away from, so there is no
        // direction to move in — both mean "start somewhere".
        assertEquals("2026-09", steppedPeriod("", -1, fallback = "2026-09"))
        assertEquals("2026-09", steppedPeriod("", 1, fallback = "2026-09"))
    }

    @Test
    fun `a step from a month moves one month`() {
        assertEquals("2026-08", steppedPeriod("2026-09", -1))
        assertEquals("2026-10", steppedPeriod("2026-09", 1))
    }

    @Test
    fun `stepping crosses the year in both directions`() {
        assertEquals("2025-12", steppedPeriod("2026-01", -1))
        assertEquals("2027-01", steppedPeriod("2026-12", 1))
    }

    @Test
    fun `there is no floor on how far back the arrows reach`() {
        // The whole point of replacing the dropdown: 2019 is reachable, which is
        // where "when did we last pay for that?" tends to live.
        assertEquals("2019-03", steppedPeriod("2019-04", -1))
    }

    @Test
    fun `stepping never lands back on all time`() {
        // Blank is a state only the tab itself and Clear filters can produce.
        // An arrow that could reach it would strand the other arrow: from all
        // time both go forwards.
        var period = "2026-09"
        repeat(24) { period = steppedPeriod(period, -1) }
        assertNotEquals("", period)
        assertEquals("2024-09", period)
    }
}
