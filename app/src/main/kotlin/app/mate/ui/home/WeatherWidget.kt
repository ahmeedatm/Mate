package app.mate.ui.home

import android.content.Context
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.mate.R
import app.mate.ui.Look
import app.mate.ui.themeColor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Exemple du catalogue : une après-midi d'automne à Paris, qui se rafraîchit vers le soir. */
class WeatherSample(val city: Int, val forecast: Weather.Forecast, val air: Int) : WidgetSample {
    companion object {
        fun paris() = WeatherSample(
            R.string.widget_weather_sample_city,
            Weather.Forecast(
                temperature = 12.0,
                code = 2,
                min = 8.0,
                max = 14.0,
                days = listOf(
                    Weather.Day("2026-11-06", 61, 7.0, 12.0),
                    Weather.Day("2026-11-07", 3, 6.0, 11.0),
                    Weather.Day("2026-11-08", 1, 5.0, 13.0),
                    Weather.Day("2026-11-09", 0, 4.0, 12.0),
                ),
                hours = listOf(13.0, 13.0, 12.0, 11.0, 10.0, 9.0, 9.0, 8.0, 8.0, 8.0, 7.0, 7.0)
                    .mapIndexed { i, t -> Weather.Hour(Long.MAX_VALUE / 2, (14 + i) % 24, t, if (i < 4) 2 else 3) },
            ),
            air = 2,
        )
    }
}

/**
 * Météo de la ville choisie, lue par la tablette chez MET Norway (gratuit, sans clé ni compte, voir Weather), toutes les
 * 30 min. Une mise en page par famille ([WeatherFace], direction Cadran) : Pastille = température et petite courbe des
 * prochaines heures ; Ligne = température et détails (ciel, min / max, ville), la courbe en 4 × 1 ; Colonne = la courbe
 * de haut en bas ; Bloc, Grand et Plein écran = détails, qualité de l'air, courbe et jours suivants.
 */
class WeatherWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val emoji = label(44f)
    private val temp = label(40f, light = true)
    private val sky = label(17f).apply { oneLine() }
    private val range = label(15f, R.attr.c_text_dim).apply { oneLine() }
    private val city = label(14f, R.attr.c_text_faint).apply { oneLine() }

    /** Pastille et Colonne : la colonne de détails n'a pas la place, la ville passe sous la température. */
    private val smallCity = label(14f, R.attr.c_text_faint).apply { oneLine() }
    private val details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val now = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }

    /** Indice de qualité de l'air de la ville, coloré par tranche ([AirQuality]) ; caché sans mesure. */
    private val air = label(15f).apply { oneLine() }

    /** Une colonne par jour suivant (jour, emoji, min / max). */
    private val days = LinearLayout(context)
    private val curve = TempCurve(context)
    override val view = FrameLayout(context).asCard(16).apply {
        setOnClickListener { if (!placed.config.has("lat")) showSettings() }
    }

    private var face = WeatherFace.of(placed.size)
    private var fetchedAt = 0L
    private var floating: android.animation.ValueAnimator? = null
    private var current: Weather.Forecast? = null
    private var airIndex: Int? = null

    /** Aperçu du catalogue : la ville de l'exemple, sans rien écrire dans `placed.config`. */
    private var previewCity: String? = null

    override val hasSettings get() = true

    init {
        // La hauteur d'une rangée dépend du profil de l'Accueil (RowProfile) : les tailles se recalculent à chaque changement.
        view.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) view.post(::fit)
        }
    }

    /** L'emoji du temps flotte doucement tant que la page est à l'écran. */
    override fun setActive(active: Boolean) {
        if (active && floating == null) floating = Look.float(emoji)
        if (!active) {
            Look.stop(floating, emoji)
            floating = null
        }
    }

    override fun applySize() {
        face = WeatherFace.of(placed.size)
        for (part in listOf(emoji, temp, sky, range, city, smallCity, details, now, air, days, curve)) (part.parent as? ViewGroup)?.removeView(part)
        view.removeAllViews()
        val pill = family == ShapeFamily.PILL
        view.setPadding(dp(if (pill) 12 else 18), dp(if (pill) 10 else 14), dp(if (pill) 12 else 18), dp(if (pill) 10 else 14))
        now.addView(emoji)
        now.addView(temp, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        for (line in listOf(sky, range, city)) details.addView(line)
        curve.across = face.curve != WeatherFace.Curve.DOWN
        curve.withHours = !pill
        curve.maxPoints = if (pill) PILL_POINTS else Weather.HOURS
        view.addView(if (family == ShapeFamily.ROW) rowLayout() else stackLayout(), FrameLayout.LayoutParams(MATCH, MATCH))
        render()
        view.post(::fit)
    }

    /** Température et détails côte à côte, la courbe à droite en 4 × 1. */
    private fun rowLayout() = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(now)
        val curveSide = face.curve != WeatherFace.Curve.NONE
        addView(details, LinearLayout.LayoutParams(if (curveSide) WRAP else 0, WRAP, if (curveSide) 0f else 1f).apply { marginStart = dp(16) })
        if (curveSide) addView(curve, LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginStart = dp(24) })
    }

    /** De haut en bas : température (et détails), ville, air, courbe, jours. */
    private fun stackLayout() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        val head = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(now)
            if (face.details) addView(details, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(16) })
        }
        addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        if (!face.details) addView(smallCity)
        if (face.air) addView(air)
        if (face.curve != WeatherFace.Curve.NONE) addView(curve, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(12) })
        if (face.days) addView(days, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
    }

    /** Taille de la température selon la hauteur réelle de la carte : grande en plein écran, discrète en Pastille. */
    private fun fit() {
        val h = view.height - view.paddingTop - view.paddingBottom
        if (h <= 0) return
        val part = when (family) {
            ShapeFamily.PILL -> PILL_TEMP_PART
            ShapeFamily.ROW -> ROW_TEMP_PART
            else -> STACK_TEMP_PART
        }
        val px = (h * part).coerceIn(sp(TEMP_MIN_SP), sp(TEMP_MAX_SP))
        temp.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        emoji.setTextSize(TypedValue.COMPLEX_UNIT_PX, px * EMOJI_PART)
        sky.setTextSize(TypedValue.COMPLEX_UNIT_PX, (px * SKY_PART).coerceIn(sp(DETAIL_MIN_SP), sp(DETAIL_MAX_SP)))
        for (line in listOf(range, city, smallCity, air)) line.setTextSize(TypedValue.COMPLEX_UNIT_PX, (px * DETAIL_PART).coerceIn(sp(DETAIL_MIN_SP), sp(DETAIL_MAX_SP)))
    }

    override fun showPreview(sample: WidgetSample) {
        if (sample !is WeatherSample) return
        previewCity = context.getString(sample.city)
        current = sample.forecast
        airIndex = sample.air
        render()
    }

    override suspend fun load() {
        // Pas encore de ville : celle de la position de la tablette, sinon « Choisir ma ville ».
        if (!placed.config.has("lat") && Weather.autoCity(context, placed.config)) host.save()
        if (!placed.config.has("lat")) return render()
        if (current != null && SystemClock.elapsedRealtime() - fetchedAt < Weather.REFRESH_MS) return
        current = runCatching { Weather.forecast(placed.config.getDouble("lat"), placed.config.getDouble("lon")) }.getOrNull() ?: current
        if (current != null) fetchedAt = SystemClock.elapsedRealtime()
        render()
        // L'air est un plus, lu après l'affichage de la météo (sa réponse peut traîner) et seulement si elle est à jour.
        if (current == null) return
        airIndex = runCatching { AirQuality.index(placed.config.getDouble("lat"), placed.config.getDouble("lon")) }.getOrNull() ?: airIndex
        renderAir()
    }

    private fun render() {
        val name = previewCity ?: placed.config.optString("city")
        val f = current
        if (name.isBlank()) {
            emoji.text = "🌤"
            temp.text = ""
            sky.setText(R.string.widget_weather_choose)
            sky.maxLines = 2
            range.text = ""
            city.text = ""
            smallCity.setText(R.string.widget_weather_choose)
            smallCity.visibility = View.VISIBLE
            // Sans ville : ni courbe ni jours vides sous l'invitation, qui reste au milieu de la carte.
            for (part in listOf(days, air, curve)) part.visibility = View.GONE
            return
        }
        sky.maxLines = 1
        city.text = name
        smallCity.visibility = View.VISIBLE
        if (f == null) {
            emoji.text = "…"
            temp.text = ""
            sky.setText(R.string.loading)
            range.text = ""
            smallCity.text = name
            for (part in listOf(days, air, curve)) part.visibility = View.GONE
            return
        }
        val weather = Weather.describe(f.code)
        emoji.text = weather.emoji
        temp.text = context.getString(R.string.widget_weather_temp, f.temperature.roundToInt())
        sky.setText(weather.text)
        range.text = context.getString(R.string.widget_weather_range, f.min.roundToInt(), f.max.roundToInt())
        smallCity.text = if (family == ShapeFamily.COLUMN) context.getString(R.string.widget_weather_city_range, name, f.min.roundToInt(), f.max.roundToInt()) else name
        val hours = Weather.upcoming(f.hours, System.currentTimeMillis())
        curve.visibility = if (hours.size >= 2) View.VISIBLE else View.GONE
        curve.show(hours)
        renderAir()
        renderDays(f.days)
    }

    private fun renderAir() {
        val index = airIndex
        air.visibility = if (index == null || !face.air) View.GONE else View.VISIBLE
        if (index == null) return
        val level = AirQuality.levelOf(index)
        air.text = context.getString(R.string.widget_weather_air, index, context.getString(level.text))
        air.setTextColor(themeColor(context, level.color))
    }

    private fun renderDays(list: List<Weather.Day>) {
        days.removeAllViews()
        days.visibility = if (face.days && list.isNotEmpty()) View.VISIBLE else View.GONE
        if (!face.days) return
        for (day in list.take(4)) {
            val name = runCatching { dayName.format(isoDate.parse(day.date)!!) }.getOrDefault("").replaceFirstChar { it.uppercase() }
            days.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(label(15f, R.attr.c_text_dim).apply { text = name })
                    addView(label(30f).apply { text = Weather.describe(day.code).emoji })
                    addView(label(15f).apply { text = context.getString(R.string.widget_weather_day, day.min.roundToInt(), day.max.roundToInt()) })
                },
                LinearLayout.LayoutParams(0, WRAP, 1f),
            )
        }
    }

    override fun showSettings() = Weather.chooseCity(context, host.scope, placed.config) {
        current = null
        airIndex = null
        host.save()
        render()
        host.scope.launch { load() }
    }

    private fun TextView.oneLine() {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** Température en part de la hauteur de la carte, bornée ; l'emoji suit en part de la température. */
        const val PILL_TEMP_PART = 0.3f
        const val ROW_TEMP_PART = 0.5f
        const val STACK_TEMP_PART = 0.16f
        const val TEMP_MIN_SP = 26f
        const val TEMP_MAX_SP = 110f

        /** Heures de la petite courbe de la Pastille. */
        const val PILL_POINTS = 5
        const val EMOJI_PART = 0.9f

        /** Ciel, puis min / max, ville et air, en part de la température, bornés. */
        const val SKY_PART = 0.3f
        const val DETAIL_PART = 0.24f
        const val DETAIL_MIN_SP = 14f
        const val DETAIL_MAX_SP = 24f

        val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayName = SimpleDateFormat("EEE", Locale.FRANCE)
    }
}
