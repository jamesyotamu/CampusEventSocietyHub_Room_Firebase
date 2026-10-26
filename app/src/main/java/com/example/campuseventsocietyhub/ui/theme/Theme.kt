package com.example.campuseventsocietyhub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val CampusColors = lightColorScheme(
    primary = NavyBlue,
    onPrimary = White,
    secondary = Gold,
    onSecondary = NavyBlue,
    tertiary = RoyalBlue,
    background = LightBackground,
    onBackground = DarkText,
    surface = White,
    onSurface = DarkText,
    error = ErrorRed
)

@Composable
fun CampusEventSocietyHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CampusColors, content = content)
}
