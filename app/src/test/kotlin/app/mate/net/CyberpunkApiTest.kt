package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CyberpunkApiTest {
    @Test
    fun readsPresence() {
        val p = CyberpunkApi.parsePresence(
            """{"available":true,"gameFound":true,"modInstalled":true,"running":true,"inGame":true,"session":"s-42"}""",
        )
        assertTrue(p.available)
        assertTrue(p.inGame)
        assertEquals("s-42", p.session)
    }

    @Test
    fun presenceToleratesMissingFields() {
        val p = CyberpunkApi.parsePresence("""{"available":true}""")
        assertTrue(p.available)
        assertFalse(p.inGame)
        assertFalse(p.running)
        assertNull(p.session)
    }

    @Test
    fun unavailablePresenceCannotBeInGame() {
        assertFalse(CyberpunkPresence.UNAVAILABLE.available)
        assertFalse(CyberpunkPresence.UNAVAILABLE.inGame)
    }

    @Test
    fun readsTheLiveState() {
        val s = CyberpunkApi.parseSnapshot(
            """{"status":"ingame","install":{"gameFound":true,"cet":true,"modInstalled":true,"modVersion":"1.0.0","current":"1.0.0","pending":false},
               "state":{"v":1,"seq":12,"at":1,"inGame":true,"session":"abc","mod":"1.0.0",
                 "player":{"level":15,"cred":12,"attrs":{"body":4,"ref":5,"tech":9,"int":9,"cool":9},"health":86,"humanity":89,"money":16937,"pts":{"attr":1,"perk":2}},
                 "pos":{"x":1.5,"y":-2,"z":3,"yaw":90},
                 "cyberware":{"Eyes":[{"tdb":"Items.X","name":"Optiques","quality":"Common"}],"Legs":[false]},
                 "tracked":{"kind":"quest","id":"q1","quest":"Disasterpiece","desc":"Rejoindre Judy."},
                 "call":{"phase":"active","contact":"Judy","audio":true},
                 "blocks":{"inv":3,"contacts":2,"quests":1},
                 "pins":[{"pid":"p1","x":1,"y":2,"z":3}],"acks":[],"errors":{}}}""",
        )
        assertEquals(CyberpunkStatus.INGAME, s.status)
        assertEquals("1.0.0", s.install.modVersion)
        val st = s.state!!
        assertEquals(15, st.player?.level)
        assertEquals(9, st.player?.attrs?.cool)
        assertEquals(86, st.player?.health)
        assertEquals(16937L, st.player?.money)
        assertEquals(90.0, st.pos?.yaw!!, 0.0)
        assertEquals("Optiques", st.cyberware.getValue("Eyes").single()?.name)
        assertNull(st.cyberware.getValue("Legs").single())
        assertEquals("Disasterpiece", st.tracked?.quest)
        assertEquals(CyberpunkCallPhase.ACTIVE, st.call?.phase)
        assertEquals("Judy", st.call?.contact)
        assertEquals(CyberpunkBlocks(3, 2, 1), st.blocks)
        assertEquals("p1", st.pins.single().pid)
    }

    @Test
    fun menuAndOffHaveNoState() {
        val s = CyberpunkApi.parseSnapshot("""{"status":"menu","install":{"gameFound":true},"state":null}""")
        assertEquals(CyberpunkStatus.MENU, s.status)
        assertNull(s.state)
        assertFalse(s.install.modInstalled)
        assertEquals(CyberpunkStatus.OFF, CyberpunkApi.parseSnapshot("""{"status":"off"}""").status)
    }

    @Test
    fun stateToleratesAbsentBlocks() {
        val s = CyberpunkApi.parseSnapshot("""{"status":"ingame","state":{"inGame":true}}""").state!!
        assertNull(s.player)
        assertNull(s.tracked)
        assertNull(s.call)
        assertEquals(CyberpunkBlocks(0, 0, 0), s.blocks)
        assertTrue(s.cyberware.isEmpty())
    }

    @Test
    fun callPhaseNoneIsNoCall() {
        val s = CyberpunkApi.parseSnapshot("""{"status":"ingame","state":{"call":{"phase":"none"}}}""").state!!
        assertNull(s.call)
    }

    @Test
    fun readsInventory() {
        val items = CyberpunkApi.parseInventory(
            """{"v":1,"items":[{"tdb":"Items.A","name":"MaxDoc","type":"Con_Injector","quality":"Uncommon","qty":2,"usable":true},{"name":"Vis"}, 5]}""",
        )
        assertEquals(2, items.size)
        assertEquals(2, items[0].qty)
        assertTrue(items[0].usable)
        assertEquals(1, items[1].qty)
        assertFalse(items[1].usable)
    }

    @Test
    fun readsContactsAndThreads() {
        val c = CyberpunkApi.parseContacts(
            """{"contacts":[{"id":"judy","name":"Judy","callable":true,"unread":0},{"id":"x","name":"X","callable":false,"unread":true}],
               "threads":[{"name":"Regina","preview":"Salut","unread":2,"time":"21:48"}]}""",
        )
        assertEquals(listOf("Judy", "X"), c.contacts.map { it.name })
        assertTrue(c.contacts[0].callable)
        assertEquals(1, c.contacts[1].unread)
        assertEquals(2, c.threads.single().unread)
        assertEquals(3, c.unread)
    }

    @Test
    fun readsQuests() {
        val q = CyberpunkApi.parseQuests(
            """{"quests":[{"id":"q1","title":"Disasterpiece","kind":"main","objective":"Rejoindre Judy.","tracked":true},{"id":"q2","title":"Autre"}]}""",
        )
        assertEquals(2, q.size)
        assertTrue(q[0].tracked)
        assertNull(q[1].objective)
    }

    @Test
    fun readsInstallAndActionResults() {
        assertTrue(CyberpunkApi.parseInstall("""{"ok":true,"deferred":true}""").deferred)
        assertFalse(CyberpunkApi.parseInstall("""{"ok":true}""").deferred)
        val a = CyberpunkApi.parseAction("""{"ok":true,"id":7,"res":"done"}""")
        assertTrue(a.ok)
        assertEquals(7, a.id)
    }

    @Test
    fun readsPinsAsObjectOrArray() {
        val inObject = CyberpunkApi.parsePins("""{"pins":[{"id":"a","x":1,"y":2,"z":3,"name":"Base","tracked":true}]}""")
        val inArray = CyberpunkApi.parsePins("""[{"id":"b","x":4,"y":5}]""")
        assertEquals("Base", inObject.single().name)
        assertTrue(inObject.single().tracked)
        assertEquals(0.0, inArray.single().z, 0.0)
    }

    @Test
    fun marksTheTrackedPinFromTheTopLevelId() {
        val pins = CyberpunkApi.parsePins("""{"pins":[{"id":"a","x":1,"y":2,"z":3,"at":5},{"id":"b","x":4,"y":5,"z":0}],"tracked":"b"}""")
        assertEquals(listOf(false, true), pins.map { it.tracked })
    }

    @Test
    fun readsTheOptionalObjectivePosition() {
        val withPos = CyberpunkApi.parseSnapshot(
            """{"status":"ingame","state":{"tracked":{"kind":"quest","id":"q1","pos":{"x":10.5,"y":-3}}}}""",
        ).state!!.tracked!!
        val without = CyberpunkApi.parseSnapshot("""{"status":"ingame","state":{"tracked":{"kind":"quest","id":"q1"}}}""").state!!.tracked!!
        assertEquals(10.5, withPos.x!!, 0.0)
        assertEquals(-3.0, withPos.y!!, 0.0)
        assertNull(without.x)
    }

    @Test
    fun readsTheMapDescription() {
        val m = CyberpunkApi.parseMap(
            """{"status":"ready","done":197,"total":197,"tile":512,"levels":3,"size":[7000,7000],"affine":[1,0,2,0,-1,3]}""",
        )
        assertEquals(CyberpunkMapStatus.READY, m.status)
        assertEquals(3, m.levels)
        assertEquals(7000, m.width)
        assertEquals(listOf(1.0, 0.0, 2.0, 0.0, -1.0, 3.0), m.affine)
        assertEquals(CyberpunkMapStatus.ABSENT, CyberpunkApi.parseMap("""{"status":"absent"}""").status)
        assertNull(CyberpunkApi.parseMap("""{"status":"loading","done":2,"total":9}""").affine)
    }

    @Test
    fun tilePathFollowsTheContract() {
        assertEquals("/cyberpunk/tiles/1/2/3.webp", CyberpunkApi.tilePath(1, 2, 3))
    }

    @Test
    fun oldBackendIsUnavailable() {
        assertTrue(CyberpunkApi.isUnavailable(HttpException(404, "Route GET:/cyberpunk not found")))
        assertTrue(CyberpunkApi.isUnavailable(HttpException(403, "Plugin coupé")))
        assertFalse(CyberpunkApi.isUnavailable(HttpException(500, "")))
    }

    @Test
    fun readsThreadHashFlagsAndKeepsOldFilesReadable() {
        val c = CyberpunkApi.parseContacts(
            """{"contacts":[],"threads":[
              {"name":"Takemura","preview":"Salut","unread":2,"hash":"-1234567890123456789","multi":true,"canReply":true},
              {"name":"Ancien","unread":0}]}""",
        )
        assertEquals("-1234567890123456789", c.threads[0].hash)
        assertTrue(c.threads[0].multi)
        assertTrue(c.threads[0].canReply)
        assertEquals("", c.threads[1].hash)
        assertFalse(c.threads[1].canReply)
    }

    @Test
    fun readsAThreadDetail() {
        val d = CyberpunkApi.parseThread(
            """{"v":1,"hash":"12","version":"3:-77",
               "messages":[{"hash":"1","text":"Salut","player":false},{"hash":"2","text":"Yo","player":true}],
               "choices":[{"hash":"-5","text":"Oui"},{"hash":"6","text":"Non"}]}""",
        )
        assertEquals("3:-77", d.version)
        assertEquals(listOf(false, true), d.messages.map { it.player })
        assertEquals("Yo", d.messages[1].text)
        assertEquals(listOf("-5", "6"), d.choices.map { it.hash })
    }

    @Test
    fun threadDetailWithoutChoicesIsEmpty() {
        val d = CyberpunkApi.parseThread("""{"version":"0:0"}""")
        assertTrue(d.messages.isEmpty())
        assertTrue(d.choices.isEmpty())
    }

    @Test
    fun readsThePhoneLook() {
        val p = CyberpunkApi.parsePhone("""{"status":"ready","assets":["frame_message.png","raj-bold.ttf"]}""")
        assertEquals(CyberpunkPhoneStatus.READY, p.status)
        assertEquals(listOf("frame_message.png", "raj-bold.ttf"), p.assets)
        assertEquals(CyberpunkPhoneStatus.LOADING, CyberpunkApi.parsePhone("""{"status":"loading"}""").status)
        val err = CyberpunkApi.parsePhone("""{"status":"error","error":"x"}""")
        assertEquals(CyberpunkPhoneStatus.ERROR, err.status)
        assertEquals(CyberpunkPhoneStatus.ABSENT, CyberpunkApi.parsePhone("""{}""").status)
    }

    @Test
    fun readsTheRefusalCodeOfAnErrorBody() {
        assertEquals("choix-perime", errorRes("""{"error":"Ce choix n'est plus ouvert","res":"choix-perime"}"""))
        assertNull(errorRes("""{"error":"x"}"""))
        assertNull(errorRes("pas du json"))
    }

    @Test
    fun staleChoiceIsRecognisedFromTheException() {
        assertTrue(CyberpunkApi.isStaleChoice(HttpException(409, "x", "choix-perime")))
        assertFalse(CyberpunkApi.isStaleChoice(HttpException(409, "x", "fil-introuvable")))
        assertFalse(CyberpunkApi.isStaleChoice(HttpException(504, "x")))
    }

    @Test
    fun `en vehicule ou a pied selon le champ vehicle de l'etat`() {
        assertTrue(CyberpunkApi.parseSnapshot("""{"status":"ingame","state":{"vehicle":{"speed":12.5}}}""").state!!.inVehicle)
        assertFalse(CyberpunkApi.parseSnapshot("""{"status":"ingame","state":{}}""").state!!.inVehicle)
    }

    @Test
    fun `position du vehicule fraiche, perimee, sans vitesse`() {
        val p = CyberpunkApi.parseLivePos("""{"fresh":true,"vehicle":true,"x":1.5,"y":-2,"yaw":90,"speed":20}""")
        assertEquals(CyberpunkLivePos(1.5, -2.0, 90.0, 20.0), p)
        assertNull(CyberpunkApi.parseLivePos("""{"fresh":false}"""))
        assertNull(CyberpunkApi.parseLivePos("""{"fresh":true,"vehicle":false,"x":1,"y":2,"yaw":0}"""))
        assertNull(CyberpunkApi.parseLivePos("""{"fresh":true,"vehicle":true,"y":2,"yaw":0}"""))
        assertNull(CyberpunkApi.parseLivePos("""{"fresh":true,"vehicle":true,"x":1,"y":2,"yaw":0,"speed":null}""")!!.speed)
    }
}
