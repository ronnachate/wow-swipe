package com.wow.wswipe.engine

import com.wow.wswipe.layout.Key
import com.wow.wswipe.layout.KeyboardLayout
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Trie over *key sequences* (not letters): every word is turned into the list of
 * keys you would pass over to type it, with repeated keys collapsed. Letters that
 * live on the same key (base + second-layer, e.g. ส/ศ) share a path.
 */
class KeyTrie(private val layout: KeyboardLayout, val dict: Dictionary) {
    private var n = 1
    private var nodeKey = IntArray(1 shl 16)
    private var firstChild = IntArray(1 shl 16) { -1 }
    private var nextSibling = IntArray(1 shl 16) { -1 }
    private var wordHead = IntArray(1 shl 16) { -1 }
    private var wordNext = IntArray(dict.size + 256) { -1 }
    private val seq = IntArray(64)

    init {
        nodeKey[0] = -1
        for (id in 0 until dict.size) insert(id)
    }

    /** Add dictionary word [id] (also used for words learned while typing). */
    @Synchronized
    fun insert(id: Int): Boolean {
        val w = dict.word(id)
        var len = 0
        for (c in w) {
            val k = layout.keyForChar(c) ?: return false
            if (len > 0 && seq[len - 1] == k.charIndex) continue
            if (len == seq.size) return false
            seq[len++] = k.charIndex
        }
        if (len == 0) return false
        if (id >= wordNext.size) wordNext = wordNext.copyOf(maxOf(id + 1, wordNext.size * 2)).also { it.fill(-1, wordNext.size, it.size) }
        var node = 0
        for (i in 0 until len) node = childOrCreate(node, seq[i])
        // Avoid duplicate entries if the same id is inserted twice.
        var w2 = wordHead[node]
        while (w2 != -1) { if (w2 == id) return true; w2 = wordNext[w2] }
        wordNext[id] = wordHead[node]
        wordHead[node] = id
        return true
    }

    private fun childOrCreate(parent: Int, key: Int): Int {
        var c = firstChild[parent]
        while (c != -1) {
            if (nodeKey[c] == key) return c
            c = nextSibling[c]
        }
        if (n == nodeKey.size) grow()
        val id = n++
        nodeKey[id] = key
        firstChild[id] = -1
        wordHead[id] = -1
        nextSibling[id] = firstChild[parent]
        firstChild[parent] = id
        return id
    }

    private fun grow() {
        val s = nodeKey.size * 2
        nodeKey = nodeKey.copyOf(s)
        firstChild = firstChild.copyOf(s).also { it.fill(-1, n, s) }
        nextSibling = nextSibling.copyOf(s).also { it.fill(-1, n, s) }
        wordHead = wordHead.copyOf(s).also { it.fill(-1, n, s) }
    }

    val nodeCount get() = n
    fun key(node: Int) = nodeKey[node]
    fun firstChild(node: Int) = firstChild[node]
    fun nextSibling(node: Int) = nextSibling[node]
    fun firstWord(node: Int) = wordHead[node]
    fun nextWord(wordId: Int) = wordNext[wordId]
}

class SwipeCandidate(val word: String, val score: Double) {
    override fun toString() = "$word(${"%.2f".format(score)})"
}

/**
 * Swipe-to-word decoder:
 *
 *  - The finger path is resampled at a fixed step so fast swipes don't skip keys.
 *  - Each sample "touches" its key, the horizontally nearer neighbour, and the key
 *    above/below when the finger is in the top/bottom 30% of the key.
 *  - A word is a candidate when its key sequence appears, in order, along the
 *    touched keys, starting on the first key and ending on the last one.
 *  - Candidates are ranked by how closely the path passes each letter, sharp
 *    corners the word doesn't explain, path length vs. the word's ideal length,
 *    and word frequency.
 */
class SwipeDecoder(private val layout: KeyboardLayout, private val trie: KeyTrie) {

    private val dict = trie.dict
    private val nKeys = layout.charKeys.size

