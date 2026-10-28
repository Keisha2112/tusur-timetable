package com.keisha.tusur.timetable.ui.schedule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.keisha.tusur.timetable.domain.model.Week

/**
 * Плавно меняет содержимое недели: следующая въезжает справа, предыдущая — слева.
 * Панель недель остаётся на месте, чтобы стрелки и точки не прыгали.
 */
@Composable
internal fun WeekTransition(
    week: Week?,
    modifier: Modifier = Modifier,
    content: @Composable (Week?) -> Unit,
) {
    AnimatedContent(
        targetState = week,
        modifier = modifier,
        contentKey = { it?.id },
        transitionSpec = {
            val from = initialState
            val to = targetState
            if (from == null || to == null || from.id == to.id) {
                fadeIn(tween(180)) togetherWith fadeOut(tween(180))
            } else {
                val direction = if (to.startDate.isAfter(from.startDate)) 1 else -1
                slideInHorizontally(
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                    initialOffsetX = { width -> direction * width },
                ) togetherWith slideOutHorizontally(
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                    targetOffsetX = { width -> -direction * width },
                )
            }
        },
        label = "week-transition",
    ) { targetWeek ->
        content(targetWeek)
    }
}
