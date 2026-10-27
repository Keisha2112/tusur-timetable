package com.keisha.tusur.timetable.data.network

import android.content.Context
import android.os.Build
import com.keisha.tusur.timetable.R
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Сайт расписания отдаёт только листовой сертификат `*.tusur.ru`, без промежуточного
 * и корневого. Браузеры добирают промежуточный через AIA, а Android 6.0 просто
 * не может построить цепочку: корня GlobalSign R46 (2019) в системе ещё нет.
 *
 * Поэтому на API 23–25:
 *  - доверяем корень R46 и промежуточный AlphaSSL CA 2025 как дополнительным якорям;
 *  - если сервер прислал один лист, достраиваем цепочку промежуточным сертификатом.
 *
 * Системная проверка не отключается: сначала пробуем штатный TrustManager,
 * и только если он не знает цепочку — свой. Начиная с Android 8.0 (API 26)
 * корень уже есть в системе, и подмена не нужна.
 */
object LegacyTls {

    @Volatile
    private var legacyTrustManager: X509TrustManager? = null

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || legacyTrustManager != null) return
        legacyTrustManager = buildTrustManager(context.applicationContext)
    }

    fun trustManager(): X509TrustManager? = legacyTrustManager

    fun sslContext(trustManager: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }

    private fun buildTrustManager(context: Context): X509TrustManager? = runCatching {
        val root = loadCertificate(context, R.raw.globalsign_root_r46)
        val intermediate = loadCertificate(context, R.raw.globalsign_alphassl_ca_2025)

        CompositeTrustManager(
            system = trustManagerFor(null),
            extra = trustManagerFor(keyStoreOf(root, intermediate)),
            intermediate = intermediate,
        )
    }.getOrNull()

    private fun loadCertificate(context: Context, resourceId: Int): X509Certificate {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        return context.resources.openRawResource(resourceId).use {
            certificateFactory.generateCertificate(it) as X509Certificate
        }
    }

    private fun keyStoreOf(vararg certificates: X509Certificate): KeyStore =
        KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            certificates.forEachIndexed { index, certificate ->
                setCertificateEntry("tusur-ca-$index", certificate)
            }
        }

    private fun trustManagerFor(keyStore: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * Пробует системную проверку, затем свою. Если сервер не прислал промежуточный
     * сертификат, он добавляется в цепочку из ресурсов приложения.
     */
    private class CompositeTrustManager(
        private val system: X509TrustManager,
        private val extra: X509TrustManager,
        private val intermediate: X509Certificate,
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            system.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            var lastError: CertificateException? = null

            try {
                system.checkServerTrusted(chain, authType)
                return
            } catch (error: CertificateException) {
                lastError = error
            }

            val completedChain = if (chain.size == 1) arrayOf(chain[0], intermediate) else chain
            try {
                extra.checkServerTrusted(completedChain, authType)
                return
            } catch (error: CertificateException) {
                lastError = error
            }

            throw lastError ?: CertificateException("Цепочка сертификатов не прошла проверку")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            (system.acceptedIssuers.asList() + extra.acceptedIssuers.asList()).toTypedArray()
    }
}
