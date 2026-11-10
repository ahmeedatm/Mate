package app.mate.ui.home

import app.mate.FakeContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileIconsTest {

    private fun layout(vararg names: String) = HomeLayout(mutableListOf(), names.toMutableList<String?>())

    @Test
    fun `les icones survivent a l'enregistrement`() {
        val context = FakeContext()
        val layout = layout("Bureau", "Musique", "Réunion")
        layout.setIcon(1, "music")
        HomeLayout.save(context, layout)
        val back = HomeLayout.load(context)
        assertEquals(listOf(null, "music", null), back.names.indices.map { back.iconOf(it) })
    }

    @Test
    fun `l'icone Valorant est dans la boite`() {
        assertEquals(app.mate.R.drawable.ic_valorant, ProfileIcons.drawable("valorant"))
    }

    @Test
    fun `une icone retiree du choix n'a plus de dessin`() {
        assertNull(ProfileIcons.drawable("game"))
    }

    @Test
    fun `une disposition sans icones se relit sans icone choisie`() {
        val context = FakeContext()
        val old = """{"v":2,"pages":2,"names":["Bureau","Jeux"],"widgets":[]}"""
        context.getSharedPreferences("companion", 0).edit().putString("home_layout_cadran", old).apply()
        val back = HomeLayout.load(context)
        assertNull(back.iconOf(0))
        assertNull(back.iconOf(1))
    }

    @Test
    fun `l'icone suit l'ecran monte par-dessus Reunion`() {
        val layout = layout("Bureau", "Code", "Réunion", "Sport")
        layout.callProfile = 2
        layout.setIcon(1, "code")
        layout.setIcon(3, "fitness")
        // Sport monte d'un cran dans la barre : il saute Réunion, caché hors appel.
        layout.moveProfile(3, 1)
        assertEquals(listOf("Bureau", "Sport", "Code", "Réunion"), layout.names)
        assertEquals(listOf(null, "fitness", "code", null), layout.names.indices.map { layout.iconOf(it) })
        assertEquals(3, layout.callProfile)
    }

    @Test
    fun `l'icone suit l'ecran descendu`() {
        val layout = layout("Bureau", "Code", "Sport")
        layout.setIcon(0, "work")
        layout.moveProfile(0, 2)
        assertEquals(listOf("Code", "Sport", "Bureau"), layout.names)
        assertEquals(listOf(null, null, "work"), layout.names.indices.map { layout.iconOf(it) })
    }

    @Test
    fun `supprimer un ecran retire son icone, les suivantes reculent`() {
        val layout = layout("Bureau", "Code", "Sport")
        layout.setIcon(1, "code")
        layout.setIcon(2, "fitness")
        layout.removeProfile(1)
        assertEquals(listOf(null, "fitness"), layout.names.indices.map { layout.iconOf(it) })
    }

    @Test
    fun `un ecran ajoute n'a pas d'icone choisie et la copie est independante`() {
        val layout = layout("Bureau")
        layout.setIcon(0, "star")
        val added = layout.addProfile("Nouveau")
        assertNull(layout.iconOf(added))
        val copy = layout.copy()
        copy.setIcon(0, "heart")
        assertEquals("star", layout.iconOf(0))
        assertEquals("heart", copy.iconOf(0))
    }

    @Test
    fun `l'icone montree est la choisie, sinon celle du role`() {
        val layout = layout("Bureau", "Libre", "Réunion")
        layout.callProfile = 2
        assertEquals("home", ProfileIcons.keyOf(layout, 0))
        assertEquals("widgets", ProfileIcons.keyOf(layout, 1))
        assertEquals("video", ProfileIcons.keyOf(layout, 2))
        layout.setIcon(1, "cafe")
        assertEquals("cafe", ProfileIcons.keyOf(layout, 1))
        // Clé inconnue (icône retirée d'une version future) : retour à l'icône du rôle.
        layout.setIcon(1, "inconnue")
        assertEquals("widgets", ProfileIcons.keyOf(layout, 1))
    }

    @Test
    fun `un choix en attente remplace l'enregistre sans le modifier, null revient au role`() {
        val layout = layout("Bureau", "Libre")
        layout.setIcon(1, "cafe")
        assertEquals("star", ProfileIcons.keyOf(layout, 1, "star"))
        assertEquals("widgets", ProfileIcons.keyOf(layout, 1, null))
        assertEquals("home", ProfileIcons.keyOf(layout, 0, null))
        assertEquals("cafe", layout.iconOf(1))
    }

    @Test
    fun `cles et icones de la grille sont uniques`() {
        assertEquals(ProfileIcons.ALL.size, ProfileIcons.ALL.map { it.key }.toSet().size)
        assertEquals(ProfileIcons.ALL.size, ProfileIcons.ALL.map { it.drawable }.toSet().size)
    }

    @Test
    fun `les cles deja enregistrees gardent leur icone`() {
        val before = listOf(
            "home", "work", "dashboard", "widgets", "apps", "sound", "laptop", "display", "tablet", "keyboard", "code", "agents",
            "chat", "mail", "call", "video", "mic", "present", "event", "tasks", "doc", "timer", "alarm", "book", "school", "idea",
            "music", "note", "headphones", "camera", "palette", "image", "folder", "speed", "bolt", "fitness", "cafe", "water",
            "cart", "flight", "place", "sun", "cloud", "night", "star", "valorant", "heart", "lock",
        )
        assertEquals(emptyList<String>(), before.filter { ProfileIcons.drawable(it) == null })
    }

    @Test
    fun `la grille est rangee par themes, chaque icone dans un seul`() {
        assertEquals(ProfileIcons.GROUPS.flatMap { it.icons }, ProfileIcons.ALL)
        assertTrue(ProfileIcons.GROUPS.size >= 6)
        assertTrue(ProfileIcons.GROUPS.all { it.icons.isNotEmpty() })
        assertTrue(ProfileIcons.ALL.size >= 100)
    }

    @Test
    fun `maison, sport, voyage, medias et nature ont leurs icones`() {
        for (key in listOf("bed", "run", "train", "gamepad", "movie", "forest", "pets", "trophy")) {
            assertTrue(key, ProfileIcons.drawable(key) != null)
        }
    }
}
