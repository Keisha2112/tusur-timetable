package com.keisha.tusur.timetable

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.keisha.tusur.timetable.ui.TusurApp
import com.keisha.tusur.timetable.ui.theme.TusurTimetableTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as App).container

        setContent {
            TusurTimetableTheme {
                TusurApp(container)
            }
        }
    }
}
