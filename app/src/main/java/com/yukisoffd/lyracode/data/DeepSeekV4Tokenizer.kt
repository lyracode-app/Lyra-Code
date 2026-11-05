package com.yukisoffd.lyracode.data

import android.content.Context
import android.util.Log
import java.util.LinkedHashMap

/** Local text counts using the bundled DeepSeek V4 pre-tokenization, ByteLevel BPE and added tokens. */
class DeepSeekV4Tokenizer private constructor(private val encoder: DeepSeekBpeEncoder?) {
    internal val cacheVersion: Int get() = if (encoder == null) -1 else LOCAL_TOKEN_CACHE_VERSION
    private val counts = TokenCountCache()

    fun count(text: String): Long = counts.count(text) {
        if (text.isEmpty()) 0L else encoder?.count(text) ?: estimateFallback(text)
    }

    private fun estimateFallback(text: String): Long {
        var tokens = 0L
        var asciiRun = 0L
        for (ch in text) {
            if (ch.code in 0x20..0x7E) asciiRun++ else {
                tokens += (asciiRun + 3) / 4 + 1
                asciiRun = 0
            }
        }
        return tokens + (asciiRun + 3) / 4
    }

    companion object {
        @Volatile private var cached: DeepSeekV4Tokenizer? = null

        fun get(context: Context): DeepSeekV4Tokenizer = cached ?: synchronized(this) {
            cached ?: load(context.applicationContext).also { cached = it }
        }

        private fun load(context: Context): DeepSeekV4Tokenizer {
            val encoder = runCatching {
                context.assets.open("deepseek-v4.bin").buffered().use(DeepSeekBpeEncoder::read)
            }.getOrElse {
                Log.w("DeepSeekV4Tokenizer", "Unable to load bundled V4 rules; using fallback estimate", it)
                null
            }
            return DeepSeekV4Tokenizer(encoder)
        }
    }
}

/** Bounds retained text as well as entry count; large histories belong in the database cache. */
internal class TokenCountCache(private val maxChars: Int = 1_048_576, private val maxEntries: Int = 256) {
    private val values = LinkedHashMap<String, Long>(16, 0.75f, true)
    private var chars = 0

    fun count(text: String, calculate: () -> Long): Long {
        if (text.length > maxChars) return calculate()
        synchronized(this) { values[text]?.let { return it } }
        val result = calculate()
        synchronized(this) {
            if (values.put(text, result) == null) chars += text.length
            val iterator = values.entries.iterator()
            while (chars > maxChars || values.size > maxEntries) {
                chars -= iterator.next().key.length
                iterator.remove()
            }
        }
        return result
    }
}
