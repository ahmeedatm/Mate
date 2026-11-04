package app.mate.installers

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Qui peut lire les installeurs servis sur le WiFi, sans Android (testé dans app/src/test) : seulement un navigateur qui
 * connaît le code affiché sur Bienvenue (`/<code>/…`). L'ordinateur appairé l'apprend par l'état du poll (HTTPS) : le
 * jeton d'appairage ne part jamais sur ce port en clair. Tout le reste : refusé, sans rien dire du contenu.
 */
object InstallerAccess {

    /** Sans 0, O, 1, I, L : le code est lu sur la tablette puis tapé sur l'ordinateur. */
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val CODE_LENGTH = 5

    /** `path` : chemin à servir, relatif à la racine des installeurs ; `prefix` : à mettre devant les liens de la page. */
    class Granted(val path: String, val prefix: String)

    fun newCode(random: java.util.Random = SecureRandom()): String =
        String(CharArray(CODE_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** Null : refusé (pas de code tiré, ou chemin hors de `/<code>/…`). La comparaison se fait à temps constant. */
    fun resolve(path: String, code: String?): Granted? {
        if (code.isNullOrEmpty() || !path.startsWith("/")) return null
        val first = path.removePrefix("/").substringBefore('/')
        if (!same(code, first.uppercase())) return null
        return Granted("/" + path.removePrefix("/").substringAfter('/', ""), "/$code")
    }

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
