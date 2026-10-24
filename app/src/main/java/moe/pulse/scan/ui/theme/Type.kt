package moe.pulse.scan.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Default = Typography()

/**
 * System sans throughout; brand accents (badges, watermark) may use mono.
 */
val PulseTypography = Typography(
    displayMedium = Default.displayMedium.copy(fontWeight = FontWeight.Bold),
    headlineMedium = Default.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = Default.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Default.labelLarge.copy(letterSpacing = 0.4.sp),
    labelMedium = Default.labelMedium.copy(letterSpacing = 0.5.sp),
)

/** Small utility style for brand badges / status readouts. */
val BadgeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 11.sp,
    letterSpacing = 1.2.sp,
)
