package app.mate.ui

import kotlin.math.PI
import kotlin.math.sin

/**
 * Réglages purs du fond animé de Hush (ui/HushBackdrop) : une aura de trois halos rose et violet qui dérivent et
 * respirent, et quatre petits éclats de cristal tenus en l'air (clin d'œil à sa télékinésie et à ses cristaux violets).
 * Positions en fractions de l'écran, en fonction du temps : HushPaletteTest rejoue le fond sur une grille de points et
 * d'instants pour trouver son point le plus clair, sur lequel l'accent rose doit garder 4,5:1.
 */
object HushGlow {
    /**
     * Attente avant de redemander une image : plus qu'un rafraîchissement, moins que deux, donc une image sur deux
     * (27 i/s à 55 Hz sur la tablette, 30 à 60 Hz). Un fond lent n'a pas besoin des 55 (docs/tablette-4.2.md).
     */
    const val FRAME_DELAY_MS = 20L

    /** Instant figé quand Réduire les animations est choisi : une image fixe où les halos sont bien répartis. */
    const val STILL_AT_MS = 7_000L

    /** Couleur d'une couche : rose = c_accent, violet = c_hue_violet. */
    enum class Tint { PINK, VIOLET }

    /**
     * Halo rond de rayon `radius` (fraction de la largeur), centré autour de (`x`, `y`) et qui s'en écarte de `driftX`,
     * `driftY` sur deux périodes différentes (une boucle qui ne se répète pas vite) ; il grossit et rétrécit de
     * `breath` sur `breathMs`.
     */
    data class Halo(
        val tint: Tint,
        val alpha: Float,
        val radius: Float,
        val x: Float,
        val y: Float,
        val driftX: Float,
        val driftY: Float,
        val periodX: Long,
        val periodY: Long,
        val breath: Float,
        val breathMs: Long,
    )

    /** Dans l'ordre du dessin : le grand violet d'abord, les roses par-dessus. */
    val HALOS = listOf(
        Halo(Tint.VIOLET, 0.13f, 0.65f, 0.7f, 0.65f, 0.2f, 0.18f, 41_000L, 31_000L, 0.12f, 17_000L),
        Halo(Tint.PINK, 0.12f, 0.55f, 0.3f, 0.35f, 0.18f, 0.15f, 29_000L, 37_000L, 0.12f, 13_000L),
        Halo(Tint.PINK, 0.08f, 0.42f, 0.55f, 0.2f, 0.25f, 0.2f, 47_000L, 23_000L, 0.1f, 19_000L),
    )

    /** Dégradé d'un halo : opacité relative HALO_STRENGTH aux arrêts HALO_STOPS (plein au centre, nul au bord). */
    val HALO_STOPS = floatArrayOf(0f, 0.45f, 1f)
    val HALO_STRENGTH = floatArrayOf(1f, 0.45f, 0f)

    /**
     * Éclat de cristal posé en (`x`, `y`), de `size` dp, qui flotte de `bob` dp et oscille de `tilt` degrés. Places
     * choisies hors du contenu habituel : marge du haut, à droite de l'heure, creux de la barre de gauche.
     */
    data class Shard(val x: Float, val y: Float, val size: Float, val bob: Float, val bobMs: Long, val tilt: Float, val tiltMs: Long)

    val SHARDS = listOf(
        Shard(0.9f, 0.3f, 11f, 6f, 6_200L, 14f, 9_000L),
        Shard(0.63f, 0.1f, 7f, 5f, 5_300L, -18f, 7_400L),
        Shard(0.72f, 0.22f, 9f, 7f, 7_100L, 12f, 11_000L),
        Shard(0.033f, 0.58f, 8f, 5f, 4_700L, -16f, 8_300L),
    )

    /**
     * Éclats opaques, teinte mêlée au noir de c_bg : corps violet sombre, arête rose (ses yeux). Opaques, ils cachent
     * le halo au lieu de s'y ajouter : ils se voient à leur forme nette sans rendre le fond plus clair.
     */
    const val SHARD_FILL = 0.26f
    const val SHARD_EDGE = 0.31f

    /** Avancée, de 0 à 1, d'un cycle de `periodMs` à l'instant `now`. */
    fun phase(now: Long, periodMs: Long): Float = (now % periodMs).toFloat() / periodMs

    /** Onde de -1 à 1 sur `periodMs`. */
    fun wave(now: Long, periodMs: Long): Float = sin(2 * PI * phase(now, periodMs)).toFloat()

    /** Centre (fractions de l'écran) et échelle d'un halo à l'instant `now` ; trois fonctions, rien d'alloué par image. */
    fun haloX(halo: Halo, now: Long): Float = halo.x + halo.driftX * wave(now, halo.periodX)

    fun haloY(halo: Halo, now: Long): Float = halo.y + halo.driftY * wave(now, halo.periodY)

    fun haloScale(halo: Halo, now: Long): Float = 1f + halo.breath * wave(now, halo.breathMs)

    /** Opacité relative d'un halo à la distance `f` de son centre (fraction de son rayon), comme son RadialGradient. */
    fun falloff(f: Float): Float {
        if (f >= 1f) return 0f
        val i = if (f < HALO_STOPS[1]) 0 else 1
        val t = (f - HALO_STOPS[i]) / (HALO_STOPS[i + 1] - HALO_STOPS[i])
        return HALO_STRENGTH[i] + (HALO_STRENGTH[i + 1] - HALO_STRENGTH[i]) * t
    }
}
