package app.mate.ui.home

import androidx.annotation.AttrRes
import androidx.annotation.StringRes
import app.mate.R
import org.json.JSONObject
import java.util.Locale

/**
 * Qualité de l'air de la ville du widget Météo, lue chez Open-Meteo (gratuit, sans clé ni compte, données CAMS du
 * programme Copernicus, licence CC BY 4.0 citée dans Réglages > À propos) : l'indice européen (EAQI, de 0 à 100 et plus).
 * Même rythme que la météo ([Weather.REFRESH_MS]).
 * shortcut: Open-Meteo interdit l'usage commercial en gratuit, à remplacer si Mate devient commercial.
 */
object AirQuality {
    /** Les tranches de l'indice européen, avec la couleur du thème et le libellé de chacune. */
    enum class Level(@AttrRes val color: Int, @StringRes val text: Int) {
        GOOD(R.attr.c_ok, R.string.air_good),
        FAIR(R.attr.c_hue_yellow, R.string.air_fair),
        MODERATE(R.attr.c_warn, R.string.air_moderate),
        POOR(R.attr.c_ko, R.string.air_poor),
        VERY_POOR(R.attr.c_hue_violet, R.string.air_very_poor),
    }

    /** Indice européen : 0-20 bon, 20-40 correct, 40-60 moyen, 60-80 mauvais, au-delà très mauvais. */
    fun levelOf(index: Int): Level = when {
        index <= 20 -> Level.GOOD
        index <= 40 -> Level.FAIR
        index <= 60 -> Level.MODERATE
        index <= 80 -> Level.POOR
        else -> Level.VERY_POOR
    }

    /** Indice de la mesure actuelle, arrondi ; null quand le service n'a pas de mesure pour ce lieu. */
    fun parse(json: JSONObject): Int? {
        val current = json.optJSONObject("current") ?: return null
        if (current.isNull("european_aqi")) return null
        return Math.round(current.getDouble("european_aqi")).toInt()
    }

    suspend fun index(lat: Double, lon: Double): Int? = parse(
        Weather.get(
            String.format(
                Locale.ROOT,
                "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.4f&longitude=%.4f&current=european_aqi",
                lat,
                lon,
            ),
        ),
    )
}
