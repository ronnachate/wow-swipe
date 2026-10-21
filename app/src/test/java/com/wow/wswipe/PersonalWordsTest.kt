package com.wow.wswipe

import com.wow.wswipe.engine.Dictionary
import com.wow.wswipe.engine.EmailSuggester
import com.wow.wswipe.engine.KeyTrie
import com.wow.wswipe.engine.SwipeDecoder
import com.wow.wswipe.layout.Key
import com.wow.wswipe.layout.KeyboardLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersonalWordsTest {
    private val assets = listOf("src/main/assets", "app/src/main/assets").map(::File).first { it.exists() }
    private fun layout(id: String) =
        KeyboardLayout.parse(File(assets, "layouts/$id.xml").inputStream()).also { it.measure(1080f, 800f) }
    private fun dict(name: String) = Dictionary.load(File(assets, "dict/$name.tsv").inputStream())

    /** Straight-line path through the word's keys. */
    private fun path(l: KeyboardLayout, w: String): Pair<FloatArray, FloatArray> {
        val keys = ArrayList<Key>()
        for (c in w) { val k = l.keyForChar(c)!!; if (keys.isEmpty() || keys.last() !== k) keys += k }
        val x = ArrayList<Float>(); val y = ArrayList<Float>()
        for (i in 0 until keys.size - 1) for (s in 0 until 10) {
            val t = s / 10f
            x += keys[i].cx + (keys[i + 1].cx - keys[i].cx) * t; y += keys[i].cy + (keys[i + 1].cy - keys[i].cy) * t
        }
        x += keys.last().cx; y += keys.last().cy
        return x.toFloatArray() to y.toFloatArray()
    }

    @Test
    fun learnedNameCanBeCompletedAndSwiped() {
        val l = layout("en_qwerty"); val d = dict("en")
        val trie = KeyTrie(l, d); val dec = SwipeDecoder(l, trie)
        assertFalse(d.contains("Ronnachate"))
        val id = d.addWord("Ronnachate"); trie.insert(id); d.learn("Ronnachate")
        assertTrue("completion", "Ronnachate" in d.completions("ron", 3))
        val (x, y) = path(l, "ronnachate")
        val top = dec.decode(x, y, x.size).map { it.word }
        assertTrue("swipe: $top", "Ronnachate" in top.take(3))
    }

    @Test
    fun learnedThaiWordCanBeSwiped() {
        val l = layout("th_kedmanee_11"); val d = dict("th")
        val trie = KeyTrie(l, d); val dec = SwipeDecoder(l, trie)
        val w = "รณชาติ"
        val id = d.addWord(w); trie.insert(id); d.learn(w)
        val (x, y) = path(l, w)
        val top = dec.decode(x, y, x.size).map { it.word }
        assertTrue("swipe: $top", w in top.take(3))
    }

    @Test
    fun removedWordIsNotSuggested() {
        val l = layout("en_qwerty"); val d = dict("en")
        val dec = SwipeDecoder(l, KeyTrie(l, d))
        assertTrue("there" in d.completions("ther", 3))
        d.remove("there")
        assertFalse("there" in d.completions("ther", 3))
        val (x, y) = path(l, "there")
        assertFalse("there" in dec.decode(x, y, x.size).map { it.word })
    }

    @Test
    fun manyLearnedWordsStayFast() {
        val l = layout("en_qwerty"); val d = dict("en")
        val trie = KeyTrie(l, d); val dec = SwipeDecoder(l, trie)
        val letters = "abcdefghijklmnopqrstuvwxyz"
        val r = kotlin.random.Random(3)
        repeat(3000) { val w = (1..(4 + r.nextInt(6))).map { letters[r.nextInt(26)] }.joinToString(""); trie.insert(d.addWord("Zq$w")) }
        val (x, y) = path(l, "keyboard")
        repeat(5) { dec.decode(x, y, x.size) }
        val t0 = System.nanoTime()
        repeat(50) { dec.decode(x, y, x.size) }
        val ms = (System.nanoTime() - t0) / 50 / 1e6
        println("decode with 3000 user words: ${"%.2f".format(ms)} ms")
        assertTrue("too slow: $ms ms", ms < 20)
        assertEquals("keyboard", dec.decode(x, y, x.size).first().word)
    }

    @Test
    fun emailSuggestions() {
        val e = EmailSuggester()
        assertEquals("ron@gmail.com", e.suggest("ron@").first())
        assertEquals(listOf("ron@hotmail.com"), e.suggest("ron@h"))
        assertTrue(e.suggest("ron").isEmpty())
        assertTrue(e.learn("Ronnachate@wow.co.th"))
        assertFalse(e.learn("not an email"))
        assertEquals("ronnachate@wow.co.th", e.suggest("ron").first())
        assertEquals("ron@wow.co.th", e.suggest("ron@").first())  // your own domain comes first
        assertTrue("ron@gmail.com" in e.suggest("ron@"))
        e.forget("ron@gmail.com")
        assertFalse("ron@gmail.com" in e.suggest("ron@", 10))
        val copy = EmailSuggester().apply { load(e.serialize()) }
        assertEquals(e.suggest("ron@", 10), copy.suggest("ron@", 10))
    }
}
