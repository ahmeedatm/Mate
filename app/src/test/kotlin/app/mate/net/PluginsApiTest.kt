package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginsApiTest {

    @Test
    fun `appli trouvee et plugin integre`() {
        val json = """{"plugins":[
            {"id":"discord","kind":"app","name":"Discord","description":"Micro","app":"Discord","on":true,"fresh":true},
            {"id":"mixer","kind":"native","name":"Mixage du son","description":"Volumes","app":null,"on":false,"fresh":false}
        ]}"""
        assertEquals(
            listOf(
                Plugin("discord", "Discord", "Micro", "Discord", native = false, on = true, fresh = true),
                Plugin("mixer", "Mixage du son", "Volumes", null, native = true, on = false, fresh = false),
            ),
            parsePlugins(json),
        )
    }

    @Test
    fun `champs absents actif et deja propose, nom tire de l'id`() {
        assertEquals(listOf(Plugin("open", "open", "", null, native = false, on = true, fresh = false)), parsePlugins("""{"plugins":[{"id":"open"}]}"""))
    }

    @Test
    fun `sans liste aucun plugin`() {
        assertEquals(emptyList<Plugin>(), parsePlugins("{}"))
    }
}
