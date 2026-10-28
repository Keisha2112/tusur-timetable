package com.keisha.tusur.timetable.ui.schedule

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Свайп влево/вправо для перелистывания недель. Вертикальная прокрутка списка
 * при этом не затрагивается: жест срабатывает только после горизонтального порога.
 */
internal fun Modifier.weekSwipe(
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Modifier = composed {
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    pointerInput(canGoPrevious, canGoNext) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onHorizontalDrag = { change, dragAmount ->
                total += dragAmount
                change.consume()
            },
            onDragEnd = {
                when {
                    total <= -threshold && canGoNext -> onNext()
                    total >= threshold && canGoPrevious -> onPrevious()
                }
                total = 0f
            },
            onDragCancel = { total = 0f },
        )
    }
}

private val SWIPE_THRESHOLD = 64.dp
