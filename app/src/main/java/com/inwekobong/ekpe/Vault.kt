package com.inwekobong.ekpe

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Encrypts in independent 64 KB AES-GCM chunks so big files never need to sit in memory. */
class ChunkOut(private val o: OutputStream, private val key: SecretKey) : OutputStream() {
    private val buf = ByteArray(65536); private var n = 0
    override fun write(b: Int) { if (n == buf.size) flushChunk(); buf[n++] = b.toByte() }
    override fun write(b: ByteArray, off: Int, len: Int) {
        var p = off; var l = len
        while (l > 0) { if (n == buf.size) flushChunk(); val c = minOf(l, buf.size - n); System.arraycopy(b, p, buf, n, c); n += c; p += c; l -= c }
    }
    private fun flushChunk() {
        if (n == 0) return
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key)
        val ct = c.doFinal(buf, 0, n)
        o.write(ByteBuffer.allocate(4).putInt(ct.size).array()); o.write(c.iv); o.write(ct); n = 0
    }
    override fun close() { flushChunk(); o.write(ByteArray(4)); o.flush(); o.close() }
}

class ChunkIn(i: InputStream, private val key: SecretKey) : InputStream() {
    private val d = DataInputStream(i)
    private var cur = ByteArray(0); private var p = 0; private var done = false
    private fun fill(): Boolean {
        while (p >= cur.size) {
            if (done) return false
            val len = d.readInt()
            if (len == 0) { done = true; return false }
            if (len < 16 || len > 65536 + 16) throw IOException("Corrupt data")
            val iv = ByteArray(12); d.readFully(iv); val ct = ByteArray(len); d.readFully(ct)
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cur = c.doFinal(ct); p = 0
        }
        return true
    }
    override fun read(): Int { if (!fill()) return -1; return cur[p++].toInt() and 0xFF }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val c = minOf(len, cur.size - p); System.arraycopy(cur, p, b, off, c); p += c; return c
    }
    override fun close() = d.close()
}

class VaultItem(val id: String, val name: String, val type: String, val size: Long, val time: Long)

object VaultStore {
    private const val ALIAS = "ekpe_vault_key"
    private lateinit var dir: File

    fun init(c: android.content.Context) { dir = File(c.filesDir, "vault").also { it.mkdirs() } }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    private fun idx() = File(dir, "index.bin")

    @Synchronized fun list(): MutableList<VaultItem> {
        val f = idx(); if (!f.exists()) return ArrayList()
        return try {
            val txt = ChunkIn(FileInputStream(f), key()).use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONArray(txt)
            (0 until arr.length()).map { val o = arr.getJSONObject(it); VaultItem(o.getString("id"), o.getString("n"), o.getString("t"), o.getLong("s"), o.getLong("ts")) }.toMutableList()
        } catch (e: Exception) { ArrayList() }
    }

    @Synchronized private fun save(items: List<VaultItem>) {
        val arr = JSONArray(); items.forEach { arr.put(JSONObject().put("id", it.id).put("n", it.name).put("t", it.type).put("s", it.size).put("ts", it.time)) }
        ChunkOut(FileOutputStream(idx()), key()).use { it.write(arr.toString().toByteArray()) }
    }

    @Synchronized private fun addItem(id: String, name: String, type: String, size: Long) {
        val l = list(); l.add(VaultItem(id, name, type, size, System.currentTimeMillis())); save(l)
    }

    fun addStream(name: String, type: String, ins: InputStream): String {
        val id = UUID.randomUUID().toString(); var n = 0L
        try {
            ChunkOut(FileOutputStream(File(dir, "$id.bin")), key()).use { o ->
                val b = ByteArray(65536); while (true) { val r = ins.read(b); if (r < 0) break; o.write(b, 0, r); n += r }
            }
            addItem(id, name, type, n)
        } catch (e: Exception) { File(dir, "$id.bin").delete(); throw e }
        return id
    }

    fun addNote(title: String, text: String) { addStream(title, "note", ByteArrayInputStream(text.toByteArray())) }
    fun open(id: String): InputStream = ChunkIn(FileInputStream(File(dir, "$id.bin")), key())
    fun readBytes(id: String): ByteArray = open(id).use { it.readBytes() }

    @Synchronized fun delete(id: String) { File(dir, "$id.bin").delete(); save(list().filter { it.id != id }) }

