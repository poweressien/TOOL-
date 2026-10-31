package com.inwekobong.ekpe

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.ContactsContract
import android.view.View
import android.widget.*

class ToolsSection(private val a: MainActivity) {
    private lateinit var out: LinearLayout
    private var torch = false

    fun view(): View {
        val p = a.page()
        p.add(a.tv("Extra Tools", 22f, C.TXT, true), 0)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(a.button("Torch") { toggleTorch() }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(a.button("Device info") { deviceInfo() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        row.addView(a.button("Duplicates") { duplicates() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        p.add(row, 10)
        p.add(a.tv("Torch = flashlight · Device info = hardware, battery, storage, network · Duplicates = contacts saved twice", 12f, C.SUB), 6)
        out = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        p.add(out, 12)
        return a.scroll(p)
    }

    private fun show(rows: List<Row>) { out.removeAllViews(); out.addView(a.table(rows)); out.applyTypeface(a.tf) }

    private fun toggleTorch() {
        try {
            val cm = a.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            torch = !torch; cm.setTorchMode(id, torch)
        } catch (e: Exception) { a.toast("No flashlight available") }
    }

    private fun deviceInfo() {
        val am = a.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        val st = StatFs(Environment.getDataDirectory().path)
        val bat = a.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val temp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val cm = a.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when { caps == null -> "Offline"; caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"; caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"; else -> "Other" }
        fun gb(b: Long) = String.format("%.1f GB", b / 1073741824.0)
        show(listOf(Row("DEVICE", header = true), Row("Model", "${Build.MANUFACTURER} ${Build.MODEL}"), Row("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
            Row("Security patch", Build.VERSION.SECURITY_PATCH), Row("Board / CPU ABI", "${Build.BOARD} / ${Build.SUPPORTED_ABIS.firstOrNull()}"),
            Row("MEMORY & STORAGE", header = true), Row("RAM", "${gb(mi.availMem)} free of ${gb(mi.totalMem)}"),
            Row("Storage", "${gb(st.availableBytes)} free of ${gb(st.totalBytes)}"),
            Row("BATTERY & NETWORK", header = true), Row("Battery", "$lvl %  ·  $temp °C"), Row("Connection", net)))
    }

    private fun duplicates() {
        if (a.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            a.ask(arrayOf(Manifest.permission.READ_CONTACTS)) { duplicates() }; return
        }
        val map = HashMap<String, MutableSet<String>>()
        a.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.CONTACT_ID), null, null, null)?.use {
            while (it.moveToNext()) {
                val k = (it.getString(0) ?: "").filter { c -> c.isDigit() }.takeLast(9)
                if (k.length >= 7) map.getOrPut(k) { mutableSetOf() }.add("${it.getString(1)}#${it.getLong(2)}")
            }
        }
        val dups = map.filter { it.value.size > 1 }
        val rows = ArrayList<Row>()
        rows += Row("DUPLICATE CONTACTS (${dups.size})", header = true)
        if (dups.isEmpty()) rows += Row("Result", "No duplicates found 🎉")
        dups.forEach { (k, v) -> rows += Row("…$k", v.joinToString("\n") { it.substringBefore('#') }) }
        show(rows)
    }
}
