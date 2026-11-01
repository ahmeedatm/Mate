package app.mate.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.mate.Computers
import app.mate.R
import app.mate.net.BackendClient
import app.mate.ui.errorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * « Partager » > Mate : dépose les fichiers (PDF, image…) ou le lien reçus dans le dossier partagé de l'ordinateur
 * actif. Fenêtre transparente, fermée dès l'envoi fini ; les droits de lecture des fichiers durent tant qu'elle vit.
 */
class ShareActivity : AppCompatActivity() {

    private class Item(val file: File, val mimeType: String)

    private class TooBig(val name: String) : Exception()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val computer = Computers.active(this)
        if (computer == null) return finishWith(getString(R.string.error_no_computer))
        val uris = streams(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { uris.isEmpty() && ShareLogic.isLink(it) }
        if (uris.isEmpty() && text == null) return finishWith(getString(R.string.share_nothing))

        Toast.makeText(this, getString(R.string.share_sending, computer.name), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val message = try {
                val sent = withContext(Dispatchers.IO) { collect(uris, text) }.map { send(it) }
                if (sent.size == 1) getString(R.string.share_sent, sent[0]) else getString(R.string.share_sent_many, sent.size)
            } catch (e: TooBig) {
                getString(R.string.share_too_big, e.name)
            } catch (e: Exception) {
                errorText(e)
            }
            finishWith(message)
        }
    }

    private fun finishWith(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        finish()
    }

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> {
        val fromClip = (0 until (intent.clipData?.itemCount ?: 0)).mapNotNull { intent.clipData?.getItemAt(it)?.uri }
        if (fromClip.isNotEmpty()) return fromClip
        return if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        } else {
            listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        }
    }

    /** Copie chaque fichier reçu (ou le lien) dans le cache, sous son nom, en refusant ceux qui dépassent 20 Mo. */
    private fun collect(uris: List<Uri>, link: String?): List<Item> {
        val dir = File(cacheDir, "partage").apply { deleteRecursively(); mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
        if (link != null) {
            return listOf(Item(File(dir, ShareLogic.linkFileName(stamp)).apply { writeText(link) }, "text/plain"))
        }
        return uris.mapIndexed { i, uri ->
            var name: String? = null
            var size = -1L
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) {
                    name = it.getString(0)
                    size = if (it.isNull(1)) -1L else it.getLong(1)
                }
            }
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            val fileName = ShareLogic.fileName(name, mime, stamp)
            if (size >= 0 && !ShareLogic.fits(size)) throw TooBig(fileName)
            // Un dossier par fichier : deux fichiers de même nom ne s'écrasent pas dans le cache.
            val file = File(File(dir, i.toString()).apply { mkdirs() }, fileName)
            val input = contentResolver.openInputStream(uri) ?: throw java.io.IOException("Lecture impossible : $fileName")
            input.use { src -> file.outputStream().use { src.copyTo(it) } }
            if (!ShareLogic.fits(file.length())) throw TooBig(fileName)
            Item(file, mime)
        }
    }

    private suspend fun send(item: Item): String = BackendClient(this).docs.share(item.file, item.mimeType)
}
