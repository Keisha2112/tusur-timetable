package com.keisha.tusur.timetable.ui.update

import android.content.pm.PackageInstaller
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keisha.tusur.timetable.data.repository.UpdateRepository
import com.keisha.tusur.timetable.data.update.UpdateInstallEvents
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.update.AppUpdate
import com.keisha.tusur.timetable.domain.update.InstallResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UpdateError { NETWORK, SERVER, PARSE, VERIFICATION, INSTALL, UNKNOWN }

data class UpdateUiState(
    val currentVersion: String,
    val isSupported: Boolean,
    val available: AppUpdate? = null,
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadFraction: Float? = null,
    val error: UpdateError? = null,
    val hasChecked: Boolean = false,
    val showDialog: Boolean = false,
    val showPermissionDialog: Boolean = false,
)

sealed interface UpdateEvent {
    data object UpToDate : UpdateEvent
    data object InstallStarted : UpdateEvent
    data object PermissionRequired : UpdateEvent
    data class Failed(val error: UpdateError) : UpdateEvent
}

class UpdateViewModel(private val repository: UpdateRepository) : ViewModel() {

    private val _state = MutableStateFlow(
        UpdateUiState(
            currentVersion = repository.currentVersionName,
            isSupported = repository.isSupported,
        ),
    )
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    // CONFLATED: события установки с другого экрана не должны копиться
    // и всплывать снекбаром через день при открытии настроек.
    private val _events = Channel<UpdateEvent>(Channel.CONFLATED)
    val events: Flow<UpdateEvent> = _events.receiveAsFlow()

    private var checkJob: Job? = null

    init {
        // Пользователь мог отменить системную установку или она упала:
        // не оставляем экран в состоянии «устанавливается».
        viewModelScope.launch {
            UpdateInstallEvents.statuses.collect { status ->
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION,
                    PackageInstaller.STATUS_SUCCESS -> Unit
                    else -> {
                        _state.update {
                            it.copy(
                                isDownloading = false,
                                showDialog = false,
                                error = UpdateError.INSTALL,
                            )
                        }
                        _events.send(UpdateEvent.Failed(UpdateError.INSTALL))
                    }
                }
            }
        }
    }

    /**
     * Тихая проверка при запуске: ошибки не показываем, иначе офлайн превращается
     * в постоянный баннер. Время проверки всё равно записываем, чтобы не долбить сеть.
     */
    fun checkAutomatically() {
        if (!_state.value.isSupported) return
        if (checkJob?.isActive == true || _state.value.isDownloading) return
        checkJob = viewModelScope.launch {
            var attempted = false
            try {
                if (!repository.shouldAutoCheck()) return@launch
                _state.update { it.copy(isChecking = true, error = null) }
                attempted = true
                val update = repository.checkForUpdate()
                val skipped = update != null &&
                    repository.isVersionSkipped(update.versionName)
                _state.update {
                    it.copy(
                        available = update,
                        hasChecked = true,
                        error = null,
                        showDialog = update != null && !skipped,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Фоновая проверка не должна мешать работе с расписанием.
            } finally {
                _state.update { it.copy(isChecking = false) }
                if (attempted) {
                    markCheckedQuietly()
                }
            }
        }
    }

    /** Проверка по кнопке: результат и ошибки видны в настройках. */
    fun checkManually() {
        if (checkJob?.isActive == true || _state.value.isDownloading) return
        checkJob = viewModelScope.launch {
            _state.update { it.copy(isChecking = true, error = null, showDialog = false) }
            try {
                val update = repository.checkForUpdate()
                if (update != null) {
                    _state.update {
                        it.copy(
                            available = update,
                            hasChecked = true,
                            error = null,
                            showDialog = false,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            available = null,
                            hasChecked = true,
                            error = null,
                            showDialog = false,
                        )
                    }
                    _events.send(UpdateEvent.UpToDate)
                }
                markCheckedQuietly()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.toUpdateError()
                _state.update { it.copy(hasChecked = true, error = error) }
                _events.send(UpdateEvent.Failed(error))
            } finally {
                _state.update { it.copy(isChecking = false) }
            }
        }
    }

    fun downloadAndInstall() {
        val update = _state.value.available ?: return
        if (_state.value.isDownloading || checkJob?.isActive == true) return
        if (!repository.canInstallPackages()) {
            _state.update { it.copy(showPermissionDialog = true) }
            viewModelScope.launch { _events.send(UpdateEvent.PermissionRequired) }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(isDownloading = true, downloadFraction = 0f, error = null)
            }
            try {
                val apk = repository.download(update) { progress ->
                    _state.update { it.copy(downloadFraction = progress.fraction) }
                }
                val result = try {
                    repository.install(apk)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Сбой уже на этапе установки, а не загрузки файла.
                    val error = if (e is AppError) e.toUpdateError() else UpdateError.INSTALL
                    _state.update { it.copy(error = error) }
                    _events.send(UpdateEvent.Failed(error))
                    return@launch
                }
                when (result) {
                    InstallResult.Started -> {
                        _state.update { it.copy(showDialog = false) }
                        _events.send(UpdateEvent.InstallStarted)
                    }
                    InstallResult.PermissionRequired -> {
                        _state.update { it.copy(showPermissionDialog = true) }
                        _events.send(UpdateEvent.PermissionRequired)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.toUpdateError()
                _state.update { it.copy(error = error) }
                _events.send(UpdateEvent.Failed(error))
            } finally {
                _state.update { it.copy(isDownloading = false) }
            }
        }
    }

    fun skipVersion() {
        val update = _state.value.available ?: return
        viewModelScope.launch {
            try {
                repository.skipVersion(update.versionName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Не записали — версия просто предложится снова.
            }
            _state.update { it.copy(showDialog = false, error = null) }
        }
    }

    fun dismissDialog() {
        _state.update { it.copy(showDialog = false, error = null) }
    }

    fun openInstallPermissionSettings() {
        repository.openInstallPermissionSettings()
        _state.update { it.copy(showPermissionDialog = false) }
    }

    fun dismissPermissionDialog() {
        _state.update { it.copy(showPermissionDialog = false) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private suspend fun markCheckedQuietly() {
        try {
            repository.markChecked()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Время проверки не сохранилось — не критично.
        }
    }
}

private fun Throwable.toUpdateError(): UpdateError = when (this) {
    is AppError.Network -> UpdateError.NETWORK
    is AppError.Server, is AppError.NotFound -> UpdateError.SERVER
    is AppError.Parse -> UpdateError.PARSE
    is AppError.InvalidUpdate -> UpdateError.VERIFICATION
    else -> UpdateError.UNKNOWN
}
