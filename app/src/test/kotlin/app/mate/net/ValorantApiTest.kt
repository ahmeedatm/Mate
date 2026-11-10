package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValorantApiTest {
    @Test
    fun readsTheMatchWithBothTeams() {
        val v = ValorantApi.parse(
            """{"state":"ingame","queue":"Compétition","map":"Summit","score":{"ally":2,"enemy":0},
                "me":{"name":"Eliot#EUW","tier":10,"rank":"Argent 2","rr":42,"level":90},
                "players":[
                  {"name":"Eliot#EUW","agent":"Sova","locked":true,"tier":10,"rank":"Argent 2","rr":42,"ally":true,"me":true},
                  {"name":null,"agent":null,"locked":true,"tier":9,"rank":"Argent 1","rr":null,"ally":false,"me":false}
                ]}""",
        )
        assertEquals(ValorantState.INGAME, v.state)
        assertEquals("Summit", v.map)
        assertEquals(2, v.score?.ally)
        assertEquals(0, v.score?.enemy)
        assertEquals("Argent 2", v.me?.rank)
        assertEquals(42, v.me?.rr)
        assertEquals(listOf("Eliot#EUW"), v.allies.map { it.name })
        assertTrue(v.allies.single().me)
        val hidden = v.enemies.single()
        assertNull(hidden.name)
        assertNull(hidden.agent)
        assertNull(hidden.rr)
        assertEquals(9, hidden.tier)
    }

    @Test
    fun closedGameHasNoPlayerAndNoMe() {
        val v = ValorantApi.parse("""{"state":"off","queue":null,"map":null,"score":null,"me":null,"players":[]}""")
        assertEquals(ValorantState.OFF, v.state)
        assertNull(v.me)
        assertNull(v.score)
        assertNull(v.queue)
        assertTrue(v.allies.isEmpty() && v.enemies.isEmpty())
    }

    @Test
    fun unknownStateFromANewerComputerIsTheMenu() {
        assertEquals(ValorantState.MENU, ValorantApi.parse("""{"state":"replay","players":[]}""").state)
    }

    @Test
    fun agentNotYetLockedDuringSelection() {
        val v = ValorantApi.parse(
            """{"state":"pregame","players":[{"name":"Kinj#EUW","agent":"Jett","locked":false,"tier":12,"rank":"Or 1","rr":15,"ally":true,"me":false}]}""",
        )
        assertEquals(ValorantState.PREGAME, v.state)
        assertFalse(v.allies.single().locked)
        assertEquals("Jett", v.allies.single().agent)
    }

    @Test
    fun readsTrackerStatsWhenTheComputerSendsThem() {
        val v = ValorantApi.parse(
            """{"state":"ingame","players":[{"name":"Nox#7781","agent":"Jett","agentId":"add6443a-41bd-e414-f6ad-e58d267f4e95",
                "locked":true,"tier":19,"rank":"Diamant 2","rr":12,"ally":true,"me":false,"level":36,"peakTier":24,"winRate":82,
                "kd":1.62,"hs":31,"party":1}],"history":[],"end":null}""",
        )
        val p = v.allies.single()
        assertEquals("add6443a-41bd-e414-f6ad-e58d267f4e95", p.agentId)
        assertEquals(36, p.level)
        assertEquals(24, p.peakTier)
        assertEquals(82, p.winRate)
        assertEquals(1.62, p.kd!!, 0.001)
        assertEquals(31, p.hs)
        assertEquals(1, p.party)
        assertNull(v.end)
    }

    @Test
    fun anOlderComputerWithoutStatsLeavesThemEmpty() {
        val p = ValorantApi.parse(
            """{"state":"ingame","players":[{"name":"Kinj#EUW","agent":"Sage","locked":true,"tier":12,"rank":"Or 1","rr":15,"ally":true,"me":false}]}""",
        ).allies.single()
        assertNull(p.agentId)
        assertNull(p.kd)
        assertNull(p.party)
        assertNull(p.level)
    }

    @Test
    fun readsMyLastMatchesAndTheEndScoreboard() {
        val v = ValorantApi.parse(
            """{"state":"menu","players":[],"me":{"name":"Eliot#EUW","tier":14,"rank":"Or 3","rr":42,"level":214,"peakTier":17},
                "history":[{"won":true,"score":{"ally":13,"enemy":9},"map":"Ascent","agent":"Sova","agentId":"a","kills":19,"deaths":15,
                  "assists":6,"acs":231,"rr":21},
                  {"won":null,"score":null,"map":"Bind","agent":null,"agentId":null,"kills":null,"deaths":null,"assists":null,"acs":null,"rr":-17}],
                "end":{"status":"ready","map":"Ascent","queue":"Compétition","mode":"teams","won":true,"score":{"ally":13,"enemy":9},
                  "players":[{"name":"Nox#7781","agent":"Jett","agentId":"b","tier":19,"rank":"Diamant 2","ally":true,"me":false,
                  "acs":312,"kills":26,"deaths":12,"assists":4,"hs":33,"adr":197,"firstKills":6,"mvp":true}]}}""",
        )
        assertEquals(17, v.me?.peakTier)
        assertEquals(2, v.history.size)
        val first = v.history[0]
        assertEquals(true, first.won)
        assertEquals(13, first.score?.ally)
        assertEquals(21, first.rr)
        assertEquals(231, first.acs)
        assertNull(v.history[1].won)
        assertNull(v.history[1].kills)
        assertEquals(-17, v.history[1].rr)
        val end = v.end!!
        assertEquals(ValorantEndStatus.READY, end.status)
        assertFalse(end.ffa)
        assertEquals(true, end.won)
        val mvp = end.players.single()
        assertTrue(mvp.mvp)
        assertEquals(312, mvp.acs)
        assertEquals(197, mvp.adr)
        assertEquals(6, mvp.firstKills)
        assertEquals(33, mvp.hs)
    }

    @Test
    fun scoreboardStillLoadingOrMissing() {
        val loading = ValorantApi.parse("""{"state":"menu","players":[],"end":{"status":"loading","players":[]}}""").end
        assertEquals(ValorantEndStatus.LOADING, loading?.status)
        val missing = ValorantApi.parse("""{"state":"menu","players":[],"end":{"status":"missing","players":[]}}""").end
        assertEquals(ValorantEndStatus.MISSING, missing?.status)
    }

    @Test
    fun aMalformedItemIsSkippedNotTheWholeWidget() {
        val v = ValorantApi.parse(
            """{"state":"menu","players":[null,{"name":"Kinj#EUW","agent":"Sage","locked":true,"tier":12,"rank":"Or 1","rr":15,"ally":true,"me":false}],
                "history":[1,{"won":true,"rr":12}]}""",
        )
        assertEquals(listOf("Kinj#EUW"), v.players.map { it.name })
        assertEquals(listOf(12), v.history.map { it.rr })
    }

    @Test
    fun readsRoundsMapTodayAndMapStats() {
        val v = ValorantApi.parse(
            """{"state":"ingame","queue":"Compétition","map":"Ascent","score":{"ally":2,"enemy":1},"me":null,"players":[],
                "mapId":"7eaecc1b-4337-bbf6-6ab9-04b8f06b3319","rounds":[true,false,null],
                "today":{"rr":-6,"wins":1,"losses":2,"results":[false,true,null,false]},
                "mapStats":{"wins":5,"games":8,"window":40,"results":[true,false,null]}}""",
        )
        assertEquals("7eaecc1b-4337-bbf6-6ab9-04b8f06b3319", v.mapId)
        assertEquals(listOf(true, false, null), v.rounds.map { it.won })
        assertTrue(v.rounds.all { it.end == null })
        assertEquals(ValorantToday(-6, 1, 2, listOf(false, true, null, false)), v.today)
        assertEquals(ValorantMapStats(5, 8, 40, listOf(true, false, null)), v.mapStats)
    }

    @Test
    fun readsEachRoundOfTheEndWithHowItEnded() {
        val v = ValorantApi.parse(
            """{"state":"menu","queue":null,"map":null,"score":null,"me":null,"players":[],
                "end":{"status":"ready","map":"Ascent","mapId":"7eaecc1b-4337-bbf6-6ab9-04b8f06b3319","queue":"Compétition",
                  "mode":"teams","won":true,"score":{"ally":13,"enemy":9},"players":[],"rr":21,
                  "rounds":[{"won":true,"end":"detonate"},{"won":false,"end":"elimination"},{"won":true,"end":"defuse"},
                            {"won":false,"end":"time"},{"won":null,"end":null},{"won":true,"end":"inconnue"}]}}""",
        )
        val end = v.end!!
        assertEquals(21, end.rr)
        assertEquals("7eaecc1b-4337-bbf6-6ab9-04b8f06b3319", end.mapId)
        assertEquals(
            listOf(
                ValorantRound(true, ValorantRoundEnd.DETONATE),
                ValorantRound(false, ValorantRoundEnd.ELIMINATION),
                ValorantRound(true, ValorantRoundEnd.DEFUSE),
                ValorantRound(false, ValorantRoundEnd.TIME),
                ValorantRound(null, null),
                ValorantRound(true, null),
            ),
            end.rounds,
        )
    }

    @Test
    fun olderCompanionHasNoRoundsMapNorDay() {
        val v = ValorantApi.parse(
            """{"state":"ingame","queue":null,"map":"Ascent","score":{"ally":1,"enemy":0},"me":null,"players":[],
                "end":{"status":"ready","map":"Ascent","queue":null,"mode":"teams","won":true,"score":null,"players":[]}}""",
        )
        assertNull(v.mapId)
        assertTrue(v.rounds.isEmpty())
        assertNull(v.today)
        assertNull(v.mapStats)
        assertTrue(v.end!!.rounds.isEmpty())
        assertNull(v.end!!.rr)
        assertNull(v.end!!.mapId)
    }
}
