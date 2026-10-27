package com.keisha.tusur.timetable.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.keisha.tusur.timetable.AppContainer
import com.keisha.tusur.timetable.ui.onboarding.FacultyPickerScreen
import com.keisha.tusur.timetable.ui.onboarding.GroupPickerScreen
import com.keisha.tusur.timetable.ui.schedule.ScheduleScreen
import com.keisha.tusur.timetable.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.first

private object Routes {
    const val ONBOARDING_FACULTY = "onboarding/faculty"
    const val ONBOARDING_GROUP = "onboarding/group/{facultyCode}/{facultyName}"
    const val SCHEDULE = "schedule"
    const val SETTINGS = "settings"

    fun group(facultyCode: String, facultyName: String): String =
        "onboarding/group/${Uri.encode(facultyCode)}/${Uri.encode(facultyName)}"
}

/**
 * Корневая навигация. Стартовый экран зависит от сохранённого выбора группы,
 * поэтому сначала дожидаемся первого значения из DataStore.
 */
@Composable
fun TusurApp(container: AppContainer) {
    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val selection = container.settingsRepository.selection.first()
        startDestination = if (selection == null) Routes.ONBOARDING_FACULTY else Routes.SCHEDULE
    }

    val destination = startDestination
    if (destination == null) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = destination) {
        composable(Routes.ONBOARDING_FACULTY) {
            FacultyPickerScreen(
                container = container,
                onFacultyChosen = { faculty ->
                    navController.navigate(Routes.group(faculty.code, faculty.name))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.ONBOARDING_GROUP,
            arguments = listOf(
                navArgument("facultyCode") { type = NavType.StringType },
                navArgument("facultyName") { type = NavType.StringType },
            ),
        ) { entry ->
            val facultyCode = entry.arguments?.getString("facultyCode").orEmpty()
            val facultyName = entry.arguments?.getString("facultyName").orEmpty()
            GroupPickerScreen(
                container = container,
                facultyCode = facultyCode,
                facultyName = facultyName,
                onGroupChosen = {
                    navController.navigate(Routes.SCHEDULE) {
                        popUpTo(Routes.SCHEDULE) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SCHEDULE) {
            ScheduleScreen(
                container = container,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onChangeGroup = { navController.navigate(Routes.ONBOARDING_FACULTY) },
            )
        }
    }
}
