package com.keisha.tusur.timetable.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Base = Typography()

// Системный шрифт поддерживает кириллицу и системное масштабирование текста.
val TusurTypography = Typography(
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    bodyLarge = Base.bodyLarge.copy(letterSpacing = 0.sp),
    bodyMedium = Base.bodyMedium.copy(letterSpacing = 0.sp),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    labelMedium = Base.labelMedium.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
)
