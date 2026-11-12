package com.yukisoffd.lyracode.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalCompositionStateTest {
    @Test
    fun `clearing a PTY discards old candidates without sending them to the new shell`() {
        val events = mutableListOf<String>()
        val state = TerminalCompositionState(events::add) { events += "BACKSPACE" }
        state.update("中文候选")
        state.discard()
        state.finish()
        state.commit("new")
        assertEquals(listOf("new"), events)
    }
    @Test
    fun `appended composing text is sent once`() {
        val events = mutableListOf<String>()
        val state = TerminalCompositionState(events::add) { events += "BACKSPACE" }

        state.update("c")
        state.update("ca")
        state.commit("cat")

        assertEquals(listOf("c", "a", "t"), events)
    }

    @Test
    fun `composition replacement edits the remote terminal`() {
        val events = mutableListOf<String>()
        val state = TerminalCompositionState(events::add) { events += "BACKSPACE" }

        state.update("car")
        state.commit("cat")

        assertEquals(listOf("car", "BACKSPACE", "t"), events)
    }

    @Test
    fun `committed clipboard text is sent immediately`() {
        val events = mutableListOf<String>()
        val state = TerminalCompositionState(events::add) { events += "BACKSPACE" }

        state.commit("echo hello")

        assertEquals(listOf("echo hello"), events)
    }

    @Test
    fun `recreated input connection forgets stale composition`() {
        val events = mutableListOf<String>()
        val state = TerminalCompositionState(events::add) { events += "BACKSPACE" }

        state.update("old")
        state.finish()
        state.update("n")

        assertEquals(listOf("old", "n"), events)
    }

    @Test
    fun `editor mode boundary does not rewrite insert text into save command`() {
        val events = mutableListOf<String>()
        val state = TerminalCompositionState(events::add) { events += "BACKSPACE" }

        state.update("body")
        state.finish() // terminal toolbar Esc
        state.update(":")
        state.finish()
        state.update("w")

        assertEquals(listOf("body", ":", "w"), events)
    }

    @Test
    fun `Korean and Japanese candidates remain local until committed`() {
        val events = mutableListOf<String>()
        val state = TerminalCompositionState(events::add) { events += "BACKSPACE" }
        state.update("ㅎ")
        state.update("하")
        state.update("한")
        assertEquals(emptyList<String>(), events)
        state.commit("한")
        state.update("にほん")
        state.commit("日本")
        assertEquals(listOf("한", "日本"), events)
    }

    @Test
    fun `pinyin ASCII is replaced exactly once on Chinese commit`() {
        val events = mutableListOf<String>()
        val state = TerminalCompositionState(events::add) { events += "BACKSPACE" }
        state.update("ni")
        state.commit("你")
        assertEquals(listOf("ni", "BACKSPACE", "BACKSPACE", "你"), events)
    }

    @Test
    fun `local candidate deletion does not erase remote text or split surrogate pairs`() {
        val events = mutableListOf<String>()
        val state = TerminalCompositionState(events::add) { events += "BACKSPACE" }
        state.update("你𠀀")
        state.deleteBefore(2, inCodePoints = false)
        assertEquals(emptyList<String>(), events)
        state.finish()
        assertEquals(listOf("你"), events)
    }
}
