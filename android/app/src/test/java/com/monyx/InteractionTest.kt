package com.monyx

import androidx.compose.ui.graphics.Color
import com.monyx.data.AccountEntity
import com.monyx.data.MonyxRepository
import com.monyx.sync.Rejection
import com.monyx.sync.SyncEngine
import com.monyx.ui.add.AddSaveSlot
import com.monyx.ui.budget.PlanState
import com.monyx.ui.overview.OverviewViewModel
import com.monyx.ui.overview.PieSlice
import com.monyx.ui.overview.sliceIdAt
import com.monyx.ui.theme.Palette
import com.monyx.ui.transactions.TransactionsViewModel
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
    fun `a subcategory with no colour of its own takes its parent's`() {
        val parent = Palette.colorForChild(childColor = "teal", parentColor = null, parentId = null, ownId = "p")
        val child = Palette.colorForChild(childColor = null, parentColor = "teal", parentId = "p", ownId = "c")
        assertEquals("an uncoloured child belongs to its family", parent, child)
    }

    @Test
    fun `a subcategory that HAS been given a colour keeps it`() {
        // The other half of "by default". Without this the colour picker in the
        // subcategory dialog is a control that silently does nothing.
        val parent = Palette.colorForChild(childColor = "teal", parentColor = null, parentId = null, ownId = "p")
        val child = Palette.colorForChild(childColor = "coral", parentColor = "teal", parentId = "p", ownId = "c")
        assertNotEquals(parent, child)
        assertEquals(Palette.color("coral"), child)
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

    private fun toggle(current: Set<String>, id: String) = OverviewViewModel.toggledAccounts(
        current = current,
        id = id,
        defaults = setOf("portfel", "poduszka"),
        archived = setOf("wakacje"),
    )

    @Test
    fun `an account kept out of the summary is switched on as an extra`() {
        // Portfel and Poduszka are the default view; PZU is savings held elsewhere.
        assertEquals(setOf("portfel", "poduszka", "pzu", "wakacje"), toggle(emptySet(), "pzu"))
    }

    @Test
    fun `switching the extra off again returns to the default view`() {
        assertEquals(emptySet<String>(), toggle(setOf("portfel", "poduszka", "pzu", "wakacje"), "pzu"))
    }

    @Test
    fun `a default account switched off narrows the view and drops the archived fund`() {
        assertEquals(setOf("poduszka"), toggle(emptySet(), "portfel"))
        assertEquals(setOf("poduszka", "pzu"), toggle(setOf("portfel", "poduszka", "pzu", "wakacje"), "portfel"))
    }

    @Test
    fun `turning the last one off returns to the default view`() {
        assertEquals(emptySet<String>(), toggle(setOf("pzu"), "pzu"))
    }

    @Test
    fun `an archived account kept out of the summary never rides along`() {
        // A trip account can be both: finished AND paid for from outside the
        // household. It has no button — archived accounts get none — so if it
        // rode along with the counted archived fund, switching PZU on would put
        // its spending into the figures with nothing on screen able to take it
        // back out. Only accounts that COUNT are passed as `archived`, so the
        // set below is what the view model hands over.
        val switchedOn = OverviewViewModel.toggledAccounts(
            current = emptySet(),
            id = "pzu",
            defaults = setOf("portfel", "poduszka"),
            archived = setOf("wakacje"),
        )
        assertEquals(setOf("portfel", "poduszka", "pzu", "wakacje"), switchedOn)
        assertEquals("a trip nobody in the house paid for must stay out", false, "tatry" in switchedOn)
    }

    // ------------------------------------------- what the ledger can filter by

    private val ledgerAccounts = listOf(
        AccountEntity(id = "portfel", name = "Portfel"),
        AccountEntity(id = "pzu", name = "PZU", excludedFromSummary = 1),
        AccountEntity(id = "tatry", name = "Tatry 2026", archived = 1, excludedFromSummary = 1),
    )

    @Test
    fun `the ledger does not offer an archived account`() {
        assertEquals(
            listOf("portfel", "pzu"),
            TransactionsViewModel.filterableAccounts(ledgerAccounts, null).map { it.id },
        )
    }

    @Test
    fun `an account outside the summary is still offered`() {
        // Excluded is about whose money it is, archived about whether it is
        // finished with. Only the second one takes an account off this list.
        val offered = TransactionsViewModel.filterableAccounts(ledgerAccounts, null)
        assertEquals("savings held elsewhere are still a ledger you can read", true, offered.any { it.id == "pzu" })
    }

    @Test
    fun `an archived account arriving from settings stays in the list`() {
        // Settings is the only route to it, and it lands with the filter already
        // set. Leave it out and the chip names an account its own dropdown does
        // not contain — a filter with no way back to "all".
        assertEquals(
            listOf("portfel", "pzu", "tatry"),
            TransactionsViewModel.filterableAccounts(ledgerAccounts, "tatry").map { it.id },
        )
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

    @Test
    fun `a month that ended in the red takes the shortfall out of this one`() {
        // September 2026: Portfel closed August at -1 111,86, the plan is 19 600
        // and 16 640,32 has gone. Without the carry-over this read 2 959,68 left.
        val september = plan(planned = 19_600_00, assigned = 0, spent = 16_640_32)
            .copy(carryOverMinor = -1_111_86, carryOverAccounts = listOf("Portfel"))
        assertEquals(1_847_82L, september.leftToSpendMinor)
        assertEquals(true, september.hasCarryOver)
    }

    @Test
    fun `money left over last month adds to this one`() {
        val carried = plan(planned = 1_000_00, assigned = 0, spent = 200_00)
            .copy(carryOverMinor = 300_00, carryOverAccounts = listOf("Portfel"))
        assertEquals(1_100_00L, carried.leftToSpendMinor)
    }

    @Test
    fun `no spending last month means nothing is carried`() {
        val first = plan(planned = 1_000_00, assigned = 0, spent = 200_00)
        assertEquals(false, first.hasCarryOver)
        assertEquals(800_00L, first.leftToSpendMinor)
    }

    @Test
    fun `left to assign takes last month's shortfall off the top`() {
        // October 2026, from the screen: 20 000 of income, Portfel 4 186,58 in
        // the red, 16 355 already handed out. This read +3 645 — room to plan
        // another three and a half thousand złoty that does not exist.
        val october = plan(planned = 20_000_00, assigned = 16_355_00)
            .copy(carryOverMinor = -4_186_58, carryOverAccounts = listOf("Portfel"))
        assertEquals(15_813_42L, october.availableMinor)
        assertEquals(-541_58L, october.leftToAssignMinor)
        assertEquals("already past the line, and it must say so", true, october.overAssigned)
    }

    @Test
    fun `money left over last month is there to be assigned`() {
        // The same rule in the other direction: a surplus is money you may
        // give a job to, not just money that quietly widens "left to spend".
        val carried = plan(planned = 1_000_00, assigned = 1_000_00)
            .copy(carryOverMinor = 300_00, carryOverAccounts = listOf("Portfel"))
        assertEquals(300_00L, carried.leftToAssignMinor)
        assertEquals(false, carried.overAssigned)
    }

    @Test
    fun `both figures start from the same pot`() {
        // The defect this guards: the two differed by the carry-over, so one
        // line of the card said the month had 20 000 and another said 15 813,42.
        // They may differ by assigned-versus-spent and by nothing else.
        val state = plan(planned = 19_600_00, assigned = 12_000_00, spent = 16_640_32)
            .copy(carryOverMinor = -1_111_86, carryOverAccounts = listOf("Portfel"))
        val assignedVsSpent = state.spentMinor - state.assignedMinor
        assertEquals(assignedVsSpent, state.leftToAssignMinor!! - state.leftToSpendMinor!!)
    }

    @Test
    fun `a shortfall bigger than the plan leaves nothing to assign`() {
        // Available goes negative rather than clamping at zero: the month is
        // underwater before it starts, and rounding that up to "0 left" is the
        // one reading that would make it look survivable.
        val underwater = plan(planned = 1_000_00, assigned = 0)
            .copy(carryOverMinor = -1_500_00, carryOverAccounts = listOf("Portfel"))
        assertEquals(-500_00L, underwater.availableMinor)
        assertEquals(-500_00L, underwater.leftToAssignMinor)
        assertEquals(true, underwater.overAssigned)
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
}

/**
 * Which stored ids the account strip is still allowed to act on.
 *
 * The remembered selection outlives what it names, and an id nothing on screen
 * can reach is money in the figures with no way to take it back out.
 */
class VisibleSelectionTest {

    private fun account(id: String, archived: Int = 0, outside: Int = 0) =
        AccountEntity(
            id = id,
            name = id,
            archived = archived,
            excludedFromSummary = outside,
        )

    private val accounts = listOf(
        account("portfel"),
        account("oszczednosci", outside = 1),
        account("wakacje", archived = 1, outside = 1),
        account("stary-fundusz", archived = 1),
    )

    /**
     * The bug this was written for. An archived account that still counted rode
     * along with the default set, that set was persisted, and switching the
     * account out of the summary afterwards could not remove the stored id —
     * no tap could either. It moved one household's carry-over by 17 900 zł
     * while having no button on the screen at all.
     */
    @Test
    fun `an archived account kept out of the summary is dropped`() {
        val stored = setOf("portfel", "wakacje")
        assertEquals(setOf("portfel"), OverviewViewModel.visibleSelection(stored, accounts))
    }

    /** It has no button either, but it rides along by design. */
    @Test
    fun `an archived account that still counts is kept`() {
        val stored = setOf("portfel", "stary-fundusz")
        assertEquals(stored, OverviewViewModel.visibleSelection(stored, accounts))
    }

    /** Outside the summary but open: it has a button, so it stays. */
    @Test
    fun `an open account outside the summary is kept`() {
        val stored = setOf("portfel", "oszczednosci")
        assertEquals(stored, OverviewViewModel.visibleSelection(stored, accounts))
    }

    @Test
    fun `an id no account answers to is dropped`() {
        assertEquals(setOf("portfel"), OverviewViewModel.visibleSelection(setOf("portfel", "gone"), accounts))
    }

    /** Nothing left that can be seen means all accounts, not no accounts. */
    @Test
    fun `a selection of nothing but invisible ids collapses to empty`() {
        assertEquals(emptySet<String>(), OverviewViewModel.visibleSelection(setOf("wakacje", "gone"), accounts))
    }

    @Test
    fun `the default empty selection is left alone`() {
        assertEquals(emptySet<String>(), OverviewViewModel.visibleSelection(emptySet(), accounts))
    }

    // ---------------------------------------------- the bar's middle button

    /**
     * The keypad's save button lives in the navigation bar now, which means the
     * button and the screen it commits are composed in different places. What
     * is decidable here is the handover itself: which of the two answers a tap
     * gets, and that an inactive slot gives neither.
     *
     * Worth pinning because the failure is a wrong row rather than a wrong
     * pixel — the bar reads one state and then runs a lambda between frames.
     */
    @Test
    fun `a tap on an unfinished entry is refused, not saved`() {
        val slot = AddSaveSlot()
        var saves = 0
        var refusals = 0

        assertEquals(false, slot.active)
        slot.tap()
        assertEquals("an inactive slot answers nothing", 0, saves + refusals)

        slot.offer(enabled = false, onSave = { saves++ }, onRefused = { refusals++ })
        assertEquals(true, slot.active)
        slot.tap()
        assertEquals(0, saves)
        assertEquals("the screen is asked to say what is missing", 1, refusals)

        slot.offer(enabled = true, onSave = { saves++ }, onRefused = { refusals++ })
        slot.tap()
        assertEquals(1, saves)
        assertEquals(1, refusals)
    }

    /**
     * Leaving the keypad — a different tab, or the rule editor opening over it
     * — takes the button back. Both lambdas go with it: they close over the
     * screen that has gone, and a tap arriving afterwards would commit a draft
     * nobody is looking at.
     */
    @Test
    fun `withdrawing the offer drops both answers with it`() {
        val slot = AddSaveSlot()
        var saves = 0
        var refusals = 0
        slot.offer(enabled = true, onSave = { saves++ }, onRefused = { refusals++ })

        slot.withdraw()

        assertEquals(false, slot.active)
        assertEquals(false, slot.enabled)
        slot.tap()
        assertEquals(0, saves)
        assertEquals(0, refusals)
    }
}
