package app.mate.ui

import java.io.File

/** Lecture des documents de l'ordinateur sans dépasser la mémoire de la tablette (tas de 128 Mo), testée sans Android. */
object DocReading {

    /** 4 Mpx : 16 Mo en ARGB, sous la limite de dessin d'un bitmap. */
    const val MAX_PIXELS = 4_000_000L

    /** Les `max` premiers caractères, sans lire le reste du fichier (un `.log` de plusieurs centaines de Mo). */
    fun readStart(file: File, max: Int): String = file.bufferedReader().use { reader ->
        val buffer = CharArray(max)
        var count = 0
        while (count < max) {
            val n = reader.read(buffer, count, max - count)
            if (n < 0) break
            count += n
        }
        String(buffer, 0, count)
    }

    /** Réduction au décodage (puissance de 2) : largeur ramenée près de `targetWidth`, et jamais plus de [MAX_PIXELS]. */
    fun sampleSize(width: Int, height: Int, targetWidth: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetWidth || (width.toLong() / sample) * (height.toLong() / sample) > MAX_PIXELS) sample *= 2
        return sample
    }
}
