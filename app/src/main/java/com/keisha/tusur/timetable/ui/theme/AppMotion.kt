package com.keisha.tusur.timetable.ui.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.domain.model.AnimationMode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class AppMotion(val mode: AnimationMode, val scale: Float) {
    val disabled: Boolean get() = scale == 0f
}

internal val LocalAppMotion = staticCompositionLocalOf { AppMotion(AnimationMode.NORMAL, 1f) }

internal class AppMotionDurationScale(initial: Float) : MotionDurationScale {
    private val scale = mutableFloatStateOf(initial)
    override var scaleFactor: Float
        get() = scale.floatValue
        set(value) { scale.floatValue = value }
}

internal fun animatorScale(resolver: ContentResolver): Float =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        .takeIf { it.isFinite() && it >= 0f } ?: 1f

internal fun observeAnimatorScale(resolver: ContentResolver) = callbackFlow {
    val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            trySend(animatorScale(resolver))
        }
    }
    resolver.registerContentObserver(uri, false, observer)
    trySend(animatorScale(resolver))
    awaitClose { resolver.unregisterContentObserver(observer) }
}.distinctUntilChanged()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppMotionProvider(motion: AppMotion, content: @Composable () -> Unit) {
    val ripple = if (motion.disabled) null else LocalRippleConfiguration.current
    CompositionLocalProvider(LocalAppMotion provides motion, LocalRippleConfiguration provides ripple, content = content)
}

@Composable
internal fun ApplyDialogMotion() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    val normal = remember(window) { window.attributes.windowAnimations }
    val motion = LocalAppMotion.current
    val style = when {
        motion.disabled -> 0
        motion.mode == AnimationMode.FAST && normal != 0 -> R.style.TusurDialogFastAnimations
        else -> normal
    }
    SideEffect {
        if (window.attributes.windowAnimations != style) window.setWindowAnimations(style)
    }
}
