package com.mikhail.authenticator.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3D5AFE),
    onPrimary = Color.White,
    secondary = Color(0xFF00897B),
    background = Color(0xFFF6F7FB),
    surface = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8C9EFF),
    onPrimary = Color(0xFF0B1020),
    secondary = Color(0xFF4DB6AC),
    background = Color(0xFF0E1116),
    surface = Color(0xFF161A21),
)

@Composable
fun TwoFactorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