    private fun pbKey(pw: CharArray, salt: ByteArray): SecretKey {
        val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw, salt, 150000, 256))
        return SecretKeySpec(k.encoded, "AES")
    }

    /** Backup file: "EKPV1" + salt + chunk-encrypted container, protected by the password you choose. */
    fun backup(out: OutputStream, pw: CharArray) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        out.write("EKPV1".toByteArray()); out.write(salt)
        val items = list()
        DataOutputStream(ChunkOut(out, pbKey(pw, salt))).use { d ->
            d.writeInt(items.size)
            for (it in items) {
                d.writeUTF(it.name); d.writeUTF(it.type); d.writeLong(it.size)
                open(it.id).use { s -> val b = ByteArray(65536); var left = it.size; while (left > 0) { val r = s.read(b, 0, minOf(b.size.toLong(), left).toInt()); if (r < 0) throw EOFException(); d.write(b, 0, r); left -= r } }
            }
        }
    }

    fun restore(ins: InputStream, pw: CharArray): Int {
        val head = ByteArray(5); DataInputStream(ins).readFully(head)
        if (String(head) != "EKPV1") throw IOException("Not an EKPE vault backup")
        val salt = ByteArray(16); DataInputStream(ins).readFully(salt)
        val d = DataInputStream(ChunkIn(ins, pbKey(pw, salt)))
        val made = ArrayList<VaultItem>()
        try {
            val count = d.readInt()
            repeat(count) {
                val name = d.readUTF(); val type = d.readUTF(); val size = d.readLong()
                val id = UUID.randomUUID().toString(); var left = size
                ChunkOut(FileOutputStream(File(dir, "$id.bin")), key()).use { o ->
                    val b = ByteArray(65536); while (left > 0) { val r = d.read(b, 0, minOf(b.size.toLong(), left).toInt()); if (r < 0) throw EOFException(); o.write(b, 0, r); left -= r }
                }
                made.add(VaultItem(id, name, type, size, System.currentTimeMillis()))
            }
        } catch (e: Exception) { made.forEach { File(dir, "${it.id}.bin").delete() }; throw IOException("Wrong password or damaged backup") }
        synchronized(this) { val l = list(); l.addAll(made); save(l) }
        return made.size
    }
}

class VaultSection(private val a: MainActivity) : Tool {
    private lateinit var box: LinearLayout
    private var unlocked = false

    override fun view(): View {
        VaultStore.init(a)
        box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        lockedUi()
        return box
    }

    override fun stop() {
        unlocked = false
        a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (::box.isInitialized) lockedUi()
    }

    private fun lockedUi() {
        box.removeAllViews()
        box.add(a.tv("Private Vault", 20f, C.TXT, true), 0)
        box.add(a.tv("Photos, files and notes are encrypted on this phone with a key held in Android's secure hardware, and opened with your fingerprint (or screen lock). Uninstalling the app erases the vault, so use Backup regularly.", 12f, C.SUB), 4)
        box.add(a.button("🔒 Unlock") { unlock() }, 12)
        box.applyTypeface(a.tf)
    }

