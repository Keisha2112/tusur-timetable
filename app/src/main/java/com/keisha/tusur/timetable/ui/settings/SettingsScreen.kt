@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.keisha.tusur.timetable.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.keisha.tusur.timetable.AppContainer
import com.keisha.tusur.timetable.R
import com.keisha.tusur.timetable.data.repository.UserSelection
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.model.ThemeMode
import com.keisha.tusur.timetable.domain.time.ScheduleTime
import com.keisha.tusur.timetable.ui.update.UpdateEvent
import com.keisha.tusur.timetable.ui.update.UpdateUiState
import com.keisha.tusur.timetable.ui.update.UpdateViewModel
import com.keisha.tusur.timetable.ui.update.messageRes
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val SOURCE_URL = "https://timetable.tusur.ru"

@Composable
fun SettingsScreen(
    container: AppContainer,
    updateViewModel: UpdateViewModel,
    onBack: () -> Unit,
    onChangeGroup: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(
        key = "settings",
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    catalogRepository = container.catalogRepository,
                    scheduleRepository = container.scheduleRepository,
                    teacherScheduleRepository = container.teacherScheduleRepository,
                    settingsRepository = container.settingsRepository,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val canNotify by container.scheduleNotifications.deliveryAvailable.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val linkErrorText = stringResource(R.string.settings_link_error)
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    var notificationPermissionDenied by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationPermissionDenied = !granted
        if (granted) viewModel.setNotificationsEnabled(true)
        scope.launch { container.scheduleNotifications.reconcile() }
    }

    BackHandler(onBack = onBack)

    // Расписание могло обновиться, пока экран был закрыт.
    LaunchedEffect(viewModel) {
        viewModel.refreshLastUpdated()
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                SettingsEvent.CacheCleared -> context.getString(R.string.settings_clear_cache_done)
                is SettingsEvent.CacheClearFailed -> context.getString(event.error.messageRes())
                SettingsEvent.NotificationSettingFailed -> context.getString(R.string.schedule_notifications_save_failed)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    val updateUpToDateText = stringResource(R.string.settings_up_to_date)
    val installStartedText = stringResource(R.string.update_install_started)
    LaunchedEffect(updateViewModel) {
        updateViewModel.events.collect { event ->
            // PermissionRequired показывает отдельный диалог, снекбар тут лишний.
            val message = when (event) {
                UpdateEvent.UpToDate -> updateUpToDateText
                UpdateEvent.InstallStarted -> installStartedText
                UpdateEvent.PermissionRequired -> null
                is UpdateEvent.Failed -> context.getString(event.error.messageRes())
            }
            if (message != null) {
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    val openSource: () -> Unit = {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar(linkErrorText) }
        }
    }

    Scaffold(
        topBar = { SettingsTopBar(onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CurrentGroupCard(
                    selection = state.selection,
                    isLoading = state.isLoading,
                    onChangeGroup = onChangeGroup,
                )
            }
            item {
                AppearanceCard(
                    themeMode = state.themeMode,
                    onThemeModeChange = viewModel::setThemeMode,
                )
            }
            item {
                NotificationsCard(
                    enabled = state.notificationsEnabled,
                    blocked = !canNotify && (state.notificationsEnabled || notificationPermissionDenied),
                    onToggle = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            notificationPermissionDenied = false
                            viewModel.setNotificationsEnabled(enabled)
                        }
                    },
                    onOpenSystemSettings = {
                        runCatching { context.startActivity(container.scheduleNotifications.settingsIntent()) }
                    },
                )
            }
            item {
                DataCard(
                    lastUpdated = state.lastUpdated,
                    isClearingCache = state.isClearingCache,
                    onOpenSource = openSource,
                    onClearCache = { showClearDialog = true },
                )
            }
            item {
                UpdateCard(
                    state = updateState,
                    onCheck = updateViewModel::checkManually,
                    onUpdate = updateViewModel::downloadAndInstall,
                )
            }
            item {
                AboutCard()
            }
        }
    }

    if (showClearDialog) {
        ClearCacheDialog(
            onConfirm = {
                showClearDialog = false
                viewModel.clearCache()
            },
            onDismiss = { showClearDialog = false },
        )
    }
}

@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
private fun CurrentGroupCard(
    selection: UserSelection?,
    isLoading: Boolean,
    onChangeGroup: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_current_group),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            when {
                isLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.loading),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                selection == null -> Text(
                    text = stringResource(R.string.settings_no_group),
                    style = MaterialTheme.typography.titleLarge,
                )
                else -> {
                    Text(
                        text = selection.groupName,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = selection.facultyName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onChangeGroup,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.SwapHoriz,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_change_group))
            }
        }
    }
}

