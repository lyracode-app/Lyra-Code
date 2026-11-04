package com.yukisoffd.lyracode.ai

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class SubAgentProgressFormatterTest {
    @Test
    fun `concurrent status contains one batch summary and raw child detail in every locale`() {
        for (locale in LOCALES) {
            val strings = strings(locale)
            val formatter = formatter(strings)
            assertEquals(
                locale,
                render(strings, "status_sub_agent_progress_concurrent_detail", 0, 2, 2, "Generating"),
                formatter.format(2, 0, 2, 2, "Agent", "Generating"),
            )
        }
    }

    @Test
    fun `concurrent start status contains only one batch summary`() {
        for (locale in LOCALES) {
            val strings = strings(locale)
            val formatter = formatter(strings)
            for (detail in listOf(null, "", "  ")) {
                assertEquals(
                    locale,
                    render(strings, "status_sub_agent_progress_concurrent", 0, 2, 2),
                    formatter.format(2, 0, 2, 2, "Agent", detail),
                )
            }
        }
    }

    @Test
    fun `sequential status retains the agent name and formats raw detail once`() {
        for (locale in LOCALES) {
            val strings = strings(locale)
            val formatter = formatter(strings)
            assertEquals(
                render(strings, "status_sub_agent_progress_running", 1, 3, "Agent"),
                formatter.format(1, 1, 3, 1, "Agent"),
            )
            assertEquals(
                render(strings, "status_sub_agent_progress_detail", 1, 3, "Agent", "Generating"),
                formatter.format(1, 1, 3, 1, "Agent", "Generating"),
            )
        }
    }

    private fun formatter(strings: Map<String, String>) = SubAgentProgressFormatter(
        sequential = { completed, total, agent ->
            render(strings, "status_sub_agent_progress_running", completed, total, agent)
        },
        sequentialDetail = { completed, total, agent, detail ->
            render(strings, "status_sub_agent_progress_detail", completed, total, agent, detail)
        },
        concurrent = { completed, total, running ->
            render(strings, "status_sub_agent_progress_concurrent", completed, total, running)
        },
        concurrentDetail = { completed, total, running, detail ->
            render(strings, "status_sub_agent_progress_concurrent_detail", completed, total, running, detail)
        },
    )

    private fun render(strings: Map<String, String>, key: String, vararg args: Any): String =
        String.format(Locale.ROOT, strings.getValue(key), *args)

    private fun strings(locale: String): Map<String, String> {
        val relative = "src/main/res/$locale/strings.xml"
        val file = listOf(File(relative), File("app/$relative")).first { it.isFile }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            .getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private companion object {
        val LOCALES = listOf("values", "values-en", "values-zh-rCN", "values-zh-rTW")
    }
}
