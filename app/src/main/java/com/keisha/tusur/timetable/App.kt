package com.keisha.tusur.timetable

import android.app.Application
import com.keisha.tusur.timetable.data.network.LegacyTls

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        LegacyTls.init(this)
        container = AppContainer(this)
    }
}
