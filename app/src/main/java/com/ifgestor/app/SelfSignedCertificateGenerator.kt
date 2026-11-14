package com.ifgestor.app

import android.content.Context
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object SelfSignedCertificateGenerator {

    private const val KEYSTORE_FILE_NAME = "ifgestor_local_tls.p12"
    private const val KEY_ALIAS = "ifgestor_https_key"
    private const val PREF_KEYSTORE_PASS = "tls_keystore_secret"

    @Volatile
    private var cachedSslContext: SSLContext? = null

    @Volatile
    private var cachedFingerprint: String? = null

    /**
     * Retorna o SSLContext configurado com o certificado X.509 local do dispositivo.
     * Caso ainda não exista ou esteja inválido, gera automaticamente.
     */
    @Synchronized
    fun getOrCreateSslContext(context: Context): SSLContext {
        cachedSslContext?.let { return it }

        val appContext = context.applicationContext
        val password = getOrCreateKeystorePassword(appContext).toCharArray()
        val keyStoreFile = File(appContext.filesDir, KEYSTORE_FILE_NAME)

        val keyStore = KeyStore.getInstance("PKCS12")
        val loaded = if (keyStoreFile.exists()) {
            try {
                FileInputStream(keyStoreFile).use { fis ->
                    keyStore.load(fis, password)
                }
                keyStore.containsAlias(KEY_ALIAS)
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }

        if (!loaded) {
            generateAndSaveKeyStore(keyStore, keyStoreFile, password)
            AuditLogManager.getInstance(appContext).logEvent(
                action = "CERTIFICADO_TLS_GERADO",
                actor = "Sistema",
                details = "Certificado autoassinado HTTPS (RSA-2048 / SHA256) criado com sucesso",
                success = true
            )
        }

        // Calcula o Fingerprint SHA-256 para exibição e conferência na aba Servidor PC
        try {
            val cert = keyStore.getCertificate(KEY_ALIAS) as? X509Certificate
            if (cert != null) {
                cachedFingerprint = computeSha256Fingerprint(cert)
            }
        } catch (_: Exception) {
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, createLocalP2pTrustManagers(), SecureRandom())

        cachedSslContext = sslContext
        return sslContext
    }

    /**
     * Retorna a fábrica de sockets para o Servidor HTTPS embarcado (Item 5).
     */
    fun getServerSocketFactory(context: Context): SSLServerSocketFactory {
        return getOrCreateSslContext(context).serverSocketFactory
    }

    /**
     * Retorna a fábrica de sockets para chamadas HTTPS entre celulares na mesma rede (P2P - Item 8).
     */
    fun getClientSocketFactory(context: Context): SSLSocketFactory {
        return getOrCreateSslContext(context).socketFactory
    }

    /**
     * Retorna a impressão digital SHA-256 do certificado TLS ativo no aparelho.
     */
    fun getCertificateFingerprint(context: Context): String {
        if (cachedFingerprint == null) {
            try {
                getOrCreateSslContext(context)
            } catch (_: Exception) {
            }
        }
        return cachedFingerprint ?: "Gerando certificado TLS..."
    }

    private fun generateAndSaveKeyStore(
        keyStore: KeyStore,
        keyStoreFile: File,
        password: CharArray
    ) {
        val secureRandom = SecureRandom()
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, secureRandom)
        val keyPair = keyPairGenerator.generateKeyPair()

        val now = System.currentTimeMillis()
        // Validade de 10 anos para uso offline em rede local sem expirar durante vistorias
        val notBefore = Date(now - 24 * 60 * 60 * 1000L)
        val notAfter = Date(now + 10L * 365 * 24 * 60 * 60 * 1000L)
        val serial = BigInteger(64, secureRandom).abs()

        val subject = X500Name("CN=ifgestor.local, O=IFGestor Local Security, C=BR")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )

        // Nomes alternativos (SAN) para suportar resolução mDNS e conflitos automáticos (-2, -3)
        val subjectAltNames = GeneralNames(
            arrayOf(
                GeneralName(GeneralName.dNSName, "ifgestor.local"),
                GeneralName(GeneralName.dNSName, "ifgestor-2.local"),
                GeneralName(GeneralName.dNSName, "ifgestor-3.local"),
                GeneralName(GeneralName.dNSName, "ifgestor-4.local"),
                GeneralName(GeneralName.dNSName, "localhost"),
                GeneralName(GeneralName.iPAddress, "127.0.0.1")
            )
        )

        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.subjectAlternativeName, false, subjectAltNames)

        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        val certHolder = builder.build(signer)
        val certificate: X509Certificate = JcaX509CertificateConverter().getCertificate(certHolder)

        keyStore.load(null, null)
        keyStore.setKeyEntry(
            KEY_ALIAS,
            keyPair.private,
            password,
            arrayOf<Certificate>(certificate)
        )

        val tempFile = File(keyStoreFile.parentFile, "$KEYSTORE_FILE_NAME.part")
        FileOutputStream(tempFile).use { fos ->
            keyStore.store(fos, password)
        }
        if (keyStoreFile.exists()) {
            keyStoreFile.delete()
        }
        tempFile.renameTo(keyStoreFile)
    }

    private fun getOrCreateKeystorePassword(context: Context): String {
        val prefs = SettingsManager(context).getRawPreferences()
        val existing = prefs.getString(PREF_KEYSTORE_PASS, null)
        if (!existing.isNullOrBlank()) return existing

        val randomBytes = ByteArray(24)
        SecureRandom().nextBytes(randomBytes)
        val generated = Base64.encodeToString(randomBytes, Base64.NO_WRAP)
        prefs.edit().putString(PREF_KEYSTORE_PASS, generated).apply()
        return generated
    }

    private fun computeSha256Fingerprint(cert: X509Certificate): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(cert.encoded)
        return digest.joinToString(":") { byte -> String.format("%02X", byte) }
    }

    /**
     * TrustManager para permitir comunicação HTTPS/TLS entre celulares IFGestor na rede local (P2P),
     * onde a autenticação forte ocorre via Duplo 2FA (Item 8).
     */
    fun createLocalP2pTrustManagers(): Array<TrustManager> {
        return arrayOf(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        )
    }
}
