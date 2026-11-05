package app.mate.ui.home

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import app.mate.R
import app.mate.net.ValorantEnd
import app.mate.net.ValorantEndPlayer
import app.mate.net.ValorantMatch
import app.mate.net.ValorantPlayer

/**
 * Mise en page du widget selon sa forme : `summary` (pastille, ligne, colonne) ne montre que mon rang et le score ;
 * `side` met les équipes côte à côte (4 cases de large, ou 3 × 2, trop bas pour dix lignes l'une sous l'autre) ;
 * `detail` a toutes les colonnes de stats (4 de large, ou 3 de large et 3 de haut), sinon l'essentiel (agent, nom,
 * rang, K/D) ; `stretch` : les lignes se partagent la hauteur (sinon elles gardent la leur, tableau centré).
 */
data class ValorantShape(val summary: Boolean, val detail: Boolean, val side: Boolean, val stretch: Boolean = true) {
    companion object {
        fun of(size: WidgetSize) = ValorantShape(
            summary = size.family == ShapeFamily.PILL || size.family == ShapeFamily.ROW || size.family == ShapeFamily.COLUMN,
            detail = size.w >= 4 || (size.w == 3 && size.h >= 3),
            side = size.w >= 4 || (size.w == 3 && size.h <= 2),
            // Cinq lignes par colonne côte à côte en 3 ou 4 de haut, dix l'une sous l'autre en 4 de haut : trop de place,
            // les lignes gardent leur hauteur et le tableau se centre.
            stretch = size.h <= 2 || (size.w < 4 && size.h == 3),
        )
    }
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
