package com.unowhy.companion.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureRulesTest {

    private fun owner(longPress: Boolean, swipe: Boolean) = object : GestureOwner {
        override val ownsLongPress = longPress
        override val ownsSwipe = swipe
    }

    @Test fun `sans proprietaire, l'appui long et le balayage restent a la grille`() {
        assertTrue(GestureRules.allowLongPress(null, onSlider = false))
        assertTrue(GestureRules.allowSwipe(null, onSlider = false, narrow = false))
    }

    @Test fun `un curseur garde les deux gestes`() {
        assertFalse(GestureRules.allowLongPress(null, onSlider = true))
        assertFalse(GestureRules.allowSwipe(null, onSlider = true, narrow = false))
    }

    @Test fun `un proprietaire d'appui long l'empeche, pas le balayage`() {
        val o = owner(longPress = true, swipe = false)
        assertFalse(GestureRules.allowLongPress(o, onSlider = false))
        assertTrue(GestureRules.allowSwipe(o, onSlider = false, narrow = false))
    }

    @Test fun `un proprietaire de balayage l'empeche, pas l'appui long`() {
        val o = owner(longPress = false, swipe = true)
        assertTrue(GestureRules.allowLongPress(o, onSlider = false))
        assertFalse(GestureRules.allowSwipe(o, onSlider = false, narrow = false))
    }

    @Test fun `en mode etroit le balayage est toujours refuse`() {
        assertFalse(GestureRules.allowSwipe(null, onSlider = false, narrow = true))
        assertFalse(GestureRules.allowSwipe(owner(longPress = false, swipe = false), onSlider = false, narrow = true))
    }
}
