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
 * Pièces du téléphone du jeu, extraites par l'ordinateur et gardées dans le cache de l'appli (jamais dans l'APK) :
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

        /** Côté le plus court gardé à 64 px au moins : assez net pour un cadre de 420 dp, sans garder le 1080p. */
        private const val MIN_SIDE_PX = 64

        /** Pas plus d'une requête par pièce : celles déjà en cache disque ne sont pas redemandées. */
        suspend fun load(context: Context, api: CyberpunkApi, available: List<String>): PhoneLook = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }

            suspend fun fetch(name: String): File? {
                if (name !in available) return null
                val file = File(dir, name)
                if (file.length() > 0) return file
                return runCatchingCancellable { api.phoneAsset(name) }
                    .onSuccess { file.writeBytes(it) }.map { file }.getOrNull()
            }

            val bitmaps = PIECES.mapNotNull { name -> fetch(name)?.let { decode(it) }?.let { name to it } }.toMap()
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

        private fun decode(file: File): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val side = minOf(bounds.outWidth, bounds.outHeight)
            if (side <= 0) return null
            var sample = 1
            while (side / (sample * 2) >= MIN_SIDE_PX) sample *= 2
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
