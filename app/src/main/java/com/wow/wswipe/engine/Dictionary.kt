package com.wow.wswipe.engine

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.TreeMap
import kotlin.math.ln

/**
 * Word list with frequencies, loaded from a "word<TAB>frequency" file, plus the
 * user's own words (names, slang …) added at runtime. Ids are stable: base words
 * are 0 until [baseSize], user words come after.
 */
class Dictionary private constructor(baseWords: Array<String>, baseFreq: IntArray) {
    private val wordList = ArrayList<String>(baseWords.size + 256).apply { addAll(baseWords) }
    private var freq = baseFreq.copyOf(baseFreq.size + 256)
    val baseSize = baseWords.size

    private val index = HashMap<String, Int>(baseWords.size * 2)
    /** Base word ids sorted alphabetically, for prefix search. */
    private val sorted: IntArray
    /** User words by lower-case form, for case-insensitive prefix search. */
    private val userSorted = TreeMap<String, Int>()
    private val userBoost = HashMap<Int, Int>()
    private val removed = HashSet<Int>()
    val maxLogFreq: Double
    /** Frequency a newly learned word starts with (≈ a common word). */
    val newWordFreq: Int

    init {
        wordList.forEachIndexed { i, w -> index.putIfAbsent(w, i) }
        sorted = wordList.indices.sortedWith { a, b -> wordList[a].compareTo(wordList[b]) }.toIntArray()
        maxLogFreq = ln((baseFreq.maxOrNull() ?: 1).toDouble() + 1.0)
        newWordFreq = if (baseSize > 1500) baseFreq[1500] else 100
    }

    val size get() = wordList.size

    fun word(id: Int): String = wordList[id]

    fun idOf(word: String): Int = index[word] ?: -1

    fun contains(word: String) = index.containsKey(word)

    fun isUserWord(id: Int) = id >= baseSize

    fun isRemoved(id: Int) = id >= 0 && id in removed

    fun frequency(id: Int): Int = if (id in removed) 0 else freq[id] + (userBoost[id] ?: 0)

    /** 0..1, log-scaled. */
    fun normFreq(id: Int): Double = ln(frequency(id).toDouble() + 1.0) / maxLogFreq

    /** The [n] most frequent words (for the idle suggestion bar). */
    fun topWords(n: Int): List<String> {
        val out = ArrayList<String>(n)
        var i = 0
        while (out.size < n && i < baseSize) { if (i !in removed) out += wordList[i]; i++ }
        return out
    }

    /** The user used [word]: rank it higher next time. */
    fun learn(word: String) {
        val id = idOf(word)
        if (id < 0) return
        removed.remove(id)
        userBoost[id] = (userBoost[id] ?: 0) + maxOf(50, freq[id] / 4)
    }

    /** Add a new user word; returns its id (or the existing id). */
    @Synchronized
    fun addWord(word: String): Int {
        idOf(word).let { if (it >= 0) { removed.remove(it); return it } }
        val id = wordList.size
        wordList += word
        if (id >= freq.size) freq = freq.copyOf(freq.size * 2)
        freq[id] = newWordFreq
        index[word] = id
        userSorted[word.lowercase()] = id
        return id
    }

    /** Stop suggesting [word] (user long-pressed it). */
    fun remove(word: String): Boolean {
        val id = idOf(word)
        if (id < 0) return false
        removed += id
        userBoost.remove(id)
        return true
    }

    /** Most frequent words starting with [prefix] (excluding [prefix] itself). */
    fun completions(prefix: String, limit: Int = 3): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val lowerPrefix = prefix.lowercase()
        val best = ArrayList<Int>()
        var i = lowerBound(prefix)
        var scanned = 0
        while (i < sorted.size && scanned < 20000) {
            val id = sorted[i]
            val w = wordList[id]
            if (!w.startsWith(prefix)) break
            if (w != prefix && id !in removed) best += id
            i++; scanned++
        }
        // User words match case-insensitively ("ron" → "Ronnachate").
        for ((lw, id) in userSorted.tailMap(lowerPrefix)) {
            if (!lw.startsWith(lowerPrefix)) break
            if (lw != lowerPrefix && id !in removed) best += id
        }
        return best.distinct().sortedByDescending { frequency(it) }.take(limit).map { wordList[it] }
    }

    /** True if some word starts with [prefix]. */
    fun hasPrefix(prefix: String): Boolean {
        val lo = lowerBound(prefix)
        if (lo < sorted.size && wordList[sorted[lo]].startsWith(prefix)) return true
        val lp = prefix.lowercase()
        return userSorted.ceilingKey(lp)?.startsWith(lp) == true
    }

    private fun lowerBound(prefix: String): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (wordList[sorted[mid]] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        fun load(input: InputStream): Dictionary {
            val ws = ArrayList<String>(120_000)
            val fs = ArrayList<Int>(120_000)
            BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16).useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    ws += line.substring(0, tab)
                    fs += line.substring(tab + 1).trim().toIntOrNull() ?: 1
                }
            }
            return Dictionary(ws.toTypedArray(), fs.toIntArray())
        }
    }
}
