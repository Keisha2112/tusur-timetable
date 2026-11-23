package com.keisha.tusur.timetable

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keisha.tusur.timetable.domain.model.ThemeMode
import com.keisha.tusur.timetable.domain.model.Appearance
import com.keisha.tusur.timetable.data.notification.ScheduleNoticeDestination
import com.keisha.tusur.timetable.ui.TusurApp
import com.keisha.tusur.timetable.ui.theme.TusurTimetableTheme
import com.keisha.tusur.timetable.ui.theme.AppMotion
import com.keisha.tusur.timetable.ui.theme.AppMotionDurationScale
import com.keisha.tusur.timetable.ui.theme.AppMotionProvider
import com.keisha.tusur.timetable.ui.theme.animatorScale
import com.keisha.tusur.timetable.ui.theme.observeAnimatorScale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val noticeDestination = MutableStateFlow<ScheduleNoticeDestination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as App).container
        noticeDestination.value = ScheduleNoticeDestination.from(intent)

        val initialScale = animatorScale(contentResolver)
        val duration = AppMotionDurationScale(initialScale)
        val host = ComposeView(this)
        host.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        host.setParentCompositionContext(host.createLifecycleAwareWindowRecomposer(duration, lifecycle))
        host.setContent {
            val appearance by container.settingsRepository.appearance.collectAsStateWithLifecycle(initialValue = Appearance())
            val systemScale by remember { observeAnimatorScale(contentResolver) }
                .collectAsStateWithLifecycle(initialValue = initialScale)
            val destination by noticeDestination.collectAsStateWithLifecycle()
            val motion = AppMotion(appearance.animationMode, appearance.animationMode.scale * systemScale)

            val darkTheme = when (appearance.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // Иконки системных панелей подстраиваем под выбранную тему, а не под тему системы.
            SideEffect {
                duration.scaleFactor = motion.scale
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }

            AppMotionProvider(motion) {
                TusurTimetableTheme(darkTheme = darkTheme, palette = appearance.palette) {
                    TusurApp(container, destination) {
                        noticeDestination.value = null
                        setIntent(Intent(this, MainActivity::class.java))
                    }
                }
            }
        }
        setContentView(host)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        noticeDestination.value = ScheduleNoticeDestination.from(intent)
    }

    override fun onResume() {
        super.onResume()
        val container = (application as App).container
        container.applicationScope.launch { container.scheduleNotifications.reconcile() }
    }
}
