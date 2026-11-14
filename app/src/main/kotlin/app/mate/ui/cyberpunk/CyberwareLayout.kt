package app.mate.ui.cyberpunk

import app.mate.ui.cyberpunk.CyberpunkItems.Zone

/**
 * Placement de l'écran cyberware du charcudoc (logique pure). Les nombres viennent de la mise en page du jeu
 * (`base\gameplay\gui\fullscreen\ripperdoc\ripperdoc.inkwidget`, extraite le 14/11/2026) : un écran de 3840 × 2160,
 * le corps centré, une ancre par zone, et pour chaque système une image de `man_body` / `woman_body` posée par son
 * centre, à sa taille 1080p doublée puis réduite (0,92 chez l'homme, 0,9 chez la femme).
 */
object CyberwareLayout {
    enum class Body { MAN, WOMAN }

    /** Rectangle en pixels de la vue (pas de `RectF` : la logique se teste en JVM). */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun centerX() = (left + right) / 2

        fun centerY() = (top + bottom) / 2

        fun width() = right - left

        fun height() = bottom - top
    }

    /** Coin haut gauche du bloc d'une zone (nom puis cases). */
    data class Spot(val x: Float, val y: Float)

    /** Image d'un système : nom de la pièce extraite, taille 1080p, centre décalé du centre du corps (unités du jeu). */
    data class Part(val asset: String, val width: Int, val height: Int, val dx: Int, val dy: Int, val scale: Float)

    // Ancres du jeu (frontalAnchor, armsAnchor…). Le système immunitaire n'en a pas dans la version 2.x : sous la peau.
    private val anchors = mapOf(
        Zone.FRONTAL to (1200 to 300), Zone.ARMS to (1050 to 600), Zone.SKELETON to (900 to 900),
        Zone.NERVOUS to (1200 to 1200), Zone.SKIN to (1200 to 1500), Zone.IMMUNE to (1200 to 1800),
        Zone.OS to (2250 to 300), Zone.EYES to (2370 to 600), Zone.HANDS to (2250 to 900),
        Zone.CARDIO to (2250 to 1200), Zone.LEGS to (2250 to 1500),
    )

    /** Centre du corps (paperDollWrapper, décalé de 100 vers le bas). */
    private const val BODY_X = 1920
    private const val BODY_Y = 1130

    /** Partie de l'écran du jeu gardée sur la tablette : les deux colonnes de zones et le corps entier. */
    private const val CROP_LEFT = 880
    private const val CROP_TOP = 180
    private const val CROP_WIDTH = 2040
    private const val CROP_HEIGHT = 1770

    /** Côté d'une case d'implant dans le jeu. */
    private const val SLOT_UNITS = 120

    private const val MAN_SCALE = 0.92f
    private const val WOMAN_SCALE = 0.9f

    private fun man(asset: String, w: Int, h: Int, dx: Int, dy: Int) = Part("ma_$asset.png", w, h, dx, dy, MAN_SCALE)

    private fun woman(asset: String, w: Int, h: Int, dx: Int, dy: Int) = Part("wo_$asset.png", w, h, dx, dy, WOMAN_SCALE)

    private val parts = mapOf(
        Body.MAN to mapOf(
            Zone.SKELETON to man("full_skeleton", 545, 931, 50, -85), Zone.CARDIO to man("circular", 545, 931, 50, -85),
            Zone.IMMUNE to man("immune", 545, 930, 50, -85), Zone.SKIN to man("intergumentary", 544, 931, 50, -85),
            Zone.NERVOUS to man("nervous", 545, 930, 50, -85), Zone.ARMS to man("arms", 458, 164, 51, -315),
            Zone.HANDS to man("hands", 544, 111, 50, -152), Zone.LEGS to man("legs", 266, 547, 39, 261),
            Zone.EYES to man("ocular", 95, 109, 50, -824), Zone.OS to man("operating_system", 254, 380, 62, -580),
            Zone.FRONTAL to man("pfrontal_cortex", 102, 174, 56, -765),
        ),
        Body.WOMAN to mapOf(
            Zone.SKELETON to woman("skeleton", 367, 891, 42, -110), Zone.CARDIO to woman("circular", 367, 891, 42, -110),
            Zone.IMMUNE to woman("immune", 367, 891, 42, -110), Zone.SKIN to woman("intergumentary", 367, 891, 42, -110),
            Zone.NERVOUS to woman("nervous", 367, 891, 42, -110), Zone.ARMS to woman("arms", 363, 219, 46, -274),
            Zone.HANDS to woman("hands", 367, 99, 42, -157), Zone.LEGS to woman("legs", 170, 535, 49, 210),
            Zone.EYES to woman("ocular", 81, 92, 52, -815), Zone.OS to woman("operating_system", 202, 334, 42, -611),
            Zone.FRONTAL to woman("frontal_cortex", 89, 166, 53, -760),
        ),
    )

    /** Image d'un système ; le corps entier vu par défaut est celui de la peau, comme la vidéo du jeu au repos. */
    fun part(body: Body, zone: Zone): Part = parts.getValue(body).getValue(zone)

    fun base(body: Body): Part = part(body, Zone.SKIN)

    /** Toutes les pièces du corps à charger pour ce corps. */
    fun assets(body: Body): List<String> = parts.getValue(body).values.map { it.asset }

    /** Mise à l'échelle de l'écran du jeu dans une vue de `width` × `height` pixels, centrée. */
    class Fit internal constructor(private val scale: Float, private val ox: Float, private val oy: Float) {
        val slotPx: Float = SLOT_UNITS * scale

        private fun x(u: Int) = ox + (u - CROP_LEFT) * scale

        private fun y(u: Int) = oy + (u - CROP_TOP) * scale

        fun zone(zone: Zone?): Spot? = anchors[zone]?.let { (ax, ay) -> Spot(x(ax), y(ay)) }

        fun partRect(p: Part): Box {
            val w = p.width * 2 * p.scale * scale
            val h = p.height * 2 * p.scale * scale
            val cx = x(BODY_X + p.dx)
            val cy = y(BODY_Y + p.dy)
            return Box(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        }

        /** Le corps entier (homme), pour placer ce qui l'entoure. */
        val body: Box get() = partRect(base(Body.MAN))
    }

    fun fit(width: Float, height: Float): Fit {
        val scale = minOf(width / CROP_WIDTH, height / CROP_HEIGHT)
        return Fit(scale, (width - CROP_WIDTH * scale) / 2, (height - CROP_HEIGHT * scale) / 2)
    }
}
