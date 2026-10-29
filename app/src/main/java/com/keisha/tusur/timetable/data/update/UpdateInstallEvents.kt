package com.keisha.tusur.timetable.data.update

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Статусы сессии [android.content.pm.PackageInstaller] для UI.
 *
 * BroadcastReceiver живёт в процессе приложения, а экран настроек может быть
 * закрыт. SharedFlow без replay не копит старые события; текущее состояние
 * установки всё равно восстанавливается по версии после перезапуска.
 */
object UpdateInstallEvents {

    private val _statuses = MutableSharedFlow<Int>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val statuses: SharedFlow<Int> = _statuses.asSharedFlow()

    fun publish(status: Int) {
        _statuses.tryEmit(status)
    }
}
