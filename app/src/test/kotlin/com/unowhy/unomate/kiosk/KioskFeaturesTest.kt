package com.unowhy.unomate.kiosk

import android.app.admin.DevicePolicyManager
import android.content.pm.ActivityInfo
import com.unowhy.unomate.device.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

class KioskFeaturesTest {

    @Test
    fun `verrouillage d'ecran sans aucune fonction par defaut`() {
        assertEquals(DevicePolicyManager.LOCK_TASK_FEATURE_NONE, Kiosk.lockTaskFeatures(powerMenu = false))
    }

    @Test
    fun `menu marche-arret permis seulement sur demande`() {
        assertEquals(DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS, Kiosk.lockTaskFeatures(powerMenu = true))
    }

    @Test
    fun `paysage fixe ou selon le capteur`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Screen.orientation(locked = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, Screen.orientation(locked = false))
    }
}
