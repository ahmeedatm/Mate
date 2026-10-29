package com.unowhy.unomate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CitiesTest {

    /** Extrait au format de assets/villes.tsv (triée par population décroissante). */
    private val sample = Cities.parse(
        """
        # Villes de GeoNames, licence CC BY 4.0.
        Paris	Île-de-France	FR	48.8534	2.3488	2138551
        Saint-Étienne	Auvergne-Rhône-Alpes	FR	45.4339	4.3900	171483
        Clermont-Ferrand	Auvergne-Rhône-Alpes	FR	45.7797	3.0869	147284
        Brest	Brest	BY	52.1089	23.7175	347138
        Brest	Bretagne	FR	48.3903	-4.4863	144899
        Le Mans	Pays de la Loire	FR	48.0077	0.1984	144515
        Parisot	Occitanie	FR	44.2639	1.8608	1012
        ligne cassée
        """.trimIndent().lineSequence(),
    )

    private fun names(query: String) = Cities.search(sample, query).map { "${it.name}/${it.country}" }

    @Test
    fun `ignore accents, majuscules et traits d'union`() {
        assertEquals(listOf("Saint-Étienne/FR"), names("saint etienne"))
        assertEquals(listOf("Saint-Étienne/FR"), names("ST-ÉTIENNE".replace("ST", "SAINT")))
    }

    @Test
    fun `nom exact d'abord, puis debut du nom, les plus peuples d'abord`() {
        assertEquals(listOf("Paris/FR", "Parisot/FR"), names("paris"))
        assertEquals(listOf("Brest/BY", "Brest/FR"), names("Brest"))
    }

    @Test
    fun `trouve un mot dans le nom`() {
        assertEquals(listOf("Clermont-Ferrand/FR"), names("ferrand"))
        assertEquals(listOf("Le Mans/FR"), names("mans"))
    }

    @Test
    fun `ville la plus proche d'une position`() {
        // Place de la Bastille, puis le port de Brest, puis Minsk.
        assertEquals("Paris", Cities.nearest(sample, 48.853, 2.369)?.name)
        assertEquals("FR", Cities.nearest(sample, 48.38, -4.49)?.country)
        assertEquals("BY", Cities.nearest(sample, 53.9, 27.56)?.country)
    }

    @Test
    fun `distance a l'echelle d'une ville`() {
        // Paris - Lyon : environ 392 km à vol d'oiseau.
        val d = Cities.distanceKm(48.8534, 2.3488, 45.7491, 4.8479)
        assertTrue("$d km", d in 385.0..400.0)
    }

    @Test
    fun `rien sous deux lettres, lignes invalides ignorees`() {
        assertEquals(emptyList<String>(), names("p"))
        assertEquals(7, sample.size)
    }

    @Test
    fun `libelle en francais, sans repeter le nom comme region`() {
        val brest = sample.filter { it.name == "Brest" }
        assertEquals("Brest · Biélorussie", Cities.label(brest[0]))
        assertEquals("Brest · Bretagne, France", Cities.label(brest[1]))
    }

    @Test
    fun `la liste embarquee se lit et contient les villes de France`() {
        val file = File("src/main/assets/villes.tsv")
        val all = Cities.parse(file.readLines().asSequence())
        assertTrue("au moins 20 000 villes", all.size > 20_000)
        assertEquals("FR", Cities.search(all, "Lannion").first().country)
        // Clé calculée par scripts/villes.mjs = celle de la tablette, sinon une ville ne se trouverait plus.
        val wrong = all.filter { it.key != Cities.normalize(it.name) }.map { it.name }
        assertEquals(emptyList<String>(), wrong)
    }
}