    private fun unlock() {
        val can = BiometricManager.from(a).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) { a.toast("Set up a fingerprint or screen lock in phone Settings first."); return }
        val prompt = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                unlocked = true
                a.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                openUi()
            }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Private Vault").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
    }

    private fun openUi() {
        if (!unlocked) return
        box.removeAllViews()
        box.add(a.tv("Private Vault · unlocked", 20f, C.TXT, true), 0)
        val r1 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        r1.addView(a.button("Add file / photo") { addFile() }, LinearLayout.LayoutParams(0, -2, 1f))
        r1.addView(a.button("Add note") { addNote() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        box.add(r1, 10)
        val r2 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        r2.addView(a.button("Backup") { backup() }, LinearLayout.LayoutParams(0, -2, 1f))
        r2.addView(a.button("Restore") { restore() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        r2.addView(a.button("Lock") { stop() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        box.add(r2, 6)
        val items = VaultStore.list().sortedByDescending { it.time }
        box.add(a.tv("${items.size} item(s)", 12f, C.SUB), 10)
        items.forEach { it -> box.add(itemCard(it), 6) }
        box.applyTypeface(a.tf)
    }

    private fun itemCard(it: VaultItem): View {
        val c = a.card()
        c.addView(a.tv((if (it.type == "note") "📝 " else if (it.type == "image") "🖼 " else "📄 ") + it.name, 14f, C.TXT, true))
        c.addView(a.tv("${it.size / 1024} KB", 11f, C.SUB))
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(a.button("Open") { view(it) }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(a.button("Export") { export(it) }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        r.addView(a.button("Delete") {
            AlertDialog.Builder(a).setMessage("Delete \"${it.name}\" from the vault?").setPositiveButton("Delete") { _, _ -> VaultStore.delete(it.id); openUi() }.setNegativeButton("Cancel", null).show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        c.add(r, 8)
        return c
    }

    private fun view(it: VaultItem) {
        try {
            when (it.type) {
                "note" -> AlertDialog.Builder(a).setTitle(it.name).setMessage(VaultStore.readBytes(it.id).toString(Charsets.UTF_8)).setPositiveButton("Close", null).show()
                "image" -> {
                    val bytes = VaultStore.readBytes(it.id)
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    var s = 1; while (o.outWidth / s > 2048 || o.outHeight / s > 2048) s *= 2
                    val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
                    AlertDialog.Builder(a).setView(ImageView(a).apply { setImageBitmap(bm); adjustViewBounds = true }).setPositiveButton("Close", null).show()
                }
                else -> a.toast("Use Export to open this file in another app.")
            }
        } catch (e: Exception) { a.toast("Could not open: ${e.message}") }
    }

    private fun export(it: VaultItem) {
        a.saveFile(it.name) { uri ->
            if (uri != null) Thread {
                val ok = runCatching { a.contentResolver.openOutputStream(uri)!!.use { o -> VaultStore.open(it.id).use { s -> s.copyTo(o) } } }.isSuccess
                a.runOnUiThread { a.toast(if (ok) "Exported (this copy is NOT encrypted)" else "Export failed") }
            }.start()
        }
    }

    private fun addFile() {
        a.pickFile { uri ->
            if (uri != null) Thread {
                val ok = runCatching {
                    var nm = "file"; a.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) nm = c.getString(i) } }
                    val mime = a.contentResolver.getType(uri) ?: ""
                    a.contentResolver.openInputStream(uri)!!.use { VaultStore.addStream(nm, if (mime.startsWith("image/")) "image" else "file", it) }
                }
                a.runOnUiThread { if (ok.isFailure) a.toast("Could not add: ${ok.exceptionOrNull()?.message}") else a.toast("Added. You can now delete the original."); openUi() }
            }.start()
        }
    }

    private fun addNote() {
        val t = a.edit("Title"); val b = a.edit("Note text").apply { minLines = 4 }
        val l = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(8), a.dp(16), 0) }
        l.add(t, 0); l.add(b, 8)
        AlertDialog.Builder(a).setTitle("New note").setView(l).setPositiveButton("Save") { _, _ ->
            runCatching { VaultStore.addNote(t.text.toString().ifBlank { "Note" }, b.text.toString()) }.onFailure { a.toast("Could not save note") }; openUi()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun askPw(title: String, then: (CharArray) -> Unit) {
        val e = a.edit("Backup password").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val l = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(8), a.dp(16), 0) }; l.add(e, 0)
        AlertDialog.Builder(a).setTitle(title).setView(l).setPositiveButton("OK") { _, _ ->
            val pw = e.text.toString().toCharArray(); if (pw.size < 6) a.toast("Use at least 6 characters") else then(pw)
        }.setNegativeButton("Cancel", null).show()
    }

    private fun backup() = askPw("Choose a backup password (don't lose it)") { pw ->
        a.saveFile("EKPE-vault.ekpv") { uri ->
            if (uri != null) Thread {
                val r = runCatching { a.contentResolver.openOutputStream(uri)!!.use { VaultStore.backup(it, pw) } }
                a.runOnUiThread { a.toast(if (r.isSuccess) "Backup saved. Keep it somewhere safe (Drive, PC)." else "Backup failed: ${r.exceptionOrNull()?.message}") }
            }.start()
        }
    }

    private fun restore() = a.pickFile { uri ->
        if (uri != null) askPw("Backup password") { pw ->
            Thread {
                val r = runCatching { a.contentResolver.openInputStream(uri)!!.use { VaultStore.restore(it, pw) } }
                a.runOnUiThread { a.toast(if (r.isSuccess) "Restored ${r.getOrNull()} item(s)" else "Restore failed: ${r.exceptionOrNull()?.message}"); openUi() }
            }.start()
        }
    }
}
