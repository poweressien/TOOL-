package com.inwekobong.ekpe

import android.content.ContentValues
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object FontStore {
    // Google Fonts (OFL) files fetched on demand from the public google/fonts repository
    val catalog = listOf(
        "Pacifico" to "pacifico/Pacifico-Regular.ttf", "Lobster" to "lobster/Lobster-Regular.ttf",
        "Bangers" to "bangers/Bangers-Regular.ttf", "Permanent Marker" to "permanentmarker/PermanentMarker-Regular.ttf",
        "Righteous" to "righteous/Righteous-Regular.ttf", "Orbitron" to "orbitron/Orbitron[wght].ttf",
        "Comfortaa" to "comfortaa/Comfortaa[wght].ttf", "Caveat" to "caveat/Caveat[wght].ttf",
        "Special Elite" to "specialelite/SpecialElite-Regular.ttf", "Press Start 2P" to "pressstart2p/PressStart2P-Regular.ttf",
        "Abril Fatface" to "abrilfatface/AbrilFatface-Regular.ttf", "Satisfy" to "satisfy/Satisfy-Regular.ttf",
        "Indie Flower" to "indieflower/IndieFlower-Regular.ttf", "Amatic SC" to "amaticsc/AmaticSC-Regular.ttf",
        "Monoton" to "monoton/Monoton-Regular.ttf", "Creepster" to "creepster/Creepster-Regular.ttf",
        "Bungee" to "bungee/Bungee-Regular.ttf", "Chewy" to "chewy/Chewy-Regular.ttf",
        "Courgette" to "courgette/Courgette-Regular.ttf", "Kaushan Script" to "kaushanscript/KaushanScript-Regular.ttf",
        "Great Vibes" to "greatvibes/GreatVibes-Regular.ttf", "Rock Salt" to "rocksalt/RockSalt-Regular.ttf",
        "Russo One" to "russoone/RussoOne-Regular.ttf", "Sigmar One" to "sigmarone/SigmarOne-Regular.ttf",
        "Roboto Mono" to "robotomono/RobotoMono[wght].ttf", "Playfair Display" to "playfairdisplay/PlayfairDisplay[wght].ttf",
        "Lobster Two" to "lobstertwo/LobsterTwo-Regular.ttf", "Fredericka the Great" to "frederickathegreat/FrederickatheGreat-Regular.ttf"
    )

    fun dir(c: Context) = File(c.filesDir, "fonts").apply { mkdirs() }
    fun systemFonts(): List<File> = File("/system/fonts").listFiles()
        ?.filter { it.extension.lowercase() in listOf("ttf", "otf") }?.sortedBy { it.name } ?: emptyList()
    fun imported(c: Context): List<File> = dir(c).listFiles()?.sortedBy { it.name } ?: emptyList()

    fun load(path: String?): Typeface? = try {
        if (path.isNullOrEmpty()) null else Typeface.createFromFile(path)
    } catch (e: Exception) { null }

    fun importFrom(c: Context, uri: Uri): File? = try {
        val name = (uri.lastPathSegment ?: "font_${System.currentTimeMillis()}.ttf").substringAfterLast('/').let {
            if (it.lowercase().endsWith(".ttf") || it.lowercase().endsWith(".otf")) it else "$it.ttf"
        }
        val out = File(dir(c), name)
        c.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        if (load(out.path) == null) { out.delete(); null } else out
    } catch (e: Exception) { null }

    fun download(c: Context, title: String, path: String): File? = try {
        val enc = path.split("/").joinToString("/") { Uri.encode(it) }
        val conn = URL("https://raw.githubusercontent.com/google/fonts/main/ofl/$enc").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        if (conn.responseCode != 200) null else {
            val out = File(dir(c), title.replace(" ", "") + ".ttf")
            conn.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            if (load(out.path) == null) { out.delete(); null } else out
        }
    } catch (e: Exception) { null }

    /** Copies a font into Downloads/EKPEFonts so font managers (HyperOS Themes, Samsung, etc.) can import it. */
    fun exportToDownloads(c: Context, f: File): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, "font/ttf")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/EKPEFonts")
            }
            val u = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
            c.contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        } else {
            val d = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "EKPEFonts")
            d.mkdirs(); f.copyTo(File(d, f.name), true)
        }
        true
    } catch (e: Exception) { false }
}
