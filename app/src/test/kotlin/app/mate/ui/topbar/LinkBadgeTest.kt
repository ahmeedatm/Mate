package app.mate.ui.topbar

import app.mate.R
import app.mate.net.Link
import app.mate.ui.AppTheme
import app.mate.ui.home.Down
import app.mate.ui.home.LinkWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pastille de connexion du bandeau : discrète quand l'ordinateur répond, voyante dès qu'il manque, lisible dans chaque thème. */
class LinkBadgeTest {
    @Test
    fun `ordinateur qui repond pastille discrete`() {
        assertEquals(LinkBadgeState(LinkTone.OK, R.string.link_badge_online), linkBadge(Link.State.ONLINE, null))
    }

    @Test
    fun `premier essai ou coupure breve pas encore d alerte`() {
        assertEquals(LinkBadgeState(LinkTone.WAIT, R.string.link_badge_wait), linkBadge(Link.State.OFFLINE, null))
    }

    @Test
    fun `coupure apres le delai de grace avec son heure`() {
        val down = Down("Mac", 0L)
        assertEquals(LinkBadgeState(LinkTone.DOWN, R.string.link_badge_down_since, down.since()), linkBadge(Link.State.OFFLINE, down))
    }

    @Test
    fun `ordinateur jamais vu repondre sans heure`() {
        assertEquals(LinkBadgeState(LinkTone.DOWN, R.string.link_badge_down), linkBadge(Link.State.OFFLINE, Down("Mac", null)))
    }

    @Test
    fun `jeton refuse`() {
        assertEquals(LinkTone.REFUSED, linkBadge(Link.State.UNAUTHORIZED, null)?.tone)
    }

    @Test
    fun `sans ordinateur rien`() {
        assertNull(linkBadge(Link.State.NONE, null))
    }

    @Test
    fun `chaque ton a sa propre forme`() {
        assertEquals(LinkTone.entries.size, LinkTone.entries.map { it.shape }.toSet().size)
    }

    @Test
    fun `seules la coupure et le refus ont un fond`() {
        assertEquals(listOf(LinkTone.DOWN, LinkTone.REFUSED), LinkTone.entries.filter { it.fill != null })
    }

    @Test
    fun `connexion pendant les 10 premieres secondes puis injoignable depuis l heure de la coupure`() {
        val watch = LinkWatch()
        val t0 = 1_000_000L
        watch.onLink("pc", Link.State.ONLINE, t0)
        watch.onLink("pc", Link.State.OFFLINE, t0 + 1_000)
        val early = watch.down("Mac", t0 + 1_000 + LinkWatch.GRACE_MS - 1)
        assertEquals(LinkTone.WAIT, linkBadge(Link.State.OFFLINE, early)?.tone)
        val late = watch.down("Mac", t0 + 1_000 + LinkWatch.GRACE_MS)
        val state = linkBadge(Link.State.OFFLINE, late)
        assertEquals(LinkTone.DOWN, state?.tone)
        assertEquals(late?.since(), state?.since)
        assertTrue(state?.since?.matches(Regex("""\d\d:\d\d""")) == true)
    }

    // --- Lisibilité dans chaque thème (res/values/palettes.xml)

    private val palettes = File("src/main/res/values/palettes.xml").readText()
    private val all = Regex("""<style name="Palette\.(\w+)"""").findAll(palettes).map { it.groupValues[1] }.toList()

    @Test
    fun `chaque theme de l appli est verifie`() {
        assertEquals(AppTheme.entries.size, all.size)
    }

    private val names = mapOf(
        R.attr.c_ok to "c_ok", R.attr.c_ko to "c_ko", R.attr.c_ko_soft to "c_ko_soft", R.attr.c_warn to "c_warn",
        R.attr.c_text to "c_text", R.attr.c_text_dim to "c_text_dim", R.attr.c_text_faint to "c_text_faint",
        R.attr.c_surface_raised to "c_surface_raised",
    )

    private fun attrName(attr: Int) = names[attr] ?: error("attribut inconnu du test : $attr")

    private fun argb(palette: String, attr: String): Int {
        val style = palettes.substringAfter("""<style name="Palette.$palette"""").substringBefore("</style>")
        val hex = Regex("""<item name="$attr">#([0-9A-Fa-f]{8})</item>""").find(style)?.groupValues?.get(1)
            ?: error("$attr absent de Palette.$palette")
        return hex.toLong(16).toInt()
    }

    /** Couleur `top` posée sur l'opaque `under` (les fonds c_ko_soft sont translucides). */
    private fun over(top: Int, under: Int): Int {
        val a = (top ushr 24) / 255.0
        fun ch(shift: Int) = (((top shr shift) and 0xFF) * a + ((under shr shift) and 0xFF) * (1 - a)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun luminance(c: Int): Double {
        val rgb = listOf(16, 8, 0).map { ((c shr it) and 0xFF) / 255.0 }
            .map { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]
    }

    private fun ratio(a: Int, b: Int): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `le texte de la pastille fait au moins 4,5 pour 1 sur son fond dans chaque theme`() {
        for (p in all) {
            val bg = argb(p, "c_bg")
            for (tone in LinkTone.entries) {
                val fill = tone.fill?.let { over(argb(p, attrName(it)), bg) } ?: bg
                val text = argb(p, attrName(tone.text))
                val r = ratio(text, fill)
                assertTrue("$p $tone : texte sur fond = $r", r >= 4.5)
            }
        }
    }

    @Test
    fun `le point de la pastille fait au moins 3 pour 1 sur son fond dans chaque theme`() {
        for (p in all) {
            val bg = argb(p, "c_bg")
            for (tone in LinkTone.entries) {
                val fill = tone.fill?.let { over(argb(p, attrName(it)), bg) } ?: bg
                val r = ratio(argb(p, attrName(tone.dot)), fill)
                assertTrue("$p $tone : point sur fond = $r", r >= 3.0)
            }
        }
    }

    @Test
    fun `le filet de la pastille fait au moins 3 pour 1 sur le fond de l ecran dans chaque theme`() {
        for (p in all) {
            val bg = argb(p, "c_bg")
            for (tone in LinkTone.entries.filter { it.stroke != null }) {
                val r = ratio(argb(p, attrName(tone.stroke!!)), bg)
                assertTrue("$p $tone : filet sur fond = $r", r >= 3.0)
            }
        }
    }
}
