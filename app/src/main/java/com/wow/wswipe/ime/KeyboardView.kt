package com.wow.wswipe.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.wow.wswipe.layout.Key
import com.wow.wswipe.layout.KeyType
import com.wow.wswipe.layout.KeyboardLayout
import com.wow.wswipe.layout.LayoutKind
import com.wow.wswipe.layout.Shade
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the keyboard and turns touches into taps,
 * long-presses (second character + popup) and swipes.
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onFunctionKey(key: Key)
        fun onFunctionLongPress(key: Key): Boolean
        fun onSwipe(xs: FloatArray, ys: FloatArray, count: Int)
        fun onSuggestion(index: Int)
        /** Long-press on a suggestion: forget that word. */
        fun onSuggestionLongPress(index: Int)
        fun onCursorMove(steps: Int)
        fun onBackspace()
    }

    var listener: Listener? = null

    var layout: KeyboardLayout? = null
        set(v) { field = v; measureLayout(); invalidate() }

    var shifted = false
        set(v) { field = v; invalidate() }
    var capsLock = false
        set(v) { field = v; invalidate() }

    /** Suggestion strip contents; [suggestionFocus] is drawn bold. */
    var suggestions: List<String> = emptyList()
        set(v) { field = v; invalidate() }
    var suggestionFocus = 0
    /** Optional shorter text to draw for each suggestion (e.g. "@gmail.com"). */
    var suggestionLabels: List<String>? = null

    var languageLabel = "TH"
    var spaceLabel = ""
    var enterLabel = "⏎"
    var longPressDelay = 250L
    var haptics = true
    var showTrail = true
    var swipeEnabled = true
    /** E-mail field: the comma key becomes "@", like Gboard. */
    var emailMode = false
        set(v) { field = v; invalidate() }
    var heightPercent = 100
        set(v) { field = v; requestLayout() }

    private val dp = resources.displayMetrics.density
    private val stripH get() = 42 * dp
    private val rowH: Float
        get() {
            val landscape = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            return 54 * dp * heightPercent / 100f * (if (landscape) 0.72f else 1f)
        }

    // ---- colours -------------------------------------------------------------
    private val cBackground = Color.rgb(90, 90, 90)
    private val cStrip = Color.rgb(70, 70, 70)
    private val cStripLine = Color.rgb(50, 50, 50)
    private val cHold = Color.rgb(50, 200, 240)          // accent: TH label, Shift, trail
    private val cPopupSel = Color.rgb(160, 182, 198)     // selected popup choice: soft blue-grey
    private val cText = Color.BLACK
    private val cAltText = Color.rgb(70, 70, 70)
    private val cLightText = Color.WHITE

    private fun keyColor(k: Key, down: Boolean, hold: Boolean): Int = when (k.shade) {
        // Held keys get a muted blue-grey that stays close to the key's own grey.
        Shade.LIGHT -> if (hold) Color.rgb(122, 136, 148) else if (down) Color.rgb(100, 100, 100) else Color.rgb(150, 150, 150)
        Shade.MEDIUM -> if (hold) Color.rgb(98, 112, 124) else if (down) Color.rgb(70, 70, 70) else Color.rgb(120, 120, 120)
        Shade.DARK, Shade.ACTION -> if (hold) Color.rgb(58, 70, 82) else if (down) Color.rgb(50, 50, 50) else Color.rgb(70, 70, 70)
        // Same cyan as the "W" in the app icon (#32C8F0); deeper blue when pressed / Shift on.
        Shade.SPECIAL -> if (hold) Color.rgb(20, 130, 180) else if (down) Color.rgb(35, 165, 205) else Color.rgb(50, 200, 240)
    }

    /** Medium (500) weight for key characters; between regular and bold. */
    private val keyTypeface: Typeface =
        if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false)
        else Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(200, 50, 200, 240)
    }
    private val rect = RectF()

    // ---- touch state --------------------------------------------------------
    private val handler = Handler(Looper.getMainLooper())
    private var activePointer = -1
    private var downKey: Key? = null
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var stripDown = -1
    private var spaceDragging = false
    private var spaceStepsSent = 0
    private var xs = FloatArray(512)
    private var ys = FloatArray(512)
    private var count = 0

    // long-press popup
    private var popupKey: Key? = null
    private var popupChoices: List<String> = emptyList()
    private var popupSel = 0
    private val popupRect = RectF()
    private var popupCellW = 0f

    private val longPressRunnable = Runnable { onLongPress() }
    private var stripLongFired = false
    private val stripLongPress = Runnable {
        val n = min(suggestions.size, 3)
        if (stripDown in 0 until n) {
            stripLongFired = true
            if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onSuggestionLongPress(orderFor(n)[stripDown])
        }
    }
    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (downKey?.type == KeyType.BACKSPACE) {
                listener?.onBackspace()
                handler.postDelayed(this, 55)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rows = layout?.rows?.size ?: 5
        setMeasuredDimension(w, (stripH + rows * rowH).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = measureLayout()

    private fun measureLayout() {
        val l = layout ?: return
        if (width == 0) { requestLayout(); return }
        l.measure(width.toFloat(), height - stripH, stripH)
        if (l.rows.size * rowH + stripH != height.toFloat()) requestLayout()
    }

    // ---- drawing ------------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        canvas.drawColor(cBackground)
        drawStrip(canvas)
        val mx = 2.5f * dp
        val my = 4f * dp
        val radius = 7 * dp
        for (k in l.keys) {
            val pressed = k === downKey && !swiping && popupKey == null
            val holding = k === popupKey ||
                (k.type == KeyType.SHIFT && (shifted || capsLock))
            val gx = min(mx, k.width * 0.04f)   // thinner gaps on narrow keys
            rect.set(k.left + gx, k.top + my, k.right - gx, k.bottom - my)
            fill.color = keyColor(k, pressed, holding)
            canvas.drawRoundRect(rect, radius, radius, fill)
            drawLabel(canvas, l, k)
        }
        if (showTrail && swiping && count > 1) {
            trailPaint.strokeWidth = 5 * dp
            val p = Path()
            p.moveTo(xs[0], ys[0])
            for (i in 1 until count) p.lineTo(xs[i], ys[i])
            canvas.drawPath(p, trailPaint)
        }
        if (popupKey != null) drawPopup(canvas, l)
    }

    private fun drawStrip(canvas: Canvas) {
        fill.color = cStrip
        canvas.drawRect(0f, 0f, width.toFloat(), stripH, fill)
        fill.color = cStripLine
        canvas.drawRect(0f, stripH - dp, width.toFloat(), stripH, fill)
        val s = suggestions
        if (s.isEmpty()) return
        val n = min(s.size, 3)
        val cellW = width / n.toFloat()
        for (i in 0 until n) {
            val cx = cellW * i + cellW / 2f
            if (stripDown == i) {
                fill.color = Color.rgb(120, 120, 120)
                canvas.drawRect(cellW * i, 0f, cellW * (i + 1), stripH - dp, fill)
            }
            text.color = cLightText
            text.typeface = if (i == slotOf(suggestionFocus, n)) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            text.textSize = 17 * dp
            val idx = orderFor(n)[i]
            val word = suggestionLabels?.getOrNull(idx) ?: s[idx]
            canvas.drawText(ellipsize(word, cellW - 8 * dp), cx, stripH / 2f + 6 * dp, text)
            if (i > 0) {
                fill.color = Color.rgb(150, 150, 150)
                canvas.drawRect(cellW * i, stripH * 0.25f, cellW * i + dp, stripH * 0.75f, fill)
            }
        }
    }

    /** With 3 suggestions the best one (index 0) goes in the middle. */
    private fun orderFor(n: Int) = if (n == 3) intArrayOf(1, 0, 2) else IntArray(n) { it }
    private fun slotOf(index: Int, n: Int) = orderFor(n).indexOf(index)

    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 1 && text.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    private fun drawLabel(canvas: Canvas, l: KeyboardLayout, k: Key) {
        val h = k.height
        val cy = k.cy
        text.typeface = Typeface.DEFAULT
        when (k.type) {
            KeyType.CHAR -> {
                val main = mainLabel(l, k)
                val small = smallLabel(l, k)
                text.color = cText
                // Size by the smaller of height and width, so narrow keys (11 columns)
                // don't get letters that crowd their hint.
                val w = k.width
                text.textSize = min(h * (if (l.kind == LayoutKind.LANGUAGE) 0.48f else 0.42f), w * 0.58f)
                text.typeface = keyTypeface   // medium weight: easier to read than regular
                drawKeyText(canvas, main, k.cx, cy + h * 0.08f)
                text.typeface = Typeface.DEFAULT
                if (small != null) {
                    text.color = cAltText
                    text.textSize = min(h * 0.34f, w * 0.46f)   // second-layer hint, easy to read
                    drawHint(canvas, small, k.right - min(7 * dp, w * 0.12f), k.top + 4 * dp + min(5 * dp, h * 0.06f))
                }
            }
            KeyType.SPACE -> {
                text.color = cAltText
                text.textSize = h * 0.26f
                drawKeyText(canvas, spaceLabel, k.cx, cy)
            }
            else -> {
                val label = when (k.type) {
                    KeyType.BACKSPACE -> "⌫"
                    KeyType.SHIFT -> if (capsLock) "⇪" else "⇧"
                    KeyType.ENTER -> enterLabel
                    KeyType.LANGUAGE -> languageLabel
                    KeyType.NUMERIC -> "123"
                    KeyType.SYMBOL -> "#+="
                    KeyType.ALPHA -> languageLabel
                    KeyType.SWITCH -> "⌨"
                    else -> ""
                }
                text.color = when {
                    k.type == KeyType.LANGUAGE || k.type == KeyType.ALPHA -> cHold
                    k.shade == Shade.LIGHT -> cText
                    k.shade == Shade.SPECIAL -> if (k.type == KeyType.SHIFT && (shifted || capsLock)) cLightText else Color.rgb(25, 25, 25)
                    else -> cLightText
                }
                text.typeface = Typeface.DEFAULT_BOLD
                text.textSize = h * if (label.length > 2) 0.28f else 0.36f
                drawInkCentered(canvas, label, k.cx, cy)
            }
        }
    }

    private val bounds = android.graphics.Rect()

    private fun isMark(c: Char) = Character.getType(c) == Character.NON_SPACING_MARK.toInt()

    /**
     * Gboard-style placement. Thai letters share one baseline (so tall letters like
     * ป ฝ keep their tails) and Latin lowercase sits on the x-height, both centred
     * on the key; lone vowel/tone marks, digits, capitals and symbols are centred by
     * their visible shape.
     */
    private fun drawKeyText(canvas: Canvas, s: String, cx: Float, cy: Float) {
        if (s.isEmpty()) return
        val c = s[0]
        val ref = when {
            s.length == 1 && isMark(c) -> null
            c in '\u0E01'..'\u0E2E' || c in '\u0E40'..'\u0E44' || c == '\u0E2F' || c == '\u0E46' || c == '\u0E30' || c == '\u0E32' || c == '\u0E33' -> "ก"
            c in 'a'..'z' -> "x"
            else -> null
        }
        if (ref == null) {
            if (s.length == 1 && isMark(c)) {
                // Marks are tiny glyphs; draw them larger so they read like letters.
                val size = text.textSize
                text.textSize = size * 1.3f
                drawInkCentered(canvas, s, cx, cy)
                text.textSize = size
            } else drawInkCentered(canvas, s, cx, cy)
            return
        }
        text.textAlign = Paint.Align.CENTER
        text.getTextBounds(ref, 0, ref.length, bounds)
        canvas.drawText(display(s), cx, cy - bounds.exactCenterY(), text)
    }

    private fun drawInkCentered(canvas: Canvas, s: String, cx: Float, cy: Float) {
        val d = display(s)
        text.textAlign = Paint.Align.LEFT
        text.getTextBounds(d, 0, d.length, bounds)
        if (bounds.isEmpty) { text.textAlign = Paint.Align.CENTER; canvas.drawText(d, cx, cy, text); return }
        canvas.drawText(d, cx - bounds.exactCenterX(), cy - bounds.exactCenterY(), text)
        text.textAlign = Paint.Align.CENTER
    }

    /** Small second-layer hint, pinned to the key's top-right corner by its ink. */
    private fun drawHint(canvas: Canvas, s: String, right: Float, top: Float) {
        // Tone/vowel marks (๋ ๊ ็ ์ …) are tiny glyphs: draw them much larger so they're readable.
        if (s.length == 1 && isMark(s[0])) {
            val size = text.textSize
            text.textSize = size * 1.6f
            drawHintText(canvas, s, right, top)
            text.textSize = size
        } else drawHintText(canvas, s, right, top)
    }

    private fun drawHintText(canvas: Canvas, s: String, right: Float, top: Float) {
        val d = display(s)
        text.textAlign = Paint.Align.LEFT
        text.getTextBounds(d, 0, d.length, bounds)
        if (!bounds.isEmpty) canvas.drawText(d, right - bounds.right, top - bounds.top, text)
        text.textAlign = Paint.Align.CENTER
    }

    /**
     * Thai vowel/tone marks (ั ี ่ ุ …) are drawn on their own. A lone mark
     * would make the text shaper add a dotted circle, so it sits on an invisible
     * no-break space instead.
     */
    private fun display(s: String): String =
        if (s.isNotEmpty() && Character.getType(s[0]) == Character.NON_SPACING_MARK.toInt()) " $s" else s

    private fun mainLabel(l: KeyboardLayout, k: Key): String {
        if (emailMode && k.base == "," && k.row == l.rows.size - 1) return "@"
        if (!shifted && !capsLock) return k.base
        return if (l.swap) k.alt ?: k.base else k.base.uppercase()
    }

    private fun smallLabel(l: KeyboardLayout, k: Key): String? {
        if ((shifted || capsLock) && l.swap && k.alt != null) return k.base
        return k.altLabel
    }

    private fun drawPopup(canvas: Canvas, l: KeyboardLayout) {
        fill.color = Color.rgb(40, 40, 40)
        canvas.drawRoundRect(popupRect, 6 * dp, 6 * dp, fill)
        for ((i, c) in popupChoices.withIndex()) {
            val left = popupRect.left + i * popupCellW
            rect.set(left + 2 * dp, popupRect.top + 2 * dp, left + popupCellW - 2 * dp, popupRect.bottom - 2 * dp)
            fill.color = if (i == popupSel) cPopupSel else Color.rgb(150, 150, 150)
            canvas.drawRoundRect(rect, 7 * dp, 7 * dp, fill)
            text.color = cText
            text.typeface = keyTypeface
            text.textSize = l.keyHeight * if (c.length > 2) 0.26f else 0.40f
            drawKeyText(canvas, c, rect.centerX(), rect.centerY())
        }
    }

    // ---- touch --------------------------------------------------------------
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> start(e, 0)
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger: finish the first touch as a tap (fast two-thumb typing).
                if (activePointer != -1 && !swiping && popupKey == null && !spaceDragging) {
                    finishTap()
                }
                reset()
                start(e, e.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(activePointer)
                if (i >= 0) {
                    for (h in 0 until e.historySize) move(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                    move(e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == activePointer) end(e.getX(e.actionIndex), e.getY(e.actionIndex))
            }
            MotionEvent.ACTION_UP -> {
                val i = e.findPointerIndex(activePointer)
                if (i >= 0) end(e.getX(i), e.getY(i)) else reset()
            }
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return true
    }

    private fun start(e: MotionEvent, index: Int) {
        val x = e.getX(index)
        val y = e.getY(index)
        activePointer = e.getPointerId(index)
        count = 0
        addPoint(x, y)
        downX = x; downY = y
        if (y < stripH) {
            val n = min(suggestions.size, 3)
            stripDown = if (n > 0) min(n - 1, (x / (width / n.toFloat())).toInt()) else -1
            stripLongFired = false
            if (stripDown >= 0) handler.postDelayed(stripLongPress, 550)
            invalidate()
            return
        }
        val k = layout?.keyAt(x, y) ?: return
        downKey = k
        if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (k.type == KeyType.BACKSPACE) {
            listener?.onBackspace()
            handler.postDelayed(repeatRunnable, 400)
        } else {
            handler.postDelayed(longPressRunnable, longPressDelay)
        }
        invalidate()
    }

    private fun move(x: Float, y: Float) {
        val k = downKey ?: return
        addPoint(x, y)
        val l = layout ?: return
        if (popupKey != null) {
            val idx = ((x - popupRect.left) / popupCellW).toInt().coerceIn(0, popupChoices.size - 1)
            if (idx != popupSel) { popupSel = idx; invalidate() }
            return
        }
        val dist = hypot(x - downX, y - downY)
        if (k.type == KeyType.SPACE) {
            val stepW = l.keyWidth * 0.35f
            if (spaceDragging || abs(x - downX) > l.keyWidth * 0.6f) {
                if (!spaceDragging) { spaceDragging = true; handler.removeCallbacks(longPressRunnable) }
                val steps = ((x - downX) / stepW).toInt()
                if (steps != spaceStepsSent) { listener?.onCursorMove(steps - spaceStepsSent); spaceStepsSent = steps }
            }
            return
        }
        if (!swiping && swipeEnabled && k.isChar && l.kind == LayoutKind.LANGUAGE && dist > l.keyWidth * 0.45f) {
            swiping = true
            handler.removeCallbacks(longPressRunnable)
        }
        if (swiping) invalidate()
    }

    private fun end(x: Float, y: Float) {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(stripLongPress)
        handler.removeCallbacks(repeatRunnable)
        if (stripDown >= 0) {
            val n = min(suggestions.size, 3)
            if (!stripLongFired && y < stripH && n > 0) {
                val slot = min(n - 1, (x / (width / n.toFloat())).toInt())
                if (slot == stripDown) listener?.onSuggestion(orderFor(n)[slot])
            }
            reset(); return
        }
        val pk = popupKey
        when {
            pk != null -> {
                popupChoices.getOrNull(popupSel)?.let { listener?.onText(caseFor(it)) }
            }
            swiping -> {
                addPoint(x, y)
                listener?.onSwipe(xs.copyOf(count), ys.copyOf(count), count)
            }
            spaceDragging -> Unit
            else -> finishTap()
        }
        reset()
    }

    private fun finishTap() {
        val k = downKey ?: return
        val l = layout ?: return
        when (k.type) {
            KeyType.CHAR -> listener?.onText(mainLabel(l, k))
            KeyType.BACKSPACE, KeyType.GAP -> Unit
            else -> listener?.onFunctionKey(k)
        }
    }

    private fun caseFor(s: String): String {
        val l = layout ?: return s
        return if ((shifted || capsLock) && !l.swap && s.length == 1) s.uppercase() else s
    }

    private fun onLongPress() {
        val k = downKey ?: return
        val l = layout ?: return
        if (swiping) return
        if (!k.isChar) {
            if (listener?.onFunctionLongPress(k) == true) {
                if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                downKey = null
                invalidate()
            }
            return
        }
        val choices = k.holdChoices(shifted || capsLock, l.swap)
        if (choices.isEmpty()) return
        popupKey = k
        popupChoices = choices
        popupSel = 0
        // Popup sits above the key; wide lists start at the key and run right.
        val cellW = min(l.keyWidth, width / max(1f, choices.size.toFloat()))
        popupCellW = cellW
        val totalW = cellW * choices.size
        var left = k.cx - cellW / 2f
        if (left + totalW > width) left = width - totalW
        if (left < 0) left = 0f
        val top = max(0f, k.top - l.keyHeight * 1.05f)
        popupRect.set(left, top, left + totalW, top + l.keyHeight)
        if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun addPoint(x: Float, y: Float) {
        if (count == xs.size) { xs = xs.copyOf(count * 2); ys = ys.copyOf(count * 2) }
        xs[count] = x; ys[count] = y; count++
    }

    private fun reset() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(stripLongPress)
        handler.removeCallbacks(repeatRunnable)
        activePointer = -1
        downKey = null
        swiping = false
        popupKey = null
        stripDown = -1
        spaceDragging = false
        spaceStepsSent = 0
        count = 0
        invalidate()
    }
}
