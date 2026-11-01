package com.inwekobong.ekpe

import android.Manifest
import android.app.AlertDialog
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.SmsManager
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

data class Rule(val id: String, val name: String, val trig: String, val thr: Double, val acts: List<String>, val phone: String, val msg: String, val cool: Int, val on: Boolean) {
    fun json() = JSONObject().put("id", id).put("n", name).put("t", trig).put("th", thr).put("a", JSONArray(acts)).put("p", phone).put("m", msg).put("c", cool).put("on", on)
    companion object {
        fun from(o: JSONObject) = Rule(o.getString("id"), o.getString("n"), o.getString("t"), o.getDouble("th"), (0 until o.getJSONArray("a").length()).map { o.getJSONArray("a").getString(it) }, o.optString("p"), o.optString("m"), o.optInt("c", 60), o.optBoolean("on", true))
    }
}

val TRIGGERS = linkedMapOf(
    "ble_near" to "New Bluetooth device closer than N metres",
    "signal_low" to "Mobile signal below N %",
    "emf_high" to "Magnetic field changes by more than N µT",
    "wifi_new" to "A new Wi-Fi network appears",
    "battery_low" to "Battery below N %",
    "offline" to "Phone goes offline"
)
val ACTIONS = linkedMapOf(
    "notify" to "Pop-up alert", "torch" to "Flash the torch", "vibrate" to "Vibrate", "alarm" to "Sound the alarm",
    "sms" to "Send SMS to number", "whatsapp" to "WhatsApp message (tap to send)"
)

object AutomationEngine {
    @Volatile private var running = false
    private val last = HashMap<String, Long>()
    private val seenBle = HashSet<String>(); private val seenWifi = HashSet<String>()
    private var ticks = 0

    fun rules(c: Context): MutableList<Rule> = runCatching {
        val a = JSONArray(c.prefs().getString("auto_rules", "[]")); (0 until a.length()).map { Rule.from(a.getJSONObject(it)) }.toMutableList()
    }.getOrDefault(ArrayList())

    fun save(c: Context, l: List<Rule>) { val a = JSONArray(); l.forEach { a.put(it.json()) }; c.prefs().edit().putString("auto_rules", a.toString()).apply() }

    fun start(c: Context) {
        if (running) return
        running = true; val ctx = c.applicationContext; RadarEngine.start(ctx)
        Thread { while (running) { try { eval(ctx) } catch (e: Exception) { }; try { Thread.sleep(2000) } catch (e: InterruptedException) { break } } }.apply { isDaemon = true }.start()
    }
    fun stop() { running = false }

