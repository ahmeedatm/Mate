package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class EditButtonsTest {

    @Test fun `un widget qui s'agrandit a le bouton Agrandir`() {
        assertEquals(
            listOf(EditButton.REMOVE, EditButton.SETTINGS, EditButton.RESIZE, EditButton.EXPAND),
            EditButtons.of(expandable = true, hasSettings = true, resizable = true),
        )
    }

    @Test fun `sans agrandissement, ni reglages, ni autre taille, il ne reste que retirer`() {
        assertEquals(listOf(EditButton.REMOVE), EditButtons.of(expandable = false, hasSettings = false, resizable = false))
    }

    @Test fun `chaque bouton a son propre coin`() {
        val corners = EditButton.entries.map { it.corner }
        assertEquals(corners.size, corners.toSet().size)
        assertEquals(EditButton.Corner.BOTTOM_LEFT, EditButton.EXPAND.corner)
    }
}
