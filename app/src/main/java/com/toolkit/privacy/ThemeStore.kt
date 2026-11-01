package com.toolkit.privacy

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color as AColor
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat

/** The four ways to colour the app. */
enum class ThemeMode(val label: String, val blurb: String) {
    POWDER_BLACK("Powder Black", "Soft black with butter-yellow accents"),
    BUTTER_WHITE("Butter White", "Warm creamy white with golden accents"),
    SYSTEM("System", "Follows your phone: dark or light, plus your wallpaper accent on Android 12+"),
    CUSTOM("Custom", "Pick your own background, cards, text and accent colours")
}

/** One resolved palette. All values are opaque ARGB ints so both Compose and classic Views can use them. */
data class AppColors(
    val dark: Boolean,
    val bg: Int,
    val card: Int,
    val text: Int,
    val sub: Int,
    val accent: Int,
    val onAccent: Int
) {
    fun toScheme(): ColorScheme {
        val bgC = Color(bg); val cardC = Color(card); val textC = Color(text); val subC = Color(sub); val accC = Color(accent)
        val onAcc = Color(onAccent)
        val container = Color(ColorUtils.blendARGB(card, accent, 0.28f))
        val outline = Color(ColorUtils.blendARGB(bg, text, 0.35f))
        val outlineVar = Color(ColorUtils.blendARGB(bg, text, 0.16f))
        return if (dark) darkColorScheme(
            primary = accC, onPrimary = onAcc, primaryContainer = container, onPrimaryContainer = textC,
            secondary = accC, onSecondary = onAcc, secondaryContainer = container, onSecondaryContainer = textC,
            tertiary = accC, onTertiary = onAcc,
            background = bgC, onBackground = textC,
            surface = bgC, onSurface = textC, surfaceVariant = cardC, onSurfaceVariant = subC,
            surfaceTint = accC, outline = outline, outlineVariant = outlineVar,
            surfaceContainerLowest = bgC, surfaceContainerLow = bgC, surfaceContainer = cardC,
            surfaceContainerHigh = cardC, surfaceContainerHighest = cardC
        ) else lightColorScheme(
            primary = accC, onPrimary = onAcc, primaryContainer = container, onPrimaryContainer = textC,
            secondary = accC, onSecondary = onAcc, secondaryContainer = container, onSecondaryContainer = textC,
            tertiary = accC, onTertiary = onAcc,
            background = bgC, onBackground = textC,
            surface = bgC, onSurface = textC, surfaceVariant = cardC, onSurfaceVariant = subC,
            surfaceTint = accC, outline = outline, outlineVariant = outlineVar,
            surfaceContainerLowest = bgC, surfaceContainerLow = bgC, surfaceContainer = cardC,
            surfaceContainerHigh = cardC, surfaceContainerHighest = cardC
        )
    }
}

/**
 * App-wide colour settings. One store feeds both the Compose screens (through [AppTheme]) and the classic-View EKPE
 * screens (through `C` in the EKPE package). Changing anything bumps [version] so Compose redraws at once and the
 * EKPE screen rebuilds the next time it is shown.
 */
object ThemeStore {
    private const val FILE = "ekpe_theme"
    private const val K_MODE = "mode"
    private const val K_BASE_DARK = "c_dark"
    private const val K_BG = "c_bg"
    private const val K_CARD = "c_card"
    private const val K_TEXT = "c_text"
    private const val K_ACCENT = "c_accent"

    val POWDER = AppColors(true, 0xFF0E1013.toInt(), 0xFF1A1D23.toInt(), 0xFFEDEEF0.toInt(), 0xFF9BA1AB.toInt(), 0xFFF3D87A.toInt(), 0xFF1A1500.toInt())
    val BUTTER = AppColors(false, 0xFFFFFBEC.toInt(), 0xFFFFF1C9.toInt(), 0xFF1F1B12.toInt(), 0xFF6F6753.toInt(), 0xFF8A6400.toInt(), 0xFFFFFFFF.toInt())

    private var versionState by mutableIntStateOf(0)
    val version: Int get() = versionState

    @Volatile private var inited = false
    private var mode = ThemeMode.POWDER_BLACK
    private var cDark = true
    private var cBg = POWDER.bg
    private var cCard = POWDER.card
    private var cText = POWDER.text
    private var cAccent = POWDER.accent

