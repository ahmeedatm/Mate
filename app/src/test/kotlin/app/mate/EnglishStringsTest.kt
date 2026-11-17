package app.mate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.text.SimpleDateFormat
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Textes anglais (`values-en`, Réglages > Langue) face aux textes français : même clés, mêmes valeurs insérées,
 * apostrophes échappées, pluriels complets. Un texte français sans traduction doit le dire
 * (`tools:ignore="MissingTranslation"`, commande vocale) ou ne pas se traduire (`translatable="false"`).
 */
class EnglishStringsTest {

    private class Res(val attrs: Map<String, String>, val texts: List<String>, val quantities: Set<String>)

    private fun read(path: String): Map<String, Res> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val out = LinkedHashMap<String, Res>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            val attrs = (0 until e.attributes.length).associate { e.attributes.item(it).nodeName to e.attributes.item(it).nodeValue }
            val items = e.getElementsByTagName("item")
            val texts = if (e.tagName == "string") listOf(e.textContent) else (0 until items.length).map { items.item(it).textContent }
            val quantities = (0 until items.length).mapNotNull { (items.item(it) as Element).getAttribute("quantity").ifEmpty { null } }.toSet()
            out[e.getAttribute("name")] = Res(attrs, texts, quantities)
        }
        return out
    }

    private val fr = read("src/main/res/values/strings.xml")
    private val en = read("src/main/res/values-en/strings.xml")
    private val rawEn = File("src/main/res/values-en/strings.xml").readText()

    private fun placeholders(text: String) = Regex("""%(\d+\$)?[-#+0,(]*\d*(\.\d+)?[sdfxXc%]""").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `chaque texte anglais traduit un texte francais existant`() {
        val unknown = en.keys - fr.keys
        assertTrue("Clés anglaises sans texte français : $unknown", unknown.isEmpty())
        val fixed = en.keys.filter { fr.getValue(it).attrs["translatable"] == "false" }
        assertTrue("Textes à ne pas traduire, traduits : $fixed", fixed.isEmpty())
    }

    @Test
    fun `chaque texte francais a sa traduction, sauf exception dite`() {
        val missing = fr.filter { (name, res) ->
            name !in en && res.attrs["translatable"] != "false" && res.attrs["tools:ignore"]?.contains("MissingTranslation") != true
        }.keys
        assertTrue("Textes sans traduction anglaise : $missing", missing.isEmpty())
    }

    @Test
    fun `memes valeurs inserees, en meme nombre`() {
        for ((name, res) in en) {
            val french = fr.getValue(name)
            assertEquals(name, placeholders(french.texts.joinToString(" ")), placeholders(res.texts.joinToString(" ")))
        }
    }

    @Test
    fun `apostrophes echappees et pluriels complets`() {
        // Une apostrophe sans \ casse la compilation des ressources ou disparaît à l'affichage.
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(rawEn).forEach { m ->
            val text = m.groupValues[2]
            if (!(text.startsWith("\"") && text.endsWith("\""))) {
                assertTrue("${m.groupValues[1]} : apostrophe non échappée", !Regex("""(?<!\\)'""").containsMatchIn(text))
            }
        }
        for ((name, res) in en) {
            if (fr.getValue(name).quantities.isNotEmpty()) {
                assertTrue("$name : pluriel sans one ou other", res.quantities.containsAll(setOf("one", "other")))
            }
        }
    }

    @Test
    fun `formats de date anglais valides`() {
        for (name in listOf("checklist_due_format", "agents_clock_format")) {
            val pattern = en.getValue(name).texts.single().replace("\\'", "'")
            SimpleDateFormat(pattern, AppLanguage.ENGLISH.locale).format(0L)
        }
    }
}
