package app.mate.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Modèle français de Vosk pour le mot de réveil (`vosk-model-small-fr-0.22`, 42 Mo zippé, 68 Mo décompressé) : pas
 * dans l'APK (chaque mise à jour le retéléchargerait), téléchargé une fois depuis alphacephei.com à la première
 * activation, empreinte SHA-256 vérifiée, décompressé dans les fichiers de l'appli. Prêt seulement après une
 * décompression complète : dossier temporaire puis renommage. Un échec laisse [State.Failed] ; [ensure] réessaie
 * (au prochain passage de Mate à l'écran). Téléchargement hors du fil principal, indépendant de l'activité.
 */
object WakeModel {
    private const val TAG = "WakeModel"
    const val NAME = "vosk-model-small-fr-0.22"
    private const val URL_ZIP = "https://alphacephei.com/vosk/models/$NAME.zip"
    /** Empreinte du zip relevée le 29/10/2026 (42 233 323 octets). */
    private const val SHA256 = "cabf6180e177eb9b3a9a9d43a437bd5e549f3a7d09525e5d69a3fed787be12ad"

    sealed interface State {
        /** Pas encore téléchargé. */
        data object Absent : State
        data class Downloading(val percent: Int) : State
        data object Ready : State
        /** Téléchargement, empreinte ou décompression ratés : nouvel essai au prochain [ensure]. */
        data object Failed : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow<State>(State.Absent)
    val state: StateFlow<State> = _state

    /** Dossier du modèle, à donner à `org.vosk.Model` ; n'existe qu'une fois le modèle complet. */
    fun dir(context: Context) = File(context.filesDir, NAME)

    /** État lu sur le disque (au premier appel) : prêt si le dossier final existe. */
    fun refresh(context: Context): State {
        if (_state.value == State.Absent && dir(context).isDirectory) _state.value = State.Ready
        return _state.value
    }

    /** Télécharge le modèle s'il manque et qu'aucun téléchargement n'est en cours (fil principal). */
    @Synchronized
    fun ensure(context: Context) {
        if (refresh(context) == State.Ready || job?.isActive == true) return
        val files = context.applicationContext.filesDir
        _state.value = State.Downloading(0)
        job = scope.launch {
            _state.value = try {
                install(files)
                State.Ready
            } catch (e: Exception) {
                Log.w(TAG, "modèle vocal indisponible", e)
                State.Failed
            }
        }
    }

    private fun install(files: File) {
        val zip = File(files, "$NAME.zip.part")
        val tmp = File(files, "$NAME.tmp")
        try {
            tmp.deleteRecursively()
            download(zip)
            unzip(zip, tmp)
            val root = File(tmp, NAME)
            if (!File(root, "am/final.mdl").isFile) throw IOException("modèle incomplet")
            if (!root.renameTo(File(files, NAME))) throw IOException("renommage impossible")
        } finally {
            zip.delete()
            tmp.deleteRecursively()
        }
    }

    /** Écrit le zip dans `zip` en calculant son empreinte au passage ; refuse un fichier qui n'est pas le bon. */
    private fun download(zip: File) {
        val connection = URL(URL_ZIP).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var shown = 0
            connection.inputStream.use { input ->
                zip.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        val percent = if (total > 0) (read * 100 / total).toInt().coerceAtMost(99) else 0
                        if (percent != shown) {
                            shown = percent
                            _state.value = State.Downloading(percent)
                        }
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != SHA256) throw IOException("empreinte inattendue $hash")
        } finally {
            connection.disconnect()
        }
    }

    private fun unzip(zip: File, target: File) {
        if (!target.mkdirs()) throw IOException("dossier temporaire impossible")
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val out = WakeLogic.zipTarget(target, entry.name) ?: throw IOException("entrée hors du dossier : ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }
}
