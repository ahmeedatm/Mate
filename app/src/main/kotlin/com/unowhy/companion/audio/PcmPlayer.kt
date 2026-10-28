package com.unowhy.companion.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/** Joue un petit son déjà calculé (mono, 16 bits), puis libère la piste : les sons de l'appli sont fabriqués à la volée, sans fichier. */
object PcmPlayer {
    fun play(data: ShortArray, rate: Int) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(data.size * 2)
            .build()
        try {
            track.write(data, 0, data.size)
            track.play()
        } catch (e: IllegalStateException) {
            track.release()
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({ track.release() }, data.size * 1000L / rate + 200)
    }
}