    private fun eval(c: Context) {
        val rs = rules(c).filter { it.on }
        val j = JSONObject(RadarEngine.snapshot())
        val warm = ticks++ >= 2
        val newBle = ArrayList<JSONObject>(); val newWifi = ArrayList<String>()
        j.optJSONArray("ble")?.let { for (i in 0 until it.length()) { val o = it.getJSONObject(i); if (seenBle.add(o.optString("a"))) newBle.add(o) } }
        j.optJSONArray("wifi")?.let { for (i in 0 until it.length()) { val s = it.getJSONObject(i).optString("s"); if (s.isNotEmpty() && seenWifi.add(s)) newWifi.add(s) } }
        if (!warm) return
        var sig = -1
        j.optJSONArray("cells")?.let { for (i in 0 until it.length()) { val o = it.getJSONObject(i); if (o.optBoolean("reg")) sig = o.optInt("pct") } }
        val bat = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / maxOf(1, it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)) } ?: -1
        for (r in rs) {
            val why: String? = when (r.trig) {
                "ble_near" -> newBle.firstOrNull { it.optDouble("d", 99.0) <= r.thr }?.let { "Bluetooth ${it.optString("n").ifEmpty { it.optString("a") }} about ${it.optDouble("d")} m away" }
                "signal_low" -> if (sig in 0 until r.thr.toInt()) "Mobile signal is $sig%" else null
                "emf_high" -> if (Math.abs(j.optDouble("emf") - j.optDouble("base")) > r.thr) "Magnetic field jumped to ${j.optDouble("emf").toInt()} µT" else null
                "wifi_new" -> newWifi.firstOrNull()?.let { "New Wi-Fi: $it" }
                "battery_low" -> if (bat in 0 until r.thr.toInt()) "Battery is $bat%" else null
                "offline" -> if (j.optJSONObject("net")?.optString("type") == "Offline") "Phone is offline" else null
                else -> null
            }
            if (why != null) fire(c, r, why, false)
        }
    }

    fun fire(c: Context, r: Rule, why: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - (last[r.id] ?: 0L) < r.cool * 1000L) return
        last[r.id] = now
        val text = if (r.msg.isNotBlank()) "${r.msg} ($why)" else why
        for (a in r.acts) runCatching {
            when (a) {
                "notify" -> GuardNotify.post(c, 9000 + (now % 500).toInt(), "Rule: ${r.name}", why)
                "torch" -> Thread { val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    repeat(3) { cm.setTorchMode(id, true); Thread.sleep(500); cm.setTorchMode(id, false); Thread.sleep(300) } }.start()
                "vibrate" -> @Suppress("DEPRECATION") (c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
                "alarm" -> { val rt = RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)); rt.play(); Thread { Thread.sleep(6000); rt.stop() }.start() }
                "sms" -> {
                    if (c.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) GuardNotify.post(c, 9500, "Rule: ${r.name}", "SMS not sent: allow SMS permission in Automation Rules.")
                    else { val sm = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault(); sm.sendTextMessage(r.phone, null, text.take(150), null, null) }
                }
                "whatsapp" -> {
                    val num = r.phone.filter { it.isDigit() }
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$num?text=" + Uri.encode(text))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    GuardNotify.channels(c)
                    val n = Notification.Builder(c, com.toolkit.privacy.SoundCatalog.alertChannel(c, "guard_alerts", "Guard alerts")).setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("Rule: ${r.name}").setContentText("Tap to send the WhatsApp message")
                        .setContentIntent(PendingIntent.getActivity(c, (now % 1000).toInt(), i, PendingIntent.FLAG_IMMUTABLE)).setAutoCancel(true).build()
                    c.getSystemService(android.app.NotificationManager::class.java).notify(9600 + (now % 300).toInt(), n)
                    runCatching { c.startActivity(i) }   // only works while the app is on screen; Android blocks it from the background
                }
            }
        }
    }
}

class AutomationService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        GuardNotify.channels(this)
        val n = Notification.Builder(this, "guard_svc").setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle("Automation rules are running").setContentText("Watching Radar for your rules").setOngoing(true).build()
        try { if (Build.VERSION.SDK_INT >= 29) startForeground(62, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(62, n) } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        AutomationEngine.start(this); return START_STICKY
    }
    override fun onDestroy() { AutomationEngine.stop(); super.onDestroy() }
}

class AutomationSection(private val a: MainActivity) : Tool {
    private lateinit var list: LinearLayout

