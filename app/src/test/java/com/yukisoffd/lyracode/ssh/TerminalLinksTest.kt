package com.yukisoffd.lyracode.ssh

import org.junit.Assert.*
import org.junit.Test

class TerminalLinksTest {
    private fun snapshot(text: String, columns: Int = 120, rows: Int = 12): TerminalSnapshot =
        TerminalEmulator(columns, rows).apply { accept(text.toByteArray()) }.snapshot()

    private fun selectedText(screen: TerminalSnapshot, link: TerminalLink): String =
        TerminalSelectionText.extract(screen, TerminalSelection(
            TerminalPosition(link.startIndex / screen.columns, link.startIndex % screen.columns),
            TerminalPosition(link.endIndex / screen.columns, link.endIndex % screen.columns),
        ))

    @Test fun findsEchoedAndOutputLinksWithoutShellQuotesOrProsePunctuation() {
        val screen = snapshot("curl 'https://example.com/a?q=1&next=two'\r\nListening: http://localhost:8080/test.")
        val links = TerminalLinks.find(screen)
        assertEquals(listOf("https://example.com/a?q=1&next=two", "http://localhost:8080/test"), links.map { it.text })
        assertEquals(6, links.first().startIndex)
        assertTrue(links.first().contains(TerminalPosition(0, 8), screen.columns))
        assertFalse(links.first().contains(TerminalPosition(0, 4), screen.columns))
        assertFalse(links.last().contains(TerminalPosition(1, 47), screen.columns))
        links.forEach { assertEquals(it.text, selectedText(screen, it)) }
    }

    @Test fun preservesBalancedPathParenthesesAndIpv6Brackets() {
        val screen = snapshot("(https://en.wikipedia.org/wiki/Function_(mathematics)). http://[::1]:8080/a")
        assertEquals(listOf("https://en.wikipedia.org/wiki/Function_(mathematics)", "http://[::1]:8080/a"),
            TerminalLinks.find(screen).map { it.text })
    }

    @Test fun joinsAutomaticWrapsButNeverJoinsExplicitNewlines() {
        val url = "https://example.com/long/path?q=one&next=two#fragment"
        val wrapped = snapshot(url, columns = 13)
        val link = TerminalLinks.find(wrapped).single()
        assertEquals(url, link.text)
        assertTrue(link.endIndex / wrapped.columns > 0)
        assertEquals(url, selectedText(wrapped, link))
        assertEquals(listOf("https://exa"), TerminalLinks.find(snapshot("https://exa\r\nmple.com", columns = 13)).map { it.text })
    }

    @Test fun mapsUnicodeWideGlyphsCombiningMarksAndSupplementaryCharactersToCells() {
        val url = "https://例子.test/文e\u0301😀"
        val screen = snapshot("中😀 e\u0301 $url", columns = 17)
        val link = TerminalLinks.find(screen).single()
        assertEquals(url, link.text)
        assertEquals(url, selectedText(screen, link))
        assertTrue(screen.cells[link.endIndex].continuation)
    }

    @Test fun ignoresWideCharacterWrapPaddingInsideALink() {
        val url = "https://a.b/中文"
        val screen = snapshot(url, columns = 13)
        assertTrue(screen.cells[12].wrapPadding)
        val link = TerminalLinks.find(screen).single()
        assertEquals(url, link.text)
        assertEquals(url, selectedText(screen, link))
    }

    @Test fun detectsLinksSplitByAnsiStyleChangesAndChinesePunctuation() {
        val screen = snapshot("访问：https://exam\u001b[31mple.com/路径\u001b[0m，完成。")
        val link = TerminalLinks.find(screen).single()
        assertEquals("https://example.com/路径", link.text)
        assertEquals(link.text, selectedText(screen, link))
    }

    @Test fun recognizesChineseQuotesParenthesesAndUnicodeSpacesAroundUrls() {
        val screen = snapshot("（https://example.com/a） “https://example.com/b”\u3000https://example.com/c\u00a0done")
        assertEquals(listOf("https://example.com/a", "https://example.com/b", "https://example.com/c"),
            TerminalLinks.find(screen).map { it.text })
    }

    @Test fun includesOffscreenPartsOfVisibleWrappedLinksAndSkipsUnrelatedHistory() {
        val url = "https://example.com/" + "a".repeat(70)
        val screen = snapshot(url, columns = 12)
        val link = TerminalLinks.find(screen, firstRow = 2, lastRow = 3).single()
        assertEquals(url, link.text)
        assertEquals(0, link.startIndex)
        assertTrue(TerminalLinks.find(screen, firstRow = 10, lastRow = 12).isEmpty())
        assertTrue(TerminalLinks.find(screen, firstRow = 0, lastRow = 0).isEmpty())
    }

    @Test fun supportsWwwAndExplicitTerminalUriSchemes() {
        val values = listOf("www.example.com/path", "ssh://user@host:22", "ftp://host/file", "custom+tool://host/action")
        assertEquals(values, TerminalLinks.find(snapshot(values.joinToString(" "))).map { it.text })
    }

    @Test fun resizingReflowsLinkCoordinatesWithoutChangingCopiedText() {
        val url = "https://example.com/a/long/path?query=value"
        val terminal = TerminalEmulator(14, 10)
        terminal.accept("echo $url".toByteArray())
        for (columns in listOf(14, 24, 11, 40)) {
            terminal.resize(columns, 10)
            val screen = terminal.snapshot()
            val link = TerminalLinks.find(screen).single()
            assertEquals(url, link.text)
            assertEquals(url, selectedText(screen, link))
        }
    }

    @Test fun ignoresEmptySchemesAndEmptyScreens() {
        assertTrue(TerminalLinks.find(snapshot("https:// www. ordinary text")).isEmpty())
        assertTrue(TerminalLinks.find(TerminalSnapshot(0, 0, emptyList(), 0, 0, false)).isEmpty())
    }
}
