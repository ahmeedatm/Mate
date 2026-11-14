package app.mate.ui.cyberpunk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingTest {
    @Test fun `une lecture en vehicule fait basculer, deux a pied font revenir`() {
        val s = DriveSwitch()
        assertFalse(s.feed(fresh = true, inVehicle = false))
        assertTrue(s.feed(fresh = true, inVehicle = true))
        assertTrue(s.feed(fresh = true, inVehicle = false))
        assertTrue(s.feed(fresh = true, inVehicle = true))
        assertTrue(s.feed(fresh = true, inVehicle = false))
        assertFalse(s.feed(fresh = true, inVehicle = false))
    }

    @Test fun `jeu en pause ou au menu, rien ne bouge`() {
        val s = DriveSwitch()
        s.feed(fresh = true, inVehicle = true)
        repeat(5) { assertTrue(s.feed(fresh = false, inVehicle = false)) }
        s.feed(fresh = true, inVehicle = false)
        assertTrue(s.feed(fresh = false, inVehicle = false))
        assertFalse(s.feed(fresh = true, inVehicle = false))
    }

    @Test fun `partie finie ou lien perdu, retour a pied tout de suite`() {
        val s = DriveSwitch()
        s.feed(fresh = true, inVehicle = true)
        s.reset()
        assertFalse(s.driving)
        assertTrue(s.feed(fresh = true, inVehicle = true))
    }

    @Test fun `lissage du zoom selon le temps, pas le nombre d'images`() {
        val slow = (1..5).fold(0.0) { z, _ -> easeToward(z, 10.0, 200L) }
        val fast = (1..50).fold(0.0) { z, _ -> easeToward(z, 10.0, 20L) }
        assertEquals(slow, fast, 1e-6)
        assertTrue(slow > 0.0 && slow < 10.0)
    }

    @Test fun `angle le plus court`() {
        assertEquals(20.0, shortestTurn(350.0, 10.0), 1e-9)
        assertEquals(-20.0, shortestTurn(10.0, 350.0), 1e-9)
        assertEquals(-90.0, shortestTurn(-180.0, 90.0), 1e-9)
    }

    @Test fun `zoom de conduite recule avec la vitesse, borne`() {
        assertEquals(MapCamera.DRIVE_SCALE_NEAR, driveScale(0.0), 1e-9)
        assertEquals(MapCamera.DRIVE_SCALE_FAR, driveScale(1000.0), 1e-9)
        assertTrue(driveScale(10.0) < MapCamera.DRIVE_SCALE_NEAR && driveScale(10.0) > MapCamera.DRIVE_SCALE_FAR)
        assertEquals(MapCamera.DRIVE_SCALE_NEAR, driveScale(-3.0), 1e-9)
    }

    @Test fun `sans position pas de pose, premiere position rendue telle quelle`() {
        val t = PoseTrack()
        assertNull(t.at(0))
        t.push(1_000, 10.0, 20.0, 45.0)
        val p = t.at(1_000)!!
        assertEquals(10.0, p.x, 1e-9)
        assertEquals(20.0, p.y, 1e-9)
        assertEquals(45.0, p.yaw, 1e-9)
    }

    @Test fun `la pose glisse vers la position extrapolee, sans saut`() {
        val t = PoseTrack()
        t.push(0, 0.0, 0.0, 0.0)
        t.at(0)
        t.push(250, 5.0, 0.0, 0.0) // 20 m/s vers l'est
        val first = t.at(270)!!
        assertTrue("pas de saut : ${first.x}", first.x > 0.0 && first.x < 5.0)
        var p = first
        for (now in 290..600 step 20) p = t.at(now.toLong())!!
        // Plus de nouvelle position depuis 250 ms : extrapolée au plus de MAX_EXTRAPOLATE_MS, soit 5 + 20 × 0,3 = 11 m.
        assertTrue("${p.x}", p.x > 9.0 && p.x <= 11.0 + 1e-9)
        assertEquals(20.0, t.speed, 1e-9)
    }

    @Test fun `le cap tourne par le plus court chemin`() {
        val t = PoseTrack()
        t.push(0, 0.0, 0.0, 350.0)
        t.at(0)
        t.push(250, 0.0, 0.0, 10.0)
        val p = t.at(300)!!
        assertTrue("${p.yaw}", p.yaw > 350.0 || p.yaw < 10.0)
    }

    @Test fun `un grand saut (voyage rapide) se prend d'un coup`() {
        val t = PoseTrack()
        t.push(0, 0.0, 0.0, 0.0)
        t.at(0)
        t.push(250, 3000.0, 0.0, 0.0)
        assertEquals(3000.0, t.at(260)!!.x, 1e-9)
        assertEquals(0.0, t.speed, 1e-9)
    }
}
