package com.keisha.tusur.timetable.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat

/**
 * Принимает статус сессии [PackageInstaller].
 *
 * Система сначала присылает `STATUS_PENDING_USER_ACTION` вместе с Intent'ом
 * диалога подтверждения — его и показываем. Остальные статусы (успех, отказ)
 * здесь не нужны: установку завершает система, а новая версия видна по
 * versionName после перезапуска.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        UpdateInstallEvents.publish(status)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Log.i(TAG, "Статус установки: $status")
            return
        }

        val confirmation = IntentCompat.getParcelableExtra(
            intent,
            Intent.EXTRA_INTENT,
            Intent::class.java,
        ) ?: return
        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(confirmation) }
            .onFailure { Log.w(TAG, "Не удалось показать подтверждение установки", it) }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.keisha.tusur.timetable.action.INSTALL_STATUS"
        private const val TAG = "UpdateInstallReceiver"
    }
}
