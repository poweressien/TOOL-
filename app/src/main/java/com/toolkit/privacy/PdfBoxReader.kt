package com.toolkit.privacy

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * Second-tier PDF text reader. The built-in parser in [PdfText] is fast and tiny; PDFBox understands embedded fonts with
 * custom encodings (many bank statements, invoices and e-books), so it is tried before falling back to OCR.
 */
object PdfBoxReader {
    fun read(ctx: Context, bytes: ByteArray, maxPages: Int = 60): PdfText.Info? = try {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        PDDocument.load(bytes).use { doc ->
            if (doc.isEncrypted) return@use null
            val pages = doc.numberOfPages
            val st = PDFTextStripper().apply { startPage = 1; endPage = minOf(pages, maxPages); sortByPosition = true }
            val text = st.getText(doc).orEmpty()
            val meta = LinkedHashMap<String, String>()
            doc.documentInformation?.let { i ->
                i.title?.takeIf { it.isNotBlank() }?.let { meta["Title"] = it }
                i.author?.takeIf { it.isNotBlank() }?.let { meta["Author"] = it }
                i.creator?.takeIf { it.isNotBlank() }?.let { meta["Creator"] = it }
                i.producer?.takeIf { it.isNotBlank() }?.let { meta["Producer"] = it }
                i.creationDate?.time?.let { meta["Created"] = Fmt.time(it.time, "yyyy-MM-dd") }
            }
            PdfText.Info(pages, text, meta, false)
        }
    } catch (e: Throwable) { null }
}
