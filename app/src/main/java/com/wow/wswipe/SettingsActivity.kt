package com.wow.wswipe

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.wow.wswipe.ime.Prefs

/** Setup + settings screen (plain framework views, no extra libraries). */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun title(t: String, size: Float = 18f) = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.WHITE)
            setPadding(0, pad, 0, pad / 2)
        }
        fun note(t: String) = TextView(this).apply {
            text = t; textSize = 14f; setTextColor(Color.rgb(200, 200, 200))
        }

        col.addView(title("WSwipe", 28f))
        col.addView(note("Thai + English swipe keyboard."))
        status = note("")
        col.addView(status)

        col.addView(title("1. Turn on WSwipe"))
        col.addView(Button(this).apply {
            text = "Open keyboard settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        col.addView(title("2. Make it your keyboard"))
        col.addView(Button(this).apply {
            text = "Choose WSwipe"
            setOnClickListener { imm().showInputMethodPicker() }
        })
        col.addView(title("3. Languages (optional)"))
        col.addView(note("ไทย and English are both on. If the keyboard picker only lists one, turn the other on here."))
        col.addView(Button(this).apply {
            text = "Choose languages"
            setOnClickListener {
                val id = android.content.ComponentName(this@SettingsActivity, com.wow.wswipe.ime.WSwipeService::class.java)
                    .flattenToShortString()
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS)
                    .putExtra(Settings.EXTRA_INPUT_METHOD_ID, id))
            }
        })
        col.addView(title("Try it"))
        col.addView(EditText(this).apply {
            hint = "พิมพ์ที่นี่ / type here"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            minLines = 3; gravity = Gravity.TOP
        })

        col.addView(title("Settings"))
        col.addView(toggle("Swipe typing", prefs.swipeEnabled) { prefs.swipeEnabled = it })
        col.addView(toggle("Show swipe trail", prefs.showTrail) { prefs.showTrail = it })
        col.addView(toggle("Vibrate on key press", prefs.haptics) { prefs.haptics = it })
        col.addView(slider("Long-press delay", "ms", 100, 800, prefs.longPressDelay) { prefs.longPressDelay = it })
        col.addView(slider("Keyboard height", "%", 70, 140, prefs.heightPercent) { prefs.heightPercent = it })

        col.addView(title("About"))
        col.addView(note(
            "Long-press any key to type its second character; slide along the popup for more.\n" +
            "Swipe across letters to type a word. Tap a suggestion to replace the swiped word.\n" +
            "Backspace right after a swipe deletes the whole word. Drag on the space bar to move the cursor.\n\n" +
            "Word lists: Thai National Corpus frequencies via PyThaiNLP (Apache-2.0); " +
            "English frequencies from hermitdave/FrequencyWords (MIT, OpenSubtitles)."
        ))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(48, 48, 48))
            addView(col, MATCH_PARENT, WRAP_CONTENT)
        })
    }

    override fun onResume() {
        super.onResume()
        val enabled = imm().enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        status.text = when {
            !enabled -> "Status: not turned on yet — do step 1."
            !current.startsWith(packageName) -> "Status: turned on — now do step 2."
            else -> "Status: WSwipe is your keyboard ✓"
        }
    }

    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label; isChecked = value; textSize = 16f; setTextColor(Color.WHITE)
        setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun slider(label: String, unit: String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tv = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE); text = "$label: $value$unit" }
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    tv.text = "$label: ${p + min}$unit"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { onChange((s?.progress ?: 0) + min) }
            })
        }
        box.addView(tv); box.addView(sb)
        return box
    }
}
