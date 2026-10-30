package app.mate.ui.home

import android.content.Context
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import app.mate.R
import app.mate.focus.Water
import app.mate.ui.RollingText
import app.mate.ui.WaterSettings
import app.mate.ui.dp
import app.mate.ui.themeColor
import app.mate.ui.themeFont
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Météo et eau à droite du bandeau de l'Accueil ([HeroWidget], [DayWidget]), dans `view_hero_compl` : ville réglable
 * par ⚙ en mode Modifier (toucher la météo sans ville la demande), toucher l'eau = la boîte Eau (l'appui long appartient
 * à la grille, pour le mode Modifier).
 */
class HeroComplications(private val context: Context, private val host: HomeHost, private val placed: Placed, root: View) {
    private val wxTemp = root.findViewById<TextView>(R.id.hero_wx_temp)
    private val wxLabel = root.findViewById<TextView>(R.id.hero_wx_label)
    private val glasses = root.findViewById<GlassBars>(R.id.hero_glasses)
    private val glassesCount = root.findViewById<RollingText>(R.id.hero_glasses_count)
    private val glassesLabel = root.findViewById<TextView>(R.id.hero_glasses_label)
    private val waterListener: (Water.State) -> Unit = { renderWater(it, animate = true) }
    private var forecast: Weather.Forecast? = null
    private var fetchedAt = 0L

    init {
        val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics)
        glassesCount.cellEm = 0.58f
        glassesCount.color = themeColor(context, R.attr.c_text_dim)
        glassesCount.typeface = themeFont(context, R.attr.c_font_regular)
        glassesCount.setSize(sp, context.dp(20))
        root.findViewById<View>(R.id.hero_weather).setOnClickListener { if (!placed.config.has("lat")) showSettings() }
        root.findViewById<View>(R.id.hero_water).setOnClickListener { WaterSettings.show(context) }
    }

    fun setActive(active: Boolean) {
        if (active) Water.observe(waterListener) else Water.unobserve(waterListener)
    }

    fun render(animate: Boolean) {
        renderWeather()
        renderWater(Water.state(context), animate)
    }

    private fun renderWeather() {
        val f = forecast
        val named = placed.config.optString("city").isNotBlank()
        if (f == null) {
            wxTemp.setCompoundDrawablesRelative(null, null, null, null)
            wxTemp.text = ""
            wxLabel.setText(if (named) R.string.loading else R.string.widget_weather_choose)
            return
        }
        val sky = Weather.describe(f.code)
        wxTemp.setCompoundDrawablesRelativeWithIntrinsicBounds(sky.icon, 0, 0, 0)
        wxTemp.text = context.getString(R.string.widget_weather_temp, f.temperature.roundToInt())
        // La ville d'abord : trouvée seule par la position, elle doit se voir pour pouvoir être corrigée.
        wxLabel.text = context.getString(R.string.hero_wx_label, placed.config.optString("city"), context.getString(sky.text), f.max.roundToInt())
    }

    private fun renderWater(state: Water.State, animate: Boolean) {
        glasses.show(state.glasses, state.goal, animate)
        glassesCount.set(state.glasses.toString(), animate)
        glassesLabel.text = context.resources.getQuantityString(R.plurals.hero_glasses, state.glasses, state.goal)
    }

    suspend fun load() {
        // Pas encore de ville : celle de la position de la tablette, sinon « Choisir ma ville ».
        if (!placed.config.has("lat") && Weather.autoCity(context, placed.config)) host.save()
        if (!placed.config.has("lat")) return renderWeather()
        if (forecast != null && SystemClock.elapsedRealtime() - fetchedAt < Weather.REFRESH_MS) return
        forecast = runCatching { Weather.forecast(placed.config.getDouble("lat"), placed.config.getDouble("lon")) }.getOrNull() ?: forecast
        if (forecast != null) fetchedAt = SystemClock.elapsedRealtime()
        renderWeather()
    }

    fun showSettings() = Weather.chooseCity(context, host.scope, placed.config) {
        forecast = null
        host.save()
        renderWeather()
        host.scope.launch { load() }
    }
}
