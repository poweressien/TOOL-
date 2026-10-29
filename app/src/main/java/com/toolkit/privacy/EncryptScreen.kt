package com.toolkit.privacy

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Chunked AES-256-GCM (1 MB chunks, each authenticated, truncation-safe). Key from PBKDF2-HMAC-SHA256. */
object FileCrypt {
    private val MAGIC = "PTE1".toByteArray()
    private const val CHUNK = 1 shl 20

    private fun key(pw: CharArray, salt: ByteArray) = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw, salt, 200_000, 256)).encoded, "AES"
    )

    private fun iv(prefix: ByteArray, counter: Int): ByteArray = prefix + ByteBuffer.allocate(4).putInt(counter).array()
    private fun aad(counter: Int, last: Boolean): ByteArray = ByteBuffer.allocate(5).putInt(counter).put(if (last) 1 else 0).array()

    private fun readFully(i: InputStream, b: ByteArray): Int {
        var n = 0
        while (n < b.size) { val r = i.read(b, n, b.size - n); if (r < 0) break; n += r }
        return n
    }

    fun encrypt(inp: InputStream, out: OutputStream, pw: CharArray) {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val prefix = ByteArray(8).also { rnd.nextBytes(it) }
        out.write(MAGIC); out.write(salt); out.write(prefix)
        val k = key(pw, salt)
        val pin = PushbackInputStream(inp, 1)
        val buf = ByteArray(CHUNK)
        var counter = 0
        while (true) {
            val n = readFully(pin, buf)
            var last = n < CHUNK
            if (!last) { val b = pin.read(); if (b == -1) last = true else pin.unread(b) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv(prefix, counter)))
            c.updateAAD(aad(counter, last))
            val ct = c.doFinal(buf, 0, n)
            out.write(ByteBuffer.allocate(4).putInt(ct.size).array()); out.write(ct)
            counter++
            if (last) break
        }
        out.flush()
    }

    fun decrypt(inp: InputStream, out: OutputStream, pw: CharArray) {
        val head = ByteArray(28)
        if (readFully(inp, head) < 28 || !head.copyOfRange(0, 4).contentEquals(MAGIC)) throw IOException("Not a Privacy Toolkit encrypted file")
        val salt = head.copyOfRange(4, 20)
        val prefix = head.copyOfRange(20, 28)
        val k = key(pw, salt)
        val pin = PushbackInputStream(inp, 1)
        var counter = 0
        while (true) {
            val lenB = ByteArray(4)
            if (readFully(pin, lenB) < 4) throw IOException("File is truncated")
            val len = ByteBuffer.wrap(lenB).int
            if (len < 16 || len > CHUNK + 16) throw IOException("File is corrupted")
            val ct = ByteArray(len)
            if (readFully(pin, ct) < len) throw IOException("File is truncated")
            val b = pin.read()
            val last = b == -1
            if (!last) pin.unread(b)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv(prefix, counter)))
            c.updateAAD(aad(counter, last))
            out.write(c.doFinal(ct))
            counter++
            if (last) break
        }
        out.flush()
    }
}

private fun shred(ctx: Context, uri: Uri, size: Long): Boolean {
    val cr = ctx.contentResolver
    val rnd = SecureRandom()
    val ok = cr.openFileDescriptor(uri, "rw")?.use { pfd ->
        val ch = FileOutputStream(pfd.fileDescriptor).channel
        val buf = ByteArray(1 shl 16)
        for (pass in 0 until 3) {
            ch.position(0)
            var left = if (size > 0) size else ch.size()
            while (left > 0) {
                val n = minOf(left, buf.size.toLong()).toInt()
                if (pass == 1) java.util.Arrays.fill(buf, 0.toByte()) else rnd.nextBytes(buf)
                ch.write(ByteBuffer.wrap(buf, 0, n))
                left -= n
            }
            ch.force(true)
        }
        true
    } ?: false
    if (!ok) return false
    return try { DocumentsContract.deleteDocument(cr, uri) } catch (e: Exception) { false }
}

@Composable
fun EncryptScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<Picked?>(null) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Result>>(emptyList()) }
    var shredList by remember { mutableStateOf<List<Picked>>(emptyList()) }
    var confirmShred by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { file = pickFile(ctx, uri); results = emptyList(); msg = "" }
    }
    val shredPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) shredList = uris.map { pickFile(ctx, it) }
    }

    fun process(encrypt: Boolean) {
        val f = file ?: return
        if (encrypt && (pw.length < 6 || pw != pw2)) { msg = "Use 6+ characters and make both passwords match"; return }
        if (pw.isEmpty()) { msg = "Enter the password"; return }
        busy = true; msg = ""; results = emptyList()
        scope.launch {
            try {
                val outDir = cacheOut(ctx)
                val outName = if (encrypt) f.name + ".ptenc" else if (f.name.endsWith(".ptenc")) f.name.removeSuffix(".ptenc") else f.name + ".dec"
                val out = File(outDir, outName)
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(f.uri)!!.use { i ->
                        out.outputStream().buffered().use { o ->
                            if (encrypt) FileCrypt.encrypt(i, o, pw.toCharArray()) else FileCrypt.decrypt(i, o, pw.toCharArray())
                        }
                    }
                }
                results = listOf(Result(out.name, out, f.size, out.length(), "application/octet-stream"))
                msg = if (encrypt) "Encrypted. Keep the password safe, it can't be recovered." else "Decrypted."
            } catch (e: Exception) {
                msg = if (!encrypt) "Wrong password or corrupted file" else "Failed: ${e.message}"
            }
            busy = false
        }
    }

    if (confirmShred) {
        AlertDialog(
            onDismissRequest = { confirmShred = false },
            title = { Text("Shred ${shredList.size} file(s)?") },
            text = { Text("The files will be overwritten with random data and deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmShred = false; busy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { shredList.count { shred(ctx, it.uri, it.size) } }
                        msg = "Shredded $ok of ${shredList.size} file(s)"
                        shredList = emptyList(); busy = false
                    }
                }) { Text("Shred") }
            },
            dismissButton = { TextButton(onClick = { confirmShred = false }) { Text("Cancel") } }
        )
    }

    Column(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("File encryptor", style = MaterialTheme.typography.headlineSmall)
        Text("AES-256-GCM, fully offline. Works on any file size.", style = MaterialTheme.typography.bodySmall)
        Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text(if (file == null) "Choose file" else "Change file") }
        file?.let { Text("${it.name} · ${fmtSize(it.size)}", fontWeight = FontWeight.Medium) }
        OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pw2, { pw2 = it }, label = { Text("Confirm (for encrypting)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && file != null, onClick = { process(true) }) { Text("Encrypt") }
            OutlinedButton(enabled = !busy && file != null, onClick = { process(false) }) { Text("Decrypt") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg, fontWeight = FontWeight.Medium)
        ResultList(results)

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("Secure shredder", style = MaterialTheme.typography.titleLarge)
        Text(
            "Overwrites a file 3 times then deletes it. On flash storage, wear-levelling means this can't be guaranteed, so for sensitive files encrypt first, then shred.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { shredPicker.launch(arrayOf("*/*")) }) { Text("Choose files to shred") }
            if (shredList.isNotEmpty()) Button(enabled = !busy, onClick = { confirmShred = true }) { Text("Shred ${shredList.size}") }
        }
    }
}
