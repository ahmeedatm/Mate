package com.unowhy.companion.ui

import com.unowhy.companion.R
import com.unowhy.companion.net.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pastille de connexion : chaque état a sa forme et son mot, lisibles sans distinguer le vert du rouge. */
class LinkMarkTest {
    @Test
    fun `trois etats d un ordinateur trois formes`() {
        val shapes = listOf(Link.State.ONLINE, Link.State.UNAUTHORIZED, Link.State.OFFLINE).map { linkMark(it).shape }
        assertEquals(listOf(LinkShape.FILLED, LinkShape.RING, LinkShape.CROSS), shapes)
    }

    @Test
    fun `etat ecrit a cote du nom`() {
        assertEquals(R.string.link_state_online, linkMark(Link.State.ONLINE).label)
        assertEquals(R.string.link_state_unauthorized, linkMark(Link.State.UNAUTHORIZED).label)
        assertEquals(R.string.link_state_offline, linkMark(Link.State.OFFLINE).label)
    }

    @Test
    fun `sans ordinateur le nom dit deja Aucun`() {
        val mark = linkMark(Link.State.NONE)
        assertNull(mark.label)
        assertEquals(LinkShape.CROSS, mark.shape)
        assertEquals(R.string.no_computer, mark.spoken)
    }

    @Test
    fun `TalkBack garde les phrases longues`() {
        assertEquals(R.string.online, linkMark(Link.State.ONLINE).spoken)
        assertEquals(R.string.unauthorized, linkMark(Link.State.UNAUTHORIZED).spoken)
        assertEquals(R.string.offline, linkMark(Link.State.OFFLINE).spoken)
    }
}
