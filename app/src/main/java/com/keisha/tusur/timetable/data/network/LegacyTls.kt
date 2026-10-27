package com.keisha.tusur.timetable.data.network

import android.content.Context
import com.keisha.tusur.timetable.R
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Запасные якоря GlobalSign для систем, где корня R46 ещё нет в хранилище.
 *
 * Сайт выдаёт сертификат `*.tusur.ru` от GlobalSign GCC R46 AlphaSSL CA 2025,
 * а корень R46 появился в системном хранилище только в Android 14. На Android 8–13
 * цепочка не проверяется (`Trust anchor for certification path not found`),
 * и приложение ошибочно сообщает об отсутствии интернета.
 *
 * Поэтому на всех версиях:
 *  - доверяем корень R46 и промежуточный AlphaSSL CA 2025 как дополнительным якорям;
 *  - если сервер прислал только лист, достраиваем цепочку промежуточным сертификатом.
 *
 * Системная проверка не отключается и выполняется первой: дополнительные якоря
 * используются, только если штатный TrustManager не смог построить цепочку.
 */
object LegacyTls {

    @Volatile
    private var legacyTrustManager: X509TrustManager? = null

    fun init(context: Context) {
        if (legacyTrustManager != null) return
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
