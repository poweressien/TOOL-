package com.toolkit.privacy

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/** Scans for nearby Bluetooth LE + classic devices and lists paired / connected ones. Call from the main thread. */
@SuppressLint("MissingPermission")
class BtScanner(private val ctx: Context, private val onChange: () -> Unit) {
    companion object {
        fun neededPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        fun hasPermissions(ctx: Context) = neededPermissions().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

        fun adapter(ctx: Context): BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        fun locationOn(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 31) return true
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            return try { lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { true }
        }
    }

    val devices = LinkedHashMap<String, BtDevice>()
    var scanning = false
        private set
    private val handler = Handler(Looper.getMainLooper())
    private var receiverOn = false
    private val proxies = ArrayList<Pair<Int, BluetoothProfile>>()

    private fun typeName(t: Int) = when (t) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"; BluetoothDevice.DEVICE_TYPE_LE -> "LE"; BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"; else -> "Unknown"
    }

    private fun entry(d: BluetoothDevice): BtDevice {
        val e = devices.getOrPut(d.address) { BtDevice(d.address) }
        try { d.name?.let { e.name = it } } catch (x: Exception) { }
        e.type = typeName(d.type)
        e.bonded = d.bondState == BluetoothDevice.BOND_BONDED
        e.lastSeen = System.currentTimeMillis()
        return e
    }

    /** Public BluetoothClass.deviceClass already holds major (0x1F00) + minor (0x00FC) bits. */
    private fun classOf(d: BluetoothDevice): Int = try { d.bluetoothClass?.deviceClass ?: 0 } catch (x: Exception) { 0 }

    private val bleCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val e = entry(r.device)
            e.classInt = classOf(r.device).takeIf { it != 0 } ?: e.classInt
            e.rssi = r.rssi
            e.seenCount++
            val rec = r.scanRecord
            if (rec != null) {
                rec.deviceName?.let { if (e.name.isNullOrBlank()) e.name = it }
                if (rec.txPowerLevel != Int.MIN_VALUE) e.txPower = rec.txPowerLevel
                val md = rec.manufacturerSpecificData
                for (i in 0 until md.size()) e.mfr[md.keyAt(i)] = md.valueAt(i)
                rec.serviceUuids?.forEach { e.services += it.uuid.toString() }
                rec.serviceData?.keys?.forEach { e.services += it.uuid.toString() }
            }
            if (Build.VERSION.SDK_INT >= 26) e.connectable = r.isConnectable
            if (e.type == "Unknown") e.type = "LE"
            onChange()
        }
    }

    private val classicRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION") val d: BluetoothDevice = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val e = entry(d)
                    e.classInt = classOf(d).takeIf { it != 0 } ?: e.classInt
                    val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    if (rssi != Short.MIN_VALUE) e.rssi = rssi.toInt()
                    e.seenCount++
                    onChange()
                }
            }
        }
    }

    fun start(seconds: Int = 20) {
        val ad = adapter(ctx) ?: return
        if (!ad.isEnabled || scanning) return
        devices.values.forEach { it.seenCount = 0 }
        loadPaired(ad)
        scanning = true
        try {
            ad.bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), bleCb)
        } catch (e: Exception) { }
        try {
            if (!receiverOn) { ctx.registerReceiver(classicRx, IntentFilter(BluetoothDevice.ACTION_FOUND)); receiverOn = true }
            ad.startDiscovery()
        } catch (e: Exception) { }
        handler.postDelayed({ stop() }, seconds * 1000L)
        onChange()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        val ad = adapter(ctx)
        try { ad?.bluetoothLeScanner?.stopScan(bleCb) } catch (e: Exception) { }
        try { ad?.cancelDiscovery() } catch (e: Exception) { }
        if (receiverOn) { try { ctx.unregisterReceiver(classicRx) } catch (e: Exception) { }; receiverOn = false }
        scanning = false
        onChange()
    }

    fun destroy() {
        stop()
        val ad = adapter(ctx)
        proxies.forEach { (t, p) -> try { ad?.closeProfileProxy(t, p) } catch (e: Exception) { } }
        proxies.clear()
    }

    /** Paired devices + devices currently connected over GATT / audio profiles. */
    fun loadPaired(adIn: BluetoothAdapter? = null) {
        val ad = adIn ?: adapter(ctx) ?: return
        try { ad.bondedDevices?.forEach { entry(it).bonded = true } } catch (e: Exception) { }
        try {
            val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.getConnectedDevices(BluetoothProfile.GATT).forEach { entry(it).connected = true }
        } catch (e: Exception) { }
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            try {
                ad.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        try { proxy.connectedDevices.forEach { entry(it).connected = true } } catch (e: Exception) { }
                        proxies += p to proxy
                        handler.post { onChange() }
                    }
                    override fun onServiceDisconnected(p: Int) {}
                }, profile)
            } catch (e: Exception) { }
        }
        // some phones expose isConnected() on bonded devices
        try {
            ad.bondedDevices?.forEach { d ->
                val m = d.javaClass.getMethod("isConnected")
                if (m.invoke(d) as? Boolean == true) entry(d).connected = true
            }
        } catch (e: Exception) { }
        onChange()
    }

    // ------------------------------------------------------------------ GATT probe

    private val U = { s: String -> UUID.fromString("0000$s-0000-1000-8000-00805f9b34fb") }

    /** Connects, reads Device Information + Battery, reports lines. Gives up after ~15 s. */
    fun probe(address: String, done: (List<String>) -> Unit) {
        val ad = adapter(ctx) ?: run { done(listOf("Bluetooth unavailable")); return }
        val dev = try { ad.getRemoteDevice(address) } catch (e: Exception) { done(listOf("Bad address")); return }
        val lines = ArrayList<String>()
        val queue = ArrayDeque<BluetoothGattCharacteristic>()
        var finished = false
        var gattRef: BluetoothGatt? = null
        fun finish(msg: String? = null) {
            if (finished) return
            finished = true
            if (msg != null) lines += msg
            try { gattRef?.disconnect(); gattRef?.close() } catch (e: Exception) { }
            handler.post { done(if (lines.isEmpty()) listOf("Connected, but the device shares no readable info") else lines) }
        }
        val labels = mapOf("2A29" to "Manufacturer", "2A24" to "Model", "2A25" to "Serial number", "2A26" to "Firmware", "2A27" to "Hardware", "2A28" to "Software", "2A19" to "Battery")
        fun next(g: BluetoothGatt) {
            val c = queue.removeFirstOrNull()
            if (c == null) { finish(); return }
            if (!g.readCharacteristic(c)) next(g)
        }
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) finish(if (lines.isEmpty()) "Couldn't connect (device busy, out of range or doesn't allow connections)" else null)
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val names = g.services.map { BtLogic.serviceName(it.uuid.toString()) ?: BtLogic.shortUuid(it.uuid.toString()) }
                lines += "Offers ${g.services.size} services: " + names.take(8).joinToString(", ")
                for (s in listOf("180A", "180F")) g.getService(U(s))?.characteristics?.forEach { c ->
                    if (BtLogic.shortUuid(c.uuid.toString()) in labels) queue.add(c)
                }
                next(g)
            }
            @Deprecated("old signature")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                @Suppress("DEPRECATION") handle(g, c, c.value, status)
            }
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) { handle(g, c, value, status) }
            private fun handle(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && v != null) {
                    val id = BtLogic.shortUuid(c.uuid.toString())
                    val text = if (id == "2A19") "${v[0].toInt() and 0xFF}%" else String(v).trim { it <= ' ' }
                    if (text.isNotEmpty()) lines += "${labels[id]}: $text"
                }
                next(g)
            }
        }
        handler.postDelayed({ finish(if (lines.isEmpty()) "Timed out. The device didn't answer" else null) }, 15000)
        try {
            gattRef = if (Build.VERSION.SDK_INT >= 23) dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE) else dev.connectGatt(ctx, false, cb)
        } catch (e: Exception) { finish("Couldn't start a connection: ${e.message}") }
    }
}
