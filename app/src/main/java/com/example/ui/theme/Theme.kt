package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = NeonFelt,
    secondary = BallGold,
    tertiary = ChalkBlue,
    background = VelvetBlack,
    surface = PanelDark,
    onPrimary = VelvetBlack,
    onSecondary = VelvetBlack,
    onTertiary = WhiteBall,
    onBackground = WhiteBall,
    onSurface = WhiteBall
)

private val LightColorScheme = lightColorScheme(
    primary = FeltGreen,
    secondary = BallGold,
    tertiary = ChalkBlue,
    background = WhiteBall,
    surface = PanelDark,
    onPrimary = WhiteBall,
    onSecondary = VelvetBlack,
    onTertiary = WhiteBall,
    onBackground = VelvetBlack,
    onSurface = WhiteBall
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Set to false to enforce our gorgeous brand theme consistency
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else DarkColorScheme // Always use dark theme for maximum billiard lounge immersion!

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
