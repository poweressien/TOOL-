package com.toolkit.privacy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wi-Fi passwords the user revealed or typed. Encrypted with a hardware-backed Keystore key; never leaves the phone. */
object WifiVaultStore {
    private const val ALIAS = "pt_wifi_store"
    private fun file(ctx: Context) = File(ctx.filesDir, "wifi_saved.bin")

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return kg.generateKey()
    }

    fun load(ctx: Context): List<WifiCred> = try {
        val b = file(ctx).readBytes()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12))) }
        val arr = JSONArray(String(c.doFinal(b, 12, b.size - 12)))
        (0 until arr.length()).map { val o = arr.getJSONObject(it); WifiCred(o.optString("s"), o.optString("p"), o.optString("t"), o.optBoolean("h")) }
    } catch (e: Exception) { emptyList() }

    fun save(ctx: Context, list: List<WifiCred>) {
        try {
            val arr = JSONArray(); list.forEach { arr.put(JSONObject().put("s", it.ssid).put("p", it.password).put("t", it.security).put("h", it.hidden)) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            file(ctx).writeBytes(c.iv + c.doFinal(arr.toString().toByteArray()))
        } catch (e: Exception) { }
    }

    fun add(ctx: Context, c: WifiCred) { save(ctx, load(ctx).filterNot { it.ssid == c.ssid } + c) }
    fun remove(ctx: Context, ssid: String) { save(ctx, load(ctx).filterNot { it.ssid == ssid }) }
}
