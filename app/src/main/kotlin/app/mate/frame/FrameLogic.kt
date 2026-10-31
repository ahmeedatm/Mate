package app.mate.frame

import java.security.MessageDigest
import kotlin.random.Random

/** Photo du dossier « Cadre photo » de l'ordinateur (`GET /frame`). */
data class FramePhoto(val path: String, val size: Long, val mtimeMs: Long)

/** Ce que la synchronisation doit faire : photos à télécharger, fichiers du cache à effacer. */
data class FrameSync(val download: List<FramePhoto>, val delete: Set<String>)

/**
 * Règles du cadre photo, sans Android : quand il se lance, à quelle luminosité, ce que le cache garde, dans quel
 * ordre passent les photos. Tout est fait pour que l'écran consomme peu : rien ne bouge entre deux photos.
 */
object FrameLogic {

    /** Luminosité du cadre branché : une photo se regarde de loin, pas besoin de l'écran à fond. */
    const val LEVEL_PLUGGED = 0.35f

    /** Sur batterie, plus bas encore. */
    const val LEVEL_BATTERY = 0.2f

    /** Sous ce niveau, débranchée, le cadre ne se lance plus (l'écran garde son atténuation). */
    const val MIN_BATTERY = 20

    /** Délais proposés avant le cadre, en minutes ; 0 = jamais. */
    val DELAYS_MIN = listOf(0, 2, 5, 10, 30)

    /** Durées d'une photo proposées, en secondes. */
    val INTERVALS_S = listOf(30, 60, 300)

    /** Le cadre ne se lance pas par-dessus ce qui sert encore (appel, Focus qui tourne, caméra, document…). */
    data class Context(
        val idleMs: Long,
        val delayMin: Int,
        val night: Boolean,
        val batteryLevel: Int?,
        val charging: Boolean,
        val busy: Boolean,
    )

    /** Faut-il lancer le cadre ? Les photos se comptent ensuite : sans photo, il ne s'affiche pas. */
    fun shouldStart(c: Context): Boolean =
        c.delayMin > 0 && c.idleMs >= c.delayMin * 60_000L && canRun(c.night, c.batteryLevel, c.charging) && !c.busy

    /** Le cadre affiché continue-t-il ? La nuit (atténuation) et la batterie faible l'arrêtent. */
    fun canRun(night: Boolean, batteryLevel: Int?, charging: Boolean): Boolean =
        !night && (charging || batteryLevel == null || batteryLevel >= MIN_BATTERY)

    /** Luminosité pendant le cadre : jamais plus haute que celle choisie ([base], 0 à 1). */
    fun level(base: Float, charging: Boolean): Float = minOf(base, if (charging) LEVEL_PLUGGED else LEVEL_BATTERY)

    /** Nom du fichier en cache : change si la photo est remplacée (taille ou date), pour la retélécharger. */
    fun cacheName(photo: FramePhoto): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("${photo.path}|${photo.size}|${photo.mtimeMs}".toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) } + ".jpg"
    }

    fun plan(remote: List<FramePhoto>, cached: Set<String>): FrameSync {
        val wanted = remote.associateBy(::cacheName)
        return FrameSync(wanted.filterKeys { it !in cached }.values.toList(), cached - wanted.keys)
    }

    /** Paysage : remplit l'écran (rogné au centre) ; portrait : entière, sur fond noir (sinon les visages sont coupés). */
    fun fills(width: Int, height: Int): Boolean = width >= height

    /**
     * Taille gardée en cache pour un écran [screenW]×[screenH] : juste assez pour l'affichage, jamais agrandie. Une
     * photo de 12 Mpx devient un JPEG de quelques centaines de Ko, décodé vite à chaque passage.
     */
    fun scaledSize(width: Int, height: Int, screenW: Int, screenH: Int): Pair<Int, Int> {
        val sx = screenW.toFloat() / width
        val sy = screenH.toFloat() / height
        val scale = minOf(1f, if (fills(width, height)) maxOf(sx, sy) else minOf(sx, sy))
        return Pair(maxOf(1, Math.round(width * scale)), maxOf(1, Math.round(height * scale)))
    }

    /**
     * Réduction au décodage (puissance de 2) : la plus forte qui garde au moins [targetW]×[targetH], pour ne jamais
     * charger une photo de 12 Mpx en entier dans un tas de 128 Mo.
     */
    fun sampleSize(width: Int, height: Int, targetW: Int, targetH: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetW && height / (sample * 2) >= targetH) sample *= 2
        return sample
    }

    /**
     * Ordre de passage : toutes les photos une fois, mélangées, puis un nouveau tour. Une photo ne revient jamais
     * deux fois de suite, même d'un tour à l'autre.
     */
    class Shuffle(private val random: Random = Random.Default) {
        private var round = ArrayDeque<String>()
        private var last: String? = null

        fun next(names: List<String>): String? {
            if (names.isEmpty()) return null
            round.removeAll { it !in names }
            if (round.isEmpty()) {
                round = ArrayDeque(names.shuffled(random))
                if (round.size > 1 && round.first() == last) round.addLast(round.removeFirst())
            }
            return round.removeFirst().also { last = it }
        }
    }
}
