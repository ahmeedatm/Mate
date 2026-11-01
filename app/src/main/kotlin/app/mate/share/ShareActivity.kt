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
import app.mate.UserFacingException
import app.mate.net.BackendClient
import app.mate.ui.errorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * « Partager » > Mate : dépose les fichiers (PDF, image…) ou le lien reçus dans le dossier partagé de l'ordinateur
 * actif. Fenêtre transparente, fermée dès l'envoi fini ; les droits de lecture des fichiers durent tant qu'elle vit.
 */
class ShareActivity : AppCompatActivity() {

    private class Item(val file: File, val mimeType: String)

    private class TooBig(val name: String) : Exception()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recréée par le système (processus tué) : l'envoi d'avant est perdu, pas de relance à l'aveugle.
        if (savedInstanceState != null) return finish()
        val computer = Computers.active(this)
        if (computer == null) return finishWith(getString(R.string.error_no_computer))
        val received = streams(intent)
        // Seulement des fichiers d'une autre appli (content://, jamais ceux de Mate), 10 au plus.
        val uris = ShareLogic.limitCount(received.filter { ShareLogic.acceptsUri(it.scheme, it.authority) }).first
        val refused = received.size - uris.size
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { received.isEmpty() && ShareLogic.isLink(it) }
        if (uris.isEmpty() && text == null) {
            return finishWith(getString(if (refused > 0) R.string.share_refused else R.string.share_nothing, refused, ShareLogic.MAX_FILES))
        }
        // Un sous-dossier par lancement : deux partages en même temps ne se mêlent ni ne s'effacent.
        val dir = File(cacheDir, "partage/${UUID.randomUUID()}")

        Toast.makeText(this, getString(R.string.share_sending, computer.name), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            var sent = 0
            val message = try {
                val items = withContext(Dispatchers.IO) { collect(dir, uris, text) }
                val names = items.map { send(it).also { sent++ } }
                if (names.size == 1) getString(R.string.share_sent, names[0]) else getString(R.string.share_sent_many, names.size)
            } catch (e: TooBig) {
                getString(R.string.share_too_big, e.name)
            } catch (e: Exception) {
                val reason = errorText(e)
                // Un envoi en plusieurs fichiers a pu partir en partie : le dire, pour ne pas les renvoyer en double.
                if (sent > 0) getString(R.string.share_partial, sent, uris.size, reason) else reason
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
            }
            finishWith(if (refused > 0) "$message ${getString(R.string.share_refused, refused, ShareLogic.MAX_FILES)}" else message)
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

    /** Copie chaque fichier reçu (ou le lien) dans [dir], sous son nom, en refusant ceux qui dépassent 20 Mo. */
    private fun collect(dir: File, uris: List<Uri>, link: String?): List<Item> {
        dir.mkdirs()
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
            // L'autre appli a pu retirer le droit de lecture, ou le fichier : message à part, pas « ordinateur injoignable ».
            try {
                val input = contentResolver.openInputStream(uri) ?: throw UserFacingException(R.string.share_unreadable, fileName)
                // Copie bornée : s'arrête dès que le fichier dépasse 20 Mo, sans lire la fin d'un flux sans fin.
                input.use { src -> file.outputStream().use { ShareLogic.copyBounded(src, it) } }
            } catch (e: ShareLogic.TooLarge) {
                throw TooBig(fileName)
            } catch (e: SecurityException) {
                throw UserFacingException(R.string.share_unreadable, fileName)
            } catch (e: FileNotFoundException) {
                throw UserFacingException(R.string.share_unreadable, fileName)
            }
            Item(file, mime)
        }
    }

    private suspend fun send(item: Item): String = BackendClient(this).docs.share(item.file, item.mimeType)
}
