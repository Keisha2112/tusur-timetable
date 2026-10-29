package com.keisha.tusur.timetable.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.keisha.tusur.timetable.domain.model.AppError
import com.keisha.tusur.timetable.domain.update.DownloadedApk
import com.keisha.tusur.timetable.domain.update.InstallResult
import java.io.File
import java.security.MessageDigest

/**
 * Установка скачанного APK через PackageInstaller.
 *
 * Перед установкой APK проверяется по манифесту: тот же пакет, versionCode выше
 * текущего и та же подпись, что у установленного приложения. Проверка не даёт
 * поставить чужой файл, даже если ссылка на релиз подменена.
 */
class ApkInstaller(private val context: Context) {

    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            // Android 6–7: разрешение общее для всех приложений, отдельного
            // per-app переключателя нет. Если оно выключено, системный установщик
            // покажет «Install blocked»; лучше заранее открыть настройки.
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.INSTALL_NON_MARKET_APPS,
                0,
            ) == 1
        }

    /** Открывает системный экран «Установка неизвестных приложений». */
    fun openInstallPermissionSettings() {
        val direct = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (runCatching { context.startActivity(direct) }.isSuccess) return

        // На части прошивок экран для конкретного пакета отсутствует.
        val fallback = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(fallback) }
            .onFailure { Log.w(TAG, "Настройки установки недоступны", it) }
    }

    fun install(apk: DownloadedApk): InstallResult {
        if (!canInstallPackages()) return InstallResult.PermissionRequired
        verifyApk(apk.file)

        val installer = context.packageManager.packageInstaller
        // Прошлые неудачные попытки могли оставить висящую сессию.
        runCatching {
            installer.mySessions.forEach { installer.abandonSession(it.sessionId) }
        }
        val sessionId = try {
            installer.createSession(sessionParams())
        } catch (e: SecurityException) {
            return securityFailure(e)
        } catch (e: Exception) {
            throw AppError.Unknown(e)
        }

        try {
            val session = installer.openSession(sessionId)

            try {
                session.openWrite(BASE_APK, 0, apk.file.length()).use { output ->
                    apk.file.inputStream().use { input -> input.copyTo(output) }
                    output.flush()
                    session.fsync(output)
                }
                session.commit(statusIntent(sessionId).intentSender)
            } finally {
                runCatching { session.close() }
            }
            return InstallResult.Started
        } catch (e: SecurityException) {
            runCatching { installer.abandonSession(sessionId) }
            return securityFailure(e)
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw AppError.Unknown(e)
        }
    }

    private fun sessionParams(): PackageInstaller.SessionParams =
        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }

    /** На новых Android SecurityException уже не обязательно про неизвестные источники. */
    private fun securityFailure(cause: SecurityException): InstallResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !canInstallPackages()) {
            return InstallResult.PermissionRequired
        }
        throw AppError.Unknown(cause)
    }

    private fun statusIntent(sessionId: Int): PendingIntent {
        val intent = Intent(context, UpdateInstallReceiver::class.java)
            .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
        // PackageInstaller дописывает статус и EXTRA_INTENT в момент отправки,
        // а immutable-PendingIntent такие вставки отбрасывает. С targetSdk 35+
        // commit() с immutable ещё и бросает IllegalArgumentException.
        // Интент явный, поэтому mutable здесь безопасен.
        val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
        )
    }

    private fun verifyApk(file: File) {
        if (!file.isFile || file.length() == 0L) throw AppError.InvalidUpdate()

        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, archiveFlags())
            ?: throw AppError.InvalidUpdate()
        if (archive.packageName != context.packageName) throw AppError.InvalidUpdate()

        val installed = installedPackage()
        if (archive.versionCodeLong() <= installed.versionCodeLong()) {
            throw AppError.InvalidUpdate()
        }

        val apkSigners = archive.signers()?.map { it.fingerprint() }?.toSet()
        val installedSigners = installed.signers()?.map { it.fingerprint() }?.toSet()
        if (apkSigners.isNullOrEmpty() || installedSigners.isNullOrEmpty()) {
            throw AppError.InvalidUpdate()
        }
        if (apkSigners != installedSigners) throw AppError.InvalidUpdate()
    }

    private fun installedPackage(): PackageInfo = try {
        context.packageManager.getPackageInfo(context.packageName, archiveFlags())
    } catch (e: PackageManager.NameNotFoundException) {
        throw AppError.InvalidUpdate(e)
    }

    @Suppress("DEPRECATION")
    private fun archiveFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeLong(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            versionCode.toLong()
        }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signers(): Array<Signature>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners
        } else {
            signatures
        }

    private fun Signature.fingerprint(): String =
        MessageDigest.getInstance(SHA_256).digest(toByteArray()).toHex()

    private fun ByteArray.toHex(): String = buildString(this@toHex.size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xFF
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }

    private companion object {
        const val BASE_APK = "base.apk"
        const val SHA_256 = "SHA-256"
        const val HEX_DIGITS = "0123456789abcdef"
        const val TAG = "ApkInstaller"
    }
}
