package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Palette.SpiderMan (res/values/palettes.xml) : attributs complets et contrastes WCAG lus sur le vrai fond
 * (drawable-nodpi/spiderman_wall.jpg, voile compris), au pire pixel hors de la colonne vide où se tient le logo,
 * avec l'ombre sous les textes, la barre fixe et le halo d'appui posés par-dessus.
 */
class SpiderManPaletteTest {
    private val xml = File("src/main/res/values/palettes.xml").readText()

    private fun values(name: String) = Regex("""<item name="([\w:]+)">([^<]*)</item>""")
        .findAll(xml.substringAfter("""<style name="Palette.$name"""").substringBefore("</style>"))
        .associate { it.groupValues[1] to it.groupValues[2] }

    private val spider = values("SpiderMan")

    private fun argb(attr: String): String {
        var value = spider[attr] ?: error("$attr absent de Palette.SpiderMan")
        while (value.startsWith("?attr/")) value = spider[value.removePrefix("?attr/")] ?: error("$value absent de Palette.SpiderMan")
        check(Regex("#[0-9A-Fa-f]{8}").matches(value)) { "$attr : $value n'est pas une couleur" }
        return value
    }

    /** Couleur en (alpha 0..1, r, g, b 0..255). */
    private fun parse(argb: String): List<Double> = argb.removePrefix("#").chunked(2).map { it.toInt(16).toDouble() }
        .let { listOf(it[0] / 255) + it.drop(1) }

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

    private val wall = ImageIO.read(File("src/main/res/drawable-nodpi/spiderman_wall.jpg"))

    /** Largeur de la barre fixe (activity_main.xml) : la grille de l'Accueil est centrée sur le reste de l'écran. */
    private val railWidth = Regex("""android:id="@\+id/rail"\s+android:layout_width="(\d+)dp"""")
        .find(File("src/main/res/layout/activity_main.xml").readText())!!.groupValues[1].toInt()

    private val spine = spider.getValue("c_spine").removeSuffix("dp").toInt()

    /** Milieu de la colonne vide, en px de l'écran 1280×800 (mdpi : 1 dp = 1 px). */
    private val middle = railWidth + (wall.width - railWidth) / 2

    /** Pixel le plus clair du fond là où un texte peut se poser : partout sauf dans la colonne vide. */
    private val worst: List<Double> by lazy {
        var best = parse("#FF000000")
        for (x in 0 until wall.width) {
            if (x in middle - spine / 2 until middle + spine / 2) continue
            for (y in 0 until wall.height) {
                val rgb = wall.getRGB(x, y)
                val c = listOf(1.0, (rgb shr 16 and 0xFF).toDouble(), (rgb shr 8 and 0xFF).toDouble(), (rgb and 0xFF).toDouble())
                if (luminance(c) > luminance(best)) best = c
            }
        }
        best
    }

    /** L'ombre `0 1px 6px #000c` de Widget.Hush.Text assombrit au moins de moitié le fond sous les lettres. */
    private val shadowed by lazy { over(worst, parse("#FF000000"), 0.5) }

    private fun backgrounds() = listOf(
        "ombre" to shadowed, "barre" to over(shadowed, parse(argb("c_rail"))),
        "relief" to parse(argb("c_surface_raised")),
    )

    private fun assertOn(fgs: List<String>, min: Double) {
        for (fg in fgs) for ((where, bg) in backgrounds()) {
            val r = ratio(parse(argb(fg)), bg)
            assertTrue("$fg sur $where : $r", r >= min)
        }
    }

    @Test
    fun `Spider-Man definit tous les attributs de Console`() {
        assertEquals(emptySet<String>(), values("Console").keys - spider.keys)
    }

    @Test
    fun `fond fixe a l'image du logo, sans fond anime, barre transparente`() {
        assertEquals("@drawable/spiderman_wall", spider["c_wallpaper"])
        assertEquals("false", spider["c_backdrop"])
        assertEquals(1280 to 800, wall.width to wall.height)
        assertEquals(0.0, parse(argb("c_rail"))[0], 0.0)
    }

    @Test
    fun `le logo reste net au centre de la colonne vide`() {
        val rgb = (middle - 20..middle + 20).maxOf { x -> (0 until wall.height).maxOf { y -> wall.getRGB(x, y) and 0xFF } }
        assertTrue("le centre n'est pas voilé", rgb > 200)
    }

    @Test
    fun `colonne vide de 260 dp ici, aucune dans les autres themes`() {
        assertEquals(260, spine)
        for (name in listOf("Console", "ConsoleJour", "Contraste", "Hush")) {
            val p = values(name)
            assertEquals(name, "0dp", p["c_spine"])
            assertEquals(name, "@null", p["c_wallpaper"])
            assertEquals(name, "?attr/c_text_dim", p["c_label"])
        }
    }

    @Test
    fun `widgets sans fond ni bordure, comme Hush`() {
        for (attr in listOf("c_tile_bg", "c_home_card_bg")) assertEquals(attr, "@drawable/hush_tile_bg", spider[attr])
        assertEquals("@drawable/hush_key_plate", spider["c_key_plate"])
        assertEquals("true", spider["c_mx_bare"])
    }

    @Test
    fun `couleurs du logo`() {
        assertEquals("#FFFF2BD6", spider["c_accent"])
        assertEquals("#FF1A0014", spider["c_on_accent"])
        assertEquals("#FF19E3FF", spider["c_label"])
        assertEquals("#FFF7F2FA", spider["c_text"])
        assertEquals("#FFCFC6D8", spider["c_text_dim"])
    }

    @Test
    fun `Chakra Petch partout, etiquettes en capitales sans monospace`() {
        for (attr in listOf("c_font_regular", "c_font_light", "android:fontFamily")) assertEquals(attr, "@font/chakra_petch_400", spider[attr])
        for (attr in listOf("c_font_medium", "c_font_title", "c_font_label")) assertEquals(attr, "@font/chakra_petch_500", spider[attr])
        assertEquals("@font/chakra_petch_700", spider["c_font_display"])
        assertEquals("true", spider["c_label_caps"])
        for ((attr, value) in spider) {
            if (value.startsWith("@font/")) assertTrue("$attr : $value", File("src/main/res/font/${value.removePrefix("@font/")}.ttf").isFile)
        }
    }

    @Test
    fun `ombre sous tous les textes`() {
        assertEquals("#CC000000", spider["c_text_shadow"])
        assertEquals("@style/Widget.Hush.Text", spider["android:textViewStyle"])
    }

    @Test
    fun `curseurs piste blanche, bouton blanc`() {
        assertEquals("?attr/c_text", spider["c_slider_thumb"])
        val track = parse(argb("c_slider_track"))
        assertEquals(listOf(255.0, 255.0, 255.0), track.drop(1))
        assertTrue("piste translucide", track[0] < 1)
        for (name in listOf("Console", "ConsoleJour", "Contraste", "Hush")) {
            assertEquals(name, "?attr/c_accent", values(name)["c_slider_thumb"])
            assertEquals(name, "?attr/c_text_faint", values(name)["c_slider_track"])
        }
    }

    @Test
    fun `Parler plein magenta au repos, blanc pendant l'ecoute, micro noir lisible sur les deux`() {
        assertEquals("?attr/c_accent", spider["c_talk"])
        assertEquals("?attr/c_text", spider["c_talk_live"])
        for (attr in listOf("c_talk", "c_talk_live")) assertTrue(attr, ratio(parse(argb("c_bg")), parse(argb(attr))) >= 4.5)
        for (name in listOf("Console", "ConsoleJour", "Contraste", "Hush")) {
            assertEquals(name, "?attr/c_text", values(name)["c_talk"])
            assertEquals(name, "?attr/c_accent", values(name)["c_talk_live"])
        }
    }

    @Test
    fun `textes et etiquettes au moins 4,5 sur le pire point du fond, hors colonne vide`() {
        assertOn(listOf("c_text", "c_text_dim", "c_text_faint", "c_label"), 4.5)
    }

    @Test
    fun `accent, couleurs de sens et teintes de fonction au moins 4,5 sur le pire point`() {
        val hues = spider.keys.filter { it.startsWith("c_hue_") }
        assertEquals(8, hues.size)
        assertOn(listOf("c_accent", "c_primary", "c_ok", "c_ko", "c_warn") + hues, 4.5)
    }

    @Test
    fun `traits au moins 3 et signaux des agents au moins 2,4 sur le pire point`() {
        assertOn(listOf("c_graphic", "c_mx_slot", "c_slider_thumb"), 3.0)
        assertOn(spider.keys.filter { it.startsWith("c_agent_") }, 2.4)
    }

    /** Pixel le plus clair de toute l'image, logo compris, une fois l'image en filigrane (Wallpaper.DIM). */
    private val dimmed: List<Double> by lazy {
        var best = parse("#FF000000")
        for (x in 0 until wall.width) for (y in 0 until wall.height) {
            val rgb = wall.getRGB(x, y)
            val c = listOf(1.0, (rgb shr 16 and 0xFF).toDouble(), (rgb shr 8 and 0xFF).toDouble(), (rgb and 0xFF).toDouble())
            if (luminance(c) > luminance(best)) best = c
        }
        over(over(parse("#FF000000"), best, Wallpaper.DIM.toDouble()), parse("#FF000000"), 0.5)
    }

    @Test
    fun `milieu occupe, image en filigrane, textes accent et teintes au moins 4,5 partout`() {
        val hues = spider.keys.filter { it.startsWith("c_hue_") }
        for (fg in listOf("c_text", "c_text_dim", "c_text_faint", "c_label", "c_accent", "c_ok", "c_ko", "c_warn") + hues) {
            val r = ratio(parse(argb(fg)), dimmed)
            assertTrue("$fg sur le logo en filigrane : $r", r >= 4.5)
        }
    }

    @Test
    fun `texte lisible sur le halo d'appui`() {
        val halo = over(shadowed, parse(argb("c_accent")), 0.22)
        for (fg in listOf("c_text", "c_text_dim")) assertTrue("$fg sur le halo", ratio(parse(argb(fg)), halo) >= 4.5)
    }

    @Test
    fun `texte sur les aplats et les fonds teintes au moins 4,5`() {
        for ((fg, bg) in listOf(
            "c_on_accent" to "c_accent", "c_on_primary" to "c_primary", "c_on_ko" to "c_ko", "c_accent" to "c_accent_soft",
            "c_ok" to "c_ok_soft", "c_text" to "c_surface_pressed", "c_text" to "c_accent_soft",
        )) assertTrue("$fg sur $bg", ratio(parse(argb(fg)), parse(argb(bg))) >= 4.5)
    }
}
