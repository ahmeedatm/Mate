package app.mate.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/**
 * Fait sonner la tablette pour la retrouver (ordre `ring`). Le volume d'alarme est monté au maximum le temps de la
 * sonnerie, puis remis comme avant ; un toucher sur l'écran, l'ordre d'arrêt ou [RingTone.AUTO_STOP_MS] l'arrête.
 */
object Ringer {
    private var track: AudioTrack? = null
    private var savedVolume = 0
    private var audio: AudioManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

    val isRinging: Boolean get() = track != null

    fun start(context: Context) {
        if (track != null) return
        val manager = context.applicationContext.getSystemService(AudioManager::class.java)
        val data = RingTone.samples()
        val built = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RingTone.RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(data.size * 2)
            .build()
        try {
            built.write(data, 0, data.size)
            built.setLoopPoints(0, data.size, -1)
            savedVolume = manager.getStreamVolume(AudioManager.STREAM_ALARM)
            manager.setStreamVolume(AudioManager.STREAM_ALARM, manager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            built.play()
        } catch (e: IllegalStateException) {
            built.release()
            throw e
        }
        audio = manager
        track = built
        handler.postDelayed(autoStop, RingTone.AUTO_STOP_MS)
    }

    fun stop() {
        handler.removeCallbacks(autoStop)
        val current = track ?: return
        track = null
        current.stop()
        current.release()
        audio?.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
        audio = null
    }
}
