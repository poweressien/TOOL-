package com.inwekobong.ekpe

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*

object C {
    val BG = 0xFF0F1220.toInt()
    val CARD = 0xFF1B2036.toInt()
    val ACC = 0xFF4F8CFF.toInt()
    val TXT = 0xFFE8EAF2.toInt()
    val SUB = 0xFF9AA3B8.toInt()
}

data class Row(val k: String, val v: String = "", val url: String? = null, val header: Boolean = false)

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.prefs() = getSharedPreferences("vint", Context.MODE_PRIVATE)

fun round(color: Int, r: Int): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = r.toFloat() }

fun Context.tv(text: String, size: Float = 14f, color: Int = C.TXT, bold: Boolean = false) = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

fun Context.button(text: String, onClick: () -> Unit) = Button(this).apply {
    this.text = text; isAllCaps = false; setTextColor(0xFFFFFFFF.toInt())
    background = round(C.ACC, dp(10)); setOnClickListener { onClick() }
    minHeight = 0; minimumHeight = dp(40)
}

fun Context.edit(hint: String, number: Boolean = false) = EditText(this).apply {
    this.hint = hint; setHintTextColor(C.SUB); setTextColor(C.TXT)
    background = round(C.CARD, dp(10)); setPadding(dp(12), dp(10), dp(12), dp(10))
    if (number) inputType = InputType.TYPE_CLASS_PHONE
}

fun Context.sw(text: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
    this.text = text; setTextColor(C.TXT); isChecked = checked
    setOnCheckedChangeListener { _, b -> onChange(b) }
}

fun Context.slider(label: String, max: Int, init: Int, onChange: (Int) -> Unit): LinearLayout {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val t = tv("$label: $init", 12f, C.SUB)
    val s = SeekBar(this).apply {
        this.max = max; progress = init
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { t.text = "$label: $p"; if (u) onChange(p) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }
    box.addView(t); box.addView(s)
    return box
}

fun LinearLayout.add(v: View, top: Int = 8): View {
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.topMargin = context.dp(top); addView(v, lp); return v
}

fun Context.page(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(24))
}

fun Context.scroll(inner: View): ScrollView = ScrollView(this).apply { setBackgroundColor(C.BG); addView(inner) }

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; background = round(C.CARD, dp(12)); setPadding(dp(12), dp(10), dp(12), dp(10))
}

fun Context.table(rows: List<Row>): LinearLayout {
    val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(C.CARD, dp(12)); setPadding(0, 0, 0, dp(6)) }
    var i = 0
    for (r in rows) {
        if (r.header) {
            t.addView(tv(r.k, 13f, C.ACC, true).apply { setPadding(dp(12), dp(12), dp(12), dp(4)) }); i = 0; continue
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(7), dp(12), dp(7))
            if (i % 2 == 1) setBackgroundColor(0x14FFFFFF)
        }
        val k = tv(r.k, 13f, C.SUB)
        val v = tv(r.v, 13f, if (r.url != null) C.ACC else C.TXT).apply { setTextIsSelectable(r.url == null) }
        line.addView(k, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.4f))
        line.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f))
        if (r.url != null) line.setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { Toast.makeText(this, "Nothing can open this link", Toast.LENGTH_SHORT).show() }
        }
        t.addView(line); i++
    }
    return t
}

fun View.applyTypeface(tf: Typeface?) {
    if (tf == null) return
    if (this is TextView) setTypeface(tf, typeface?.style ?: Typeface.NORMAL)
    if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).applyTypeface(tf)
}

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