    // Tunable weights
    var wGeo = 3.0
    var wCorner = 1.4
    var wLength = 1.2
    var wFreq = 2.0
    var wSkip = 0.25

    private class Seg(n: Int) {
        var key = -1
        val opt = BooleanArray(n)
        val dist = FloatArray(n) { Float.MAX_VALUE }
        var turn = 0f
    }

    private var segs: List<Seg> = emptyList()
    private var keyW = 1f
    private var pathLen = 0f
    private var budget = 0
    private val results = HashMap<Int, Double>()
    private val pos = IntArray(MAX_DEPTH)
    private val pathKeys = IntArray(MAX_DEPTH)

    @Synchronized
    fun decode(xs: FloatArray, ys: FloatArray, count: Int, maxResults: Int = 8): List<SwipeCandidate> {
        if (count < 2 || nKeys == 0) return emptyList()
        keyW = layout.keyWidth
        val (rx, ry) = resample(xs, ys, count, keyW * 0.2f)
        if (rx.size < 2) return emptyList()
        segs = buildSegments(rx, ry)
        if (segs.isEmpty()) return emptyList()
        results.clear()
        budget = 400_000
        val last = segs.size - 1
        var c = trie.firstChild(0)
        while (c != -1) {
            val k = trie.key(c)
            if (matches(0, k)) {
                pos[0] = 0; pathKeys[0] = k
                if (last == 0) { if (trie.firstWord(c) != -1) record(c, 1) }
                else search(c, 0, 1)
            }
            c = trie.nextSibling(c)
        }
        return results.entries.sortedByDescending { it.value }.take(maxResults)
            .map { SwipeCandidate(dict.word(it.key), it.value) }
    }

    private fun matches(j: Int, k: Int) = segs[j].key == k || segs[j].opt[k]

    /** The trie [node] is matched at segment [j]; [depth] keys are on the stack. */
    private fun search(node: Int, j: Int, depth: Int) {
        if (--budget < 0 || depth >= MAX_DEPTH) return
        val last = segs.size - 1
        var c = trie.firstChild(node)
        while (c != -1) {
            val k = trie.key(c)
            pathKeys[depth] = k
            // A word ends here: its last key must be touched by the final segment.
            if (trie.firstWord(c) != -1 && j < last && matches(last, k)) {
                pos[depth] = last
                record(c, depth + 1)
            }
            if (trie.firstChild(c) != -1) {
                // Continue: earliest later segment touching k, preferring a direct hit.
                var prim = -1
                var opt = -1
                var s = j + 1
                while (s < last) {
                    val sg = segs[s]
                    if (sg.key == k) { prim = s; break }
                    if (opt < 0 && sg.opt[k]) opt = s
                    s++
                }
                if (prim >= 0) { pos[depth] = prim; pathKeys[depth] = k; search(c, prim, depth + 1) }
                if (opt >= 0 && (prim < 0 || opt < prim)) { pos[depth] = opt; pathKeys[depth] = k; search(c, opt, depth + 1) }
            }
            c = trie.nextSibling(c)
        }
    }

    private fun record(node: Int, m: Int) {
        var geo = 0.0
        var ideal = 0.0
        var prev: Key? = null
        val used = BooleanArray(segs.size)
        for (i in 0 until m) {
            val k = pathKeys[i]
            used[pos[i]] = true
            val d = segs[pos[i]].dist[k] / keyW
            geo += (d * d).toDouble()
            val key = layout.charKeys[k]
            if (prev != null) ideal += hypot(key.cx - prev.cx, key.cy - prev.cy) / keyW
            prev = key
        }
        geo /= m
        var corner = 0.0
        var skipped = 0
        for (s in 1 until segs.size - 1) {
            if (used[s]) continue
            skipped++
            val t = segs[s].turn
            if (t > 50f) corner += min(1.0, (t - 50.0) / 70.0)
        }
        val actual = pathLen / keyW
        val lenPen = abs(actual - ideal) / (ideal + 1.0)
        val base = -wGeo * geo - wCorner * corner - wLength * lenPen - wSkip * skipped / (segs.size + 1.0)
        var w = trie.firstWord(node)
        while (w != -1) {
            if (dict.isRemoved(w)) { w = trie.nextWord(w); continue }
            val sc = base + wFreq * dict.normFreq(w)
            val old = results[w]
            if (old == null || sc > old) results[w] = sc
            w = trie.nextWord(w)
        }
    }

