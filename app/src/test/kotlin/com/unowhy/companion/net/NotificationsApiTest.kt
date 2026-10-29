package com.unowhy.companion.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationsApiTest {

    @Test
    fun `liste complete dans l ordre recu`() {
        val json = """{"items":[
            {"id":"2","app":"Teams","title":"Équipe","body":"Réunion à 15 h","at":1793264400000},
            {"id":"1","app":"Outlook","title":"Marie Dupont","body":"Réunion déplacée","at":1793264328285}
        ]}"""
        assertEquals(
            listOf(
                PcNotification("2", "Teams", "Équipe", "Réunion à 15 h", 1793264400000),
                PcNotification("1", "Outlook", "Marie Dupont", "Réunion déplacée", 1793264328285),
            ),
            parseNotifications(json),
        )
    }

    @Test
    fun `titre et texte vides ou absents deviennent des chaines vides`() {
        val json = """{"items":[
            {"id":"1","app":"Discord","title":"","body":"","at":1},
            {"id":"2","app":"Discord","title":null,"at":2}
        ]}"""
        assertEquals(
            listOf(PcNotification("1", "Discord", "", "", 1), PcNotification("2", "Discord", "", "", 2)),
            parseNotifications(json),
        )
    }

    @Test
    fun `entree sans identifiant ou sans appli ignoree`() {
        val json = """{"items":[
            {"app":"Outlook","title":"a","body":"b","at":1},
            {"id":"2","title":"a","body":"b","at":1},
            {"id":"3","app":"","title":"a","body":"b","at":1},
            {"id":"4","app":"Teams","title":"ok","body":"","at":1},
            "pas un objet"
        ]}"""
        assertEquals(listOf(PcNotification("4", "Teams", "ok", "", 1)), parseNotifications(json))
    }

    @Test
    fun `liste vide ou sans items`() {
        assertEquals(emptyList<PcNotification>(), parseNotifications("""{"items":[]}"""))
        assertEquals(emptyList<PcNotification>(), parseNotifications("""{}"""))
    }

    @Test
    fun `ordre notification recu en direct`() {
        val command = JSONObject(
            """{"type":"notification","id":"12","app":"Outlook","title":"Marie Dupont","body":"Réunion déplacée à 15 h","at":1793264328285}""",
        )
        assertEquals(
            PcNotification("12", "Outlook", "Marie Dupont", "Réunion déplacée à 15 h", 1793264328285),
            parseNotification(command),
        )
        assertNull(parseNotification(JSONObject("""{"type":"notification","app":"Outlook"}""")))
    }
}
