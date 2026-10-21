package com.wow.wswipe.layout

import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

enum class KeyType { CHAR, GAP, BACKSPACE, SHIFT, SPACE, ENTER, LANGUAGE, NUMERIC, SYMBOL, ALPHA, SWITCH }

enum class Shade { LIGHT, MEDIUM, DARK, ACTION, SPECIAL }

enum class LayoutKind { LANGUAGE, NUMERIC, SYMBOL }

/**
 * One key. Geometry (left/top/right/bottom) is filled in by [KeyboardLayout.measure].
 *
 * [base]  = what a tap types.
 * [alt]   = the "second layer" character: typed on long-press, and shown as the
 *           main character when Shift is on in a swap layout (Thai).
 * [more]  = extra popup choices after a long-press.
 */
class Key(
    val type: KeyType,
    val base: String,
    val alt: String?,
    val altLabel: String?,
    val more: List<String>,
    val widthPct: Float,
    val shade: Shade,
) {
    var left = 0f; var top = 0f; var right = 0f; var bottom = 0f
    var row = 0
    /** Index among the layout's character keys, or -1. */
    var charIndex = -1

    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
    val width get() = right - left
    val height get() = bottom - top

    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom

    val isChar get() = type == KeyType.CHAR

    /** All choices offered by the long-press popup, second-layer character first. */
    fun holdChoices(shifted: Boolean, swap: Boolean): List<String> {
        val out = ArrayList<String>()
        if (alt != null) out += if (shifted && swap) base else alt
        out += more
        return out.distinct()
    }
}

class Row(val keys: List<Key>)

class KeyboardLayout(
    val id: String,
    val name: String,
    val locale: String,
    val kind: LayoutKind,
    /** Shift swaps base and second-layer characters (Thai) instead of upper-casing. */
    val swap: Boolean,
    /** Words are separated by spaces (English) or not (Thai). */
    val usesSpaces: Boolean,
    val rows: List<Row>,
) {
    val keys: List<Key> = rows.flatMap { it.keys }.filter { it.type != KeyType.GAP }
    val charKeys: List<Key> = keys.filter { it.isChar && it.base.length == 1 }
    private val charToKey = HashMap<Char, Key>()

    init {
        charKeys.forEachIndexed { i, k -> k.charIndex = i }
        // First the base characters, then the second-layer ones, so a base
        // character always wins if it appears twice.
        for (k in charKeys) {
            val c = k.base[0]
            charToKey.putIfAbsent(c, k)
            if (c.isLetter()) charToKey.putIfAbsent(c.uppercaseChar(), k)
        }
        for (k in charKeys) {
            val a = k.alt ?: continue
            if (a.length == 1) charToKey.putIfAbsent(a[0], k)
        }
    }

    /** The key that types [c] (directly or as its second character), or null. */
    fun keyForChar(c: Char): Key? = charToKey[c]

    var width = 0f; private set
    var height = 0f; private set
    var keyWidth = 0f; private set
    var keyHeight = 0f; private set

    /** Lay out keys inside a [w] x [h] box whose top edge is at [top]. */
    fun measure(w: Float, h: Float, top: Float = 0f) {
        width = w; height = h
        val rowH = h / rows.size
        rows.forEachIndexed { r, row ->
            val total = row.keys.sumOf { it.widthPct.toDouble() }.toFloat()
            var x = if (total < 100f) (100f - total) / 2f * w / 100f else 0f
            val scale = if (total > 100f) 100f / total else 1f
            for (k in row.keys) {
                val kw = k.widthPct * scale * w / 100f
                k.left = x; k.right = x + kw
                k.top = top + r * rowH; k.bottom = top + (r + 1) * rowH
                k.row = r
                x += kw
            }
        }
        val widths = charKeys.map { it.width }.sorted()
        keyWidth = if (widths.isEmpty()) w / 10f else widths[widths.size / 2]
        keyHeight = rowH
    }

    /** The key under (x, y); falls back to the nearest key in that row. */
    fun keyAt(x: Float, y: Float): Key? {
        keys.firstOrNull { it.contains(x, y) }?.let { return it }
        var best: Key? = null
        var bestD = Float.MAX_VALUE
        for (k in keys) {
            val dy = if (y < k.top) k.top - y else if (y > k.bottom) y - k.bottom else 0f
            val dx = if (x < k.left) k.left - x else if (x > k.right) x - k.right else 0f
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    /** Nearest character key to (x, y) in the given row (or any row when [row] < 0). */
    fun nearestCharKey(x: Float, y: Float, row: Int = -1): Key? {
        var best: Key? = null
        var bestD = Float.MAX_VALUE
        for (k in charKeys) {
            if (row >= 0 && k.row != row) continue
            val dx = k.cx - x
            val dy = (k.cy - y) * 1.2f // rows are slightly "further apart" than columns
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    fun charKeysInRow(row: Int) = charKeys.filter { it.row == row }

    companion object {
        fun parse(input: InputStream): KeyboardLayout {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
            val root = doc.documentElement
            val defaultW = root.getAttribute("keyWidth").toFloatOrNull() ?: 10f
            val kind = when (root.getAttribute("kind")) {
                "numeric" -> LayoutKind.NUMERIC
                "symbol" -> LayoutKind.SYMBOL
                else -> LayoutKind.LANGUAGE
            }
            val rows = ArrayList<Row>()
            val rowNodes = root.getElementsByTagName("row")
            for (i in 0 until rowNodes.length) {
                val rowEl = rowNodes.item(i) as Element
                val keys = ArrayList<Key>()
                val children = rowEl.childNodes
                for (j in 0 until children.length) {
                    val el = children.item(j) as? Element ?: continue
                    val w = el.getAttribute("w").toFloatOrNull() ?: defaultW
                    if (el.tagName == "gap") {
                        keys += Key(KeyType.GAP, "", null, null, emptyList(), w, Shade.LIGHT)
                        continue
                    }
                    val fn = el.getAttribute("fn")
                    val type = when (fn) {
                        "" -> KeyType.CHAR
                        "backspace" -> KeyType.BACKSPACE
                        "shift" -> KeyType.SHIFT
                        "space" -> KeyType.SPACE
                        "enter" -> KeyType.ENTER
                        "language" -> KeyType.LANGUAGE
                        "numeric" -> KeyType.NUMERIC
                        "symbol" -> KeyType.SYMBOL
                        "alpha" -> KeyType.ALPHA
                        "switch" -> KeyType.SWITCH
                        else -> KeyType.GAP
                    }
                    val holdRaw = el.getAttribute("hold").ifEmpty { null }
                    // hold="…" means "label only": the popup shows the `more` list.
                    val alt = if (holdRaw == "…") null else holdRaw
                    val more = el.getAttribute("more").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    val shade = when (el.getAttribute("shade")) {
                        "medium" -> Shade.MEDIUM
                        "dark" -> Shade.DARK
                        "action" -> Shade.ACTION
                        "special" -> Shade.SPECIAL
                        else -> Shade.LIGHT
                    }
                    keys += Key(type, el.getAttribute("c"), alt, holdRaw, more, w, shade)
                }
                rows += Row(keys)
            }
            return KeyboardLayout(
                id = root.getAttribute("id"),
                name = root.getAttribute("name"),
                locale = root.getAttribute("locale"),
                kind = kind,
                swap = root.getAttribute("swap") == "true",
                usesSpaces = root.getAttribute("spaces") != "false",
                rows = rows,
            )
        }

        @Suppress("unused")
        internal fun approxEq(a: Float, b: Float) = abs(a - b) < 0.01f
    }
}
