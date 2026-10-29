package com.toolkit.privacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private fun renderPage(r: PdfRenderer, i: Int, scale: Float): Bitmap {
    val page = r.openPage(i)
    val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
    bmp.eraseColor(AColor.WHITE)
    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    page.close()
    return bmp
}

private fun addBitmapPage(doc: PdfDocument, bmp: Bitmap, w: Int, h: Int, num: Int) {
    val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, num).create())
    page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
    doc.finishPage(page)
}

fun pdfPageCount(ctx: Context, f: Picked): Int = try {
    ctx.contentResolver.openFileDescriptor(f.uri, "r")!!.use { pfd -> PdfRenderer(pfd).use { it.pageCount } }
} catch (e: Exception) { 0 }

private fun parseRanges(spec: String, max: Int): List<Int> {
    val out = LinkedHashSet<Int>()
    spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { t ->
        if ('-' in t) {
            val (a, b) = t.split('-').map { it.trim().toInt() }
            for (p in a..b) if (p in 1..max) out += p
        } else t.toInt().let { if (it in 1..max) out += it }
    }
    return out.toList()
}

private fun imagesToPdf(ctx: Context, files: List<Picked>, out: File, progress: (Float) -> Unit) {
    val doc = PdfDocument()
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    files.forEachIndexed { i, f ->
        val bmp = loadBitmap(ctx, f.uri, 2400) ?: return@forEachIndexed
        val land = bmp.width > bmp.height
        val pw = if (land) 842 else 595
        val ph = if (land) 595 else 842
        val s = minOf((pw - 40f) / bmp.width, (ph - 40f) / bmp.height)
        val w = bmp.width * s
        val h = bmp.height * s
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
        page.canvas.drawColor(AColor.WHITE)
        val l = (pw - w) / 2
        val t = (ph - h) / 2
        page.canvas.drawBitmap(bmp, null, RectF(l, t, l + w, t + h), paint)
        doc.finishPage(page)
        bmp.recycle()
        progress((i + 1f) / files.size)
    }
    FileOutputStream(out).use { doc.writeTo(it) }
    doc.close()
}

private fun pdfToImages(ctx: Context, f: Picked, outDir: File, progress: (Float) -> Unit): List<Result> {
    val res = mutableListOf<Result>()
    val base = f.name.substringBeforeLast('.', f.name)
    ctx.contentResolver.openFileDescriptor(f.uri, "r")!!.use { pfd ->
        PdfRenderer(pfd).use { r ->
            for (i in 0 until r.pageCount) {
                val bmp = renderPage(r, i, 2f)
                val out = File(outDir, "%s_page%02d.png".format(base, i + 1))
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                res += Result(out.name, out, 0, out.length(), "image/png")
                progress((i + 1f) / r.pageCount)
            }
        }
    }
    return res
}

private fun mergePdfs(ctx: Context, files: List<Picked>, out: File, progress: (Float) -> Unit) {
    val doc = PdfDocument()
    var num = 1
    files.forEachIndexed { fi, f ->
        ctx.contentResolver.openFileDescriptor(f.uri, "r")!!.use { pfd ->
            PdfRenderer(pfd).use { r ->
                for (i in 0 until r.pageCount) {
                    val bmp = renderPage(r, i, 2f)
                    addBitmapPage(doc, bmp, bmp.width / 2, bmp.height / 2, num++)
                    bmp.recycle()
                }
            }
        }
        progress((fi + 1f) / files.size)
    }
    FileOutputStream(out).use { doc.writeTo(it) }
    doc.close()
}

private fun splitPdf(ctx: Context, f: Picked, spec: String, out: File, progress: (Float) -> Unit) {
    val doc = PdfDocument()
    ctx.contentResolver.openFileDescriptor(f.uri, "r")!!.use { pfd ->
        PdfRenderer(pfd).use { r ->
            val pages = parseRanges(spec, r.pageCount)
            if (pages.isEmpty()) error("No valid pages in \"$spec\" (document has ${r.pageCount})")
            pages.forEachIndexed { n, p ->
                val bmp = renderPage(r, p - 1, 2f)
                addBitmapPage(doc, bmp, bmp.width / 2, bmp.height / 2, n + 1)
                bmp.recycle()
                progress((n + 1f) / pages.size)
            }
        }
    }
    FileOutputStream(out).use { doc.writeTo(it) }
    doc.close()
}

@Composable
fun PdfScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("img2pdf") }
    var picked by remember { mutableStateOf<List<Picked>>(emptyList()) }
    var pages by remember { mutableIntStateOf(0) }
    var ranges by remember { mutableStateOf("1-2") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var msg by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Result>>(emptyList()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            picked = uris.map { pickFile(ctx, it) }; results = emptyList(); msg = ""
            pages = if (mode == "split" || mode == "pdf2img") pdfPageCount(ctx, picked.first()) else 0
        }
    }
    val modes = listOf("img2pdf" to "Images → PDF", "pdf2img" to "PDF → images", "merge" to "Merge PDFs", "split" to "Split PDF")

    Column(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("PDF toolkit", style = MaterialTheme.typography.headlineSmall)
        Text("All offline. Merge and split re-render pages as images, so text becomes non-selectable.", style = MaterialTheme.typography.bodySmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(modes) { (k, l) -> FilterChip(selected = mode == k, onClick = { mode = k; picked = emptyList(); results = emptyList(); msg = "" }, label = { Text(l) }) }
        }
        Button(enabled = !busy, onClick = { picker.launch(if (mode == "img2pdf") arrayOf("image/*") else arrayOf("application/pdf")) }) {
            Text(if (mode == "img2pdf") "Choose images (in page order)" else if (mode == "merge") "Choose PDFs (in order)" else "Choose PDF")
        }
        if (picked.isNotEmpty()) {
            Text("${picked.size} file(s) selected" + if (pages > 0) " · $pages pages" else "", fontWeight = FontWeight.Medium)
            picked.take(8).forEachIndexed { i, p -> Text("${i + 1}. ${p.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
        }
        if (mode == "split") OutlinedTextField(ranges, { ranges = it }, label = { Text("Pages to keep, e.g. 1-3,5") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && picked.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(50.dp), onClick = {
            busy = true; progress = 0f; msg = ""; results = emptyList()
            scope.launch {
                try {
                    val dir = cacheOut(ctx)
                    results = withContext(Dispatchers.IO) {
                        val total = picked.sumOf { it.size }
                        when (mode) {
                            "img2pdf" -> { val o = File(dir, "images.pdf"); imagesToPdf(ctx, picked, o) { progress = it }; listOf(Result(o.name, o, total, o.length(), "application/pdf")) }
                            "pdf2img" -> pdfToImages(ctx, picked.first(), dir) { progress = it }
                            "merge" -> { val o = File(dir, "merged.pdf"); mergePdfs(ctx, picked, o) { progress = it }; listOf(Result(o.name, o, total, o.length(), "application/pdf")) }
                            else -> { val o = File(dir, picked.first().name.substringBeforeLast('.') + "-split.pdf"); splitPdf(ctx, picked.first(), ranges, o) { progress = it }; listOf(Result(o.name, o, total, o.length(), "application/pdf")) }
                        }
                    }
                    msg = "Done"
                } catch (e: Exception) { msg = "Failed: ${e.message}" }
                busy = false
            }
        }) { Text(if (busy) "Working…" else "Run") }
        if (busy) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
        ResultList(results)
    }
}
