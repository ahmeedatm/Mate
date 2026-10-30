package app.mate.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

/**
 * Enregistre un prompt dicté pour une session Claude (transcrit ensuite par whisper.cpp sur l'ordinateur) : 16 kHz mono,
 * fil à part, arrêt seul au silence ([SilenceGate]) ou par [stop]. Micro pris par [Mic] pendant l'écoute (le mot de
 * réveil le rend). `onEnd` (fil principal) reçoit le WAV, ou null si rien n'a été enregistré ; jamais après [cancel].
 * `onLevel` (fil principal, tous les 100 ms) : niveau de la voix entre 0 et 1 ([PromptAudio.level]).
 */
class PromptRecorder(private val activity: Activity) {

    @Volatile private var stopping = false
    @Volatile private var cancelled = false
    private var thread: Thread? = null

    val recording get() = thread != null

    /** `false` : micro pas autorisé ou indisponible ; rien n'est lancé. */
    fun start(onLevel: (Float) -> Unit = {}, onEnd: (ByteArray?) -> Unit): Boolean {
        if (thread != null) return false
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        stopping = false
        cancelled = false
        // Micro pris avant d'ouvrir l'enregistrement : le mot de réveil s'arrête tout de suite.
        Mic.hold(Mic.DICTATION)
        val record = open()
        if (record == null) {
            Mic.release(Mic.DICTATION)
            return false
        }
        thread = Thread({
            val pcm = capture(record, onLevel)
            activity.runOnUiThread {
                thread = null
                Mic.release(Mic.DICTATION)
                if (!cancelled) onEnd(pcm?.let { PromptAudio.wav(it) })
            }
        }, "prompt-recorder").also { it.start() }
        return true
    }

    /** Finit l'écoute avec ce qui est déjà dit. */
    fun stop() {
        stopping = true
    }

    /** Abandonne : rien n'est rendu. */
    fun cancel() {
        cancelled = true
        stopping = true
    }

    private fun open(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(PromptAudio.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                PromptAudio.RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, PromptAudio.FRAME_SAMPLES * 2 * 4),
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "micro indisponible", e)
            return null
        } catch (e: SecurityException) {
            Log.w(TAG, "micro refusé", e)
            return null
        }
        if (record.state == AudioRecord.STATE_INITIALIZED) return record
        record.release()
        return null
    }

    /** Échantillons enregistrés (petit-boutiste), null si aucun. */
    private fun capture(record: AudioRecord, onLevel: (Float) -> Unit): ByteArray? {
        val out = ByteArrayOutputStream()
        val gate = SilenceGate()
        val frame = ShortArray(PromptAudio.FRAME_SAMPLES)
        try {
            record.startRecording()
            while (!stopping) {
                var n = 0
                while (n < frame.size && !stopping) {
                    val read = record.read(frame, n, frame.size - n)
                    if (read <= 0) break
                    n += read
                }
                // Micro coupé en route (ou erreur de lecture) : on garde ce qui est déjà là.
                if (n < frame.size && !stopping) break
                for (i in 0 until n) {
                    out.write(frame[i].toInt() and 0xff)
                    out.write(frame[i].toInt() shr 8 and 0xff)
                }
                val rms = PromptAudio.rms(frame, n)
                val level = PromptAudio.level(rms)
                activity.runOnUiThread { if (!stopping) onLevel(level) }
                if (gate.feed(rms) == SilenceGate.Verdict.DONE) break
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "enregistrement interrompu", e)
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        return out.toByteArray().takeIf { it.isNotEmpty() && !cancelled }
    }

    private companion object {
        const val TAG = "PromptRecorder"
    }
}
