package com.inwekobong.ekpe

import android.content.Context
import android.net.Uri
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object DriveUploader {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val FOLDER = "EKPE Uploads"

    fun signedIn(c: Context) = GoogleSignIn.getLastSignedInAccount(c)?.email

    private fun token(c: Context): String {
        val acc = GoogleSignIn.getLastSignedInAccount(c)?.account ?: error("Not signed in to Google")
        return GoogleAuthUtil.getToken(c, acc, "oauth2:$SCOPE")
    }

    private fun call(method: String, url: String, tok: String, body: String? = null): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method; conn.setRequestProperty("Authorization", "Bearer $tok")
        conn.connectTimeout = 20000; conn.readTimeout = 60000
        if (body != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json"); conn.outputStream.use { it.write(body.toByteArray()) } }
        val code = conn.responseCode
        val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) error("Drive error $code: ${txt.take(200)}")
        return JSONObject(txt)
    }

    private fun folderId(c: Context, tok: String): String {
        val sp = c.prefs()
        sp.getString("drive_folder", null)?.let { return it }
        val q = URLEncoder.encode("name='$FOLDER' and mimeType='application/vnd.google-apps.folder' and trashed=false", "UTF-8")
        val found = call("GET", "https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id)", tok).getJSONArray("files")
        val id = if (found.length() > 0) found.getJSONObject(0).getString("id")
        else call("POST", "https://www.googleapis.com/drive/v3/files", tok,
            JSONObject().put("name", FOLDER).put("mimeType", "application/vnd.google-apps.folder").toString()).getString("id")
        sp.edit().putString("drive_folder", id).apply(); return id
    }

    /** Returns Drive file id. Streams the file so large videos do not fill memory. */
    fun upload(c: Context, uri: Uri, name: String, mime: String): String {
        val tok = token(c); val folder = folderId(c, tok)
        val b = "tk${System.currentTimeMillis()}"
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folder)).toString()
        val conn = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true; conn.setChunkedStreamingMode(256 * 1024)
        conn.connectTimeout = 20000; conn.readTimeout = 120000
        conn.setRequestProperty("Authorization", "Bearer $tok")
        conn.setRequestProperty("Content-Type", "multipart/related; boundary=$b")
        conn.outputStream.use { o ->
            o.write("--$b\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$b\r\nContent-Type: $mime\r\n\r\n".toByteArray())
            c.contentResolver.openInputStream(uri)!!.use { it.copyTo(o) }
            o.write("\r\n--$b--".toByteArray())
        }
        val code = conn.responseCode
        val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) error("Upload failed $code: ${txt.take(200)}")
        val id = JSONObject(txt).getString("id")
        record(c, id, name, mime); return id
    }

    private fun record(c: Context, id: String, name: String, mime: String) {
        val arr = JSONArray(c.prefs().getString("uploads", "[]"))
        arr.put(JSONObject().put("id", id).put("name", name).put("mime", mime).put("t", System.currentTimeMillis()))
        c.prefs().edit().putString("uploads", arr.toString()).apply()
    }

    fun uploads(c: Context): List<JSONObject> {
        val arr = JSONArray(c.prefs().getString("uploads", "[]"))
        return (arr.length() - 1 downTo 0).map { arr.getJSONObject(it) }
    }
}
