package com.wow.wswipe

import com.wow.wswipe.engine.Dictionary
import com.wow.wswipe.engine.KeyTrie
import com.wow.wswipe.engine.SwipeDecoder
import com.wow.wswipe.layout.Key
import com.wow.wswipe.layout.KeyType
import com.wow.wswipe.layout.KeyboardLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class SwipeDecoderTest {

    private val assets = listOf("src/main/assets", "app/src/main/assets").map(::File).first { it.exists() }

    private fun layout(id: String) =
        KeyboardLayout.parse(File(assets, "layouts/$id.xml").inputStream()).also { it.measure(1080f, 800f) }

    private fun dict(name: String) = Dictionary.load(File(assets, "dict/$name.tsv").inputStream())

    /** A finger-like path through the word's keys, with jitter and rounded corners. */
    private fun swipe(l: KeyboardLayout, word: String, rnd: Random, noise: Float): Pair<FloatArray, FloatArray> {
        val keys = ArrayList<Key>()
        for (c in word) { val k = l.keyForChar(c)!!; if (keys.isEmpty() || keys.last() !== k) keys += k }
        val cx = keys.map { it.cx + (rnd.nextFloat() * 2 - 1) * noise * l.keyWidth }
        val cy = keys.map { it.cy + (rnd.nextFloat() * 2 - 1) * noise * l.keyHeight }
        var x = ArrayList<Float>(); var y = ArrayList<Float>()
        for (i in 0 until keys.size - 1) for (s in 0 until 12) {
            val t = s / 12f
            x += cx[i] + (cx[i + 1] - cx[i]) * t; y += cy[i] + (cy[i + 1] - cy[i]) * t
        }
        x += cx.last(); y += cy.last()
        repeat(2) {
            val nx = ArrayList<Float>(); val ny = ArrayList<Float>()
            nx += x[0]; ny += y[0]
            for (i in 0 until x.size - 1) {
                nx += 0.75f * x[i] + 0.25f * x[i + 1]; ny += 0.75f * y[i] + 0.25f * y[i + 1]
                nx += 0.25f * x[i] + 0.75f * x[i + 1]; ny += 0.25f * y[i] + 0.75f * y[i + 1]
            }
            nx += x.last(); ny += y.last(); x = nx; y = ny
        }
        return x.toFloatArray() to y.toFloatArray()
    }

    @Test
    fun thaiLayout11Columns() {
        val l = layout("th_kedmanee_11")
        assertEquals(5, l.rows.size)
        assertEquals(listOf("ๆ", "ๅ", "ภ", "ถ", "ุ", "ึ", "ค", "ต", "จ", "ข", "ช"), l.rows[0].keys.map { it.base })
        assertEquals(listOf("ฟ", "ห", "ก", "ด", "เ", "้", "่", "า", "ส", "ว", "ง"), l.rows[2].keys.map { it.base })
        assertEquals("็", l.keyForChar('้')!!.alt)
        assertEquals("๑", l.keyForChar('ๆ')!!.alt)
        assertEquals(11, l.rows[3].keys.size)
        assertEquals(KeyType.BACKSPACE, l.rows[3].keys.last().type)
        assertEquals(KeyType.SHIFT, l.rows[4].keys.last().type)
        for (c in 'ก'..'ฮ') if (c != 'ฃ' && c != 'ฅ') assertNotNull("missing $c", l.keyForChar(c))
    }

    @Test
    fun swipeCommonThaiWords() = checkAccuracy("th_kedmanee_11", "th", minTop1 = 85, minTop3 = 97)

    @Test
    fun swipeCommonEnglishWords() = checkAccuracy("en_qwerty", "en", minTop1 = 85, minTop3 = 97)

    @Test
    fun exactThaiExamples() {
        val l = layout("th_kedmanee_11")
        val d = dict("th")
        val dec = SwipeDecoder(l, KeyTrie(l, d))
        val rnd = Random(1)
        for (w in listOf("ที่", "การ", "เป็น", "ไม่", "ประเทศ", "สวัสดี", "ขอบคุณ")) {
            val (x, y) = swipe(l, w, rnd, 0.1f)
            val top = dec.decode(x, y, x.size).map { it.word }
            assertTrue("$w not in ${top.take(3)}", w in top.take(3))
        }
    }

    private fun checkAccuracy(layoutId: String, dictName: String, minTop1: Int, minTop3: Int) {
        val l = layout(layoutId)
        val d = dict(dictName)
        val dec = SwipeDecoder(l, KeyTrie(l, d))
        val rnd = Random(7)
        val pool = (0 until 2000).map { d.word(it) }.filter { it.length >= 2 }
        var top1 = 0; var top3 = 0
        val n = 200
        repeat(n) {
            val w = pool[rnd.nextInt(pool.size)]
            val (x, y) = swipe(l, w, rnd, 0.25f)
            val r = dec.decode(x, y, x.size).map { it.word }
            if (r.firstOrNull() == w) top1++
            if (w in r.take(3)) top3++
        }
        println("$layoutId: top1=${top1 * 100 / n}% top3=${top3 * 100 / n}%")
        assertTrue("top1 ${top1 * 100 / n}%", top1 * 100 / n >= minTop1)
        assertTrue("top3 ${top3 * 100 / n}%", top3 * 100 / n >= minTop3)
    }
}
