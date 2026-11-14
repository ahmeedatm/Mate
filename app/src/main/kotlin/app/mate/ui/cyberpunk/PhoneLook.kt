package app.mate.ui.cyberpunk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import app.mate.net.CyberpunkApi
import app.mate.net.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pièces du look du jeu (téléphone, écran cyberware), extraites par l'ordinateur et gardées dans le cache de l'appli (jamais dans l'APK) :
 * masques blancs teintés en `cp_*` par l'affichage, et les deux polices. Une pièce illisible est simplement absente
 * (le tiroir retombe sur ses cadres et sur Chakra Petch).
 */
class PhoneLook(private val bitmaps: Map<String, Bitmap>, val title: Typeface?, val body: Typeface?) {
    fun bitmap(name: String): Bitmap? = bitmaps[name]

    companion object {
        val PIECES = listOf(
            "frame_message.png", "msgBuble_bg.png", "msgBuble_fg.png", "msgBuble_reply_bg.png", "msgBuble_reply_fg.png",
            "icon_message.png", "phone_icon.png",
        )
        const val TITLE_FONT = "raj-bold.ttf"
        const val BODY_FONT = "blenderpro-book.ttf"
        private const val CACHE_DIR = "cyberpunk-phone"
        private const val STAMP = "assets.txt"

        /** Côté le plus court gardé à 64 px au moins : assez net pour un cadre de 420 dp, sans garder le 1080p. */
        private const val MIN_SIDE_PX = 64

        /**
         * Pas plus d'une requête par pièce : celles déjà en cache disque ne sont pas redemandées. `pieces` : les images à
         * décoder (le téléphone par défaut) ; `minSidePx` : côté le plus court gardé au décodage.
         */
        suspend fun load(
            context: Context,
            api: CyberpunkApi,
            available: List<String>,
            pieces: List<String> = PIECES,
            minSidePx: Int = MIN_SIDE_PX,
        ): PhoneLook = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
            // Pièces ré-extraites ailleurs (autre tablette, jeu mis à jour) : la liste change, le cache repart de zéro.
            val stamp = File(dir, STAMP)
            val list = available.sorted().joinToString("\n")
            if (runCatching { stamp.readText() }.getOrNull() != list) {
                dir.listFiles()?.forEach { it.delete() }
                stamp.writeText(list)
            }

            suspend fun fetch(name: String): File? {
                if (name !in available) return null
                val file = File(dir, name)
                if (file.length() > 0) return file
                // Fichier temporaire puis renommage : une coupure ne laisse jamais une pièce tronquée en cache.
                val tmp = File(dir, "$name.tmp")
                return runCatchingCancellable { api.phoneAsset(name) }
                    .mapCatching { tmp.writeBytes(it); check(tmp.renameTo(file)); file }
                    .onFailure { tmp.delete() }.getOrNull()
            }

            val bitmaps = pieces.mapNotNull { name -> fetch(name)?.let { decode(it, minSidePx) }?.let { name to it } }.toMap()
            PhoneLook(
                bitmaps,
                fetch(TITLE_FONT)?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() },
                fetch(BODY_FONT)?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() },
            )
        }

        /** Une nouvelle préparation remplace les pièces : le cache disque est vidé. */
        fun clearCache(context: Context) {
            File(context.cacheDir, CACHE_DIR).listFiles()?.forEach { it.delete() }
        }

        private fun decode(file: File, minSidePx: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val side = minOf(bounds.outWidth, bounds.outHeight)
            if (side <= 0) return null
            var sample = 1
            while (side / (sample * 2) >= minSidePx) sample *= 2
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
