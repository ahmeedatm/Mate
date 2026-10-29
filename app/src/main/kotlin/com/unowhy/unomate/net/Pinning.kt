package com.unowhy.unomate.net

import android.annotation.SuppressLint
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Ordinateur joint en HTTP en clair hors de la tablette (ancien appairage, ou ancien compagnon) : le jeton n'est pas
 * envoyé, il faut réappairer (voir docs/securite-appairage.md).
 */
class InsecureComputerException : IOException("Connexion non chiffrée refusée")

/** SHA-256 du certificat (DER), en hexadécimal minuscule : même calcul que `fingerprintOf` du backend (src/tls.ts). */
fun fingerprintOf(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

/** 127.0.0.1 / localhost : la tablette elle-même (USB par adb reverse, émulateur), rien ne passe sur le WiFi. */
fun isLocalUrl(url: String): Boolean = runCatching { URL(url).host }.getOrNull().let { it == "127.0.0.1" || it == "localhost" }

/**
 * Certificats auto-signés des ordinateurs : pas d'autorité à consulter, la tablette compare l'empreinte à celle retenue
 * à l'appairage (`pin`). Sans `pin` (pendant l'appairage), elle retient la première vue et exige ensuite la même :
 * toutes les requêtes de l'appairage parlent au même ordinateur.
 */
// Voulu : pas d'autorité de certification pour un ordinateur du réseau local, l'empreinte épinglée la remplace
// (vérification plus stricte que la chaîne d'autorités : un seul certificat accepté).
@SuppressLint("CustomX509TrustManager")
class PinnedTrust(pin: String?) : X509TrustManager {
    @Volatile var seen: String? = pin
        private set

    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Pas de certificat")
        val fingerprint = fingerprintOf(leaf)
        synchronized(this) {
            if (seen == null) seen = fingerprint
            if (!MessageDigest.isEqual(fingerprint.toByteArray(), seen!!.toByteArray())) {
                throw CertificateException("Certificat inattendu : ce n'est pas l'ordinateur appairé")
            }
        }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = throw CertificateException("Pas de client TLS")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private val factory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@PinnedTrust), SecureRandom()) }.socketFactory
    }

    /** Une adresse IP n'a pas de nom à vérifier : l'empreinte remplace la vérification du nom. */
    fun apply(conn: HttpsURLConnection) {
        conn.sslSocketFactory = factory
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
    }

    companion object {
        private val pinned = HashMap<String, PinnedTrust>()

        /**
         * Un seul par empreinte : un BackendClient se crée à chaque appel, la même fabrique garde les sessions TLS
         * (pas de poignée de main complète à chaque requête sur la tablette lente).
         */
        fun forPin(pin: String): PinnedTrust = synchronized(pinned) { pinned.getOrPut(pin) { PinnedTrust(pin) } }
    }
}
