package com.unowhy.companion.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardApiTest {

    @Test
    fun `texte sans coupe`() {
        assertEquals(ClipContent.Text("bonjour", false), parseClip("""{"kind":"text","text":"bonjour"}"""))
    }

    @Test
    fun `texte coupe par le compagnon`() {
        assertEquals(ClipContent.Text("abc", true), parseClip("""{"kind":"text","text":"abc","truncated":true}"""))
    }

    @Test
    fun `le texte garde ses retours a la ligne et ses accents`() {
        assertEquals(ClipContent.Text("é\nà", false), parseClip("""{"kind":"text","text":"é\nà"}"""))
    }

    @Test
    fun `texte sans champ text devient vide`() {
        assertEquals(ClipContent.Text("", false), parseClip("""{"kind":"text"}"""))
    }

    @Test
    fun `texte null devient vide et pas la chaine null`() {
        assertEquals(ClipContent.Text("", false), parseClip("""{"kind":"text","text":null}"""))
    }

    @Test
    fun `chaque kind sans texte`() {
        assertEquals(ClipContent.Image, parseClip("""{"kind":"image"}"""))
        assertEquals(ClipContent.Empty, parseClip("""{"kind":"empty"}"""))
        assertEquals(ClipContent.Protected, parseClip("""{"kind":"protected"}"""))
        assertEquals(ClipContent.Other, parseClip("""{"kind":"other"}"""))
    }

    @Test
    fun `kind inconnu ou absent devient other`() {
        assertEquals(ClipContent.Other, parseClip("""{"kind":"video"}"""))
        assertEquals(ClipContent.Other, parseClip("""{}"""))
    }
}
