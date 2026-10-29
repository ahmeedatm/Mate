package com.unowhy.unomate.ui.home

import com.unowhy.unomate.FakeContext
import com.unowhy.unomate.Mode
import com.unowhy.unomate.Prefs
import com.unowhy.unomate.net.parseDemoSetup
import com.unowhy.unomate.ui.RailModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonasTest {

    /** Une page de 3 touches par titre de page utilisé par un persona (ids comme ceux du compagnon : `k<rang>-<emplacement>`). */
    private val pages: Map<String, List<KeyRef>> = Persona.entries
        .flatMap { p -> p.profiles.flatMap { it.slots.mapNotNull { s -> s.page } } }
        .distinct()
        .mapIndexed { n, title -> title to (0 until 3).map { KeyRef("k${n + 1}-$it", "$title $it", symbol = "lock") } }
        .toMap()

    private fun layout(persona: Persona, ofPages: Map<String, List<KeyRef>> = pages) =
        persona.layout(persona.profiles.map { "${persona.name} ${it.name}" }, "Réunion", ofPages, "ordi")

    private fun types(persona: Persona) = persona.profiles.flatMap { p -> p.slots.map { it.type } }.toSet()

    @Test
    fun `six personas aux ids uniques`() {
        assertEquals(6, Persona.entries.size)
        assertEquals(Persona.entries.size, Persona.entries.map { it.id }.toSet().size)
        assertEquals(Persona.SACHA, Persona.of("sacha"))
        assertNull(Persona.of("inconnu"))
        assertEquals(listOf("nour", "gwen", "camille", "aimee", "sam", "sacha"), Persona.entries.map { it.id })
    }

    @Test
    fun `chaque persona donne ses ecrans nommes, relisibles tels quels`() {
        for (persona in Persona.entries) {
            val layout = layout(persona)
            // Ses écrans dans l'ordre, puis Réunion en dernier.
            assertEquals(persona.profiles.map { "${persona.name} ${it.name}" } + "Réunion", layout.names)
            assertTrue(persona.profiles.all { it.slots.isNotEmpty() })
            val context = FakeContext()
            HomeLayout.save(context, layout, cadran = true)
            // `parse` refuse un widget hors grille ou qui en chevauche un autre : la taille de chacun est aussi gardée.
            val back = HomeLayout.load(context, cadran = true)
            assertEquals(persona.name, layout.widgets.size, back.widgets.size)
            assertEquals(layout.widgets.map { it.size }, back.widgets.map { it.size })
            assertEquals(layout.widgets.map { it.page }, back.widgets.map { it.page })
            assertTrue(layout.widgets.all { it.size in it.type.sizes })
        }
    }

    @Test
    fun `les ecrans de chaque persona`() {
        val names = Persona.entries.associateWith { p -> p.profiles.map { it.slots.map { s -> s.type } } }
        assertEquals(
            listOf(
                listOf(WidgetType.KEYS, WidgetType.KEYS, WidgetType.OPEN),
                listOf(WidgetType.KEYS, WidgetType.APPS, WidgetType.OPEN),
                listOf(WidgetType.CHECKLIST, WidgetType.AGENDA, WidgetType.TASKS),
            ),
            names[Persona.NOUR],
        )
        assertEquals(3, Persona.GWEN.profiles.size)
        assertEquals(2, Persona.CAMILLE.profiles.size)
        assertEquals(3, Persona.AIMEE.profiles.size)
        assertEquals(1, Persona.SAM.profiles.size)
        assertEquals(4, Persona.SACHA.profiles.size)
        assertEquals(setOf(WidgetType.NEXT, WidgetType.TASKS, WidgetType.AGENDA, WidgetType.OPEN, WidgetType.KEYS, WidgetType.JIRA), types(Persona.AIMEE))
        assertEquals(setOf(WidgetType.AGENTS, WidgetType.KEYS, WidgetType.GITLAB, WidgetType.JIRA, WidgetType.TASKS), types(Persona.SACHA))
        assertEquals(setOf(WidgetType.KEYS, WidgetType.APPS), types(Persona.SAM))
        assertEquals(setOf(WidgetType.FOCUS, WidgetType.NEXT, WidgetType.KEYS, WidgetType.MEDIA, WidgetType.TASKS, WidgetType.AWAY), types(Persona.CAMILLE))
        assertEquals(setOf(WidgetType.AGENDA, WidgetType.TASKS, WidgetType.KEYS, WidgetType.MEDIA, WidgetType.APPS, WidgetType.PRESENT), types(Persona.GWEN))
        // Les personas ne se ressemblent pas, et aucun n'a le héros ni la ligne du jour.
        assertEquals(Persona.entries.size, Persona.entries.map { p -> p.profiles.map { pr -> pr.slots.map { it.type to it.size } } }.toSet().size)
        assertTrue(Persona.entries.none { WidgetType.HERO in types(it) || WidgetType.DAYLINE in types(it) })
        // La barre tient au plus 5 profils : aucun persona n'y met « Plus ».
        assertTrue(Persona.entries.all { it.profiles.size < RailModel.MAX_PROFILES })
    }

    @Test
    fun `le premier ecran est l'Accueil et l'arrivee, Reunion est le profil d'appel standard, jamais de Deck`() {
        for (persona in Persona.entries) {
            val layout = layout(persona)
            assertEquals(persona.name, 0, layout.homeProfile)
            assertEquals(persona.name, -1, layout.deckProfile)
            assertEquals(persona.name, persona.profiles.size, layout.callProfile)
            assertTrue(layout.rolesSet)
            // Hors appel, la barre ne montre que les écrans du persona ; en appel, Réunion vient en dernier.
            assertEquals((0 until persona.profiles.size).toList(), layout.profilesInOrder(inCall = false))
            assertEquals((0..persona.profiles.size).toList(), layout.profilesInOrder(inCall = true))
            fun shape(list: List<Placed>) = list.map { listOf(it.type, it.size, it.col, it.row) }
            assertEquals(shape(Template.CALL_CADRAN.widgets(0)), shape(layout.on(layout.callProfile)))
            // Rien n'est reposé au chargement : Réunion déjà là, pas de Deck à retirer.
            assertFalse(layout.ensureRoles("Réunion"))
            assertFalse(layout.dropDefaultDeck())
        }
    }

    @Test
    fun `les roles survivent a l'enregistrement`() {
        val context = FakeContext()
        HomeLayout.save(context, layout(Persona.SACHA), cadran = true)
        val back = HomeLayout.load(context, cadran = true)
        assertEquals(0, back.homeProfile)
        assertEquals(4, back.callProfile)
        assertEquals(-1, back.deckProfile)
        assertTrue(back.rolesSet)
        assertEquals(back.homeProfile, back.deckTarget())
    }

    @Test
    fun `un ecran de plus que de noms est refuse`() {
        val failed = runCatching { Persona.GWEN.layout(listOf("Gwen"), "Réunion", pages, "ordi") }
        assertTrue(failed.isFailure)
    }

    @Test
    fun `un widget Touches montre les touches de sa page nommee`() {
        val layout = layout(Persona.NOUR)
        val shown = layout.widgets.filter { it.page == 0 && it.type == WidgetType.KEYS }.map { w -> KeyRef.listFor(w.config, "ordi") }
        assertEquals(listOf(pages["Excel"], pages["Libellés"]), shown)
        // Les symboles des touches suivent.
        assertTrue(shown.flatten().all { it.symbol == "lock" })
        val sacha = layout(Persona.SACHA).widgets.filter { it.page == 0 && it.type == WidgetType.KEYS }.map { w -> KeyRef.listFor(w.config, "ordi").map { it.label } }
        assertEquals(listOf(pages["Git"], pages["Tests"], pages["Déploiement"]).map { p -> p!!.map { it.label } }, sacha)
    }

    @Test
    fun `Nour a une checklist de cloture a six etapes, echeance dans deux jours a 18 heures`() {
        val now = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.OCTOBER, 29, 21, 30, 0) }.timeInMillis
        val layout = Persona.NOUR.layout(Persona.NOUR.profiles.map { it.name.toString() }, "Réunion", pages, "ordi", now)
        val widget = layout.widgets.single { it.type == WidgetType.CHECKLIST }
        assertEquals(2, widget.page)
        val list = Checklist.read(widget.config)
        assertEquals(6, list.total)
        assertEquals(listOf(true, true, false, false, false, false), list.steps.map { it.done })
        assertTrue(list.dueMs!! > now)
        assertEquals(Countdown.Days(2), list.countdown(now))
        val due = java.util.Calendar.getInstance().apply { timeInMillis = list.dueMs!! }
        assertEquals(18, due.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(31, due.get(java.util.Calendar.DAY_OF_MONTH))
        // L'écran tient sur la grille 4 × 4, sans case libre : 8 + 4 + 4 cases = 16.
        val onHome = layout.on(2)
        assertEquals(COLS * ROWS, onHome.sumOf { it.size.w * it.size.h })
        // Les autres personas n'ont pas de checklist, et deux appels ne partagent pas la config.
        assertTrue((Persona.entries - Persona.NOUR).none { WidgetType.CHECKLIST in types(it) })
        assertEquals(6, Checklist.read(layout.widgets.single { it.type == WidgetType.CHECKLIST }.config).total)
    }

    @Test
    fun `les ecrans de Nour remplissent la grille`() {
        val layout = layout(Persona.NOUR)
        for (page in 0..2) assertEquals("écran $page", COLS * ROWS, layout.on(page).sumOf { it.size.w * it.size.h })
    }

    @Test
    fun `Gwen a le widget Presentation en XL sur son ecran Presentation, au-dessus du Media`() {
        val layout = layout(Persona.GWEN)
        val slides = layout.widgets.filter { it.page == 1 }
        val present = slides.single { it.type == WidgetType.PRESENT }
        assertEquals(WidgetSize.XL, present.size)
        assertEquals(0, present.row)
        assertTrue(slides.none { it.type == WidgetType.KEYS })
        val media = slides.single { it.type == WidgetType.MEDIA }
        assertTrue(media.row >= present.row + present.size.h)
        // Le widget ne demande aucune page de touches : rien à lire côté compagnon.
        assertEquals(emptyList<KeyRef>(), KeyRef.listFor(present.config, "ordi"))
    }

    @Test
    fun `une page absente du compagnon laisse le widget Touches vide, sans planter`() {
        val widget = layout(Persona.NOUR, emptyMap()).widgets.first { it.type == WidgetType.KEYS }
        assertEquals(emptyList<KeyRef>(), KeyRef.listFor(widget.config, "ordi"))
    }

    @Test
    fun `chaque page de touches d'un persona est unique par ecran`() {
        for (persona in Persona.entries) {
            for (profile in persona.profiles) {
                val used = profile.slots.mapNotNull { it.page }
                assertEquals(persona.name, used.size, used.toSet().size)
                assertTrue(profile.slots.all { (it.type == WidgetType.KEYS) == (it.page != null) })
            }
        }
    }

    @Test
    fun `Camille a Je m'absente sur son ecran Travail, avec Focus et le prochain rendez-vous, grille pleine`() {
        val work = layout(Persona.CAMILLE).on(0)
        assertEquals(1, work.count { it.type == WidgetType.AWAY })
        assertTrue(work.any { it.type == WidgetType.FOCUS } && work.any { it.type == WidgetType.NEXT })
        assertEquals(COLS * ROWS, work.sumOf { it.size.w * it.size.h })
        assertTrue(Persona.entries.filter { it != Persona.CAMILLE }.none { WidgetType.AWAY in types(it) })
    }

    @Test
    fun `les noms des personas`() {
        for (old in listOf("sylvie", "julie", "thomas", "karim", "leo")) assertNull(old, Persona.of(old))
    }

    @Test
    fun `Sam a le plus grand texte et le mot d'eveil, les autres le texte normal`() {
        assertEquals(1.5f, Persona.SAM.textScale)
        assertEquals(true, Persona.SAM.wakeWord)
        for (persona in Persona.entries - Persona.SAM) {
            assertEquals(1f, persona.textScale)
            assertNull(persona.wakeWord)
        }
    }

    @Test
    fun `les widgets de Sam sont tous en XL`() {
        assertTrue(layout(Persona.SAM).on(0).all { it.size == WidgetSize.XL })
    }

    @Test
    fun `deux appels ne partagent pas leurs widgets`() {
        val first = layout(Persona.GWEN)
        val second = layout(Persona.GWEN)
        first.widgets.first().col = 3
        assertEquals(0, second.widgets.first().col)
    }

    @Test
    fun `les reponses du compagnon donnent les touches de Perso et des pages posees`() {
        val json = """{"persona":"sacha","keys":[{"id":"k-0","label":"Tests","glyph":"🧪","color":"#2F855A"},{"id":"k-1","label":"Build"}],
            |"pages":[{"title":"Git","keys":[{"id":"k1-0","label":"Status","symbol":"search"}]},{"title":"Tests","keys":[{"id":"k2-0","label":"Tout"}]}]}""".trimMargin()
        val parsed = parseDemoSetup(json)
        assertEquals(listOf("k-0", "k-1"), parsed.keys.map { it.id })
        assertEquals("🧪", parsed.keys[0].glyph)
        assertNull(parsed.keys[1].glyph)
        assertEquals(listOf("Git", "Tests"), parsed.pages.keys.toList())
        assertEquals("search", parsed.pages["Git"]!![0].symbol)
        assertNull(parsed.pages["Tests"]!![0].symbol)
        // Compagnon d'avant les pages : aucune page.
        assertTrue(parseDemoSetup("""{"keys":[]}""").pages.isEmpty())
    }

    @Test
    fun `la demo remplace les ecrans puis rend la disposition d'avant, roles compris`() {
        val context = FakeContext()
        val mine = HomeLayout(mutableListOf(), 1)
        mine.addProfile("Deck", listOf(Placed(WidgetType.APPS, WidgetSize.L, 0, 0, 0)))
        mine.addProfile("Maison", listOf(Placed(WidgetType.NOTE, WidgetSize.S, 0, 3, 3)))
        mine.ensureRoles("Réunion")
        mine.deckProfile = 1
        mine.homeProfile = 0
        HomeLayout.save(context, mine, cadran = true)
        val before = HomeLayout.raw(context, cadran = true)
        Prefs.setTheme(context, "RETRO")

        Demo.start(context, Persona.NOUR, layout(Persona.NOUR), cadran = true)
        assertEquals(Persona.NOUR, Demo.active(context))
        assertEquals(layout(Persona.NOUR).names, HomeLayout.load(context, cadran = true).names)

        // Un autre persona garde la sauvegarde d'origine.
        Demo.start(context, Persona.SAM, layout(Persona.SAM), cadran = true)
        assertEquals(Persona.SAM, Demo.active(context))

        assertTrue(Demo.quit(context, cadran = true))
        assertNull(Demo.active(context))
        assertEquals(before, HomeLayout.raw(context, cadran = true))
        val back = HomeLayout.load(context, cadran = true)
        assertEquals(mine.names, back.names)
        assertEquals(1, back.deckProfile)
        assertEquals(3, back.callProfile)
        assertEquals("RETRO", Prefs.theme(context))
        assertFalse(Demo.quit(context, cadran = true))
    }

    @Test
    fun `quitter rend le modele par defaut quand rien n'etait enregistre`() {
        val context = FakeContext()
        Demo.start(context, Persona.SACHA, layout(Persona.SACHA), cadran = true)
        assertTrue(Demo.quit(context, cadran = true))
        assertNull(HomeLayout.raw(context, cadran = true))
        assertNull(Prefs.theme(context))
        assertEquals(Template.defaultFor(true).build().widgets.map { it.type }, HomeLayout.load(context, cadran = true).widgets.map { it.type })
    }

    @Test
    fun `le theme du persona s'applique puis est rendu`() {
        val context = FakeContext()
        Prefs.setTheme(context, "CADRAN")
        Demo.start(context, Persona.AIMEE, layout(Persona.AIMEE), cadran = true)
        assertEquals("TABLEAU", Prefs.theme(context))
        // Sam reçoit le thème Contraste élevé, chaque autre persona le sien (thèmes de démo, voir AppThemeTest).
        assertEquals("CONTRASTE", Persona.SAM.theme)
        assertTrue(Persona.entries.all { it.theme != null })
        Demo.start(context, Persona.SAM, layout(Persona.SAM), cadran = true)
        assertEquals("CONTRASTE", Prefs.theme(context))
        Demo.start(context, Persona.NOUR, layout(Persona.NOUR), cadran = true)
        assertEquals("REGISTRE", Prefs.theme(context))
        Demo.start(context, Persona.SAM, layout(Persona.SAM), cadran = true)
        Demo.quit(context, cadran = true)
        assertEquals("CADRAN", Prefs.theme(context))
    }

    @Test
    fun `la demo garde puis rend la taille du texte et le mot d'eveil`() {
        val context = FakeContext()
        Prefs.setTextScale(context, 1.15f)
        Prefs.setWakeWord(context, false)

        Demo.start(context, Persona.SAM, layout(Persona.SAM), cadran = true)
        assertEquals(1.5f, Prefs.textScale(context))
        assertTrue(Prefs.wakeWord(context))

        // Un persona sans mot d'éveil rend celui d'avant la démo ; le texte redevient normal.
        Demo.start(context, Persona.AIMEE, layout(Persona.AIMEE), cadran = true)
        assertEquals(1f, Prefs.textScale(context))
        assertFalse(Prefs.wakeWord(context))

        assertTrue(Demo.quit(context, cadran = true))
        assertEquals(1.15f, Prefs.textScale(context))
        assertFalse(Prefs.wakeWord(context))
    }

    @Test
    fun `Aimee allume l'alerte de reunion, quitter rend la valeur d'avant`() {
        assertEquals(true, Persona.AIMEE.meetingAlert)
        for (persona in Persona.entries - Persona.AIMEE) assertNull(persona.id, persona.meetingAlert)

        // Coupée avant la démo : Aimée l'allume, un autre persona rend la valeur d'avant, quitter aussi.
        val context = FakeContext()
        assertFalse(Prefs.meetingAlert(context))
        Demo.start(context, Persona.AIMEE, layout(Persona.AIMEE), cadran = true)
        assertTrue(Prefs.meetingAlert(context))
        Demo.start(context, Persona.NOUR, layout(Persona.NOUR), cadran = true)
        assertFalse(Prefs.meetingAlert(context))
        Demo.start(context, Persona.AIMEE, layout(Persona.AIMEE), cadran = true)
        assertTrue(Demo.quit(context, cadran = true))
        assertFalse(Prefs.meetingAlert(context))

        // Allumée avant la démo : elle le reste après.
        Prefs.setMeetingAlert(context, true)
        Demo.start(context, Persona.AIMEE, layout(Persona.AIMEE), cadran = true)
        assertTrue(Demo.quit(context, cadran = true))
        assertTrue(Prefs.meetingAlert(context))
    }

    @Test
    fun `Sam allume le retour parle, quitter rend la valeur d'avant`() {
        assertEquals(true, Persona.SAM.speakActions)
        for (persona in Persona.entries - Persona.SAM) assertNull(persona.id, persona.speakActions)

        val context = FakeContext()
        assertFalse(Prefs.speakActions(context))
        Demo.start(context, Persona.SAM, layout(Persona.SAM), cadran = true)
        assertTrue(Prefs.speakActions(context))
        Demo.start(context, Persona.NOUR, layout(Persona.NOUR), cadran = true)
        assertFalse(Prefs.speakActions(context))
        Demo.start(context, Persona.SAM, layout(Persona.SAM), cadran = true)
        assertTrue(Demo.quit(context, cadran = true))
        assertFalse(Prefs.speakActions(context))

        // Allumé avant la démo : il le reste après.
        Prefs.setSpeakActions(context, true)
        Demo.start(context, Persona.SAM, layout(Persona.SAM), cadran = true)
        assertTrue(Demo.quit(context, cadran = true))
        assertTrue(Prefs.speakActions(context))
    }

    @Test
    fun `l'arrivee sur l'Accueil se lit une seule fois`() {
        val context = FakeContext()
        assertNull(Demo.takeArrival(context))

        Demo.start(context, Persona.SACHA, layout(Persona.SACHA), cadran = true)
        assertEquals(Mode.HOME, Demo.takeArrival(context))
        assertNull(Demo.takeArrival(context))

        Demo.start(context, Persona.GWEN, layout(Persona.GWEN), cadran = true)
        // Quitter efface ce qui n'a pas été lu.
        Demo.quit(context, cadran = true)
        assertNull(Demo.takeArrival(context))
    }
}
