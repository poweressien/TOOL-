package com.toolkit.privacy

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF2DD4BF), onPrimary = Color(0xFF00201C),
            secondary = Color(0xFF60A5FA),
            background = Color(0xFF0B0F14), surface = Color(0xFF0B0F14),
            surfaceVariant = Color(0xFF161D26), onSurface = Color(0xFFE6EDF3),
            onSurfaceVariant = Color(0xFF9FB0C3)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0F766E), secondary = Color(0xFF2563EB),
            background = Color(0xFFF6F8FA), surface = Color(0xFFF6F8FA),
            surfaceVariant = Color(0xFFE6ECF2)
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
