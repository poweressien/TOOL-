package com.toolkit.privacy

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL

object VpnManager {
    private var backend: GoBackend? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String = "wg0"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    private fun be(ctx: Context): GoBackend =
        backend ?: GoBackend(ctx.applicationContext).also { backend = it }

    private fun dir(ctx: Context) = File(ctx.filesDir, "configs").apply { mkdirs() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vpn", Context.MODE_PRIVATE)

    fun names(ctx: Context): List<String> =
        dir(ctx).listFiles()?.filter { it.extension == "conf" }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    fun selected(ctx: Context): String? {
        val p = prefs(ctx).getString("sel", null)
        val all = names(ctx)
        return if (p != null && p in all) p else all.firstOrNull()
    }

    fun select(ctx: Context, name: String) = prefs(ctx).edit().putString("sel", name).apply()

    /** Validates with WireGuard's parser, stores privately, returns the saved name. */
    fun saveConfig(ctx: Context, rawName: String, text: String): String {
        Config.parse(ByteArrayInputStream(text.toByteArray()))
        val name = rawName.replace(Regex("[^A-Za-z0-9 _.-]"), "_").take(40).ifBlank { "config" }
        File(dir(ctx), "$name.conf").writeText(text)
        select(ctx, name)
        return name
    }

    fun delete(ctx: Context, name: String) {
        File(dir(ctx), "$name.conf").delete()
    }

    fun endpoint(ctx: Context, name: String): String = try {
        val cfg = File(dir(ctx), "$name.conf").inputStream().use { Config.parse(it) }
        cfg.peers.firstOrNull()?.endpoint?.map { "${it.host}:${it.port}" }?.orElse("no endpoint") ?: "no endpoint"
    } catch (e: Exception) {
        "invalid"
    }

    fun connect(ctx: Context, name: String) {
        val cfg = File(dir(ctx), "$name.conf").inputStream().use { Config.parse(it) }
        be(ctx).setState(tunnel, Tunnel.State.UP, cfg)
    }

    fun disconnect(ctx: Context) {
        be(ctx).setState(tunnel, Tunnel.State.DOWN, null)
    }

    fun isUp(ctx: Context): Boolean = try {
        be(ctx).getState(tunnel) == Tunnel.State.UP
    } catch (e: Throwable) {
        false
    }

    /** (received bytes, sent bytes) */
    fun stats(ctx: Context): Pair<Long, Long> = try {
        val s = be(ctx).getStatistics(tunnel)
        s.totalRx() to s.totalTx()
    } catch (e: Throwable) {
        0L to 0L
    }

    fun publicIp(): String = try {
        val c = URL("https://api.ipify.org").openConnection().apply {
            connectTimeout = 6000
            readTimeout = 6000
        }
        c.getInputStream().bufferedReader().use { it.readText().trim() }
    } catch (e: Exception) {
        "unavailable"
    }
}
