package com.opnavigation.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF0B57D0)
private val BlueDark = Color(0xFF8AB4F8)

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color(0xFF062033),
    primaryContainer = Color(0xFF1B3A57),
    onPrimaryContainer = Color(0xFFD6E6FF),
)

@Composable
fun OpNavTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