    /** Latest resolved palette. The EKPE views read this. */
    @Volatile var current: AppColors = POWDER
        private set

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (!inited) {
            val s = sp(ctx)
            mode = try { ThemeMode.valueOf(s.getString(K_MODE, ThemeMode.POWDER_BLACK.name) ?: ThemeMode.POWDER_BLACK.name) } catch (e: Exception) { ThemeMode.POWDER_BLACK }
            cDark = s.getBoolean(K_BASE_DARK, true)
            cBg = s.getInt(K_BG, POWDER.bg); cCard = s.getInt(K_CARD, POWDER.card)
            cText = s.getInt(K_TEXT, POWDER.text); cAccent = s.getInt(K_ACCENT, POWDER.accent)
            inited = true
        }
        refresh(ctx)
    }

    /** Recomputes [current] (call from every activity's onCreate so System mode tracks the phone's dark/light setting). */
    fun refresh(ctx: Context) { current = resolve(ctx, systemIsDark(ctx)) }

    fun systemIsDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    val currentMode: ThemeMode get() = mode
    val customDark: Boolean get() = cDark
    val customBg: Int get() = cBg
    val customCard: Int get() = cCard
    val customText: Int get() = cText
    val customAccent: Int get() = cAccent

    fun onColorFor(color: Int): Int = if (ColorUtils.calculateLuminance(color) > 0.5) 0xFF1A1500.toInt() else 0xFFFFFFFF.toInt()

    fun resolve(ctx: Context, systemDark: Boolean): AppColors = when (mode) {
        ThemeMode.POWDER_BLACK -> POWDER
        ThemeMode.BUTTER_WHITE -> BUTTER
        ThemeMode.SYSTEM -> {
            val base = if (systemDark) POWDER else BUTTER
            val acc = systemAccent(ctx, systemDark) ?: base.accent
            base.copy(accent = acc, onAccent = onColorFor(acc))
        }
        ThemeMode.CUSTOM -> AppColors(cDark, cBg or 0xFF000000.toInt(), cCard or 0xFF000000.toInt(), cText or 0xFF000000.toInt(),
            ColorUtils.blendARGB(cBg or 0xFF000000.toInt(), cText or 0xFF000000.toInt(), 0.62f),
            cAccent or 0xFF000000.toInt(), onColorFor(cAccent or 0xFF000000.toInt()))
    }

    private fun systemAccent(ctx: Context, dark: Boolean): Int? {
        if (Build.VERSION.SDK_INT < 31) return null
        return try { ctx.getColor(if (dark) android.R.color.system_accent1_300 else android.R.color.system_accent1_600) } catch (e: Exception) { null }
    }

    private fun bump(ctx: Context) { refresh(ctx); versionState += 1 }

    fun setMode(ctx: Context, m: ThemeMode) {
        mode = m; sp(ctx).edit().putString(K_MODE, m.name).apply(); bump(ctx)
    }

    /** Switches to Custom and stores whichever fields are given. */
    fun setCustom(ctx: Context, dark: Boolean? = null, bg: Int? = null, card: Int? = null, text: Int? = null, accent: Int? = null) {
        if (dark != null) cDark = dark
        if (bg != null) cBg = bg or 0xFF000000.toInt()
        if (card != null) cCard = card or 0xFF000000.toInt()
        if (text != null) cText = text or 0xFF000000.toInt()
        if (accent != null) cAccent = accent or 0xFF000000.toInt()
        mode = ThemeMode.CUSTOM
        sp(ctx).edit().putString(K_MODE, mode.name).putBoolean(K_BASE_DARK, cDark).putInt(K_BG, cBg).putInt(K_CARD, cCard)
            .putInt(K_TEXT, cText).putInt(K_ACCENT, cAccent).apply()
        bump(ctx)
    }

    /** Fills the custom fields from a ready-made palette (starting point for customising). */
    fun customFrom(ctx: Context, p: AppColors) = setCustom(ctx, p.dark, p.bg, p.card, p.text, p.accent)

    fun reset(ctx: Context) {
        mode = ThemeMode.POWDER_BLACK; cDark = true
        cBg = POWDER.bg; cCard = POWDER.card; cText = POWDER.text; cAccent = POWDER.accent
        sp(ctx).edit().clear().apply(); bump(ctx)
    }

    fun parseHex(s: String): Int? {
        val t = s.trim().removePrefix("#")
        if (t.length != 6 || !t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return try { AColor.parseColor("#$t") } catch (e: Exception) { null }
    }

    fun hex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)
}

/** Wraps the whole UI in the chosen colours. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    ThemeStore.init(ctx)
    val sysDark = isSystemInDarkTheme()
    val v = ThemeStore.version
    val colors = remember(v, sysDark) { ThemeStore.resolve(ctx, sysDark) }
    SystemBarsEffect(colors)
    MaterialTheme(colorScheme = colors.toScheme(), content = content)
}

private fun Context.hostActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Paints the status bar, navigation bar and window background in the theme colours. */
@Composable
private fun SystemBarsEffect(colors: AppColors) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val w = view.context.hostActivity()?.window ?: return@SideEffect
        w.statusBarColor = colors.bg
        w.navigationBarColor = colors.bg
        w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(colors.bg))
        val ic = WindowCompat.getInsetsController(w, view)
        ic.isAppearanceLightStatusBars = !colors.dark
        ic.isAppearanceLightNavigationBars = !colors.dark
    }
}
