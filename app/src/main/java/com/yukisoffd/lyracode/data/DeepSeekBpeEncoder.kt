package com.yukisoffd.lyracode.data

import java.io.DataInputStream
import java.io.InputStream
import java.util.PriorityQueue
import java.util.regex.Pattern

/** Immutable counting tables compiled from deepseek_v4_tokenizer/tokenizer.json at build time. */
internal class DeepSeekBpeEncoder private constructor(
    private val splits: List<Pattern>,
    private val byteTokens: IntArray,
    private val pairs: LongArray,
    private val merges: LongArray,
    addedTokens: List<String>,
) {
    private class AddedTokenNode {
        val children = HashMap<Char, AddedTokenNode>()
        var terminal = false
    }

    private val addedRoot = AddedTokenNode().apply {
        for (token in addedTokens) {
            var node = this
            for (char in token) node = node.children.getOrPut(char) { AddedTokenNode() }
            node.terminal = true
        }
    }

    fun count(text: String): Long {
        var total = 0L
        var plainStart = 0
        var index = 0
        while (index < text.length) {
            var node = addedRoot
            var cursor = index
            var tokenEnd = index
            while (cursor < text.length) {
                node = node.children[text[cursor]] ?: break
                cursor++
                if (node.terminal) tokenEnd = cursor
            }
            if (tokenEnd == index) {
                index++
            } else {
                total += countSplit(text, plainStart, index, 0) + 1
                index = tokenEnd
                plainStart = tokenEnd
            }
        }
        return total + countSplit(text, plainStart, text.length, 0)
    }

    // Every Split(Isolated) keeps both matches and unmatched spans; later splits run within each span.
    private fun countSplit(text: String, start: Int, end: Int, step: Int): Long {
        if (start == end) return 0L
        if (step == splits.size) return countBpe(text.substring(start, end).toByteArray(Charsets.UTF_8))
        val matcher = splits[step].matcher(text).region(start, end)
        var cursor = start
        var total = 0L
        while (matcher.find()) {
            total += countSplit(text, cursor, matcher.start(), step + 1)
            total += countSplit(text, matcher.start(), matcher.end(), step + 1)
            cursor = matcher.end()
        }
        return total + countSplit(text, cursor, end, step + 1)
    }

    private data class Candidate(
        val rank: Int,
        val left: Int,
        val right: Int,
        val leftToken: Int,
        val rightToken: Int,
        val mergedToken: Int,
    ) : Comparable<Candidate> {
        override fun compareTo(other: Candidate): Int {
            val priority = rank.compareTo(other.rank)
            return if (priority != 0) priority else left.compareTo(other.left)
        }
    }

    private fun countBpe(bytes: ByteArray): Long {
        if (bytes.size < 2) return bytes.size.toLong()
        val tokens = IntArray(bytes.size) { byteTokens[bytes[it].toInt() and 0xff] }
        val previous = IntArray(bytes.size) { it - 1 }
        val next = IntArray(bytes.size) { if (it + 1 < bytes.size) it + 1 else -1 }
        val queue = PriorityQueue<Candidate>()
        fun offer(left: Int) {
            if (left < 0) return
            val right = next[left]
            if (right < 0) return
            val pair = (tokens[left].toLong() shl 32) or tokens[right].toLong()
            val mergeIndex = pairs.binarySearch(pair)
            if (mergeIndex < 0) return
            val merge = merges[mergeIndex]
            queue.add(Candidate((merge ushr 32).toInt(), left, right, tokens[left], tokens[right], merge.toInt()))
        }
        for (left in 0 until bytes.lastIndex) offer(left)
        var count = bytes.size.toLong()
        while (queue.isNotEmpty()) {
            val candidate = queue.remove()
            val left = candidate.left
            val right = candidate.right
            // Neighbours may already have merged since this candidate was enqueued.
            if (tokens[left] != candidate.leftToken || next[left] != right || tokens[right] != candidate.rightToken) continue
            tokens[left] = candidate.mergedToken
            tokens[right] = -1
            next[left] = next[right]
            if (next[right] >= 0) previous[next[right]] = left
            count--
            offer(previous[left])
            offer(left)
        }
        return count
    }

    companion object {
        fun read(input: InputStream): DeepSeekBpeEncoder {
            val data = DataInputStream(input)
            require(data.readInt() == 0x44535634 && data.readInt() == 1)
            val splitCount = data.readInt().also { require(it in 1..16) }
            val splits = List(splitCount) {
                val regex = data.readUTF()
                try {
                    Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS)
                } catch (_: IllegalArgumentException) {
                    // Android's ICU uses Unicode classes by default and rejects the JVM-only flag.
                    Pattern.compile(regex)
                }
            }
            val byteTokens = IntArray(256) { data.readInt().also { require(it >= 0) } }
            val mergeCount = data.readInt().also { require(it in 1..1_000_000) }
            val pairs = LongArray(mergeCount)
            val merges = LongArray(mergeCount)
            for (index in pairs.indices) {
                pairs[index] = data.readLong()
                require(pairs[index] >= 0 && (index == 0 || pairs[index - 1] < pairs[index]))
                val rank = data.readInt().also { require(it in 0 until mergeCount) }
                val token = data.readInt().also { require(it >= 0) }
                merges[index] = (rank.toLong() shl 32) or token.toLong()
            }
            val addedCount = data.readInt().also { require(it in 0..100_000) }
            val addedTokens = List(addedCount) { data.readUTF().also { require(it.isNotEmpty()) } }
            require(data.read() == -1)
            return DeepSeekBpeEncoder(splits, byteTokens, pairs, merges, addedTokens)
        }
    }
}
