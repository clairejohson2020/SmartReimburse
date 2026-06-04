package com.smartreimburse.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val SmartColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = TechBlack,
    secondary = AccentTeal,
    onSecondary = TechBlack,
    background = TechBlack,
    onBackground = TextBright,
    surface = CardDark,
    onSurface = TextBright,
    surfaceVariant = CardDarkAlt,
    onSurfaceVariant = TextMuted,
    error = WarningRed
)

@Composable
fun SmartReimburseTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = TechBlack.toArgb()
            window.navigationBarColor = TechBlack.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = SmartColorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
