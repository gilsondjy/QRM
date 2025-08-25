package com.tuduticket.qrm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = TuduGreen,
    onPrimary = TuduWhite,
    secondary = TuduDarkBlue,
    background = TuduLightGray,
    surface = TuduWhite,
    onBackground = TuduDarkBlue,
    onSurface = TuduDarkBlue
)

private val DarkColors = darkColorScheme(
    primary = TuduGreen,
    onPrimary = TuduWhite,
    secondary = TuduDarkBlue,
    background = TuduDarkBlue,
    surface = TuduDarkBlue,
    onBackground = TuduLightGray,
    onSurface = TuduLightGray
)

@Composable
fun QRMTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = Shapes,
        content = content
    )
}