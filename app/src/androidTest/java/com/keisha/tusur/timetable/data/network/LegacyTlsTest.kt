package com.keisha.tusur.timetable.data.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Дополнительные якоря должны подключаться на любой версии Android:
 * на Android 8–13 системного корня GlobalSign R46 ещё нет.
 */
@RunWith(AndroidJUnit4::class)
class LegacyTlsTest {

    @Test
    fun extraTrustAnchorsAreInstalled() {
        LegacyTls.init(ApplicationProvider.getApplicationContext<Context>())
        assertNotNull(
            "Без дополнительных якорей цепочка сайта не проверяется на Android 8–13",
            LegacyTls.trustManager(),
        )
    }
}
