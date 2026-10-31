package app.mate.voice

import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Logique pure du mot de réveil « Mate » (testée sur la JVM) : lecture des résultats de Vosk, partage du micro, garde
 * de la décompression du modèle. Le reste ([WakeWord], [WakeModel]) touche au micro, au réseau et aux fichiers.
 */
object WakeLogic {
    /** Le mot attendu, tel que Vosk l'écrit dans ses résultats. */
    const val WORD = "mate"

    /** Confiance minimale de Vosk pour « mate » (0 à 1) : en dessous, on ne réveille pas. À régler après essai. */
    const val MIN_CONF = 0.7

    /** Grammaire restreinte : tout ce qui n'est pas « mate » tombe dans `[unk]`, d'où peu de faux réveils. */
    const val GRAMMAR = """["mate", "hé mate", "hey mate", "[unk]"]"""

    /**
     * Résultat de Vosk qui réveille : un résultat final (`{"result": [{"word", "conf"…}], "text"}`) dont un mot est
     * « mate » avec une confiance ≥ [MIN_CONF]. Les résultats partiels (`{"partial": …}`), `[unk]` seul et un JSON
     * illisible ne réveillent pas.
     */
    fun isWake(json: String): Boolean = try {
        val words = JSONObject(json).optJSONArray("result")
        words != null && (0 until words.length()).any { i ->
            val word = words.optJSONObject(i)
            word != null && word.optString("word") == WORD && word.optDouble("conf", 0.0) >= MIN_CONF
        }
    } catch (e: JSONException) {
        false
    }

    /**
     * Fichier où décompresser l'entrée `name` d'un zip dans `root`, ou null si elle en sortirait (`../`, chemin absolu) :
     * un zip piégé n'écrit jamais hors du dossier du modèle.
     */
    fun zipTarget(root: File, name: String): File? {
        if (name.isEmpty() || name.startsWith("/") || name.startsWith("\\") || File(name).isAbsolute) return null
        val base = root.canonicalFile
        val target = File(base, name).canonicalFile
        return target.takeIf { it.path.startsWith(base.path + File.separator) }
    }
}

/**
 * Micro partagé : Android 10 ne le donne qu'à une écoute à la fois. Chaque usage du micro (commande vocale, dictée,
 * caméra, carte d'appel) le prend sous son nom et le rend ; le mot de réveil n'écoute que si personne ne le tient.
 * Un nom pris deux fois compte une fois, rendre un nom non pris ne fait rien. Fil principal seulement.
 */
open class MicGate {
    private val holders = linkedSetOf<String>()

    /** Appelé quand le micro se libère ou se prend (passage de libre à tenu et inversement), pas à chaque nom. */
    var onChange: (() -> Unit)? = null

    val free get() = holders.isEmpty()

    fun holds(who: String) = who in holders

    fun hold(who: String) {
        val was = free
        holders += who
        if (was) onChange?.invoke()
    }

    fun release(who: String) {
        if (holders.remove(who) && free) onChange?.invoke()
    }

    fun set(who: String, busy: Boolean) = if (busy) hold(who) else release(who)

    /** Activité détruite : rien ne reste tenu par des écrans qui n'existent plus. */
    fun clear() {
        if (free) return
        holders.clear()
        onChange?.invoke()
    }
}

/** Le micro de la tablette (singleton) et les noms de ceux qui le prennent. */
object Mic : MicGate() {
    /** Commande vocale (« Parler » ou après « Mate ») : de l'écoute jusqu'à la fin de la commande. */
    const val VOICE = "voice"
    /** Le mot de réveil lui-même, entre « Mate » et le début de la commande (bip). */
    const val WAKE = "wake"
    /** Dictée du presse-papiers (Depuis l'ordi). */
    const val CLIPBOARD = "clipboard"
    /** Dictée d'une réponse à un agent ([Dictation]). */
    const val DICTATION = "dictation"
    /** Mode Caméra à l'écran. */
    const val CAMERA = "camera"
    /** Carte d'appel affichée. */
    const val CALL = "call"
}
