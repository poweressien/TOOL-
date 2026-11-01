package com.toolkit.privacy.core

import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext

/**
 * Remembers where each screen was scrolled to, across navigation, rotation and the app being closed,
 * so coming back lands on the exact spot instead of the top.
 */
object ScrollMemory {
    private const val FILE = "toolkit_nav"
    private val mem = HashMap<String, Int>()

    fun get(ctx: Context, key: String): Int =
        mem[key] ?: ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("scroll_$key", 0)

    fun put(ctx: Context, key: String, y: Int) {
        mem[key] = y
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt("scroll_$key", y).apply()
    }

    /** Last open tool name ("" = none) and page name. */
    fun lastTool(ctx: Context): String = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("tool", "") ?: ""
    fun lastPage(ctx: Context): String = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("page", "home") ?: "home"
    fun saveLocation(ctx: Context, tool: String, page: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("tool", tool).putString("page", page).apply()
    }
}

/** A ScrollState that starts at the remembered position for [key] and saves it as the user scrolls / leaves. */
@Composable
fun rememberPersistedScroll(key: String): ScrollState {
    val ctx = LocalContext.current
    val state = remember(key) { ScrollState(ScrollMemory.get(ctx, key)) }
    LaunchedEffect(state) {
        snapshotFlow { state.value }.collect { ScrollMemory.put(ctx, key, it) }
    }
    DisposableEffect(state) { onDispose { ScrollMemory.put(ctx, key, state.value) } }
    return state
}