@Composable
private fun AppearanceCard(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    SettingsCard(title = stringResource(R.string.settings_appearance_title)) {
        Column(
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ThemeModeRow(
                label = stringResource(R.string.settings_theme_system),
                icon = Icons.Outlined.SettingsBrightness,
                selected = themeMode == ThemeMode.SYSTEM,
                onClick = { onThemeModeChange(ThemeMode.SYSTEM) },
            )
            ThemeModeRow(
                label = stringResource(R.string.settings_theme_light),
                icon = Icons.Outlined.LightMode,
                selected = themeMode == ThemeMode.LIGHT,
                onClick = { onThemeModeChange(ThemeMode.LIGHT) },
            )
            ThemeModeRow(
                label = stringResource(R.string.settings_theme_dark),
                icon = Icons.Outlined.DarkMode,
                selected = themeMode == ThemeMode.DARK,
                onClick = { onThemeModeChange(ThemeMode.DARK) },
            )
        }
    }
}

@Composable
private fun ThemeModeRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) colors.primaryContainer else colors.surfaceContainerLow,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (selected) colors.primary else colors.onSurfaceVariant,
            )
            Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
private fun NotificationsCard(
    enabled: Boolean,
    blocked: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    val title = stringResource(R.string.schedule_notifications_title)
    SettingsCard(title = title) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle)
                .semantics { contentDescription = title }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (blocked) R.string.schedule_notifications_blocked else R.string.schedule_notifications_summary,
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(checked = enabled, onCheckedChange = null)
        }
        if (blocked) {
            TextButton(onClick = onOpenSystemSettings, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.schedule_notifications_open_settings))
            }
        }
    }
}

@Composable
private fun DataCard(
    lastUpdated: Instant?,
    isClearingCache: Boolean,
    onOpenSource: () -> Unit,
    onClearCache: () -> Unit,
) {
    SettingsCard(title = stringResource(R.string.settings_data_title)) {
        SourceRow(onClick = onOpenSource)
        RowDivider()
        ClearCacheRow(
            isClearingCache = isClearingCache,
            onClick = onClearCache,
        )
        RowDivider()
        LastUpdatedRow(lastUpdated = lastUpdated)
    }
}

@Composable
private fun UpdateCard(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
) {
    SettingsCard(title = stringResource(R.string.settings_updates_title)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_version, state.currentVersion),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!state.isSupported) {
                Text(
                    text = stringResource(R.string.settings_update_debug),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            val update = state.available
            if (update != null) {
                Text(
                    text = stringResource(R.string.settings_update_available, update.versionName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val notes = update.notes
                if (!notes.isNullOrBlank()) {
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.isDownloading) {
                    val fraction = state.downloadFraction ?: 0f
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_update_downloading,
                            (fraction * 100).roundToInt(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.error?.let { error ->
                    Text(
                        text = stringResource(error.messageRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                FilledTonalButton(
                    onClick = onUpdate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    enabled = !state.isDownloading && !state.isChecking,
                ) {
                    Text(stringResource(R.string.settings_update_download))
                }
                TextButton(
                    onClick = onCheck,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isChecking && !state.isDownloading,
                ) {
                    Text(stringResource(R.string.settings_check_again))
                }
            } else {
                val error = state.error
                when {
                    state.isChecking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.settings_update_checking),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    error != null -> Text(
                        text = stringResource(error.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.hasChecked -> Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.settings_up_to_date),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = onCheck,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.medium,
                    enabled = !state.isChecking,
                ) {
                    Text(stringResource(R.string.settings_check_updates))
                }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    SettingsCard(title = stringResource(R.string.settings_about)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsCard(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)
                        .semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            content()
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SourceRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_data_source),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.settings_data_source_url),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = stringResource(R.string.cd_open_source),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClearCacheRow(
    isClearingCache: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isClearingCache, role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.DeleteSweep,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_clear_cache),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (isClearingCache) {
                Text(
                    text = stringResource(R.string.settings_cache_clearing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isClearingCache) {
            val description = stringResource(R.string.settings_cache_clearing)
            CircularProgressIndicator(
                modifier = Modifier
                    .size(20.dp)
                    .semantics { contentDescription = description },
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun LastUpdatedRow(lastUpdated: Instant?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Update,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (lastUpdated == null) {
                stringResource(R.string.settings_never_updated)
            } else {
                stringResource(
                    R.string.settings_last_update,
                    ScheduleTime.formatUpdatedAt(lastUpdated),
                )
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClearCacheDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = {
            Icon(
                imageVector = Icons.Filled.DeleteSweep,
                contentDescription = null,
            )
        },
        title = { Text(stringResource(R.string.settings_clear_cache)) },
        text = { Text(stringResource(R.string.settings_clear_cache_confirm)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_clear_cache_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@StringRes
private fun AppError.messageRes(): Int = when (this) {
    is AppError.Network -> R.string.error_network
    is AppError.Server -> R.string.error_server
    is AppError.Parse -> R.string.error_parse
    else -> R.string.error_unknown
}
