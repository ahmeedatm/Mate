package com.unowhy.companion.frame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.SystemClock
import android.util.Log
import com.unowhy.companion.Computers
import com.unowhy.companion.net.BackendClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Photos du cadre gardées sur la tablette, un dossier par ordinateur : copiées une fois, déjà réduites à la taille de
 * l'écran (et remises droites selon l'EXIF). Le cadre marche ordinateur éteint, et chaque passage ne décode qu'un
 * petit JPEG.
 */
object FrameCache {

    /** Dossier de l'ordinateur, dans le dossier partagé (backend/src/docs.ts, FRAME_DIR). */
    const val FOLDER = "Cadre photo"

    private const val TAG = "FrameCache"
    private const val SYNC_EVERY_MS = 15 * 60_000L
    private const val QUALITY = 85

    private val mutex = Mutex()
    private var lastSync = 0L
    private var lastComputer: String? = null

    private fun root(context: Context) = File(context.filesDir, "frame")

    private fun dir(context: Context): File? = Computers.active(context)?.let { File(root(context), it.id) }

    /** Photos prêtes de l'ordinateur actif (lecture du dossier : hors du thread principal). */
    fun photos(context: Context): List<File> =
        dir(context)?.listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }.orEmpty()

    /**
     * Recopie le dossier « Cadre photo » de l'ordinateur actif, au plus tous les quarts d'heure ([force] : tout de
     * suite). Ordinateur injoignable : le cache reste tel quel. Renvoie le nombre de photos prêtes.
     */
    suspend fun sync(context: Context, force: Boolean = false): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Taille de l'écran en paysage (la tablette l'est toujours en kiosque).
            val metrics = context.resources.displayMetrics
            val screenW = maxOf(metrics.widthPixels, metrics.heightPixels)
            val screenH = minOf(metrics.widthPixels, metrics.heightPixels)
            val computer = Computers.active(context)?.id
            val dir = dir(context)
            val due = force || computer != lastComputer || SystemClock.elapsedRealtime() - lastSync > SYNC_EVERY_MS
            if (dir != null && due) {
                runCatching { download(context, dir, screenW, screenH) }
                    .onSuccess { lastSync = SystemClock.elapsedRealtime(); lastComputer = computer }
                    .onFailure { Log.i(TAG, "synchro du cadre impossible : ${it.javaClass.simpleName}") }
            }
            forgetRemovedComputers(context)
            photos(context).size
        }
    }

    private suspend fun download(context: Context, dir: File, screenW: Int, screenH: Int) {
        val docs = BackendClient(context).docs
        val remote = docs.framePhotos()
        dir.mkdirs()
        val cached = dir.listFiles { f -> f.name.endsWith(".jpg") }?.map { it.name }?.toSet().orEmpty()
        val plan = FrameLogic.plan(remote, cached)
        plan.delete.forEach { File(dir, it).delete() }
        val temp = File(context.cacheDir, "frame-download")
        for (photo in plan.download) {
            try {
                docs.downloadDoc(photo.path, temp)
                val bitmap = decode(temp, screenW, screenH) ?: continue
                val part = File(dir, FrameLogic.cacheName(photo) + ".part")
                part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                bitmap.recycle()
                part.renameTo(File(dir, FrameLogic.cacheName(photo)))
            } finally {
                temp.delete()
            }
        }
    }

    /** Photo de l'ordinateur ramenée à la taille gardée en cache, droite ; null si ce n'est pas une image lisible. */
    private fun decode(file: File, screenW: Int, screenH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val rotation = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
        val turned = rotation % 180 != 0
        val w = if (turned) bounds.outHeight else bounds.outWidth
        val h = if (turned) bounds.outWidth else bounds.outHeight
        val (targetW, targetH) = FrameLogic.scaledSize(w, h, screenW, screenH)
        val options = BitmapFactory.Options().apply { inSampleSize = FrameLogic.sampleSize(w, h, targetW, targetH) }
        val sampled = BitmapFactory.decodeFile(file.path, options) ?: return null
        val sw = if (turned) sampled.height else sampled.width
        val sh = if (turned) sampled.width else sampled.height
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(targetW.toFloat() / sw, targetH.toFloat() / sh)
        }
        val result = Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, true)
        if (result !== sampled) sampled.recycle()
        return result
    }

    /** Ordinateur oublié sur la tablette : ses photos partent avec lui. */
    private fun forgetRemovedComputers(context: Context) {
        val ids = Computers.all(context).map { it.id }.toSet()
        root(context).listFiles()?.filter { it.isDirectory && it.name !in ids }?.forEach { it.deleteRecursively() }
    }
}
