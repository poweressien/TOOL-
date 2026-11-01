package com.toolkit.privacy.core

import com.toolkit.privacy.*

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color as AColor
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat

/** One resolved set of colours. Used by the Compose screens and by the EKPE (View based) screens. */
class Palette(
    val bg: Int, val card: Int, val text: Int, val sub: Int,
    val accent: Int, val onAccent: Int, val light: Boolean
)

/** The theme choices shown in Appearance. */
enum class ThemeMode(val label: String, val blurb: String) {
    POWDER("Powder Black", "Soft matte black with butter-white text."),
    BUTTER("Butter White", "Warm butter-white with powder-black text."),
    AUTO("Follow system", "Powder Black or Butter White, matching your phone's dark / light setting."),
    SYSTEM("System colors", "Uses your phone's own wallpaper colours (Android 12 and newer)."),
    CUSTOM("Custom", "Pick your own accent and background.")
}

object ThemeState {
    private const val K_MODE = "theme_mode"
    private const val K_ACCENT = "theme_accent"
    private const val K_BG = "theme_bg"

    const val DEFAULT_ACCENT = 0xFFF2C94C.toInt()
    const val DEFAULT_BG = 0xFF121214.toInt()

    var mode by mutableStateOf(ThemeMode.POWDER)
        private set
    var customAccent by mutableIntStateOf(DEFAULT_ACCENT)
        private set
    var customBg by mutableIntStateOf(DEFAULT_BG)
        private set

    /** Bumped on every change so the EKPE activity can tell its colours are stale. */
    @Volatile var version = 0
        private set
    private var loaded = false

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val sp = Prefs.sp(ctx)
        mode = try { ThemeMode.valueOf(sp.getString(K_MODE, ThemeMode.POWDER.name) ?: ThemeMode.POWDER.name) } catch (e: Exception) { ThemeMode.POWDER }
        customAccent = sp.getInt(K_ACCENT, DEFAULT_ACCENT)
        customBg = sp.getInt(K_BG, DEFAULT_BG)
    }

    fun setMode(ctx: Context, m: ThemeMode) {
        mode = m; version++
        Prefs.sp(ctx).edit().putString(K_MODE, m.name).apply()
    }

    fun setCustom(ctx: Context, accent: Int, bg: Int) {
        customAccent = accent or 0xFF000000.toInt(); customBg = bg or 0xFF000000.toInt()
        mode = ThemeMode.CUSTOM; version++
        Prefs.sp(ctx).edit().putString(K_MODE, ThemeMode.CUSTOM.name).putInt(K_ACCENT, customAccent).putInt(K_BG, customBg).apply()
    }

    fun systemDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun readable(onColor: Int) = if (ColorUtils.calculateLuminance(onColor) > 0.5) 0xFF1A1400.toInt() else 0xFFFFFFFF.toInt()

    private fun powder() = Palette(
        bg = 0xFF121214.toInt(), card = 0xFF1D1D21.toInt(), text = 0xFFFFF6DB.toInt(), sub = 0xFFB9B29E.toInt(),
        accent = 0xFFF2C94C.toInt(), onAccent = 0xFF1A1400.toInt(), light = false
    )

    private fun butter() = Palette(
        bg = 0xFFFFF9E6.toInt(), card = 0xFFF6EBC8.toInt(), text = 0xFF161618.toInt(), sub = 0xFF6B6655.toInt(),
        accent = 0xFF8F6500.toInt(), onAccent = 0xFFFFFFFF.toInt(), light = true
    )

    /** Builds a full palette from just a background and an accent. Text colour is chosen for contrast. */
    private fun derive(bg: Int, accent: Int): Palette {
        val light = ColorUtils.calculateLuminance(bg) > 0.5
        val text = if (light) 0xFF161618.toInt() else 0xFFFFF6DB.toInt()
        return Palette(
            bg = bg,
            card = ColorUtils.blendARGB(bg, text, if (light) 0.08f else 0.09f),
            text = text,
            sub = ColorUtils.blendARGB(text, bg, 0.38f),
            accent = accent, onAccent = readable(accent), light = light
        )
    }

    private fun system(ctx: Context, dark: Boolean): Palette {
        if (Build.VERSION.SDK_INT < 31) return if (dark) powder() else butter()
        return try {
            val bg = ctx.getColor(if (dark) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_10)
            val acc = ctx.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
            derive(bg, acc)
        } catch (e: Throwable) { if (dark) powder() else butter() }
    }

    fun palette(ctx: Context): Palette {
        ensureLoaded(ctx)
        val dark = systemDark(ctx)
        return when (mode) {
            ThemeMode.POWDER -> powder()
            ThemeMode.BUTTER -> butter()
            ThemeMode.AUTO -> if (dark) powder() else butter()
            ThemeMode.SYSTEM -> system(ctx, dark)
            ThemeMode.CUSTOM -> derive(customBg, customAccent)
        }
    }
}

