package app.mate.installers

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Qui peut lire les installeurs servis sur le WiFi, sans Android (testé dans app/src/test) : soit un ordinateur appairé
 * (jeton d'appairage en `Authorization: Bearer`, pour la mise à jour de l'appli portable), soit un navigateur qui connaît
 * le code affiché sur Bienvenue (`/<code>/…`). Tout le reste : refusé, sans rien dire du contenu.
 */
object InstallerAccess {

    /** Sans 0, O, 1, I, L : le code est lu sur la tablette puis tapé sur l'ordinateur. */
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val CODE_LENGTH = 5

    /** `path` : chemin à servir, relatif à la racine des installeurs ; `prefix` : à mettre devant les liens de la page. */
    class Granted(val path: String, val prefix: String)

    fun newCode(random: java.util.Random = SecureRandom()): String =
        String(CharArray(CODE_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** Null : refusé (ni jeton connu, ni bon code). Les comparaisons se font à temps constant. */
    fun resolve(path: String, authorization: String?, code: String?, tokens: List<String>): Granted? {
        val bearer = authorization?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
        if (bearer.isNotEmpty() && tokens.any { it.isNotEmpty() && same(it, bearer) }) return Granted(path, "")
        if (code.isNullOrEmpty() || !path.startsWith("/")) return null
        val first = path.removePrefix("/").substringBefore('/')
        if (!same(code, first.uppercase())) return null
        return Granted("/" + path.removePrefix("/").substringAfter('/', ""), "/$code")
    }

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
