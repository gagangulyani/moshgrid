package com.bitchat.android.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

// Standard UI semantics live in Material so stock components and custom Bitchat composables
// share one source of truth. LocalBitchatPalette below only supplies app-specific extra colors.
// MoshGrid palette (design canvas "Signal direction, rave colours"): acid #C6FF3D, cyan #3DF2FF, red #E8222B.
internal val DarkBitchatColorScheme = darkColorScheme(
    primary = Color(0xFFC6FF3D),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF2C3A0E),
    onPrimaryContainer = Color(0xFFE6FFB0),
    secondary = Color(0xFF3DF2FF),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF0B3A3D),
    onSecondaryContainer = Color(0xFFB8FBFF),
    tertiary = DarkBitchatPalette.accentOrange,
    onTertiary = Color.Black,
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFF9E9E9E),
    outline = Color(0xFF262626),
    outlineVariant = Color(0xFF1A1A1A),
    error = Color(0xFFE8222B),
    onError = Color.Black
)

// MoshGrid light palette: darker lime/cyan so text stays readable on white.
internal val LightBitchatColorScheme = lightColorScheme(
    primary = Color(0xFF4D7A00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6FFB0),
    onPrimaryContainer = Color(0xFF1C2A00),
    secondary = Color(0xFF007C85),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8F8FC),
    onSecondaryContainer = Color(0xFF00363A),
    tertiary = LightBitchatPalette.accentOrange,
    onTertiary = Color.Black,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFF4F4F4),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEAEAEA),
    onSurfaceVariant = Color(0xFF555555),
    outline = Color(0xFFD0D0D0),
    outlineVariant = Color(0xFFE2E2E2),
    error = Color(0xFFE8222B),
    onError = Color.White
)

@Composable
fun BitchatTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    // App-level override from ThemePreferenceManager
    val themePref by ThemePreferenceManager.themeFlow.collectAsState(initial = ThemePreference.System)
    val shouldUseDark = when (darkTheme) {
        true -> true
        false -> false
        null -> when (themePref) {
            ThemePreference.Dark -> true
            ThemePreference.Light -> false
            ThemePreference.System -> isSystemInDarkTheme()
        }
    }

    val colorScheme = if (shouldUseDark) DarkBitchatColorScheme else LightBitchatColorScheme
    val palette = if (shouldUseDark) DarkBitchatPalette else LightBitchatPalette

    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    if (!shouldUseDark) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (!shouldUseDark) {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else 0
            }
            window.navigationBarColor = colorScheme.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    CompositionLocalProvider(LocalBitchatPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
