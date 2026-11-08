package com.hikari.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
    ),
)

fun typographyWith(family: FontFamily?): Typography {
    if (family == null) return Typography
    return Typography(
        displayLarge = Typography.displayLarge.copy(fontFamily = family),
        displayMedium = Typography.displayMedium.copy(fontFamily = family),
        displaySmall = Typography.displaySmall.copy(fontFamily = family),
        headlineLarge = Typography.headlineLarge.copy(fontFamily = family),
        headlineMedium = Typography.headlineMedium.copy(fontFamily = family),
        headlineSmall = Typography.headlineSmall.copy(fontFamily = family),
        titleLarge = Typography.titleLarge.copy(fontFamily = family),
        titleMedium = Typography.titleMedium.copy(fontFamily = family),
        titleSmall = Typography.titleSmall.copy(fontFamily = family),
        bodyLarge = Typography.bodyLarge.copy(fontFamily = family),
        bodyMedium = Typography.bodyMedium.copy(fontFamily = family),
        bodySmall = Typography.bodySmall.copy(fontFamily = family),
        labelLarge = Typography.labelLarge.copy(fontFamily = family),
        labelMedium = Typography.labelMedium.copy(fontFamily = family),
        labelSmall = Typography.labelSmall.copy(fontFamily = family),
    )
}
