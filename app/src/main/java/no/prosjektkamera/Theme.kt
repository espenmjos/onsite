package no.prosjektkamera

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Onsite brand: navy + safety yellow, taken from the launcher icon.
val Navy = Color(0xFF13293D)
val SafetyYellow = Color(0xFFFFC400)

val OnsiteLight = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = SafetyYellow,
    onPrimaryContainer = Navy,
    secondary = Color(0xFFC99A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE9A6),
    onSecondaryContainer = Navy,
    tertiary = Color(0xFF2F5B82),
    tertiaryContainer = Color(0xFFD5E4F3),
    onTertiaryContainer = Navy,
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF14202B),
    surface = Color(0xFFF6F7F9),
    onSurface = Color(0xFF14202B),
    surfaceVariant = Color(0xFFE1E6EC),
    onSurfaceVariant = Color(0xFF4A5866),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F2F5),
    surfaceContainer = Color(0xFFEAEDF1),
    surfaceContainerHigh = Color(0xFFE4E8ED),
    surfaceContainerHighest = Color(0xFFDDE2E8),
    outline = Color(0xFF7A8794),
)

val OnsiteDark = darkColorScheme(
    primary = SafetyYellow,
    onPrimary = Navy,
    primaryContainer = SafetyYellow,
    onPrimaryContainer = Navy,
    secondary = SafetyYellow,
    onSecondary = Navy,
    secondaryContainer = Color(0xFF3A3420),
    onSecondaryContainer = Color(0xFFFFE08A),
    tertiary = Color(0xFF9CC3E6),
    tertiaryContainer = Color(0xFF223A52),
    onTertiaryContainer = Color(0xFFD5E4F3),
    background = Color(0xFF0D1823),
    onBackground = Color(0xFFE3E8ED),
    surface = Color(0xFF0D1823),
    onSurface = Color(0xFFE3E8ED),
    surfaceVariant = Color(0xFF243546),
    onSurfaceVariant = Color(0xFFB5C1CD),
    surfaceContainerLowest = Color(0xFF09121B),
    surfaceContainerLow = Color(0xFF122030),
    surfaceContainer = Color(0xFF162636),
    surfaceContainerHigh = Color(0xFF1D2E40),
    surfaceContainerHighest = Color(0xFF26394C),
    outline = Color(0xFF5E7084),
)
