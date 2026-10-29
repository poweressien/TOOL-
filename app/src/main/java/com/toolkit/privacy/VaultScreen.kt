package com.toolkit.privacy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class VSession(val key: ByteArray, val salt: ByteArray)
data class VEntry(val title: String, val user: String, val pass: String, val url: String, val notes: String)

object VaultStore {
    private val MAGIC = "PTV1".toByteArray()
    private fun file(ctx: Context) = File(ctx.filesDir, "vault.bin")
    fun bioFile(ctx: Context) = File(ctx.filesDir, "vault.bio")
    fun exists(ctx: Context) = file(ctx).exists()
    fun salt(ctx: Context): ByteArray = file(ctx).readBytes().copyOfRange(4, 20)

    fun derive(pw: CharArray, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw, salt, 150_000, 256)).encoded

    fun save(ctx: Context, s: VSession, entries: List<VEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.user).put("p", it.pass).put("l", it.url).put("n", it.notes)) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(s.key, "AES"), GCMParameterSpec(128, iv))
        val ct = c.doFinal(arr.toString().toByteArray())
        file(ctx).writeBytes(MAGIC + s.salt + iv + ct)
    }

    /** Returns null when the key is wrong. */
    fun load(ctx: Context, key: ByteArray): List<VEntry>? = try {
        val b = file(ctx).readBytes()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b.copyOfRange(20, 32)))
        val arr = JSONArray(String(c.doFinal(b, 32, b.size - 32)))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            VEntry(o.optString("t"), o.optString("u"), o.optString("p"), o.optString("l"), o.optString("n"))
        }
    } catch (e: Exception) { null }
}

object VaultBio {
    private const val ALIAS = "pt_vault_bio"

    fun available(ctx: Context) =
        BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        return kg.generateKey()
    }

    fun deleteKey() {
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (e: Exception) {}
    }

    fun encryptCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
    fun decryptCipher(iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv)) }

    fun prompt(act: FragmentActivity, cipher: Cipher, title: String, onOk: (Cipher) -> Unit, onErr: (String) -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).build()
        BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                result.cryptoObject?.cipher?.let(onOk)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onErr(errString.toString()) }
        }).authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}

