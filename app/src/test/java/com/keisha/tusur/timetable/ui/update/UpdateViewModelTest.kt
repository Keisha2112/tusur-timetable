package com.keisha.tusur.timetable.ui.update

import com.keisha.tusur.timetable.data.repository.UpdateRepository
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.update.AppUpdate
import com.keisha.tusur.timetable.domain.update.DownloadProgress
import com.keisha.tusur.timetable.domain.update.DownloadedApk
import com.keisha.tusur.timetable.domain.update.InstallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Поведение [UpdateViewModel] на fake-репозитории: тихая и ручная проверки,
 * разрешение на установку, загрузка с прогрессом и пропуск версии.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var repository: FakeUpdateRepository

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        repository = FakeUpdateRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `automatic check opens dialog for available update`() = runTest(dispatcher) {
        val update = sampleUpdate()
        repository.update = update
        val viewModel = UpdateViewModel(repository)

        viewModel.checkAutomatically()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(update, state.available)
        assertTrue(state.showDialog)
        assertTrue(state.hasChecked)
        assertFalse(state.isChecking)
        assertEquals(1, repository.markCheckedCalls)
    }

    @Test
    fun `automatic check does not show skipped version`() = runTest(dispatcher) {
        val update = sampleUpdate()
        repository.update = update
        repository.skippedVersions += update.versionName
        val viewModel = UpdateViewModel(repository)

        viewModel.checkAutomatically()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(update, state.available)
        assertFalse(state.showDialog)
        assertTrue(state.hasChecked)
    }

    @Test
    fun `manual check without update reports up to date`() = runTest(dispatcher) {
        val viewModel = UpdateViewModel(repository)

        viewModel.checkManually()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.hasChecked)
        assertNull(state.available)
        assertNull(state.error)
        assertFalse(state.showDialog)
        assertEquals(UpdateEvent.UpToDate, viewModel.nextEvent())
    }

    @Test
    fun `manual check network failure exposes error and event`() = runTest(dispatcher) {
        repository.checkFailure = AppError.Network()
        val viewModel = UpdateViewModel(repository)

        viewModel.checkManually()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.hasChecked)
        assertEquals(UpdateError.NETWORK, state.error)
        assertEquals(UpdateEvent.Failed(UpdateError.NETWORK), viewModel.nextEvent())
    }

    @Test
    fun `download without install permission asks for permission`() = runTest(dispatcher) {
        repository.update = sampleUpdate()
        repository.installAllowed = false
        val viewModel = UpdateViewModel(repository)
        viewModel.checkManually()
        advanceUntilIdle()

        viewModel.downloadAndInstall()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.showPermissionDialog)
        assertEquals(0, repository.downloadCalls)
        assertEquals(0, repository.installCalls)
        assertEquals(UpdateEvent.PermissionRequired, viewModel.nextEvent())
    }

    @Test
    fun `download reports progress and starts installation`() = runTest(dispatcher) {
        repository.update = sampleUpdate()
        val viewModel = UpdateViewModel(repository)
        viewModel.checkAutomatically()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.showDialog)

        viewModel.downloadAndInstall()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(0.5f, state.downloadFraction!!, 0.0001f)
        assertFalse(state.isDownloading)
        assertFalse(state.showDialog)
        assertNull(state.error)
        assertEquals(1, repository.downloadCalls)
        assertEquals(1, repository.installCalls)
        assertEquals(listOf(DownloadProgress(bytesRead = 50, totalBytes = 100)), repository.reportedProgress)
        assertEquals(repository.downloadedApk, repository.installedApk)
        assertEquals(UpdateEvent.InstallStarted, viewModel.nextEvent())
    }

    @Test
    fun `skip version is persisted and hides dialog`() = runTest(dispatcher) {
        val update = sampleUpdate()
        repository.update = update
        val viewModel = UpdateViewModel(repository)
        viewModel.checkAutomatically()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.showDialog)

        viewModel.skipVersion()
        advanceUntilIdle()

        assertEquals(1, repository.skipCalls)
        assertEquals(listOf(update.versionName), repository.skippedRequests)
        assertFalse(viewModel.state.value.showDialog)
    }

    private suspend fun UpdateViewModel.nextEvent(): UpdateEvent =
        withTimeout(5_000) { events.first() }

    private fun sampleUpdate() = AppUpdate(
        versionName = "1.3.0",
        tagName = "v1.3.0",
        notes = "Что нового",
        publishedAt = Instant.parse("2026-10-01T10:00:00Z"),
        downloadUrl = "https://example.com/tusur-timetable-1.3.0.apk",
        sizeBytes = 12_345_678L,
        sha256 = "abcdef0123456789",
    )
}

private class FakeUpdateRepository(
    override val currentVersionName: String = "1.2.0",
    override val isSupported: Boolean = true,
) : UpdateRepository {

    var autoCheckAllowed = true
    var update: AppUpdate? = null
    var checkFailure: Exception? = null
    val skippedVersions = mutableSetOf<String>()
    var installAllowed = true
    var downloadFailure: Exception? = null
    var installFailure: Exception? = null
    var installResult: InstallResult = InstallResult.Started

    var checkCalls = 0
    var markCheckedCalls = 0
    var skipCalls = 0
    var downloadCalls = 0
    var installCalls = 0
    var permissionSettingsOpened = 0

    val skippedRequests = mutableListOf<String>()
    val reportedProgress = mutableListOf<DownloadProgress>()
    var downloadedApk: DownloadedApk? = null
    var installedApk: DownloadedApk? = null

    override suspend fun checkForUpdate(): AppUpdate? {
        checkCalls++
        checkFailure?.let { throw it }
        return update
    }

    override suspend fun shouldAutoCheck(now: Instant): Boolean = autoCheckAllowed

    override suspend fun markChecked(at: Instant) {
        markCheckedCalls++
    }

    override suspend fun isVersionSkipped(version: String): Boolean =
        version in skippedVersions

    override suspend fun skipVersion(version: String) {
        skipCalls++
        skippedRequests += version
    }

    override fun canInstallPackages(): Boolean = installAllowed

    override fun openInstallPermissionSettings() {
        permissionSettingsOpened++
    }

    override suspend fun download(
        update: AppUpdate,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadedApk {
        downloadCalls++
        downloadFailure?.let { throw it }
        val progress = DownloadProgress(bytesRead = 50, totalBytes = 100)
        reportedProgress += progress
        onProgress(progress)
        val apk = DownloadedApk(File("tusur-timetable.apk"), update)
        downloadedApk = apk
        return apk
    }

    override suspend fun install(apk: DownloadedApk): InstallResult {
        installCalls++
        installFailure?.let { throw it }
        installedApk = apk
        return installResult
    }
}
