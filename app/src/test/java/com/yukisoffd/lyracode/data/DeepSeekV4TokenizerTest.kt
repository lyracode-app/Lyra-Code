package com.yukisoffd.lyracode.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors

class DeepSeekV4TokenizerTest {
    private val asset get() = File(checkNotNull(System.getProperty("lyra.tokenizerAsset")))
    private val encoder by lazy { asset.inputStream().buffered().use(DeepSeekBpeEncoder::read) }

    @Test fun countsMatchUpstreamV4Tokenizer() {
        val fixture = javaClass.getResourceAsStream("/deepseek-v4-counts.json")!!.bufferedReader().use { JSONObject(it.readText()) }
        val cases = fixture.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertEquals("case $index: ${case.getString("text").take(150)}", case.getLong("count"), encoder.count(case.getString("text")))
        }
    }

    @Test fun everyAddedTokenCountsAsOneIncludingNewV4Markers() {
        val source = listOf(File("../deepseek_v4_tokenizer/tokenizer.json"), File("deepseek_v4_tokenizer/tokenizer.json")).first { it.isFile }
        val tokens = JSONObject(source.readText()).getJSONArray("added_tokens")
        for (index in 0 until tokens.length()) {
            val token = tokens.getJSONObject(index).getString("content")
            assertEquals(token, 1L, encoder.count(token))
        }
        assertEquals(3L, encoder.count("<｜begin▁of▁file｜><｜end▁of▁file｜>｜DSML｜"))
    }

    @Test fun whitespaceAndUtf8BytesAreCounted() {
        assertEquals(0L, encoder.count(""))
        assertEquals(1L, encoder.count(" "))
        assertEquals(2L, encoder.count("\n\t"))
        assertEquals(2L, encoder.count("😀"))
        assertEquals(4L, encoder.count("你好，世界！"))
    }

    @Test fun sharedEncoderSupportsConcurrentCounts() {
        val text = "<｜User｜>中文 English 123456789 😀\n<｜Assistant｜>｜DSML｜".repeat(100)
        val expected = encoder.count(text)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = List(16) { executor.submit<Long> { encoder.count(text) } }
            results.forEach { assertEquals(expected, it.get().toLong()) }
        } finally { executor.shutdownNow() }
    }

    @Test fun corruptOrTruncatedTablesAreRejected() {
        val bytes = asset.readBytes()
        assertThrows(Exception::class.java) { DeepSeekBpeEncoder.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 1))) }
        assertThrows(Exception::class.java) { DeepSeekBpeEncoder.read(ByteArrayInputStream(ByteArray(8))) }
    }

    @Test fun countCacheEvictsByTextSizeAndEntryCount() {
        val cache = TokenCountCache(maxChars = 5, maxEntries = 2)
        var calls = 0
        fun count(text: String) = cache.count(text) { calls++; text.length.toLong() }
        count("ab"); count("cd"); count("ab")
        assertEquals(2, calls)
        count("efg"); count("ab")
        assertEquals(3, calls)
        count("cd")
        assertEquals(4, calls)
        count("oversized"); count("oversized")
        assertEquals(6, calls)
        count(""); count("x"); count(""); count("y"); count("x")
        assertEquals(10, calls)
    }
}