    override fun view(): View {
        val p = a.page()
        p.add(a.tv("Automation Rules", 20f, C.TXT, true), 0)
        p.add(a.tv("Example: new Bluetooth device within 3 m → flash torch + alert. Or signal below 20% → WhatsApp / SMS. Android blocks silent WhatsApp sending and, on newer versions, toggling Wi-Fi, so WhatsApp asks you to tap a notification and Wi-Fi switching isn't offered.", 12f, C.SUB), 4)
        p.add(a.sw("Run rules in the background", a.prefs().getBoolean("auto_bg", false)) { on ->
            a.prefs().edit().putBoolean("auto_bg", on).apply()
            val svc = Intent(a, AutomationService::class.java)
            if (on) { GuardPerm.startRadar(a) { runCatching { a.startForegroundService(svc) } } } else a.stopService(svc)
        }, 8)
        p.add(a.button("+ Add rule") { edit(null) }, 8)
        list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }; p.add(list, 8)
        refresh()
        if (a.prefs().getBoolean("auto_bg", false)) GuardPerm.startRadar(a) { AutomationEngine.start(a) }
        return p
    }

    private fun refresh() {
        list.removeAllViews()
        val rs = AutomationEngine.rules(a)
        if (rs.isEmpty()) list.add(a.tv("No rules yet.", 12f, C.SUB), 0)
        rs.forEach { r ->
            val c = a.card()
            c.addView(a.sw(r.name, r.on) { on -> AutomationEngine.save(a, AutomationEngine.rules(a).map { if (it.id == r.id) it.copy(on = on) else it }) })
            c.addView(a.tv("When: ${TRIGGERS[r.trig]?.replace(" N ", " ${r.thr.toInt()} ")}\nDo: ${r.acts.joinToString { ACTIONS[it] ?: it }}", 12f, C.SUB))
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(a.button("Test") { AutomationEngine.fire(a, r, "Test run", true) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(a.button("Edit") { edit(r) }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
            row.addView(a.button("Delete") { AutomationEngine.save(a, AutomationEngine.rules(a).filter { it.id != r.id }); refresh() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
            c.add(row, 8); list.add(c, 8)
        }
        list.applyTypeface(a.tf)
    }

    private fun edit(old: Rule?) {
        val keys = TRIGGERS.keys.toList()
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(8), a.dp(16), 0) }
        val name = a.edit("Rule name").apply { setText(old?.name ?: "") }
        val trig = Spinner(a).apply { adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, TRIGGERS.values.toList()); setSelection(keys.indexOf(old?.trig).coerceAtLeast(0)) }
        val thr = a.edit("N (metres, %, µT…)", true).apply { setText(old?.thr?.toInt()?.toString() ?: "3") }
        val boxes = ACTIONS.map { (k, v) -> CheckBox(a).apply { text = v; setTextColor(C.TXT); isChecked = old?.acts?.contains(k) ?: (k == "notify") } to k }
        val phone = a.edit("Phone number for SMS / WhatsApp (e.g. 2348012345678)", true).apply { setText(old?.phone ?: "") }
        val msg = a.edit("Message text").apply { setText(old?.msg ?: "") }
        val cool = a.edit("Wait between triggers (seconds)", true).apply { setText((old?.cool ?: 60).toString()) }
        col.add(name, 0); col.add(trig, 8); col.add(thr, 8); boxes.forEach { col.addView(it.first) }; col.add(phone, 8); col.add(msg, 8); col.add(cool, 8)
        AlertDialog.Builder(a).setTitle(if (old == null) "New rule" else "Edit rule").setView(ScrollView(a).apply { addView(col) }).setPositiveButton("Save") { _, _ ->
            val acts = boxes.filter { it.first.isChecked }.map { it.second }
            if (acts.isEmpty()) { a.toast("Pick at least one action"); return@setPositiveButton }
            val r = Rule(old?.id ?: System.currentTimeMillis().toString(), name.text.toString().ifBlank { "Rule" }, keys[trig.selectedItemPosition], thr.text.toString().toDoubleOrNull() ?: 3.0, acts, phone.text.toString(), msg.text.toString(), cool.text.toString().toIntOrNull() ?: 60, old?.on ?: true)
            val l = AutomationEngine.rules(a); val i = l.indexOfFirst { it.id == r.id }; if (i >= 0) l[i] = r else l.add(r)
            AutomationEngine.save(a, l)
            val need = ArrayList<String>()
            if (acts.contains("sms") && a.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.SEND_SMS)
            if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
            if (need.isNotEmpty()) a.ask(need.toTypedArray()) {}
            refresh()
        }.setNegativeButton("Cancel", null).show()
    }
}
