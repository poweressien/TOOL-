package com.inwekobong.ekpe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Reads photo metadata and runs Error Level Analysis. Gives hints, not proof. */
class MediaForensicsSection(private val a: MainActivity) : Tool {
    private lateinit var out: LinearLayout
    private lateinit var status: TextView

    override fun view(): View {
        val p = a.page()
        p.add(a.tv("Media Forensics", 20f, C.TXT, true), 0)
        p.add(a.tv("Pick a photo. You'll see its hidden metadata (device, time, GPS, editing software) and an Error Level map: edited areas of a JPEG often glow differently from the rest. Chat apps strip metadata and recompress, so a clean result is not proof, and a flag is not proof of fraud.", 12f, C.SUB), 4)
        p.add(a.button("Choose photo") { pick() }, 10)
        status = a.tv("", 12f, C.SUB); p.add(status, 8)
        out = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }; p.add(out, 8)
        return p
    }

    private fun pick() = a.pickFile { uri -> if (uri != null) { status.text = "Analysing…"; Thread { analyse(uri) }.start() } }

    private fun analyse(uri: Uri) {
        try {
            val rows = ArrayList<Row>(); val flags = ArrayList<String>()
            var name = "image"; var size = 0L
            a.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) } } }
            val mime = a.contentResolver.getType(uri) ?: ""
            val ex = a.contentResolver.openInputStream(uri)!!.use { ExifInterface(it) }
            fun g(t: String) = ex.getAttribute(t)?.trim().orEmpty()
            val make = g(ExifInterface.TAG_MAKE); val model = g(ExifInterface.TAG_MODEL); val soft = g(ExifInterface.TAG_SOFTWARE)
            val dtO = g(ExifInterface.TAG_DATETIME_ORIGINAL); val dtM = g(ExifInterface.TAG_DATETIME); val dtD = g(ExifInterface.TAG_DATETIME_DIGITIZED)
            val ll = ex.latLong
            rows += Row("FILE", header = true)
            rows += Row("Name", name); rows += Row("Type / size", "$mime · ${size / 1024} KB")
            rows += Row("METADATA", header = true)
            rows += Row("Camera", listOf(make, model).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "— none —" })
            rows += Row("Lens", g(ExifInterface.TAG_LENS_MODEL).ifEmpty { "—" })
            rows += Row("Taken", dtO.ifEmpty { "—" }); rows += Row("Modified", dtM.ifEmpty { "—" }); rows += Row("Digitized", dtD.ifEmpty { "—" })
            rows += Row("Software", soft.ifEmpty { "—" })
            rows += Row("GPS", if (ll != null) "%.5f, %.5f (tap for map)".format(ll[0], ll[1]) else "— none —", if (ll != null) "https://maps.google.com/?q=${ll[0]},${ll[1]}" else null)
            rows += Row("Exposure", listOf(g(ExifInterface.TAG_EXPOSURE_TIME).let { if (it.isEmpty()) "" else "${it}s" }, g(ExifInterface.TAG_F_NUMBER).let { if (it.isEmpty()) "" else "f/$it" }, g(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY).let { if (it.isEmpty()) "" else "ISO $it" }).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "—" })
            val w = ex.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0); val h = ex.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            rows += Row("Pixels (EXIF)", if (w > 0) "$w × $h" else "—")

            val editors = listOf("photoshop", "gimp", "canva", "snapseed", "lightroom", "pixelmator", "picsart", "photoroom", "paint", "affinity", "fotor", "pixlr", "editor", "illustrator", "wps", "camscanner")
            if (soft.isNotEmpty() && editors.any { soft.contains(it, true) }) flags += "Editing/design software in metadata: $soft"
            if (make.isEmpty() && model.isEmpty() && dtO.isEmpty()) flags += "No camera or capture time: metadata was stripped or the file is a screenshot/export"
            if (dtO.isNotEmpty() && dtM.isNotEmpty() && dtO != dtM) flags += "Modified time ($dtM) differs from capture time ($dtO)"
            if (mime.contains("png") ) flags += "PNG: usually a screenshot or export, not a camera original"

            // Error Level Analysis (meaningful for JPEG)
            var elaBm: Bitmap? = null
            if (mime.contains("jpeg") || mime.contains("jpg") || name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }) {
                val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                a.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bo) }
                var ss = 1; while (bo.outWidth / ss > 3000 || bo.outHeight / ss > 3000) ss *= 2
                val bm = a.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = ss }) }
                if (bm != null) {
                    val bos = ByteArrayOutputStream(); bm.compress(Bitmap.CompressFormat.JPEG, 90, bos)
                    val re = BitmapFactory.decodeByteArray(bos.toByteArray(), 0, bos.size())
                    val wd = bm.width; val ht = bm.height
                    val p1 = IntArray(wd * ht); val p2 = IntArray(wd * ht); bm.getPixels(p1, 0, wd, 0, 0, wd, ht); re.getPixels(p2, 0, wd, 0, 0, wd, ht)
                    val ela = IntArray(wd * ht); val lvl = FloatArray(wd * ht)
                    for (i in p1.indices) {
                        val d = (abs(((p1[i] shr 16) and 255) - ((p2[i] shr 16) and 255)) + abs(((p1[i] shr 8) and 255) - ((p2[i] shr 8) and 255)) + abs((p1[i] and 255) - (p2[i] and 255))) / 3f
                        lvl[i] = d; val v = (d * 20).toInt().coerceIn(0, 255); ela[i] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
                    }
                    elaBm = Bitmap.createBitmap(ela, wd, ht, Bitmap.Config.ARGB_8888)
                    // block statistics: 16x16 blocks whose error level is far above the image's typical level
                    val bs = 16; val bw = wd / bs; val bh = ht / bs; val means = ArrayList<Float>()
                    for (by in 0 until bh) for (bx in 0 until bw) { var s = 0f; for (y in 0 until bs) for (x in 0 until bs) s += lvl[(by * bs + y) * wd + bx * bs + x]; means.add(s / (bs * bs)) }
                    if (means.isNotEmpty()) {
                        val mean = means.average(); val sd = sqrt(means.map { (it - mean) * (it - mean) }.average())
                        val hot = means.count { it > mean + 3 * sd && it > 1.5f }
                        val pct = hot * 100.0 / means.size
                        rows += Row("ERROR LEVEL", header = true)
                        rows += Row("Average", "%.2f (higher = more compression change)".format(mean))
                        rows += Row("Outlier blocks", "%.1f%% of the picture stand out".format(pct))
                        if (pct in 0.5..30.0) flags += "Some regions have a very different error level from the rest (%.1f%% of blocks). Text or numbers pasted into a photo often look like this".format(pct)
                        if (sd < 0.15 && mean < 0.8) flags += "Very uniform error level: image was likely recompressed many times (e.g. forwarded on chat), which weakens this test"
                    }
                }
            } else rows += Row("Error level", "Skipped: only meaningful for JPEG photos")

            rows += Row("HINTS", header = true)
            if (flags.isEmpty()) rows += Row("Result", "Nothing unusual found. This does not prove the image is genuine.")
            else flags.forEachIndexed { i, f -> rows += Row("${i + 1}", f) }
            rows += Row("Remember", "Hints only. Confirm receipts and proof with the bank, seller or sender.")
            val final = elaBm
            a.runOnUiThread {
                status.text = "Done."
                out.removeAllViews()
                if (final != null) {
                    out.add(a.tv("Error Level map (bright = changes more on recompression)", 12f, C.SUB), 0)
                    out.addView(ImageView(a).apply { setImageBitmap(final); adjustViewBounds = true; setBackgroundColor(0xFF000000.toInt()) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(6); bottomMargin = a.dp(10) })
                }
                out.addView(a.table(rows)); out.applyTypeface(a.tf)
            }
        } catch (e: Throwable) { a.runOnUiThread { status.text = "Could not analyse: ${e.message ?: e.javaClass.simpleName}" } }
    }
}
