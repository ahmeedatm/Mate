package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Palette.Hush (res/values/palettes.xml) : attributs complets et contrastes WCAG lus sur le pire point du fond animé
 * (HushGlow rejoué sans appareil), avec l'ombre sous les textes, la barre fixe et le halo d'appui posés par-dessus.
 */
class HushPaletteTest {
    private val xml = File("src/main/res/values/palettes.xml").readText()

    private fun values(name: String) = Regex("""<item name="([\w:]+)">([^<]*)</item>""")
        .findAll(xml.substringAfter("""<style name="Palette.$name"""").substringBefore("</style>"))
        .associate { it.groupValues[1] to it.groupValues[2] }

    private val hush = values("Hush")

    private fun argb(attr: String): String {
        var value = hush[attr] ?: error("$attr absent de Palette.Hush")
        while (value.startsWith("?attr/")) value = hush[value.removePrefix("?attr/")] ?: error("$value absent de Palette.Hush")
        check(Regex("#[0-9A-Fa-f]{8}").matches(value)) { "$attr : $value n'est pas une couleur" }
        return value
    }

    /** Couleur en (alpha 0..1, r, g, b 0..255). */
    private fun parse(argb: String): List<Double> = argb.removePrefix("#").chunked(2).map { it.toInt(16).toDouble() }
        .let { listOf(it[0] / 255) + it.drop(1) }

    /** `top` posé sur `under` (opaque), mélange d'Android (sRGB). */
    private fun over(under: List<Double>, top: List<Double>, alpha: Double = top[0]) =
        listOf(1.0) + (1..3).map { alpha * top[it] + (1 - alpha) * under[it] }

    private fun luminance(c: List<Double>): Double {
        val rgb = c.drop(1).map { it / 255 }.map { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]
    }

    private fun ratio(a: List<Double>, b: List<Double>): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private val tints = mapOf(HushGlow.Tint.PINK to "c_accent", HushGlow.Tint.VIOLET to "c_hue_violet")

    private val screen = 1280f to 800f

    /** Couleur du fond au pixel (`px`, `py`) à l'instant `now`, halos seuls. */
    private fun sky(now: Long, px: Float, py: Float): List<Double> {
        val (w, h) = screen
        return HushGlow.HALOS.fold(parse(argb("c_bg"))) { under, halo ->
            val (x, y) = HushGlow.haloX(halo, now) to HushGlow.haloY(halo, now)
            val f = Math.hypot((px - w * x).toDouble(), (py - h * y).toDouble()) / (w * halo.radius * HushGlow.haloScale(halo, now))
            over(under, parse(argb(tints.getValue(halo.tint))), halo.alpha * HushGlow.falloff(f.toFloat()).toDouble())
        }
    }

    /**
     * Fond au plus clair, à 300 instants étalés sur une heure : les halos sur une grille de 40 px d'un écran 1280×800,
     * puis le corps et l'arête des éclats (opaques, ils remplacent le halo sous eux).
     */
    private val worst: List<Double> by lazy {
        val brightest = { a: List<Double>, b: List<Double> -> if (luminance(b) > luminance(a)) b else a }
        var best = parse(argb("c_bg"))
        for (k in 0 until 300) for (gx in 0..32) for (gy in 0..20) best = brightest(best, sky(k * 12_037L, gx * 40f, gy * 40f))
        val fill = over(parse(argb("c_bg")), parse(argb("c_hue_violet")), HushGlow.SHARD_FILL.toDouble())
        brightest(brightest(best, fill), over(parse(argb("c_bg")), parse(argb("c_accent")), HushGlow.SHARD_EDGE.toDouble()))
    }

    /** Textes sans tuile : l'ombre `0 1px 6px #000c` de Widget.Hush.Text assombrit au moins de moitié le fond sous les lettres. */
    private val shadowed = over(worst, parse("#FF000000"), 0.5)

    private val pressAlpha = "0.22"

    /** Fond nu, fond sous l'ombre, barre fixe (transparente) et relief (opaque) au pire point du fond. */
    private fun backgrounds() = listOf(
        "fond" to worst, "ombre" to shadowed, "barre" to over(worst, parse(argb("c_rail"))),
        "relief" to parse(argb("c_surface_raised")),
    )

    private fun assertOn(fgs: List<String>, min: Double) {
        for (fg in fgs) for ((where, bg) in backgrounds()) {
            val r = ratio(parse(argb(fg)), bg)
            assertTrue("$fg sur $where : $r", r >= min)
        }
    }

    @Test
    fun `Hush definit tous les attributs de Console`() {
        assertEquals(emptySet<String>(), values("Console").keys - hush.keys)
    }

    @Test
    fun `fond anime, fond opaque, barre fixe transparente`() {
        assertEquals("true", hush["c_backdrop"])
        assertEquals(1.0, parse(argb("c_bg"))[0], 0.0)
        assertEquals(0.0, parse(argb("c_rail"))[0], 0.0)
    }

    @Test
    fun `widgets sans fond ni bordure, halo rose a l'appui`() {
        for (attr in listOf("c_tile_bg", "c_home_card_bg")) assertEquals(attr, "@drawable/hush_tile_bg", hush[attr])
        // Fenêtres, incrustations et cartes de Réglages gardent le verre : sans fond elles se perdraient dans le fond animé.
        assertEquals("@drawable/console_tile_bg", hush["c_card_bg"])
        val tile = File("src/main/res/drawable/hush_tile_bg.xml").readText()
        assertTrue(!tile.contains("<stroke"))
        val rest = tile.substringAfter("</item>")
        assertTrue("au repos : rien", !rest.contains("<solid") && !rest.contains("<gradient"))
        assertTrue(tile.substringBefore("</item>").contains("@color/hush_press"))
        assertEquals("@drawable/hush_key_plate", hush["c_key_plate"])
        val plate = File("src/main/res/drawable/hush_key_plate.xml").readText()
        assertTrue(plate.contains("@color/hush_press") && !plate.substringAfter("</item>").contains("<solid"))
        for (name in listOf("Console", "ConsoleJour", "Contraste")) assertEquals(name, "@drawable/round_bg", values(name)["c_key_plate"])
        val press = File("src/main/res/color/hush_press.xml").readText()
        assertTrue(press.contains("?attr/c_accent") && press.contains("android:alpha=\"$pressAlpha\""))
    }

    @Test
    fun `texte lisible sur le halo d'appui`() {
        val halo = over(shadowed, parse(argb("c_accent")), pressAlpha.toDouble())
        for (fg in listOf("c_text", "c_text_dim")) assertTrue("$fg sur le halo", ratio(parse(argb(fg)), halo) >= 4.5)
    }

    @Test
    fun `ombre legere sous tous les textes, par la couleur de c_text_shadow`() {
        assertEquals("#CC000000", hush["c_text_shadow"])
        assertEquals("@style/Widget.Hush.Text", hush["android:textViewStyle"])
        val style = xml.substringAfter("<style name=\"Widget.Hush.Text\"").substringBefore("</style>")
        assertTrue(style.contains("<item name=\"android:shadowColor\">?attr/c_text_shadow</item>"))
        assertTrue(style.contains("<item name=\"android:shadowDy\">1</item>"))
        assertTrue(style.contains("<item name=\"android:shadowRadius\">6</item>"))
    }

    @Test
    fun `Chakra Petch pour les textes et l'heure, Share Tech Mono pour les etiquettes`() {
        assertEquals("@font/chakra_petch_400", hush["c_font_regular"])
        assertEquals("@font/chakra_petch_400", hush["c_font_light"])
        assertEquals("@font/chakra_petch_400", hush["android:fontFamily"])
        assertEquals("@font/chakra_petch_500", hush["c_font_medium"])
        assertEquals("@font/chakra_petch_500", hush["c_font_title"])
        assertEquals("@font/chakra_petch_700", hush["c_font_display"])
        assertEquals("@font/share_tech_mono_400", hush["c_font_label"])
        assertEquals("true", hush["c_label_caps"])
    }

    @Test
    fun `les autres themes gardent leur police d'etiquette et leurs minuscules`() {
        for (name in listOf("Console", "ConsoleJour", "Contraste")) {
            val p = values(name)
            assertEquals(name, p["c_font_title"], p["c_font_label"])
            assertEquals(name, "false", p["c_label_caps"])
            assertEquals(name, "#00000000", p["c_text_shadow"])
        }
    }

    @Test
    fun `l'accent est le rose vif`() {
        assertEquals("#FFFF6EB4", hush["c_accent"])
    }

    @Test
    fun `textes au moins 4,5 sur le pire point du fond, verre et relief`() {
        assertOn(listOf("c_text", "c_text_dim", "c_text_faint"), 4.5)
    }

    @Test
    fun `accent, couleurs de sens et teintes de fonction au moins 4,5 sur le pire point`() {
        val hues = hush.keys.filter { it.startsWith("c_hue_") }
        assertEquals(8, hues.size)
        assertOn(listOf("c_accent", "c_primary", "c_ok", "c_ko", "c_warn") + hues, 4.5)
    }

    @Test
    fun `traits au moins 3 et signaux des agents au moins 2,4 sur le pire point`() {
        // Table de mixage sans plaque : le rail du curseur, posé sur le fond animé, se voit (ui/mixer, c_mx_bare).
        assertOn(listOf("c_graphic", "c_mx_slot"), 3.0)
        assertOn(hush.keys.filter { it.startsWith("c_agent_") }, 2.4)
    }

    @Test
    fun `vumetre eteint discret, pour ne pas faire un deuxieme rail`() {
        assertTrue(parse(argb("c_mx_led_off"))[0] < 0.25)
    }

    @Test
    fun `texte sur les aplats et les fonds teintes au moins 4,5`() {
        for ((fg, bg) in listOf(
            "c_on_accent" to "c_accent", "c_on_primary" to "c_primary", "c_on_ko" to "c_ko", "c_accent" to "c_accent_soft",
            "c_ok" to "c_ok_soft", "c_text" to "c_surface_pressed", "c_text" to "c_accent_soft",
        )) assertTrue("$fg sur $bg", ratio(parse(argb(fg)), parse(argb(bg))) >= 4.5)
    }

    @Test
    fun `chaque police citee existe`() {
        for ((attr, value) in hush) {
            if (value.startsWith("@font/")) assertTrue("$attr : $value", File("src/main/res/font/${value.removePrefix("@font/")}.ttf").isFile)
        }
    }

    @Test
    fun `halo plein au centre, a 45 pour cent a mi-chemin du degrade, nul au bord`() {
        assertEquals(1f, HushGlow.falloff(0f), 0f)
        assertEquals(0.45f, HushGlow.falloff(0.45f), 1e-6f)
        assertEquals(0f, HushGlow.falloff(1f), 0f)
        assertEquals(0f, HushGlow.falloff(1.5f), 0f)
    }

    @Test
    fun `phase d'un cycle entre 0 et 1, revient a 0 au bout de la periode`() {
        assertEquals(0f, HushGlow.phase(0, 1_000), 0f)
        assertEquals(0.25f, HushGlow.phase(250, 1_000), 1e-6f)
        assertEquals(0f, HushGlow.phase(3_000, 1_000), 0f)
    }
}
