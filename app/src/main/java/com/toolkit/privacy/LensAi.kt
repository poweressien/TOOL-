package com.toolkit.privacy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** On-device ML Kit wrappers (all recognition runs on the phone) plus a few public lookups. */
object LensAi {
    data class Obj(val box: Rect, val label: String, val conf: Float)
    data class Block(val box: Rect, val text: String)
    data class Plant(val name: String, val common: String, val score: Double)

    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }
    private val detector by lazy { ObjectDetection.getClient(ObjectDetectorOptions.Builder().setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE).enableMultipleObjects().enableClassification().build()) }

    val languages = linkedMapOf("English" to "en", "French" to "fr", "Spanish" to "es", "Portuguese" to "pt", "German" to "de", "Italian" to "it", "Arabic" to "ar", "Chinese" to "zh",
        "Japanese" to "ja", "Korean" to "ko", "Hindi" to "hi", "Swahili" to "sw", "Russian" to "ru", "Turkish" to "tr", "Indonesian" to "id", "Dutch" to "nl", "Afrikaans" to "af")

    suspend fun labels(b: Bitmap, min: Float = 0.45f): List<Pair<String, Float>> =
        labeler.process(InputImage.fromBitmap(b, 0)).await().filter { it.confidence >= min }.sortedByDescending { it.confidence }.map { it.text to it.confidence }

    suspend fun objects(b: Bitmap): List<Obj> =
        detector.process(InputImage.fromBitmap(b, 0)).await().map { o -> Obj(o.boundingBox, o.labels.maxByOrNull { it.confidence }?.text ?: "Object", o.labels.maxOfOrNull { it.confidence } ?: 0f) }

    private fun recognizer(script: String): TextRecognizerOptionsInterface = when (script) {
        "Chinese" -> ChineseTextRecognizerOptions.Builder().build(); "Japanese" -> JapaneseTextRecognizerOptions.Builder().build()
        "Korean" -> KoreanTextRecognizerOptions.Builder().build(); "Devanagari" -> DevanagariTextRecognizerOptions.Builder().build(); else -> TextRecognizerOptions.DEFAULT_OPTIONS
    }

    val scripts = listOf("Auto", "Latin", "Chinese", "Japanese", "Korean", "Devanagari")

    private suspend fun ocrOne(b: Bitmap, script: String): Text {
        val r = TextRecognition.getClient(recognizer(script))
        try { return r.process(InputImage.fromBitmap(b, 0)).await() } finally { r.close() }
    }

    /** Auto tries Latin first and only runs the other alphabets when Latin finds almost nothing. Returns the script used and the result. */
    suspend fun ocr(b: Bitmap, script: String = "Auto"): Pair<String, Text> {
        if (script != "Auto") return script to ocrOne(b, script)
        var best = "Latin" to ocrOne(b, "Latin")
        fun letters(t: Text) = t.text.count { it.isLetter() }
        if (letters(best.second) < 8) for (s in listOf("Chinese", "Japanese", "Korean", "Devanagari")) {
            val t = try { ocrOne(b, s) } catch (e: Exception) { continue }
            if (letters(t) > letters(best.second) + 3) best = s to t
        }
        return best
    }

    fun blocks(t: Text): List<Block> = t.textBlocks.mapNotNull { bl -> bl.boundingBox?.let { Block(it, bl.text) } }

    suspend fun detectLanguage(text: String): String? {
        val id = LanguageIdentification.getClient()
        return try { id.identifyLanguage(text.take(500)).await().takeIf { it != "und" } } catch (e: Exception) { null } finally { id.close() }
    }

    /** Translates one or more strings. The language model (~30 MB) is downloaded once and then works offline. */
    suspend fun translate(texts: List<String>, src: String, dst: String): List<String>? {
        val s = TranslateLanguage.fromLanguageTag(src) ?: return null
        val d = TranslateLanguage.fromLanguageTag(dst) ?: return null
        if (s == d) return texts
        val tr = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(s).setTargetLanguage(d).build())
        return try { tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await(); texts.map { tr.translate(it).await() } } finally { tr.close() }
    }

    // ---- public lookups

    data class Wiki(val title: String, val desc: String, val extract: String, val url: String)

    fun wiki(query: String): Wiki? {
        val s = Http.jsonArray("https://en.wikipedia.org/w/api.php?action=opensearch&limit=1&format=json&search=" + URLEncoder.encode(query, "UTF-8")) ?: return null
        val title = s.optJSONArray(1)?.optString(0)?.takeIf { it.isNotBlank() } ?: return null
        val j = Http.json("https://en.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8")) ?: return null
        val extract = j.optString("extract").takeIf { it.isNotBlank() } ?: return null
        return Wiki(j.optString("title", title), j.optString("description"), extract, j.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page") ?: "")
    }

    /** Pl@ntNet species identification. Needs the user's own free API key from my.plantnet.org. */
    fun plantNet(b: Bitmap, key: String): List<Plant>? {
        val bos = ByteArrayOutputStream(); val small = Bitmap.createScaledBitmap(b, 1024, (1024f * b.height / b.width).toInt().coerceAtLeast(1), true)
        small.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        val boundary = "----lens${System.nanoTime()}"
        return try {
            val c = URL("https://my-api.plantnet.org/v2/identify/all?api-key=${URLEncoder.encode(key, "UTF-8")}&lang=en&nb-results=4").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 30000
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.outputStream.use { o ->
                o.write("--$boundary\r\nContent-Disposition: form-data; name=\"organs\"\r\n\r\nauto\r\n".toByteArray())
                o.write("--$boundary\r\nContent-Disposition: form-data; name=\"images\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                o.write(bos.toByteArray()); o.write("\r\n--$boundary--\r\n".toByteArray())
            }
            if (c.responseCode != 200) return null
            val j = org.json.JSONObject(c.inputStream.bufferedReader().readText()); val r = j.optJSONArray("results") ?: return emptyList()
            (0 until r.length()).mapNotNull { i -> val o = r.optJSONObject(i) ?: return@mapNotNull null; val sp = o.optJSONObject("species") ?: return@mapNotNull null
                Plant(sp.optString("scientificNameWithoutAuthor"), sp.optJSONArray("commonNames")?.optString(0).orEmpty(), o.optDouble("score", 0.0)) }
        } catch (e: Exception) { null }
    }

    // ---- drawing

    private fun scaleFor(b: Bitmap) = (maxOf(b.width, b.height) / 900f).coerceAtLeast(1f)

    fun drawBoxes(src: Bitmap, boxes: List<Triple<Rect, String, Int>>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true); val c = Canvas(out); val k = scaleFor(out)
        val p = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f * k; isAntiAlias = true }
        val tp = Paint().apply { textSize = 18f * k; isAntiAlias = true; color = Color.WHITE }
        val bg = Paint().apply { style = Paint.Style.FILL }
        for ((r, label, col) in boxes) {
            p.color = col; c.drawRect(r, p)
            bg.color = col; val w = tp.measureText(label) + 10f * k; val top = (r.top - 24f * k).coerceAtLeast(0f)
            c.drawRect(r.left.toFloat(), top, r.left + w, top + 24f * k, bg); c.drawText(label, r.left + 5f * k, top + 18f * k, tp)
        }
        return out
    }

    /** Paints the translation on top of each text block so the sign reads in the new language. */
    fun drawTranslated(src: Bitmap, items: List<Pair<Rect, String>>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true); val c = Canvas(out)
        val bg = Paint().apply { color = 0xE6101820.toInt(); style = Paint.Style.FILL }
        for ((r, text) in items) {
            c.drawRect(r, bg)
            val tp = TextPaint().apply { color = Color.WHITE; isAntiAlias = true }
            var size = (r.height() * 0.6f).coerceIn(14f, 90f); var layout: StaticLayout
            while (true) {
                tp.textSize = size
                layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, r.width().coerceAtLeast(20)).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
                if (layout.height <= r.height() || size <= 12f) break
                size *= 0.88f
            }
            c.save(); c.translate(r.left.toFloat(), r.top.toFloat()); c.clipRect(0, 0, r.width(), r.height()); layout.draw(c); c.restore()
        }
        return out
    }

    fun shrink(b: Bitmap, maxSide: Int): Bitmap {
        val m = maxOf(b.width, b.height); if (m <= maxSide) return b
        val s = maxSide.toFloat() / m; return Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }
}