@Composable
fun VaultScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<VSession?>(null) }
    val entries = remember { mutableStateListOf<VEntry>() }
    var exists by remember { mutableStateOf(VaultStore.exists(ctx)) }
    var hasBio by remember { mutableStateOf(VaultStore.bioFile(ctx).exists()) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<Int, VEntry>?>(null) }
    var revealed by remember { mutableStateOf(setOf<Int>()) }

    fun persist() { session?.let { VaultStore.save(ctx, it, entries.toList()) } }
    fun open(key: ByteArray, salt: ByteArray): Boolean {
        val list = VaultStore.load(ctx, key) ?: return false
        session = VSession(key, salt); entries.clear(); entries.addAll(list); return true
    }

    val s = session
    if (s == null) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Vault", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (exists) "Encrypted with AES-256-GCM. Your master password never leaves this device and can't be recovered."
                else "Create a master password. Everything is encrypted offline on this phone.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(pw, { pw = it }, label = { Text("Master password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            if (!exists) {
                OutlinedTextField(pw2, { pw2 = it }, label = { Text("Confirm master password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && pw.isNotEmpty(), onClick = {
                    if (!exists && (pw.length < 8 || pw != pw2)) { msg = "Use 8+ characters and make both match"; return@Button }
                    busy = true; msg = ""
                    scope.launch {
                        if (!exists) {
                            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                            val key = withContext(Dispatchers.Default) { VaultStore.derive(pw.toCharArray(), salt) }
                            session = VSession(key, salt); entries.clear()
                            VaultStore.save(ctx, session!!, emptyList()); exists = true
                        } else {
                            val salt = VaultStore.salt(ctx)
                            val key = withContext(Dispatchers.Default) { VaultStore.derive(pw.toCharArray(), salt) }
                            if (!open(key, salt)) msg = "Wrong master password"
                        }
                        pw = ""; pw2 = ""; busy = false
                    }
                }) { Text(if (exists) "Unlock" else "Create vault") }
                if (exists && hasBio && VaultBio.available(ctx)) {
                    OutlinedButton(onClick = {
                        val act = ctx.findActivity() ?: return@OutlinedButton
                        val blob = VaultStore.bioFile(ctx).readBytes()
                        val cipher = try { VaultBio.decryptCipher(blob.copyOfRange(0, 12)) } catch (e: Exception) {
                            VaultStore.bioFile(ctx).delete(); VaultBio.deleteKey(); hasBio = false
                            msg = "Biometrics changed, use your master password"; return@OutlinedButton
                        }
                        VaultBio.prompt(act, cipher, "Unlock vault", { c ->
                            try {
                                val key = c.doFinal(blob, 12, blob.size - 12)
                                if (!open(key, VaultStore.salt(ctx))) msg = "Unlock failed"
                            } catch (e: Exception) { msg = "Unlock failed" }
                        }, { msg = it })
                    }) { Text("Use fingerprint") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
        }
        return
    }

    editing?.let { (idx, e) ->
        var t by remember(idx) { mutableStateOf(e.title) }
        var u by remember(idx) { mutableStateOf(e.user) }
        var p by remember(idx) { mutableStateOf(e.pass) }
        var l by remember(idx) { mutableStateOf(e.url) }
        var n by remember(idx) { mutableStateOf(e.notes) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (idx < 0) "New entry" else "Edit entry") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(t, { t = it }, label = { Text("Title") }, singleLine = true)
                    OutlinedTextField(u, { u = it }, label = { Text("Username / email") }, singleLine = true)
                    OutlinedTextField(p, { p = it }, label = { Text("Password") }, singleLine = true)
                    TextButton(onClick = { p = Pw.generate(20, true, true, true, true, false) }) { Text("Generate strong password") }
                    OutlinedTextField(l, { l = it }, label = { Text("Website") }, singleLine = true)
                    OutlinedTextField(n, { n = it }, label = { Text("Notes") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ne = VEntry(t.ifBlank { "Untitled" }, u, p, l, n)
                    if (idx < 0) entries.add(ne) else entries[idx] = ne
                    persist(); editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        item {
            Text("Vault · ${entries.size} entries", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { editing = -1 to VEntry("", "", "", "", "") }) { Text("＋ Add") }
                OutlinedButton(onClick = { session = null; entries.clear(); revealed = emptySet() }) { Text("Lock") }
                if (!hasBio && VaultBio.available(ctx)) {
                    OutlinedButton(onClick = {
                        val act = ctx.findActivity() ?: return@OutlinedButton
                        try {
                            VaultBio.prompt(act, VaultBio.encryptCipher(), "Enable fingerprint unlock", { c ->
                                val ct = c.doFinal(s.key)
                                VaultStore.bioFile(ctx).writeBytes(c.iv + ct)
                                hasBio = true; msg = "Fingerprint unlock enabled"
                            }, { msg = it })
                        } catch (e: Exception) { msg = "Biometric setup failed: ${e.message}" }
                    }) { Text("Enable fingerprint") }
                }
            }
            if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
        }
        itemsIndexed(entries.toList()) { i, e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(e.title, fontWeight = FontWeight.Bold)
                    if (e.user.isNotEmpty()) Text(e.user, style = MaterialTheme.typography.bodySmall)
                    Text(if (i in revealed) e.pass else "••••••••••", fontFamily = FontFamily.Monospace)
                    if (e.url.isNotEmpty()) Text(e.url, style = MaterialTheme.typography.bodySmall)
                    if (i in revealed && e.notes.isNotEmpty()) Text(e.notes, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { revealed = if (i in revealed) revealed - i else revealed + i }) { Text(if (i in revealed) "Hide" else "Show") }
                        TextButton(onClick = { copyText(ctx, e.pass, true); msg = "Copied. Clipboard clears in 30s"; scope.launch { delay(30_000); copyText(ctx, "") } }) { Text("Copy") }
                        TextButton(onClick = { editing = i to e }) { Text("Edit") }
                        TextButton(onClick = { entries.removeAt(i); revealed = emptySet(); persist() }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
