package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = SlateNavy900,
    onPrimary = PureWhite,
    primaryContainer = Slate200,
    onPrimaryContainer = SlateNavy900,
    secondary = SafetyOrange600,
    onSecondary = PureWhite,
    secondaryContainer = SafetyOrange100,
    onSecondaryContainer = SafetyOrange950,
    tertiary = Emerald600,
    onTertiary = PureWhite,
    tertiaryContainer = Emerald100,
    onTertiaryContainer = Emerald950,
    error = Crimson600,
    onError = PureWhite,
    errorContainer = Crimson100,
    onErrorContainer = Crimson950,
    background = Slate50,
    onBackground = SlateNavy900,
    surface = PureWhite,
    onSurface = SlateNavy900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate600,
    outline = Slate300,
    outlineVariant = Slate200
)

private val DarkColorScheme = darkColorScheme(
    primary = SafetyOrange400,
    onPrimary = SlateNavy950,
    primaryContainer = SlateNavy800,
    onPrimaryContainer = Slate100,
    secondary = SafetyOrange500,
    onSecondary = SlateNavy950,
    secondaryContainer = SafetyOrange950,
    onSecondaryContainer = SafetyOrange100,
    tertiary = Emerald500,
    onTertiary = SlateNavy950,
    tertiaryContainer = Emerald950,
    onTertiaryContainer = Emerald100,
    error = Crimson500,
    onError = PureWhite,
    errorContainer = Crimson950,
    onErrorContainer = Crimson100,
    background = SlateNavy950,
    onBackground = Slate50,
    surface = SlateNavy900,
    onSurface = Slate50,
    surfaceVariant = SlateNavy800,
    onSurfaceVariant = Slate300,
    outline = Slate700,
    outlineVariant = SlateNavy800
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun GudangSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
