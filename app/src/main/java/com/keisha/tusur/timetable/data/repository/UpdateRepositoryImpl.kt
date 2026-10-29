package com.keisha.tusur.timetable.data.repository

import com.keisha.tusur.timetable.BuildConfig
import com.keisha.tusur.timetable.data.update.ApkDownloader
import com.keisha.tusur.timetable.data.update.ApkInstaller
import com.keisha.tusur.timetable.data.update.AppVersion
import com.keisha.tusur.timetable.data.update.GitHubReleaseClient
import com.keisha.tusur.timetable.data.update.UpdatePreferences
import com.keisha.tusur.timetable.domain.update.AppUpdate
import com.keisha.tusur.timetable.domain.update.DownloadProgress
import com.keisha.tusur.timetable.domain.update.DownloadedApk
import com.keisha.tusur.timetable.domain.update.InstallResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * Обновления через GitHub Releases: проверка последнего релиза, загрузка APK
 * и передача его системному установщику.
 *
 * В debug-сборке обновления отключены: applicationId там другой,
 * и установка release-APK всё равно не пройдёт.
 */
class UpdateRepositoryImpl(
    private val releaseClient: GitHubReleaseClient,
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller,
    private val preferences: UpdatePreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    override val currentVersionName: String = BuildConfig.VERSION_NAME,
    override val isSupported: Boolean = !BuildConfig.DEBUG,
) : UpdateRepository {

    override suspend fun checkForUpdate(): AppUpdate? {
        if (!isSupported) return null
        val release = releaseClient.latestRelease()
        // Текущая версия может быть новее опубликованной: тогда предлагать нечего.
        return release.takeIf { AppVersion.isNewer(currentVersionName, it.versionName) }
    }

    override suspend fun shouldAutoCheck(now: Instant): Boolean {
        if (!isSupported) return false
        val lastCheckAt = preferences.lastCheckAt.first() ?: return true
        return Duration.between(lastCheckAt, now) >= AUTO_CHECK_INTERVAL
    }

    override suspend fun markChecked(at: Instant) {
        preferences.setLastCheckAt(at)
    }

    override suspend fun isVersionSkipped(version: String): Boolean =
        preferences.skippedVersion.first() == version

    override suspend fun skipVersion(version: String) {
        preferences.setSkippedVersion(version)
    }

    override fun canInstallPackages(): Boolean = installer.canInstallPackages()

    override fun openInstallPermissionSettings() {
        installer.openInstallPermissionSettings()
    }

    override suspend fun download(
        update: AppUpdate,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadedApk = downloader.download(update, onProgress)

    override suspend fun install(apk: DownloadedApk): InstallResult = withContext(ioDispatcher) {
        // Без разрешения на установку неизвестных приложений системный диалог не откроется.
        if (!canInstallPackages()) InstallResult.PermissionRequired else installer.install(apk)
    }

    private companion object {
        val AUTO_CHECK_INTERVAL: Duration = Duration.ofHours(24)
    }
}
