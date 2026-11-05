package app.mate.ui.home

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import app.mate.R
import app.mate.net.Valorant
import app.mate.net.ValorantEnd
import app.mate.net.ValorantEndPlayer
import app.mate.net.ValorantMatch
import app.mate.net.ValorantPlayer

/** Place du widget : tout (XL, agrandi), l'essentiel (L), le minimum (T). */
enum class ValorantFit { FULL, COMPACT, STRIP }

/**
 * Une mise en forme du widget Valorant : partie (sélection ou en cours), tableau de fin, menu.
 * shortcut: trois variantes A, B, C côte à côte pour le choix d'Ahmed (05/11), une seule restera.
 */
interface ValorantLook {
    fun match(v: Valorant, kit: ValorantKit, fit: ValorantFit): View
    fun end(end: ValorantEnd, kit: ValorantKit, fit: ValorantFit): View
    fun menu(v: Valorant, kit: ValorantKit, fit: ValorantFit): View
}

/** Barre de RR 0 à 100 : piste en relief, remplie d'accent. */
fun ValorantKit.rrBar(progress: Float, height: Int = 6): View = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    background = GradientDrawable().apply {
        cornerRadius = dp(height).toFloat()
        setColor(color(R.attr.c_surface_raised))
    }
    clipToOutline = true
    addView(
        View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(height).toFloat()
                setColor(color(R.attr.c_accent))
            }
        },
        LinearLayout.LayoutParams(0, dp(height), progress.coerceIn(0.02f, 1f)),
    )
    addView(View(context), LinearLayout.LayoutParams(0, dp(height), 1f - progress.coerceIn(0.02f, 1f)))
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/** Lecture à voix haute d'un joueur en partie : nom, agent, rang, stats. */
fun ValorantKit.describe(p: ValorantPlayer): String = listOfNotNull(
    p.name ?: string(R.string.valorant_hidden),
    string(R.string.valorant_you).takeIf { p.me },
    p.agent?.let { if (p.locked) it else string(R.string.valorant_hovered, it) },
    p.rank?.let { r -> p.rr?.let { "$r, ${string(R.string.valorant_rr, it)}" } ?: r },
    p.level?.let { string(R.string.valorant_level, it) },
    p.peakTier?.let { string(R.string.valorant_peak, rankName(it)) },
    p.kd?.let { string(R.string.valorant_kd_value, ValorantKit.kd(it)) },
    p.hs?.let { string(R.string.valorant_hs_value, it) },
    p.winRate?.let { string(R.string.valorant_wr_value, it) },
    p.party?.let { string(R.string.valorant_party, it) },
).joinToString(", ")

fun ValorantKit.describe(p: ValorantEndPlayer): String = listOfNotNull(
    p.name ?: string(R.string.valorant_hidden),
    string(R.string.valorant_mvp).takeIf { p.mvp },
    p.agent,
    string(R.string.valorant_acs_value, p.acs),
    string(R.string.valorant_kda, p.kills, p.deaths, p.assists),
    p.hs?.let { string(R.string.valorant_hs_value, it) },
    string(R.string.valorant_adr_value, p.adr),
    string(R.string.valorant_fk_value, p.firstKills),
).joinToString(", ")

fun ValorantKit.describe(m: ValorantMatch): String = listOfNotNull(
    string(resultText(m.won)),
    m.score?.let { string(R.string.valorant_score, it.ally, it.enemy) },
    m.map,
    m.agent,
    if (m.kills != null && m.deaths != null && m.assists != null) string(R.string.valorant_kda, m.kills, m.deaths, m.assists) else null,
    m.acs?.let { string(R.string.valorant_acs_value, it) },
    string(R.string.valorant_rr_change, ValorantKit.signed(m.rr)),
).joinToString(", ")

fun resultText(won: Boolean?) = when (won) {
    true -> R.string.valorant_win
    false -> R.string.valorant_loss
    null -> R.string.valorant_draw
}

/** Couleur de sens d'un résultat (toujours redit par un texte ou un signe). */
fun resultHue(won: Boolean?) = when (won) {
    true -> R.attr.c_ok
    false -> R.attr.c_ko
    null -> R.attr.c_text_faint
}

fun rrHue(rr: Int) = if (rr > 0) R.attr.c_ok else if (rr < 0) R.attr.c_ko else R.attr.c_text_dim

/** Joueurs du tableau de fin : mon camp, puis l'autre (déjà triés par ACS par le compagnon). */
val ValorantEnd.allies get() = players.filter { it.ally }
val ValorantEnd.enemies get() = players.filter { !it.ally }
