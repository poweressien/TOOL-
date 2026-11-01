package com.toolkit.privacy

import android.app.Activity
import android.content.Intent
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Opening screen: plays the ANDROIDIE splash video (res/raw/splash_video.mp4, compressed to ~0.4 MB),
 * then fades into the home screen. The video is scaled to the screen width and its top and bottom edges are
 * feathered into the black background so it blends with the screen around it. Tap to skip.
 */
class SplashActivity : Activity() {
    private var mp: MediaPlayer? = null
    private var leaving = false
    private var shownAt = 0L
    private lateinit var root: FrameLayout
    private lateinit var tex: TextureView
    private lateinit var topFade: View
    private lateinit var botFade: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.sp(this).getBoolean(PREF_SPLASH, true)) { goHome(false); return }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        tex = TextureView(this)
        root.addView(tex, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        topFade = View(this).apply { background = fade(true) }
        botFade = View(this).apply { background = fade(false) }
        root.addView(topFade, FrameLayout.LayoutParams(1, 1))
        root.addView(botFade, FrameLayout.LayoutParams(1, 1))
        root.setOnClickListener { if (System.currentTimeMillis() - shownAt > 1200) goHome(true) }
        setContentView(root)
        shownAt = System.currentTimeMillis()

        tex.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = startVideo(Surface(st))
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun fade(top: Boolean) = GradientDrawable(
        if (top) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(0xFF000000.toInt(), 0x00000000)
    )

    private fun startVideo(surface: Surface) {
        try {
            val p = MediaPlayer()
            mp = p
            p.setDataSource(this, Uri.parse("android.resource://$packageName/${R.raw.splash_video}"))
            p.setSurface(surface)
            p.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) root.post { fit(w, h) } }
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener { goHome(true) }
            p.setOnErrorListener { _, _, _ -> goHome(false); true }
            p.prepareAsync()
        } catch (e: Exception) { goHome(false) }
    }

    /** Fit the video inside the screen (full width on phones) and feather its top and bottom edges. */
    private fun fit(vw: Int, vh: Int) {
        val fw = root.width.toFloat(); val fh = root.height.toFloat()
        if (fw <= 0f || fh <= 0f) return
        val scale = minOf(fw / vw, fh / vh)
        val w = (vw * scale).toInt(); val h = (vh * scale).toInt()
        tex.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        val band = (h * 0.10f).toInt()
        val top = ((fh - h) / 2f).toInt()
        topFade.layoutParams = FrameLayout.LayoutParams(w, band, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = top }
        botFade.layoutParams = FrameLayout.LayoutParams(w, band, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = top }
        tex.requestLayout()
    }

    private fun goHome(animate: Boolean) {
        if (leaving) return
        leaving = true
        val go = {
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            release(); finish()
        }
        if (animate && ::root.isInitialized) root.animate().alpha(0f).setDuration(350).withEndAction { go() }.start() else go()
    }

    private fun release() {
        try { mp?.release() } catch (e: Exception) { }
        mp = null
    }

    @Deprecated("Back skips the splash")
    override fun onBackPressed() { goHome(true) }

    override fun onPause() { super.onPause(); try { mp?.pause() } catch (e: Exception) { } }
    override fun onResume() { super.onResume(); try { if (!leaving) mp?.start() } catch (e: Exception) { } }
    override fun onDestroy() { release(); super.onDestroy() }

    companion object { const val PREF_SPLASH = "splash_on" }
}
