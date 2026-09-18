package com.monyx

import com.monyx.update.NoteItems
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteItemsTest {

    @Test
    fun `one bullet per line, whatever bullet was typed`() {
        assertEquals(listOf("one", "two", "three", "four"), NoteItems.of("- one\n* two\n• three\n\n  four  \n"))
    }

    @Test
    fun `a leading minus that is not a bullet stays`() {
        assertEquals(listOf("-5 zł is still text"), NoteItems.of("-5 zł is still text"))
    }

    @Test
    fun `blank notes have no bullets`() {
        assertEquals(emptyList<String>(), NoteItems.of("  \n"))
    }
}
