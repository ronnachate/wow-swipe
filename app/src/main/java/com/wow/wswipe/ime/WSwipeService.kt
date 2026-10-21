package com.wow.wswipe.ime

import android.content.Context
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.content.ComponentName
import android.os.Build
import android.widget.Toast
import com.wow.wswipe.engine.Dictionary
import com.wow.wswipe.engine.EmailSuggester
import com.wow.wswipe.engine.KeyTrie
import com.wow.wswipe.engine.SwipeDecoder
import com.wow.wswipe.layout.Key
import com.wow.wswipe.layout.KeyType
import com.wow.wswipe.layout.KeyboardLayout
import com.wow.wswipe.layout.LayoutKind
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class WSwipeService : InputMethodService(), KeyboardView.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    /** Dictionary + swipe decoder for one language layout. */
    private class Engine(val dict: Dictionary, val trie: KeyTrie, val decoder: SwipeDecoder)

    private class SwipeCommit(val prefix: String, var word: String, val alternatives: List<String>, val caps: Caps) {
        val full get() = prefix + word
    }

    private enum class Caps { NONE, FIRST, ALL }

    /** What the suggestion bar is currently showing. */
    private enum class Mode { IDLE, COMPLETION, SWIPE, EMAIL }
    private var mode = Mode.IDLE
    private var emailToken = ""

    private val emails = EmailSuggester()
    private var emailsDirty = false

    // Per-field flags
    private var learningAllowed = true
    private var passwordField = false
    private var emailField = false

    private lateinit var prefs: Prefs
    private var view: KeyboardView? = null
    private val layouts = HashMap<String, KeyboardLayout>()
    private val engines = ConcurrentHashMap<String, Engine>()

    private var language = "th"
    private var current: KeyboardLayout? = null

    private var shifted = false
    private var capsLock = false
    private var lastShiftTap = 0L

    /** Characters tapped since the last word boundary (for completions). */
    private val typed = StringBuilder()
    private var lastSwipe: SwipeCommit? = null
    private var editorInfo: EditorInfo? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        language = prefs.language
        for (id in listOf(TH, EN, NUMERIC, SYMBOL)) {
            layouts[id] = assets.open("layouts/$id.xml").use { KeyboardLayout.parse(it) }
        }
        try { emailsFile().takeIf { it.exists() }?.let { emails.load(it.readText()) } } catch (_: Exception) { }
        // Build dictionaries off the UI thread; swipes work as soon as they're ready.
        thread(name = "wswipe-dict") {
            for ((layoutId, dictName) in listOf(TH to "th", EN to "en")) {
                try {
                    val dict = assets.open("dict/$dictName.tsv").use { Dictionary.load(it) }
                    loadLearned(dict, dictName)
                    val layout = layouts.getValue(layoutId)
                    val trie = KeyTrie(layout, dict)
                    engines[layoutId] = Engine(dict, trie, SwipeDecoder(layout, trie))
                } catch (t: Throwable) {
                    android.util.Log.e("WSwipe", "dictionary $dictName failed", t)
                }
            }
        }
    }

    override fun onDestroy() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        saveLearned()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this)
        v.listener = this
        view = v
        applyPrefs()
        return v
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) = applyPrefs()

    private fun applyPrefs() {
        val v = view ?: return
        v.longPressDelay = prefs.longPressDelay.toLong()
        v.haptics = prefs.haptics
        v.showTrail = prefs.showTrail
        v.swipeEnabled = prefs.swipeEnabled && !passwordField
        v.heightPercent = prefs.heightPercent
        v.emailMode = emailField
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorInfo = info
        // Follow the language picked in the system keyboard picker.
        // (Android 9+ only: older versions can't sync back when the TH/EN key is used.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            langOf(imm().currentInputMethodSubtype)?.let { if (it != language) { language = it; prefs.language = it } }
        }
        resetWordState()
        capsLock = false
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME
        passwordField = (cls == InputType.TYPE_CLASS_TEXT && variation in listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        emailField = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        val uriField = cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI
        // Respect incognito / "no personalised learning" requests from apps.
        learningAllowed = !passwordField && (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        applyPrefs()
        setLayout(when {
            numeric -> NUMERIC
            emailField || uriField -> EN   // addresses are typed in English, like Gboard
            else -> languageLayoutId()
        })
        view?.enterLabel = enterLabel(info)
        updateAutoCaps()
        refreshSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        learnEmailAtCursor()
        saveLearned()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // The user moved the cursor or selected text: forget the word in progress.
        if (newSelStart != newSelEnd) { resetWordState(); showSuggestions(emptyList()); return }
        val before = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: return
        val ls = lastSwipe
        if (ls != null && !before.endsWith(ls.full)) { lastSwipe = null; refreshSuggestions() }
        if (typed.isNotEmpty() && !before.endsWith(typed)) { typed.setLength(0); refreshSuggestions() }
    }

    // ---- KeyboardView.Listener ------------------------------------------------

    override fun onText(text: String) {
        val ic = currentInputConnection ?: return
        val l = current ?: return
        acceptSwipe()
        val isWordChar = text.length == 1 && (text[0].isLetter() || Character.getType(text[0]) == Character.NON_SPACING_MARK.toInt())
        if (isWordChar && l.kind == LayoutKind.LANGUAGE) typed.append(text) else typed.setLength(0)
        ic.commitText(text, 1)
        if (shifted && !capsLock) setShift(false)
        updateAutoCaps()
        refreshSuggestions()
    }

    override fun onFunctionKey(key: Key) {
        val ic = currentInputConnection ?: return
        when (key.type) {
            KeyType.SPACE -> {
                acceptSwipe()
                learnTyped()
                learnEmailAtCursor()
                ic.commitText(" ", 1)
                updateAutoCaps()
                refreshSuggestions()
            }
            KeyType.ENTER -> {
                acceptSwipe(); learnTyped(); learnEmailAtCursor()
                val info = editorInfo
                val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                val noEnterAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
                if (info != null && !noEnterAction && action != EditorInfo.IME_ACTION_NONE &&
                    action != EditorInfo.IME_ACTION_UNSPECIFIED
                ) ic.performEditorAction(action)
                else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                updateAutoCaps()
                refreshSuggestions()
            }
            KeyType.SHIFT -> {
                val now = System.currentTimeMillis()
                when {
                    capsLock -> { capsLock = false; setShift(false) }
                    shifted && now - lastShiftTap < 400 -> { capsLock = true; setShift(true) }
                    else -> setShift(!shifted)
                }
                lastShiftTap = now
            }
            KeyType.LANGUAGE -> {
                changeLanguage(if (language == "th") "en" else "th")
                syncSystemSubtype()
            }
            KeyType.NUMERIC -> { resetWordState(); setLayout(NUMERIC); refreshSuggestions() }
            KeyType.SYMBOL -> { resetWordState(); setLayout(SYMBOL); refreshSuggestions() }
            KeyType.ALPHA -> { resetWordState(); setLayout(languageLayoutId()); updateAutoCaps(); refreshSuggestions() }
            KeyType.SWITCH -> showPicker()
            else -> Unit
        }
    }

    override fun onFunctionLongPress(key: Key): Boolean = when (key.type) {
        KeyType.LANGUAGE, KeyType.SWITCH -> { showPicker(); true }
        else -> false
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        val ls = lastSwipe
        if (ls != null) {
            // After a swipe, backspace removes the whole swiped word (and its auto space).
            val before = ic.getTextBeforeCursor(ls.full.length, 0)?.toString()
            lastSwipe = null
            if (before == ls.full) {
                ic.deleteSurroundingText(ls.full.length, 0)
                updateAutoCaps()
                refreshSuggestions()
                return
            }
        }
        val sel = ic.getSelectedText(0)
        if (sel.isNullOrEmpty()) {
            ic.deleteSurroundingText(1, 0)
            // Don't leave half a surrogate pair (emoji) behind.
            val prev = ic.getTextBeforeCursor(1, 0)
            if (prev != null && prev.length == 1 && Character.isHighSurrogate(prev[0])) ic.deleteSurroundingText(1, 0)
        } else {
            ic.commitText("", 1)
        }
        if (typed.isNotEmpty()) typed.setLength(typed.length - 1)
        updateAutoCaps()
        refreshSuggestions()
    }

    override fun onCursorMove(steps: Int) {
        resetWordState()
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
    }

    override fun onSwipe(xs: FloatArray, ys: FloatArray, count: Int) {
        val ic = currentInputConnection ?: return
        val l = current ?: return
        val engine = engines[l.id] ?: return
        val cands = engine.decoder.decode(xs, ys, count, 8).map { it.word }
        if (cands.isEmpty()) return
        acceptSwipe()
        learnTyped()
        val caps = when {
            capsLock -> Caps.ALL
            shifted -> Caps.FIRST
            else -> Caps.NONE
        }
        var prefix = ""
        if (l.usesSpaces) {
            val before = ic.getTextBeforeCursor(1, 0)
            if (!before.isNullOrEmpty() && !before[0].isWhitespace() && before[0] !in "([{\"'/@#") prefix = " "
        }
        val word = applyCaps(cands[0], caps)
        ic.commitText(prefix + word, 1)
        lastSwipe = SwipeCommit(prefix, word, cands.distinct().take(3), caps)
        if (shifted && !capsLock) setShift(false)
        mode = Mode.SWIPE
        showSuggestions(lastSwipe!!.alternatives.map { applyCaps(it, caps) }, focus = 0)
    }

    override fun onSuggestion(index: Int) {
        val ic = currentInputConnection ?: return
        val l = current ?: return
        val v = view ?: return
        val chosen = v.suggestions.getOrNull(index) ?: return
        when (mode) {
            Mode.SWIPE -> {
                val ls = lastSwipe ?: return
                val before = ic.getTextBeforeCursor(ls.full.length, 0)?.toString()
                if (before == ls.full) {
                    ic.deleteSurroundingText(ls.full.length, 0)
                    ic.commitText(ls.prefix + chosen, 1)
                    ls.word = chosen
                    learn(l.id, chosen, fromTyping = false)
                    showSuggestions(v.suggestions, focus = index)
                }
            }
            Mode.COMPLETION -> {
                val word = completionBase
                val before = ic.getTextBeforeCursor(word.length, 0)?.toString()
                if (word.isNotEmpty() && before == word) {
                    ic.deleteSurroundingText(word.length, 0)
                    val out = matchCase(chosen, word)
                    ic.commitText(if (l.usesSpaces) "$out " else out, 1)
                    learn(l.id, chosen, fromTyping = false)
                }
                typed.setLength(0)
                completionBase = ""
                updateAutoCaps()
                refreshSuggestions()
            }
            Mode.EMAIL -> {
                val token = emailToken
                val before = ic.getTextBeforeCursor(token.length, 0)?.toString()
                if (token.isNotEmpty() && before == token) {
                    ic.deleteSurroundingText(token.length, 0)
                    ic.commitText(chosen, 1)
                    if (learningAllowed && emails.learn(chosen)) emailsDirty = true
                }
                typed.setLength(0)
                refreshSuggestions()
            }
            Mode.IDLE -> {
                // Common word picked from the empty bar: type it as a whole word.
                acceptSwipe()
                var prefix = ""
                if (l.usesSpaces) {
                    val b = ic.getTextBeforeCursor(1, 0)
                    if (!b.isNullOrEmpty() && !b[0].isWhitespace()) prefix = " "
                }
                val word = if (shifted || capsLock) applyCaps(chosen, if (capsLock) Caps.ALL else Caps.FIRST) else chosen
                ic.commitText(prefix + word, 1)
                lastSwipe = SwipeCommit(prefix, word, listOf(word), Caps.NONE)
                if (shifted && !capsLock) setShift(false)
                learn(l.id, chosen, fromTyping = false)
                mode = Mode.SWIPE
                showSuggestions(listOf(word))
            }
        }
    }

    override fun onSuggestionLongPress(index: Int) {
        val l = current ?: return
        val v = view ?: return
        val word = v.suggestions.getOrNull(index) ?: return
        if (mode == Mode.EMAIL) {
            emails.forget(word); emailsDirty = true
        } else {
            val e = engines[l.id] ?: return
            val key = when { e.dict.contains(word) -> word; e.dict.contains(word.lowercase()) -> word.lowercase(); else -> return }
            e.dict.remove(key)
            synchronized(learnedCounts) {
                learnedCounts.getOrPut(l.id) { HashMap() }[key] = -1
                learnedDirty += l.id
            }
        }
        Toast.makeText(this, "Removed \u201C$word\u201D from suggestions", Toast.LENGTH_SHORT).show()
        if (mode == Mode.SWIPE) showSuggestions(v.suggestions.filterIndexed { i, _ -> i != index })
        else refreshSuggestions()
    }

    // ---- helpers ------------------------------------------------------------

    private fun languageLayoutId() = if (language == "en") EN else TH

    private fun setLayout(id: String) {
        val l = layouts[id] ?: return
        current = l
        if (l.kind != LayoutKind.LANGUAGE || l.id == TH) { shifted = false; capsLock = false }
        view?.let {
            it.layout = l
            it.languageLabel = if (language == "en") "EN" else "TH"
            it.spaceLabel = layouts[languageLayoutId()]?.name ?: ""
            it.shifted = shifted
            it.capsLock = capsLock
        }
    }

    private fun setShift(on: Boolean) {
        shifted = on
        if (!on) capsLock = false
        view?.shifted = shifted
        view?.capsLock = capsLock
    }

    /** English only: start sentences with a capital. */
    private fun updateAutoCaps() {
        val l = current ?: return
        if (l.id != EN || capsLock) return
        val info = editorInfo ?: return
        val ic = currentInputConnection ?: return
        val caps = info.inputType != InputType.TYPE_NULL && ic.getCursorCapsMode(info.inputType) != 0
        setShift(caps)
    }

    private fun applyCaps(w: String, caps: Caps) = when (caps) {
        Caps.ALL -> w.uppercase()
        Caps.FIRST -> w.replaceFirstChar { it.uppercaseChar() }
        Caps.NONE -> w
    }

    private fun matchCase(word: String, typedWord: String): String = when {
        typedWord.length > 1 && typedWord.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        typedWord.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
        else -> word
    }

    /** The part of [typed] that completions are based on (Thai has no spaces, so
     *  this is the longest tail of the typed run that starts a dictionary word). */
    private var completionBase = ""

    /**
     * Decide what the suggestion bar shows: e-mail completions, word completions,
     * or (when idle) the most common words.
     */
    private fun refreshSuggestions() {
        val l = current
        completionBase = ""
        if (l == null || passwordField) { mode = Mode.IDLE; showSuggestions(emptyList()); return }
        // 1. E-mail addresses
        val token = emailTokenBeforeCursor()
        if (token.isNotEmpty() && ('@' in token || emailField)) {
            val at = token.indexOf('@')
            // Two wide columns; after "@" only the domain is shown ("@gmail.com").
            val list = emails.suggest(token, 2)
            if (list.isNotEmpty() || emailField) {
                mode = Mode.EMAIL; emailToken = token
                val local = if (at >= 0) token.substring(0, at).lowercase() else null
                val labels = if (local == null) null else list.map {
                    if (it.substringBefore('@') == local) "@" + it.substringAfter('@') else it
                }
                showSuggestions(list, labels = labels); return
            }
        }
        val engine = engines[l.id]
        // 2. Completions of the word being typed
        if (typed.isNotEmpty() && engine != null) {
            val run = typed.toString()
            var base = run
            if (!l.usesSpaces) {
                base = ""
                for (len in minOf(run.length, 16) downTo 1) {
                    val tail = run.substring(run.length - len)
                    if (engine.dict.hasPrefix(tail)) { base = tail; break }
                }
            }
            val comps = if (base.isEmpty()) emptyList()
                else (engine.dict.completions(base.lowercase(), 3) + engine.dict.completions(base, 3))
                    .distinct().sortedByDescending { engine.dict.frequency(engine.dict.idOf(it)) }.take(3)
            if (comps.isNotEmpty()) {
                completionBase = run.substring(run.length - base.length)
                mode = Mode.COMPLETION
                showSuggestions(comps.map { if (engine.dict.isUserWord(engine.dict.idOf(it))) it else matchCase(it, completionBase) })
                return
            }
        }
        // 3. Idle: common words (not in e-mail fields or number/symbol pads)
        mode = Mode.IDLE
        if (engine == null || emailField || l.kind != LayoutKind.LANGUAGE) { showSuggestions(emptyList()); return }
        val idle = if (l.id == TH) IDLE_TH.filter { !engine.dict.isRemoved(engine.dict.idOf(it)) }
            else engine.dict.topWords(3)
        showSuggestions(idle.map { if (shifted || capsLock) applyCaps(it, Caps.FIRST) else it })
    }

    /** Trailing run of address characters before the cursor ("ron@gm"). */
    private fun emailTokenBeforeCursor(): String {
        val before = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: return ""
        var i = before.length
        while (i > 0 && EmailSuggester.isEmailChar(before[i - 1])) i--
        return before.substring(i)
    }

    private fun learnEmailAtCursor() {
        if (!learningAllowed) return
        val t = emailTokenBeforeCursor()
        if ('@' in t && emails.learn(t)) emailsDirty = true
    }

    private fun showSuggestions(list: List<String>, focus: Int = 0, labels: List<String>? = null) {
        view?.suggestionFocus = focus
        view?.suggestionLabels = labels
        view?.suggestions = list
    }

    private fun acceptSwipe() {
        val ls = lastSwipe ?: return
        current?.let { learn(it.id, ls.word, fromTyping = false) }
        lastSwipe = null
    }

    private fun learnTyped() {
        val l = current ?: return
        if (typed.isNotEmpty()) learn(l.id, typed.toString(), fromTyping = true)
        typed.setLength(0)
    }

    private val learnedDirty = HashSet<String>()
    /** Per layout: word → times used (−1 = removed by the user). */
    private val learnedCounts = HashMap<String, HashMap<String, Int>>()

    /**
     * The user committed [word]. Known words rank higher next time; a new word
     * (name, slang …) typed by hand is added to the dictionary — and becomes
     * swipeable — the second time it's used.
     */
    private fun learn(layoutId: String, word: String, fromTyping: Boolean) {
        if (!learningAllowed || word.isEmpty()) return
        val e = engines[layoutId] ?: return
        val d = e.dict
        val known = when {
            d.contains(word) -> word
            d.contains(word.lowercase()) -> word.lowercase()
            else -> null
        }
        if (known != null) {
            d.learn(known)
            bump(layoutId, known)
            return
        }
        if (!fromTyping || !looksLikeWord(layoutId, word)) return
        if (bump(layoutId, word) >= 2) {
            val id = d.addWord(word)
            e.trie.insert(id)
            d.learn(word)
        }
    }

    private fun bump(layoutId: String, word: String): Int = synchronized(learnedCounts) {
        learnedDirty += layoutId
        val m = learnedCounts.getOrPut(layoutId) { HashMap() }
        val n = maxOf(0, m[word] ?: 0) + 1
        m[word] = n
        n
    }

    /** Only learn things that look like real words for that layout. */
    private fun looksLikeWord(layoutId: String, w: String): Boolean {
        val layout = layouts[layoutId] ?: return false
        if (w.length < 2 || w.length > (if (layoutId == TH) 15 else 24)) return false
        if (w.any { layout.keyForChar(it) == null }) return false
        return if (layoutId == TH) w.all { it in '\u0E01'..'\u0E4E' }
        else w.all { it.isLetter() } && w.drop(1).count { it.isUpperCase() } <= 1
    }

    private fun learnedFile(dictName: String) = File(filesDir, "learned_$dictName.tsv")
    private fun emailsFile() = File(filesDir, "emails.tsv")

    private fun loadLearned(dict: Dictionary, dictName: String) {
        val f = learnedFile(dictName)
        if (!f.exists()) return
        val layoutId = if (dictName == "th") TH else EN
        val counts = HashMap<String, Int>()
        f.forEachLine { line ->
            val p = line.split('\t')
            val n = p.getOrNull(1)?.toIntOrNull() ?: return@forEachLine
            val w = p[0]
            counts[w] = n
            when {
                n < 0 -> dict.remove(w)
                dict.contains(w) -> repeat(minOf(n, 20)) { dict.learn(w) }
                n >= 2 -> { dict.addWord(w); repeat(minOf(n - 1, 20)) { dict.learn(w) } }
                // n == 1: seen once, waits for a second use
            }
        }
        synchronized(learnedCounts) { learnedCounts[layoutId] = counts }
    }

    private fun saveLearned() {
        synchronized(learnedCounts) {
            for (layoutId in learnedDirty) {
                val counts = learnedCounts[layoutId] ?: continue
                val name = if (layoutId == TH) "th" else "en"
                try {
                    learnedFile(name).writeText(counts.entries.joinToString("\n") { "${it.key}\t${it.value}" })
                } catch (_: Exception) { }
            }
            learnedDirty.clear()
        }
        if (emailsDirty) {
            try { emailsFile().writeText(emails.serialize()) } catch (_: Exception) { }
            emailsDirty = false
        }
    }

    private fun resetWordState() {
        typed.setLength(0)
        lastSwipe = null
    }

    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun showPicker() = imm().showInputMethodPicker()

    private val imeId get() = ComponentName(this, WSwipeService::class.java).flattenToShortString()

    private fun langOf(subtype: InputMethodSubtype?): String? {
        val tag = (subtype?.languageTag?.ifEmpty { null } ?: @Suppress("DEPRECATION") subtype?.locale) ?: return null
        return when {
            tag.startsWith("th") -> "th"
            tag.startsWith("en") -> "en"
            else -> null
        }
    }

    private fun changeLanguage(lang: String) {
        if (lang == language && current?.kind == LayoutKind.LANGUAGE) return
        language = lang
        prefs.language = lang
        resetWordState()
        setLayout(languageLayoutId())
        updateAutoCaps()
        refreshSuggestions()
    }

    /** User picked ไทย / English in the system keyboard picker. */
    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        langOf(newSubtype)?.let { changeLanguage(it) }
    }

    /** Make the system picker show the language chosen with the TH/EN key. */
    private fun syncSystemSubtype() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val info = imm().enabledInputMethodList.firstOrNull { it.id == imeId } ?: return
            val sub = imm().getEnabledInputMethodSubtypeList(info, true).firstOrNull { langOf(it) == language } ?: return
            switchInputMethod(imeId, sub)
        } catch (_: Exception) { }
    }

    private fun enterLabel(info: EditorInfo): String {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return "⏎"
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "🔍"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            else -> "⏎"
        }
    }

    companion object {
        const val TH = "th_kedmanee_11"
        /** Idle bar for Thai, left → right: สวัสดี | ขอบคุณ | มี (the bar draws item 0 in the middle). */
        private val IDLE_TH = listOf("ขอบคุณ", "สวัสดี", "มี")
        const val EN = "en_qwerty"
        const val NUMERIC = "numeric"
        const val SYMBOL = "symbol"
    }
}
