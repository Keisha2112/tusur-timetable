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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.keisha.tusur.timetable.AppContainer
import com.keisha.tusur.timetable.ui.onboarding.FacultyPickerScreen
import com.keisha.tusur.timetable.ui.onboarding.GroupPickerScreen
import com.keisha.tusur.timetable.ui.schedule.ScheduleScreen
import com.keisha.tusur.timetable.ui.schedule.TeacherScheduleScreen
import com.keisha.tusur.timetable.ui.settings.SettingsScreen
import com.keisha.tusur.timetable.ui.update.UpdateAvailableDialog
import com.keisha.tusur.timetable.ui.update.UpdatePermissionDialog
import com.keisha.tusur.timetable.ui.update.UpdateViewModel
import kotlinx.coroutines.flow.first

private object Routes {
    const val ONBOARDING_FACULTY = "onboarding/faculty"
    const val ONBOARDING_GROUP = "onboarding/group/{facultyCode}/{facultyName}"
    const val SCHEDULE = "schedule"
    const val SETTINGS = "settings"
    const val TEACHER = "teacher/{teacherName}"

    fun group(facultyCode: String, facultyName: String): String =
        "onboarding/group/${Uri.encode(facultyCode)}/${Uri.encode(facultyName)}"

    fun teacher(teacherName: String): String =
        "teacher/${Uri.encode(teacherName)}"
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

    val updateViewModel: UpdateViewModel = viewModel(
        key = "update",
        factory = viewModelFactory {
            initializer { UpdateViewModel(container.updateRepository) }
        },
    )
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()

    // Проверяем обновления на старте и при каждом возвращении в приложение.
    LaunchedEffect(Unit) { updateViewModel.checkAutomatically() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updateViewModel.checkAutomatically() }

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
                onOpenTeacher = { teacherName ->
                    navController.navigate(Routes.teacher(teacherName))
                },
            )
        }

        composable(
            route = Routes.TEACHER,
            arguments = listOf(navArgument("teacherName") { type = NavType.StringType }),
        ) { entry ->
            TeacherScheduleScreen(
                container = container,
                teacherName = entry.arguments?.getString("teacherName").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                container = container,
                updateViewModel = updateViewModel,
                onBack = { navController.popBackStack() },
                onChangeGroup = { navController.navigate(Routes.ONBOARDING_FACULTY) },
            )
        }
    }

    val availableUpdate = updateState.available
    if (updateState.showDialog && availableUpdate != null) {
        UpdateAvailableDialog(
            state = updateState,
            onUpdate = updateViewModel::downloadAndInstall,
            onLater = updateViewModel::dismissDialog,
            onSkip = updateViewModel::skipVersion,
        )
    }
    if (updateState.showPermissionDialog) {
        UpdatePermissionDialog(
            onOpenSettings = updateViewModel::openInstallPermissionSettings,
            onDismiss = updateViewModel::dismissPermissionDialog,
        )
    }
}
