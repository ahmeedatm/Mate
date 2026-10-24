package com.unowhy.companion.audio

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/** Enregistrement micro en AAC/m4a mono 16 kHz : petit fichier, accepté par les services de transcription. */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var maxReached = false

    val isRecording: Boolean get() = recorder != null

    /** [onMaxDuration] est appelé si la minute est atteinte : il faut alors appeler [stop]. */
    fun start(onMaxDuration: () -> Unit) {
        val file = File(context.cacheDir, "voice.m4a")
        maxReached = false
        @Suppress("DEPRECATION") // le constructeur avec Context n'existe qu'à partir de l'API 31
        val r = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(16_000)
            setAudioEncodingBitRate(32_000)
            setMaxDuration(60_000) // garde-fou : 1 min max par question
            setOutputFile(file.absolutePath)
            setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    maxReached = true
                    onMaxDuration()
                }
            }
            prepare()
            start()
        }
        recorder = r
        output = file
    }

    /** Arrête l'enregistrement et renvoie le fichier, ou null si trop court / en échec. */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        return try {
            r.stop()
            output
        } catch (e: RuntimeException) {
            // stop() lève une exception si rien n'a été capté, ou si l'enregistreur s'est
            // déjà arrêté seul à la durée max (le fichier est alors complet).
            if (maxReached) output else null
        } finally {
            r.release()
        }
    }

    fun release() {
        recorder?.release()
        recorder = null
    }

    companion object {
        const val MIME_TYPE = "audio/mp4"
    }
}