private fun Int.c() = Color(this)

fun paletteScheme(p: Palette) = run {
    val text = p.text
    val bg = p.bg
    fun mix(t: Float) = ColorUtils.blendARGB(bg, text, t).c()
    if (p.light) lightColorScheme(
        primary = p.accent.c(), onPrimary = p.onAccent.c(),
        primaryContainer = ColorUtils.blendARGB(bg, p.accent, 0.22f).c(), onPrimaryContainer = text.c(),
        secondary = p.accent.c(), onSecondary = p.onAccent.c(),
        secondaryContainer = ColorUtils.blendARGB(bg, p.accent, 0.18f).c(), onSecondaryContainer = text.c(),
        tertiary = p.accent.c(), onTertiary = p.onAccent.c(),
        background = bg.c(), onBackground = text.c(),
        surface = bg.c(), onSurface = text.c(),
        surfaceVariant = p.card.c(), onSurfaceVariant = p.sub.c(),
        surfaceTint = p.accent.c(),
        outline = p.sub.c(), outlineVariant = mix(0.16f),
        surfaceContainerLowest = bg.c(), surfaceContainerLow = mix(0.03f), surfaceContainer = mix(0.06f),
        surfaceContainerHigh = p.card.c(), surfaceContainerHighest = mix(0.12f),
        surfaceBright = bg.c(), surfaceDim = mix(0.08f)
    ) else darkColorScheme(
        primary = p.accent.c(), onPrimary = p.onAccent.c(),
        primaryContainer = ColorUtils.blendARGB(bg, p.accent, 0.28f).c(), onPrimaryContainer = text.c(),
        secondary = p.accent.c(), onSecondary = p.onAccent.c(),
        secondaryContainer = ColorUtils.blendARGB(bg, p.accent, 0.22f).c(), onSecondaryContainer = text.c(),
        tertiary = p.accent.c(), onTertiary = p.onAccent.c(),
        background = bg.c(), onBackground = text.c(),
        surface = bg.c(), onSurface = text.c(),
        surfaceVariant = p.card.c(), onSurfaceVariant = p.sub.c(),
        surfaceTint = p.accent.c(),
        outline = p.sub.c(), outlineVariant = mix(0.16f),
        surfaceContainerLowest = bg.c(), surfaceContainerLow = mix(0.04f), surfaceContainer = mix(0.07f),
        surfaceContainerHigh = p.card.c(), surfaceContainerHighest = mix(0.13f),
        surfaceBright = mix(0.16f), surfaceDim = bg.c()
    )
}

private fun Context.activityOrNull(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    ThemeState.ensureLoaded(ctx)
    // Reading these state values makes the whole UI recompose the moment the theme changes.
    val mode = ThemeState.mode
    val accent = ThemeState.customAccent
    val bgc = ThemeState.customBg
    val sysDark = isSystemInDarkTheme()
    val p = remember(mode, accent, bgc, sysDark) { ThemeState.palette(ctx) }
    val view = LocalView.current
    SideEffect {
        val w = ctx.activityOrNull()?.window
        if (w != null) {
            w.statusBarColor = p.bg
            w.navigationBarColor = p.bg
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(p.bg))
            val ic = WindowCompat.getInsetsController(w, view)
            ic.isAppearanceLightStatusBars = p.light
            ic.isAppearanceLightNavigationBars = p.light
        }
    }
    MaterialTheme(colorScheme = paletteScheme(p), content = content)
}

/** Parses "#RRGGBB" or "RRGGBB". Returns null when the text is not a valid colour. */
fun parseHexColor(s: String): Int? {
    val t = s.trim().removePrefix("#")
    if (t.length != 6) return null
    return try { AColor.parseColor("#$t") } catch (e: Exception) { null }
}