    private fun resample(xs: FloatArray, ys: FloatArray, count: Int, step: Float): Pair<FloatArray, FloatArray> {
        val ox = ArrayList<Float>()
        val oy = ArrayList<Float>()
        ox += xs[0]; oy += ys[0]
        var px = xs[0]; var py = ys[0]
        var carry = 0f
        pathLen = 0f
        for (i in 1 until count) {
            val dx = xs[i] - px
            val dy = ys[i] - py
            val d = sqrt(dx * dx + dy * dy)
            if (d <= 0f) continue
            pathLen += d
            var t = step - carry
            while (t <= d) {
                ox += px + dx * t / d; oy += py + dy * t / d
                t += step
            }
            carry = d - (t - step)
            px = xs[i]; py = ys[i]
        }
        if (ox.last() != px || oy.last() != py) { ox += px; oy += py }
        return ox.toFloatArray() to oy.toFloatArray()
    }

    private fun buildSegments(rx: FloatArray, ry: FloatArray): List<Seg> {
        val out = ArrayList<Seg>()
        val n = rx.size
        val turns = FloatArray(n)
        val w = 2
        for (i in w until n - w) {
            val ax = rx[i] - rx[i - w]; val ay = ry[i] - ry[i - w]
            val bx = rx[i + w] - rx[i]; val by = ry[i + w] - ry[i]
            val la = hypot(ax, ay); val lb = hypot(bx, by)
            if (la < 1e-3f || lb < 1e-3f) continue
            val cos = ((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f)
            turns[i] = Math.toDegrees(acos(cos.toDouble())).toFloat()
        }
        var cur: Seg? = null
        for (i in 0 until n) {
            val x = rx[i]; val y = ry[i]
            val k = layout.nearestCharKey(x, y) ?: continue
            // Ignore samples well outside the letter area (e.g. on the space bar).
            val outY = max(0f, max(k.top - y, y - k.bottom))
            if (outY > k.height * 0.6f) continue
            if (cur == null || cur.key != k.charIndex) {
                cur = Seg(nKeys).also { it.key = k.charIndex }
                out += cur
            }
            touch(cur, k, x, y, opt = false)
            cur.turn = max(cur.turn, turns[i])
            // Horizontal neighbour on the side the finger is on.
            val relX = (x - k.left) / k.width
            val rowKeys = layout.charKeysInRow(k.row)
            val idx = rowKeys.indexOf(k)
            val side = if (relX < 0.5f) idx - 1 else idx + 1
            if (side in rowKeys.indices && abs(rowKeys[side].cx - k.cx) < k.width * 1.6f) {
                touch(cur, rowKeys[side], x, y, opt = true)
            }
            // Vertical neighbour when in the top/bottom 30% of the key.
            val relY = (y - k.top) / k.height
            if (relY <= 0.3f) layout.nearestCharKey(x, y - k.height, k.row - 1)?.let { touch(cur, it, x, y, true) }
            if (relY >= 0.7f) layout.nearestCharKey(x, y + k.height, k.row + 1)?.let { touch(cur, it, x, y, true) }
        }
        return out
    }

    private fun touch(s: Seg, k: Key, x: Float, y: Float, opt: Boolean) {
        val i = k.charIndex
        if (i < 0) return
        if (opt && s.key != i) s.opt[i] = true
        val d = hypot(k.cx - x, k.cy - y)
        if (d < s.dist[i]) s.dist[i] = d
    }

    companion object {
        private const val MAX_DEPTH = 48
    }
}
