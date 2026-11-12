package app.mate.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.mate.Prefs
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffrement du jeton Home Assistant gardé sur la tablette (seule exception à « aucun secret sur la tablette » avec le
 * code technicien et la clé IDFM, voir CLAUDE.md) : AES-GCM, clé dans l'Android Keystore, qui ne sort jamais de la
 * puce. Blob = IV puis texte chiffré, en hexadécimal (java.util.Base64 demande l'API 26) ; un blob abîmé ou une clé perdue rendent null, jamais une exception.
 */
object HaSecrets {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val ALIAS = "mate-ha-token"

    fun seal(plain: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        return (cipher.iv + cipher.doFinal(plain.toByteArray())).joinToString("") { "%02x".format(it) }
    }

    fun open(blob: String, key: SecretKey): String? = runCatching {
        require(blob.length % 2 == 0 && blob.length > IV_BYTES * 2)
        val bytes = ByteArray(blob.length / 2) { blob.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }.getOrNull()

    /** Clé AES de l'Android Keystore, créée au premier usage. */
    fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }
}

/**
 * Home Assistant joint en direct depuis Maison. `allowHttp` : case « Autoriser HTTP » (adresse du réseau local
 * seulement, le jeton passe alors en clair sur le WiFi). `toString` ne montre jamais le jeton.
 */
class HaConfig(val url: String, val token: String, val allowHttp: Boolean) {
    override fun toString() = "HaConfig(url=$url, allowHttp=$allowHttp)"

    companion object {
        private const val KEY_URL = "ha_direct_url"
        private const val KEY_TOKEN = "ha_direct_token"
        private const val KEY_HTTP = "ha_direct_http"

        private fun prefs(context: Context) = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)

        /** null : rien de réglé, ou jeton illisible (clé du Keystore perdue) : à ressaisir. */
        fun load(context: Context, key: () -> SecretKey = HaSecrets::keystoreKey): HaConfig? {
            val p = prefs(context)
            val url = p.getString(KEY_URL, null) ?: return null
            val allowHttp = p.getBoolean(KEY_HTTP, false)
            // Revérifiée à chaque lecture : une adresse gardée par une version plus permissive ne sert pas.
            if (haUrlProblem(url, allowHttp) != null) return null
            val blob = p.getString(KEY_TOKEN, null) ?: return null
            val token = runCatching { HaSecrets.open(blob, key()) }.getOrNull() ?: return null
            return HaConfig(url, token, allowHttp)
        }

        /**
         * Adresse saisie ou copiée de l'ordinateur ; le jeton n'est écrit que chiffré. Faux : le Keystore de la tablette
         * refuse de chiffrer (rien n'est écrit, et jamais en clair).
         */
        fun save(context: Context, url: String, token: String, allowHttp: Boolean, key: () -> SecretKey = HaSecrets::keystoreKey): Boolean {
            val sealed = runCatching { HaSecrets.seal(token.trim(), key()) }.getOrNull() ?: return false
            prefs(context).edit()
                .putString(KEY_URL, url.trim().trimEnd('/'))
                .putString(KEY_TOKEN, sealed)
                .putBoolean(KEY_HTTP, allowHttp)
                .apply()
            return true
        }

        /** Adresse gardée (pour la saisie), sans le jeton. */
        fun url(context: Context): String? = prefs(context).getString(KEY_URL, null)

        fun allowHttp(context: Context): Boolean = prefs(context).getBoolean(KEY_HTTP, false)

        fun clear(context: Context) {
            prefs(context).edit().remove(KEY_URL).remove(KEY_TOKEN).remove(KEY_HTTP).apply()
        }
    }
}
