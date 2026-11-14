package app.mate.ui.cyberpunk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.mate.net.CyberpunkApi
import app.mate.net.CyberpunkPhoneLook
import app.mate.net.CyberpunkPhoneStatus
import app.mate.net.CyberpunkPoi
import app.mate.net.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Où en sont les icônes du jeu : `UNKNOWN` = l'ordinateur ne le dit pas (compagnon plus ancien), rien à proposer. */
enum class PoiIconsState { UNKNOWN, MISSING, LOADING, READY }

/**
 * Icônes des points d'intérêt : masques blancs `poi_<icône>.png` servis comme les pièces du téléphone, décodés une fois à la taille
 * d'affichage, gardés dans leur propre dossier du cache de l'appli. Une icône absente ou illisible se dessine en rond.
 */
object PoiIcons {
    private const val PREFIX = "poi_"
    private const val CACHE_DIR = "cyberpunk-poi"
    private const val STAMP = "assets.txt"

    fun fileName(icon: String) = "$PREFIX$icon.png"

    fun state(look: CyberpunkPhoneLook?): PoiIconsState = when {
        look == null -> PoiIconsState.UNKNOWN
        look.status == CyberpunkPhoneStatus.LOADING -> PoiIconsState.LOADING
        look.status == CyberpunkPhoneStatus.READY && look.assets.any { it.startsWith(PREFIX) } -> PoiIconsState.READY
        else -> PoiIconsState.MISSING
    }

    /** Noms d'icônes (sans préfixe) utilisés par les points et servis par l'ordinateur. */
    fun wanted(points: List<CyberpunkPoi>, available: List<String>): Set<String> {
        val served = available.toSet()
        return points.mapNotNull { it.icon }.filter { fileName(it) in served }.toSet()
    }

    /** Icônes voulues qui ne sont pas déjà décodées en mémoire. */
    fun missing(wanted: Set<String>, decoded: Set<String>): Set<String> = wanted - decoded

    /** Une requête au plus par icône : celles déjà en cache disque ne sont pas redemandées. Clé du résultat : nom d'icône sans préfixe. */
    suspend fun load(context: Context, api: CyberpunkApi, available: List<String>, icons: Set<String>, sizePx: Int): Map<String, Bitmap> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        // Icônes ré-extraites (jeu mis à jour) : la liste change, le cache repart de zéro.
        val stamp = File(dir, STAMP)
        val list = available.filter { it.startsWith(PREFIX) }.sorted().joinToString("\n")
        if (runCatching { stamp.readText() }.getOrNull() != list) {
            dir.listFiles()?.forEach { it.delete() }
            stamp.writeText(list)
        }
        icons.mapNotNull { icon -> fetch(dir, api, fileName(icon))?.let { decode(it, sizePx) }?.let { icon to it } }.toMap()
    }

    private suspend fun fetch(dir: File, api: CyberpunkApi, name: String): File? {
        val file = File(dir, name)
        if (file.length() > 0) return file
        // Fichier temporaire puis renommage : une coupure ne laisse jamais une icône tronquée en cache.
        val tmp = File(dir, "$name.tmp")
        return runCatchingCancellable { api.phoneAsset(name) }
            .mapCatching { tmp.writeBytes(it); check(tmp.renameTo(file)); file }
            .onFailure { tmp.delete() }.getOrNull()
    }

    private fun decode(file: File, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val side = maxOf(bounds.outWidth, bounds.outHeight)
        if (side <= 0) return null
        var sample = 1
        while (side / (sample * 2) >= sizePx) sample *= 2
        val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val ratio = sizePx.toFloat() / maxOf(raw.width, raw.height)
        if (ratio == 1f) return raw
        return Bitmap.createScaledBitmap(raw, maxOf(1, (raw.width * ratio).toInt()), maxOf(1, (raw.height * ratio).toInt()), true).also { if (it !== raw) raw.recycle() }
    }
}
