package com.keisha.tusur.timetable.domain.model

enum class ScheduleSource { GROUP, GROUP_FROM_ROOMS }

enum class AnimationMode(val scale: Float) {
    NORMAL(1f),
    OFF(0f),
    FAST(0.5f),
}

enum class ColorPalette { ORIGINAL, MARINE, TERRACOTTA, FOREST, PLUM, AMBER, GRAPHITE, ROSE }

data class Appearance(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val palette: ColorPalette = ColorPalette.ORIGINAL,
    val animationMode: AnimationMode = AnimationMode.NORMAL,
)
