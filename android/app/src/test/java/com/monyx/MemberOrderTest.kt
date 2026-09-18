package com.monyx

import com.monyx.data.MemberEntity
import com.monyx.ui.settings.memberOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The member list's order. The DAO hands them over oldest first; this is the
 * one rearrangement on top of that, and it has to be the only one.
 */
class MemberOrderTest {

    private fun member(id: String, name: String = id, createdAt: Long = 0) =
        MemberEntity(id = id, name = name, createdAt = createdAt)

    private val kuba = member("1", "Kuba", 100)
    private val guska = member("2", "Guśka", 200)
    private val hania = member("3", "Hania", 300)
    private val joined = listOf(kuba, guska, hania)

    @Test
    fun `this phone's member comes first`() {
        assertEquals(
            listOf("2", "1", "3"),
            memberOrder(joined, "2").map { it.id },
        )
    }

    @Test
    fun `everyone else keeps join order behind them`() {
        assertEquals(
            listOf("3", "1", "2"),
            memberOrder(joined, "3").map { it.id },
        )
    }

    @Test
    fun `the oldest member first is already first, and nothing moves`() {
        assertEquals(listOf("1", "2", "3"), memberOrder(joined, "1").map { it.id })
    }

    /**
     * The cold-start case. memberId arrives from the session a frame or two
     * after the members do, and a list that reordered itself at that moment
     * would move a row under a finger already on its way down.
     */
    @Test
    fun `no member id yet leaves the order exactly as it came`() {
        assertEquals(listOf("1", "2", "3"), memberOrder(joined, null).map { it.id })
    }

    @Test
    fun `an id belonging to nobody here changes nothing`() {
        assertEquals(listOf("1", "2", "3"), memberOrder(joined, "ghost").map { it.id })
    }

    @Test
    fun `an empty household stays empty`() {
        assertEquals(emptyList<String>(), memberOrder(emptyList(), "1").map { it.id })
    }

    /**
     * Every member of this household really is called Kuba until somebody
     * renames themselves, so the tint on your own row is the only thing telling
     * them apart — which makes putting the right one first the whole point.
     */
    @Test
    fun `identical names do not confuse it, because it matches on id`() {
        val all = listOf(member("a", "Kuba", 1), member("b", "Kuba", 2), member("c", "Kuba", 3))
        assertEquals(listOf("c", "a", "b"), memberOrder(all, "c").map { it.id })
    }
}
