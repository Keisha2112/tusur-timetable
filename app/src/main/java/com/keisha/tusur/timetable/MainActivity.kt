package com.keisha.tusur.timetable

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keisha.tusur.timetable.domain.model.ThemeMode
import com.keisha.tusur.timetable.data.notification.ScheduleNoticeDestination
import com.keisha.tusur.timetable.ui.TusurApp
import com.keisha.tusur.timetable.ui.theme.TusurTimetableTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val noticeDestination = MutableStateFlow<ScheduleNoticeDestination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as App).container
        noticeDestination.value = ScheduleNoticeDestination.from(intent)

        setContent {
            // Тема живёт на уровне всего приложения: смена настройки сразу перекрашивает экраны.
            val themeMode by container.settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val destination by noticeDestination.collectAsStateWithLifecycle()

            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // Иконки системных панелей подстраиваем под выбранную тему, а не под тему системы.
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }

            TusurTimetableTheme(darkTheme = darkTheme) {
                TusurApp(container, destination) {
                    noticeDestination.value = null
                    setIntent(Intent(this, MainActivity::class.java))
                }
            }
        }
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
