package com.unowhy.companion.ui.home

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.text.Normalizer
import java.util.Locale

/**
 * Recherche de ville de la météo, sans service en ligne : liste GeoNames embarquée (assets/villes.tsv, construite par
 * scripts/villes.mjs, licence CC BY 4.0 citée dans Réglages > À propos). Lue à la première recherche, libérée par
 * le système quand la mémoire manque (24 000 villes environ).
 */
object Cities {
    private const val ASSET = "villes.tsv"

    /**
     * Une ligne du fichier : nom, région, pays, latitude, longitude, population, clé. Seuls le nom et la clé sont lus
     * au chargement, le reste à l'affichage (la tablette met plusieurs secondes à découper 24 000 lignes entières).
     * `key` : nom sans accent ni ponctuation, précalculé par le script. Liste triée par population décroissante.
     */
    class City(private val line: String, val name: String, val key: String) {
        private val columns by lazy { line.split('\t') }
        val region get() = columns[1]
        val country get() = columns[2]
        val lat get() = columns[3].toDouble()
        val lon get() = columns[4].toDouble()
    }

    @Volatile private var cache = WeakReference<List<City>>(null)

    private fun load(context: Context): List<City> =
        cache.get() ?: context.assets.open(ASSET).bufferedReader().useLines { parse(it) }.also { cache = WeakReference(it) }

    /** À l'ouverture de « Choisir ma ville » : la liste est prête quand la saisie est finie. */
    suspend fun preload(context: Context) = withContext(Dispatchers.IO) { load(context) }

    /** Les villes qui commencent par `query` (ou dont un mot commence par `query`), les plus peuplées d'abord. */
    suspend fun search(context: Context, query: String, limit: Int = 6): List<Weather.Place> = withContext(Dispatchers.IO) {
        search(load(context), query, limit).map { Weather.Place(it.name, label(it), it.lat, it.lon) }
    }

    /**
     * Ville la plus proche d'une position (détection automatique de la météo), sans service en ligne. Trop loin de
     * toute ville de la liste (plus de [NEAR_KM]) : la position elle-même, arrondie à 0,1° (environ 10 km), sous le
     * nom de la plus proche.
     */
    suspend fun nearest(context: Context, lat: Double, lon: Double): Weather.Place? = withContext(Dispatchers.IO) {
        val city = nearest(load(context), lat, lon) ?: return@withContext null
        if (distanceKm(lat, lon, city.lat, city.lon) <= NEAR_KM) {
            Weather.Place(city.name, label(city), city.lat, city.lon)
        } else {
            Weather.Place(city.name, label(city), Math.round(lat * 10) / 10.0, Math.round(lon * 10) / 10.0)
        }
    }

    private const val NEAR_KM = 30.0

    fun nearest(all: List<City>, lat: Double, lon: Double): City? = all.minByOrNull { distanceKm(lat, lon, it.lat, it.lon) }

    /** Distance approchée (projection équirectangulaire) : largement assez juste à l'échelle d'une ville. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2))
        val y = Math.toRadians(lat2 - lat1)
        return Math.sqrt(x * x + y * y) * 6371.0
    }

    /** Lignes invalides (moins de 5 colonnes, coordonnées illisibles) ignorées ; clé calculée ici si la colonne manque. */
    fun parse(lines: Sequence<String>): List<City> = lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
        val name = line.substringBefore('\t')
        val tabs = line.count { it == '\t' }
        when {
            tabs >= 6 -> City(line, name, line.substringAfterLast('\t'))
            tabs >= 4 && line.split('\t').let { it[3].toDoubleOrNull() != null && it[4].toDoubleOrNull() != null } -> City(line, name, normalize(name))
            else -> null
        }
    }.toList()

    /** Nom exact d'abord, puis début du nom, puis début d'un des mots (« Clermont » trouve « Clermont-Ferrand »). */
    fun search(all: List<City>, query: String, limit: Int = 6): List<City> {
        val q = normalize(query)
        if (q.length < 2) return emptyList()
        // Un seul passage sur la liste (environ 200 ms sur la tablette pour 23 000 villes).
        val exact = ArrayList<City>()
        val start = ArrayList<City>()
        val word = ArrayList<City>()
        val inWord = " $q"
        for (city in all) {
            when {
                city.key == q -> exact += city
                city.key.startsWith(q) -> if (start.size < limit) start += city
                word.size < limit && city.key.contains(inWord) -> word += city
            }
        }
        return (exact + start + word).take(limit)
    }

    /** « Brest · Bretagne, France » ; pas de région quand elle répète le nom. */
    fun label(city: City): String {
        val country = Locale("", city.country).getDisplayCountry(Locale.FRENCH).ifBlank { city.country }
        val where = listOf(city.region.takeIf { it.isNotBlank() && it != city.name }, country).filterNotNull().joinToString(", ")
        return "${city.name} · $where"
    }

    private val MARKS = Regex("\\p{Mn}+")
    private val PUNCTUATION = Regex("[-'’.]")
    private val SPACES = Regex("\\s+")

    /** « Saint-Étienne » et « saint etienne » donnent la même clé (même calcul que `key` de scripts/villes.mjs). */
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(MARKS, "")
        .lowercase(Locale.ROOT)
        .replace(PUNCTUATION, " ")
        .replace(SPACES, " ")
        .trim()
}
