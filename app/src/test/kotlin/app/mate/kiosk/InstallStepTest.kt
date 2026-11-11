package app.mate.kiosk

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Test

class InstallStepTest {

    @Test
    fun `reponse finale d'Android, on s'arrete`() {
        for (status in listOf(PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE, PackageInstaller.STATUS_FAILURE_ABORTED)) {
            assertEquals(InstallStep.DONE, InstallStep.of(status, hasConfirmation = true, foreground = true))
        }
    }

    @Test
    fun `confirmation demandee et Mate a l'ecran, on ouvre l'ecran d'Android`() {
        assertEquals(InstallStep.SHOW_CONFIRMATION, InstallStep.of(PackageInstaller.STATUS_PENDING_USER_ACTION, hasConfirmation = true, foreground = true))
    }

    @Test
    fun `confirmation demandee mais Mate n'est pas a l'ecran, Android 10 la bloquerait`() {
        assertEquals(InstallStep.NOT_VISIBLE, InstallStep.of(PackageInstaller.STATUS_PENDING_USER_ACTION, hasConfirmation = true, foreground = false))
    }

    @Test
    fun `confirmation demandee sans son ecran, on s'arrete`() {
        assertEquals(InstallStep.DONE, InstallStep.of(PackageInstaller.STATUS_PENDING_USER_ACTION, hasConfirmation = false, foreground = true))
    }
}
