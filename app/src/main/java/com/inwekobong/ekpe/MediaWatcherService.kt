package com.inwekobong.ekpe

import android.app.*
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.WindowManager
import android.widget.*

class MediaWatcherService : Service() {
    companion object {
        const val ACT_PUSH = "push"
        fun running(c: Context) = c.prefs().getBoolean("watch_on", false)
    }

    private lateinit var wm: WindowManager
    private lateinit var nm: NotificationManager
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var worker: Handler
    private var observer: ContentObserver? = null
    private var popup: android.view.View? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("watch", "Gallery watcher", NotificationManager.IMPORTANCE_MIN))
        startForeground(10, Notification.Builder(this, "watch").setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Watching gallery for new photos & videos").build())
        val t = HandlerThread("media-watch").also { it.start() }
        worker = Handler(t.looper)
        val sp = prefs()
        if (!sp.contains("last_image")) { sp.edit().putLong("last_image", maxId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)).putLong("last_video", maxId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)).apply() }
        observer = object : ContentObserver(worker) { override fun onChange(selfChange: Boolean, uri: Uri?) { check() } }
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer!!)
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer!!)
    }

    private fun maxId(u: Uri): Long = try {
        contentResolver.query(u, arrayOf(MediaStore.MediaColumns._ID), null, null, "${MediaStore.MediaColumns._ID} DESC")?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    } catch (e: Exception) { 0L }

    private fun check() {
        scan(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "last_image", false)
        scan(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "last_video", true)
    }

    private fun scan(base: Uri, key: String, video: Boolean) {
        val last = prefs().getLong(key, 0)
        var newest = last
        try {
            contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
                "${MediaStore.MediaColumns._ID} > ?", arrayOf(last.toString()), "${MediaStore.MediaColumns._ID} ASC")?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0); newest = maxOf(newest, id)
                    val uri = ContentUris.withAppendedId(base, id)
                    onNewMedia(uri, it.getString(1) ?: "media_$id", it.getString(2) ?: if (video) "video/mp4" else "image/jpeg", video)
                }
            }
        } catch (e: Exception) {}
        if (newest > last) prefs().edit().putLong(key, newest).apply()
    }

    private fun onNewMedia(uri: Uri, name: String, mime: String, video: Boolean) {
        if (prefs().getBoolean("auto_upload", false)) { upload(uri, name, mime); return }
        val thumb = thumb(uri)
        notifyNew(uri, name, mime, video, thumb)
        if (Settings.canDrawOverlays(this)) ui.post { showPopup(uri, name, mime, video, thumb) }
    }

    private fun thumb(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(uri, Size(256, 256), null) else null
    } catch (e: Exception) { null }

    private fun pushIntent(uri: Uri, name: String, mime: String) = PendingIntent.getService(this, uri.hashCode(),
        Intent(this, MediaWatcherService::class.java).setAction(ACT_PUSH).putExtra("uri", uri.toString()).putExtra("name", name).putExtra("mime", mime),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notifyNew(uri: Uri, name: String, mime: String, video: Boolean, thumb: Bitmap?) {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= 33) return
        val b = Notification.Builder(this, com.toolkit.privacy.SoundCatalog.alertChannel(this, "media", "New media", android.app.NotificationManager.IMPORTANCE_HIGH, false)).setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(if (video) "New video captured" else "New photo captured").setContentText(name)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Push to Google Drive", pushIntent(uri, name, mime)).build())
        if (thumb != null) b.setLargeIcon(thumb).setStyle(Notification.BigPictureStyle().bigPicture(thumb))
        nm.notify(uri.hashCode(), b.build())
    }

    private fun showPopup(uri: Uri, name: String, mime: String, video: Boolean, thumb: Bitmap?) {
        try { popup?.let { wm.removeView(it) } } catch (e: Exception) {}
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(C.CARD, (14 * d).toInt()); setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt()) }
        box.addView(tv(if (video) "New video" else "New photo", 15f, C.TXT, true))
        if (thumb != null) box.addView(ImageView(this).apply { setImageBitmap(thumb) }, LinearLayout.LayoutParams((220 * d).toInt(), (160 * d).toInt()))
        box.addView(tv(name, 11f, C.SUB))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Push to Drive") { upload(uri, name, mime); dismissPopup() }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button("Dismiss") { dismissPopup() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = (8 * d).toInt() })
        box.addView(row)
        val lp = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (60 * d).toInt() }
        wm.addView(box, lp); popup = box
        ui.postDelayed({ dismissPopup() }, 12000)
    }

    private fun dismissPopup() { try { popup?.let { wm.removeView(it) } } catch (e: Exception) {}; popup = null }

    private fun upload(uri: Uri, name: String, mime: String) {
        Thread {
            try {
                DriveUploader.upload(this, uri, name, mime)
                val b = Notification.Builder(this, com.toolkit.privacy.SoundCatalog.alertChannel(this, "media", "New media", android.app.NotificationManager.IMPORTANCE_HIGH, false)).setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setContentTitle("Saved to Google Drive").setContentText(name).setAutoCancel(true)
                if (prefs().getBoolean("free_local", false)) {
                    val pi = PendingIntent.getActivity(this, uri.hashCode(), Intent(this, MainActivity::class.java)
                        .putExtra("delete_uri", uri.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    b.addAction(Notification.Action.Builder(null, "Free phone storage", pi).build())
                }
                nm.notify(uri.hashCode(), b.build())
            } catch (e: Exception) {
                nm.notify(uri.hashCode(), Notification.Builder(this, com.toolkit.privacy.SoundCatalog.alertChannel(this, "media", "New media", android.app.NotificationManager.IMPORTANCE_HIGH, false)).setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Drive upload failed").setContentText(e.message ?: "unknown error").build())
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACT_PUSH) {
            val u = Uri.parse(intent.getStringExtra("uri")); upload(u, intent.getStringExtra("name") ?: "media", intent.getStringExtra("mime") ?: "application/octet-stream")
        }
        return START_STICKY
    }

    override fun onDestroy() { observer?.let { contentResolver.unregisterContentObserver(it) }; dismissPopup(); super.onDestroy() }
}
