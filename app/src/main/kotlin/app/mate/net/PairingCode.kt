package app.mate.net

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Calculs de l'appairage côté tablette, identiques à ceux du backend (src/pairing.ts) : nombre secret et engagement,
 * puis code à 6 chiffres que la tablette affiche et que l'utilisateur saisit sur l'ordinateur.
 */
object PairingCode {
    /** Nombre secret (32 octets, base64url) et son engagement (SHA-256, base64url), envoyé en premier. */
    data class Secret(val nonce: String, val commit: String)

    fun newSecret(random: SecureRandom = SecureRandom()): Secret = secretOf(ByteArray(32).also(random::nextBytes))

    fun secretOf(bytes: ByteArray): Secret {
        val nonce = base64Url(bytes)
        return Secret(nonce, base64Url(sha256(nonce.toByteArray())))
    }

    /** `channel` : empreinte du certificat vu par la tablette ; "" en HTTP local (USB). */
    fun code(channel: String, tabletNonce: String, computerNonce: String): String {
        val h = sha256("mate-pair-1\n$channel\n$tabletNonce\n$computerNonce".toByteArray())
        val n = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
        return (n % 1_000_000).toString().padStart(6, '0')
    }

    /** "123456" → "123 456", pour l'affichage. */
    fun spaced(code: String): String = code.take(3) + " " + code.drop(3)

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** java.util.Base64 n'existe qu'à partir d'Android 8 (minSdk 24) : base64url sans remplissage, écrit ici. */
    internal fun base64Url(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            out.append(alphabet[b0 shr 2]).append(alphabet[((b0 and 3) shl 4) or (b1 shr 4)])
            if (i + 1 < bytes.size) out.append(alphabet[((b1 and 15) shl 2) or (b2 shr 6)])
            if (i + 2 < bytes.size) out.append(alphabet[b2 and 63])
            i += 3
        }
        return out.toString()
    }
}
