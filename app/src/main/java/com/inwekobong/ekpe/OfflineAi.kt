package com.inwekobong.ekpe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs a small LLM fully on the phone with MediaPipe LLM Inference. Model files (Gemma-style .task / .bin, 1-3B)
 * are big, so they live outside the APK in the app's external files folder.
 */
class OfflineAiSection(private val a: MainActivity) : Tool {
    private var llm: LlmInference? = null
    private var loaded = ""
    private lateinit var modelInfo: TextView
    private lateinit var models: LinearLayout
    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var lang: Spinner
    private lateinit var url: EditText
    private lateinit var token: EditText
    private var busy = false
    private var sr: SpeechRecognizer? = null
    private val langs = listOf("English", "Nigerian Pidgin", "Yoruba", "Igbo", "Hausa")
    private val speechTag = mapOf("English" to "en-NG", "Nigerian Pidgin" to "en-NG", "Yoruba" to "yo-NG", "Igbo" to "ig-NG", "Hausa" to "ha-NG")

    private fun dir() = File(a.getExternalFilesDir(null) ?: a.filesDir, "models").also { it.mkdirs() }

    override fun view(): View {
        val p = a.page()
        p.add(a.tv("Offline AI", 20f, C.TXT, true), 0)
        p.add(a.tv("Everything runs on the phone. You need a model file (for example a Gemma 2B .task/.bin from Hugging Face or Kaggle). 1–3B models run well only on mid-range phones and up, and need 2–4 GB of free RAM. Translation into Pidgin, Yoruba, Igbo and Hausa from a small model is rough: check important text.", 12f, C.SUB), 4)
        modelInfo = a.tv("No model loaded.", 13f, C.TXT, true); p.add(modelInfo, 10)
        models = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }; p.add(models, 6)
        p.add(a.button("Import model file from phone") { importModel() }, 6)
        url = a.edit("Or model download URL"); p.add(url, 6)
        token = a.edit("Access token (only if the site needs one)"); p.add(token, 4)
        p.add(a.button("Download model") { download() }, 4)
        input = a.edit("Type or speak here…").apply { minLines = 3; gravity = android.view.Gravity.TOP }
        p.add(input, 14)
        val lr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        lang = Spinner(a).apply { adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, langs) }
        lr.addView(a.button("🎤 Speak") { listen() }, LinearLayout.LayoutParams(0, -2, 1f))
        lr.addView(lang, LinearLayout.LayoutParams(0, -2, 1.2f).apply { leftMargin = a.dp(6) })
        p.add(lr, 6)
        p.add(a.tv("The language picker is used for speaking and as the translation target.", 11f, C.SUB), 2)
        val r1 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        r1.addView(a.button("Ask") { ask("chat") }, LinearLayout.LayoutParams(0, -2, 1f))
        r1.addView(a.button("Translate") { ask("translate") }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        r1.addView(a.button("Draft") { ask("draft") }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        p.add(r1, 6)
        p.add(a.button("Explain what Radar found") { ask("radar") }, 6)
        output = a.tv("", 14f, C.TXT).apply { setTextIsSelectable(true); background = round(C.CARD, a.dp(12)); setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10)) }
        p.add(output, 10)
        listModels()
        return p
    }

    private fun listModels() {
        models.removeAllViews()
        val fs = dir().listFiles { f -> f.isFile && (f.name.endsWith(".task") || f.name.endsWith(".bin") || f.name.endsWith(".tflite")) }?.toList() ?: emptyList()
        if (fs.isEmpty()) models.add(a.tv("No model files in ${dir().path}", 11f, C.SUB), 0)
        fs.forEach { f ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(a.tv("${f.name} (${f.length() / 1048576} MB)", 12f, if (f.name == loaded) C.ACC else C.TXT), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(a.button("Load") { load(f) })
            row.addView(a.button("Delete") { if (f.name == loaded) { llm?.close(); llm = null; loaded = "" }; f.delete(); listModels() }.apply { }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = a.dp(6) })
            models.add(row, 4)
        }
        models.applyTypeface(a.tf)
    }

    private fun load(f: File) {
        if (busy) return
        busy = true; modelInfo.text = "Loading ${f.name}… (can take a minute)"
        Thread {
            val r = runCatching {
                llm?.close(); llm = null
                LlmInference.createFromOptions(a, LlmInference.LlmInferenceOptions.builder().setModelPath(f.absolutePath).setMaxTokens(768).build())
            }
            a.runOnUiThread {
                busy = false
                if (r.isSuccess) { llm = r.getOrNull(); loaded = f.name; modelInfo.text = "Model ready: ${f.name}" }
                else modelInfo.text = "Could not load: ${r.exceptionOrNull()?.message}. Wrong format, or not enough RAM."
                listModels()
            }
        }.start()
    }

    private fun importModel() {
        a.pickFile { uri ->
            if (uri != null) Thread {
                val r = runCatching {
                    var nm = "model.task"; a.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) nm = c.getString(i) } }
                    val dst = File(dir(), File(nm).name); var n = 0L
                    a.contentResolver.openInputStream(uri)!!.use { i -> dst.outputStream().use { o -> val b = ByteArray(1 shl 16); while (true) { val k = i.read(b); if (k < 0) break; o.write(b, 0, k); n += k; if (n % (50L shl 20) < 65536) a.runOnUiThread { modelInfo.text = "Copying… ${n shr 20} MB" } } } }
                }
                a.runOnUiThread { modelInfo.text = if (r.isSuccess) "Imported. Tap Load." else "Import failed: ${r.exceptionOrNull()?.message}"; listModels() }
            }.start()
        }
    }

    private fun download() {
        val u = url.text.toString().trim(); if (!u.startsWith("http")) { a.toast("Enter a full https link"); return }
        Thread {
            val r = runCatching {
                val c = URL(u).openConnection() as HttpURLConnection; c.connectTimeout = 15000; c.readTimeout = 30000
                token.text.toString().trim().takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
                if (c.responseCode != 200) throw Exception("Server said ${c.responseCode}")
                val nm = u.substringBefore('?').substringAfterLast('/').ifEmpty { "model.task" }
                val tmp = File(dir(), "$nm.part"); var n = 0L; val tot = c.contentLengthLong
                c.inputStream.use { i -> tmp.outputStream().use { o -> val b = ByteArray(1 shl 16); var last = 0L
                    while (true) { val k = i.read(b); if (k < 0) break; o.write(b, 0, k); n += k
                        if (n - last > (20L shl 20)) { last = n; a.runOnUiThread { modelInfo.text = "Downloading ${n shr 20} MB" + if (tot > 0) " of ${tot shr 20}" else "" } } } } }
                tmp.renameTo(File(dir(), nm))
            }
            a.runOnUiThread { modelInfo.text = if (r.isSuccess) "Downloaded. Tap Load." else "Download failed: ${r.exceptionOrNull()?.message}"; listModels() }
        }.start()
    }

    private fun radarSummary(): String {
        val j = JSONObject(RadarEngine.snapshot())
        val sb = StringBuilder()
        val cells = j.optJSONArray("cells"); var serv = "none"
        if (cells != null) for (i in 0 until cells.length()) { val c = cells.getJSONObject(i); if (c.optBoolean("reg")) serv = "${c.optString("rat")} ${c.optInt("dbm")} dBm (${c.optInt("pct")}%), about ${c.optInt("d")} m (${c.optString("src")})" }
        sb.append("Serving cell tower: $serv. Other cells seen: ${(cells?.length() ?: 0) - 1}. ")
        val w = j.optJSONArray("wifi"); var open = 0
        if (w != null) for (i in 0 until w.length()) if (w.getJSONObject(i).optString("sec") == "Open") open++
        sb.append("Wi-Fi networks: ${w?.length() ?: 0} (open/unsecured: $open). Bluetooth devices: ${j.optJSONArray("ble")?.length() ?: 0}. ")
        sb.append("Magnetic field: ${"%.0f".format(j.optDouble("emf"))} µT, normal ${"%.0f".format(j.optDouble("base"))} µT. ")
        j.optJSONObject("net")?.let { sb.append("Connection: ${it.optString("type")}.") }
        return sb.toString()
    }

    private fun ask(mode: String) {
        val m = llm; if (m == null) { a.toast("Load a model first"); return }
        if (busy) return
        val txt = input.text.toString().trim()
        val target = lang.selectedItem.toString()
        val prompt = when (mode) {
            "translate" -> { if (txt.isEmpty()) { a.toast("Type something to translate"); return }; "Translate the following text into $target. Reply with only the translation.\n\n$txt" }
            "draft" -> { if (txt.isEmpty()) { a.toast("Say what the message is about"); return }; "Write a short, polite message in $target about: $txt\nReply with only the message." }
            "radar" -> { GuardPerm.startRadar(a); "You are explaining a phone's nearby-signal scan to a non-technical person in $target. Say what it means in 4 short sentences and mention anything unusual.\n\nScan: ${radarSummary()}" }
            else -> { if (txt.isEmpty()) { a.toast("Type a question"); return }; txt }
        }
        val full = if (loaded.contains("gemma", true)) "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n" else prompt
        busy = true; output.text = "Thinking…"
        Thread {
            val r = runCatching { m.generateResponse(full) }
            a.runOnUiThread { busy = false; output.text = if (r.isSuccess) r.getOrNull().orEmpty().trim() else "Error: ${r.exceptionOrNull()?.message}" }
        }.start()
    }

    private fun listen() {
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { a.ask(arrayOf(Manifest.permission.RECORD_AUDIO)) { if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen() }; return }
        if (!SpeechRecognizer.isRecognitionAvailable(a)) { a.toast("No speech recognition service on this phone"); return }
        sr?.destroy(); sr = SpeechRecognizer.createSpeechRecognizer(a)
        sr?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle) { b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { input.setText(it) } }
            override fun onPartialResults(b: Bundle) { b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { input.setText(it) } }
            override fun onError(e: Int) { a.toast("Voice input failed ($e). Offline languages may need downloading in Google app settings.") }
            override fun onReadyForSpeech(b: Bundle?) { modelInfo.append("") }
            override fun onBeginningOfSpeech() {}; override fun onRmsChanged(v: Float) {}; override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}; override fun onEvent(t: Int, b: Bundle?) {}
        })
        val it = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechTag[lang.selectedItem.toString()] ?: "en-NG")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        sr?.startListening(it)
    }

    override fun stop() { runCatching { sr?.destroy() }; sr = null }
}
