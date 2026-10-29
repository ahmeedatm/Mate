package com.unowhy.companion.focus

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.RawRes
import java.nio.ByteOrder

/**
 * Son d'ambiance (Opus en WebM) décodé en mono sur son propre fil, à la vitesse du décodeur (MediaCodec en mode
 * asynchrone : il prévient quand un tampon est prêt, aucune attente fixe) ; 48 kHz ramenés à 24 (moitié moins de
 * mémoire, assez pour une ambiance). La lecture part dès la première demi-seconde et lit [samples] jusqu'à [size]
 * pendant que le reste arrive ; [done] : fichier entier, [samples] se boucle. Décodeur rendu à la fin ou par [release],
 * toujours sur son propre fil : rendu d'ailleurs pendant qu'il livre un tampon, il fait tomber l'appli.
 */
class AmbianceDecoder(context: Context, @RawRes raw: Int) {

    private val extractor = MediaExtractor()
    private val thread = HandlerThread("ambiance").apply { start() }
    private val handler = Handler(thread.looper)
    private val codec: MediaCodec
    private var channels: Int
    private val halve: Boolean
    private var inputDone = false
    private var chunk = ShortArray(0)
    /** Échantillon en attente de son voisin quand on divise la fréquence par deux. */
    private var odd = 0
    private var pending = false

    val rate: Int

    /** Lire [size] avant [samples] : un tableau agrandi est publié avant la nouvelle taille. */
    @Volatile var samples: ShortArray
        private set

    @Volatile var size = 0
        private set

    @Volatile var done = false
        private set

    @Volatile var failed = false
        private set

    /** Décodeur rendu : les rappels encore en file ne le touchent plus (fil du décodeur seulement). */
    private var released = false

    init {
        context.resources.openRawResourceFd(raw).use { extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
        val format = extractor.getTrackFormat(0)
        extractor.selectTrack(0)
        val source = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        halve = source >= 44_100
        rate = if (halve) source / 2 else source
        // Taille connue d'avance : pas d'agrandissement pendant la lecture (une seconde de marge).
        val seconds = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1_000_000 + 1 else 180
        samples = ShortArray((seconds * rate).toInt())
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.setCallback(callback, handler)
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            failed = true
            free()
            throw e
        }
    }

    private val callback get() = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = guard {
            if (inputDone) return@guard
            val read = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
            if (read < 0) {
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
            } else {
                codec.queueInputBuffer(index, 0, read, extractor.sampleTime, 0)
                extractor.advance()
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) = guard {
            append(codec, index, info)
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                samples = samples.copyOf(size)
                done = true
                // Pas depuis un rappel du décodeur lui-même.
                handler.post { free() }
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.w("NoisePlayer", "décodage interrompu", e)
            failed = true
            handler.post { free() }
        }

        /** Rappel ignoré une fois le décodeur rendu ; une erreur arrête le décodage sans faire tomber l'appli. */
        private inline fun guard(block: () -> Unit) {
            if (released || failed) return
            try {
                block()
            } catch (e: Exception) {
                Log.w("NoisePlayer", "décodage interrompu", e)
                failed = true
                handler.post { free() }
            }
        }
    }

    private fun append(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val shorts = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
        shorts.position(info.offset / 2)
        shorts.limit((info.offset + info.size) / 2)
        val count = shorts.remaining()
        if (chunk.size < count) chunk = ShortArray(count)
        shorts.get(chunk, 0, count)
        var array = samples
        var written = size
        var i = 0
        while (i + channels <= count) {
            var sum = 0
            repeat(channels) { sum += chunk[i++] }
            val mono = sum / channels
            if (halve && !pending) {
                odd = mono
                pending = true
                continue
            }
            pending = false
            if (written == array.size) {
                array = array.copyOf(array.size + rate)
                samples = array
            }
            array[written++] = (if (halve) (odd + mono) / 2 else mono).toShort()
        }
        size = written
    }

    /** Décodage arrêté (autre ambiance, pause) : décodeur et fil rendus sur ce fil ; les échantillons déjà là restent lisibles. */
    fun release() {
        failed = failed || !done
        handler.post { free() }
    }

    private fun free() {
        if (released) return
        released = true
        runCatching { codec.release() }
        runCatching { extractor.release() }
        thread.quitSafely()
    }
}
