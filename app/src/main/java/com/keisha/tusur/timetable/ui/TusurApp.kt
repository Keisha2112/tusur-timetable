package com.keisha.tusur.timetable.ui

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.keisha.tusur.timetable.AppContainer
import com.keisha.tusur.timetable.data.notification.ScheduleNoticeDestination
import com.keisha.tusur.timetable.domain.model.SchedulePreference
import com.keisha.tusur.timetable.domain.model.ScheduleSource
import com.keisha.tusur.timetable.ui.components.LoadingSpinner
import com.keisha.tusur.timetable.ui.onboarding.FacultyPickerScreen
import com.keisha.tusur.timetable.ui.onboarding.GroupPickerScreen
import com.keisha.tusur.timetable.ui.schedule.ScheduleScreen
import com.keisha.tusur.timetable.ui.schedule.NowScreen
import com.keisha.tusur.timetable.ui.schedule.RoomScheduleScreen
import com.keisha.tusur.timetable.ui.schedule.TeacherScheduleScreen
import com.keisha.tusur.timetable.ui.settings.SettingsScreen
import com.keisha.tusur.timetable.ui.theme.LocalAppMotion
import com.keisha.tusur.timetable.ui.update.UpdateAvailableDialog
import com.keisha.tusur.timetable.ui.update.UpdatePermissionDialog
import com.keisha.tusur.timetable.ui.update.UpdateViewModel
import kotlinx.coroutines.flow.first
import java.time.LocalDate

private object Routes {
    const val ONBOARDING_FACULTY = "onboarding/faculty"
    const val ONBOARDING_GROUP = "onboarding/group/{facultyCode}/{facultyName}"
    const val SCHEDULE = "schedule"
    const val NOTICE_GROUP = "notification/schedule"
    const val NOW = "now"
    const val NOW_GROUP = "now/group"
    const val SETTINGS = "settings"
    const val TEACHER = "teacher/{teacherName}"

    fun group(facultyCode: String, facultyName: String): String =
        "onboarding/group/${Uri.encode(facultyCode)}/${Uri.encode(facultyName)}"

    fun teacher(teacherName: String): String =
        "teacher/${Uri.encode(teacherName)}"
}

private const val NOTICE_DATE = "schedule_notice_date"

/**
 * Корневая навигация. Стартовый экран зависит от сохранённого выбора группы,
 * поэтому сначала дожидаемся первого значения из DataStore.
 */
@Composable
fun TusurApp(
    container: AppContainer,
    noticeDestination: ScheduleNoticeDestination? = null,
    onNoticeHandled: () -> Unit = {},
) {
    var startDestination by remember { mutableStateOf<String?>(null) }
    val schedulePreference by container.settingsRepository.schedulePreference
        .collectAsStateWithLifecycle<SchedulePreference?>(initialValue = null)

    LaunchedEffect(Unit) {
        val selection = container.settingsRepository.selection.first()
        val source = container.settingsRepository.schedulePreference.first().source
        startDestination = if (selection != null || source == ScheduleSource.ROOM) {
            Routes.SCHEDULE
        } else {
            Routes.ONBOARDING_FACULTY
        }
    }

    val destination = startDestination
    if (destination == null || schedulePreference == null) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingSpinner()
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
    val motionOff = LocalAppMotion.current.disabled

    NavHost(
        navController = navController,
        startDestination = destination,
        enterTransition = { if (motionOff) EnterTransition.None else fadeIn(tween(700)) },
        exitTransition = { if (motionOff) ExitTransition.None else fadeOut(tween(700)) },
    ) {
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

        composable(Routes.SCHEDULE) { entry ->
            val room = schedulePreference?.takeIf { it.source == ScheduleSource.ROOM }?.auditorium
            if (room != null) {
                key(room) {
                    RoomScheduleScreen(
                        container = container,
                        room = room,
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onOpenTeacher = { navController.navigate(Routes.teacher(it)) },
                        onOpenNow = { navController.navigate(Routes.NOW) },
                    )
                }
            } else {
                GroupScheduleRoute(container, navController, entry)
            }
        }

        composable(Routes.NOTICE_GROUP) { entry ->
            GroupScheduleRoute(container, navController, entry, openedFromNotice = true)
        }

        composable(Routes.NOW) {
            val room = schedulePreference?.takeIf { it.source == ScheduleSource.ROOM }?.auditorium
            NowScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onOpenTeacher = { teacherName -> navController.navigate(Routes.teacher(teacherName)) },
                auditorium = room,
            )
        }

        composable(Routes.NOW_GROUP) {
            NowScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onOpenTeacher = { teacherName -> navController.navigate(Routes.teacher(teacherName)) },
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
                onSourceChanged = { navController.popBackStack(Routes.SCHEDULE, inclusive = false) },
            )
        }
    }

    LaunchedEffect(noticeDestination, navController) {
        val requested = noticeDestination ?: return@LaunchedEffect
        val selection = container.settingsRepository.selection.first()
        if (selection?.facultyCode == requested.facultyCode && selection.groupSlug == requested.groupSlug) {
            val source = container.settingsRepository.schedulePreference.first().source
            val target = if (source == ScheduleSource.ROOM) Routes.NOTICE_GROUP else Routes.SCHEDULE
            if (navController.currentDestination?.route != target) {
                if (navController.currentDestination?.route != Routes.SCHEDULE &&
                    !navController.popBackStack(Routes.SCHEDULE, inclusive = false)
                ) {
                    navController.navigate(Routes.SCHEDULE) { launchSingleTop = true }
                }
                if (target == Routes.NOTICE_GROUP) navController.navigate(target)
            }
            navController.currentBackStackEntry?.savedStateHandle?.set(NOTICE_DATE, requested.date.toString())
        }
        onNoticeHandled()
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

@Composable
private fun GroupScheduleRoute(
    container: AppContainer,
    navController: NavHostController,
    entry: NavBackStackEntry,
    openedFromNotice: Boolean = false,
) {
    val target by entry.savedStateHandle.getStateFlow<String?>(NOTICE_DATE, null)
        .collectAsStateWithLifecycle()
    ScheduleScreen(
        container = container,
        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
        onOpenTeacher = { navController.navigate(Routes.teacher(it)) },
        onOpenNow = { navController.navigate(if (openedFromNotice) Routes.NOW_GROUP else Routes.NOW) },
        requestedDate = target?.let(LocalDate::parse),
        onDateHandled = { entry.savedStateHandle[NOTICE_DATE] = null as String? },
        onBack = if (openedFromNotice) ({ navController.popBackStack() }) else null,
    )
}
