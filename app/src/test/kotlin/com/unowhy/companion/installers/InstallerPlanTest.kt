package com.unowhy.companion.installers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstallerPlanTest {

    private val mac = Installer(Os.MAC, "Compagnon-Unowhy-Mac.zip", 77_000_000, "aa", "1.2.0")
    private val windows = Installer(Os.WINDOWS, "Compagnon-Unowhy-Windows.exe", 23_000_000, "bb", "1.2.0")

    @Test
    fun `parse lit les installeurs du manifeste`() {
        val json = """{"updates":[],"installers":[
            {"os":"mac","file":"Compagnon-Unowhy-Mac.zip","size":77000000,"sha256":"aa","version":"1.2.0"},
            {"os":"windows","file":"Compagnon-Unowhy-Windows.exe","size":23000000,"sha256":"bb","version":"1.2.0"}]}"""
        assertEquals(listOf(mac, windows), InstallerPlan.parse(json))
    }

    @Test
    fun `parse sans la cle installers donne une liste vide`() {
        assertEquals(emptyList<Installer>(), InstallerPlan.parse("""{"updates":[]}"""))
    }

    @Test
    fun `parse ignore un OS inconnu et un nom de fichier qui sort du dossier`() {
        val json = """{"installers":[
            {"os":"linux","file":"a.deb","size":1,"sha256":"aa","version":"1"},
            {"os":"mac","file":"../x.zip","size":1,"sha256":"aa","version":"1"},
            {"os":"mac","file":"a/b.zip","size":1,"sha256":"aa","version":"1"},
            {"os":"mac","file":".cache","size":1,"sha256":"aa","version":"1"},
            {"os":"mac","file":"ok.zip","size":0,"sha256":"aa","version":"1"},
            {"os":"windows","file":"ok.exe","size":5,"sha256":"bb","version":"1"}]}"""
        assertEquals(listOf(Installer(Os.WINDOWS, "ok.exe", 5, "bb", "1")), InstallerPlan.parse(json))
    }

    @Test
    fun `toJson puis parse redonne la liste`() {
        assertEquals(listOf(mac, windows), InstallerPlan.parse(InstallerPlan.toJson(listOf(mac, windows))))
    }

    @Test
    fun `toDownload prend les absents et ceux dont l'empreinte a change`() {
        assertEquals(listOf(mac, windows), InstallerPlan.toDownload(listOf(mac, windows), emptyList()))
        assertEquals(listOf(windows), InstallerPlan.toDownload(listOf(mac, windows), listOf(mac)))
        val older = mac.copy(sha256 = "00", version = "1.1.0")
        assertEquals(listOf(mac), InstallerPlan.toDownload(listOf(mac), listOf(older)))
        assertEquals(emptyList<Installer>(), InstallerPlan.toDownload(listOf(mac), listOf(mac.copy(sha256 = "AA"))))
    }

    @Test
    fun `toDelete retire ce qui n'est plus annonce, jamais sur une annonce vide`() {
        val old = Installer(Os.MAC, "Ancien.zip", 1, "cc", "1.0.0")
        assertEquals(listOf(old), InstallerPlan.toDelete(listOf(mac, windows), listOf(old, mac)))
        assertEquals(emptyList<Installer>(), InstallerPlan.toDelete(emptyList(), listOf(old, mac)))
    }

    @Test
    fun `osOf reconnait Mac et Windows`() {
        assertEquals(Os.MAC, InstallerPlan.osOf("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Safari/605.1.15"))
        assertEquals(Os.WINDOWS, InstallerPlan.osOf("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36"))
        assertNull(InstallerPlan.osOf("Mozilla/5.0 (X11; Linux x86_64) Firefox/130.0"))
        assertNull(InstallerPlan.osOf("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari/604.1"))
        assertNull(InstallerPlan.osOf(null))
    }

    @Test
    fun `newerFor propose l'installeur de l'OS quand le compagnon est plus ancien`() {
        assertEquals(mac, InstallerPlan.newerFor("mac", "1.1.9", listOf(mac, windows)))
        assertEquals(windows, InstallerPlan.newerFor("windows", "0.9.0", listOf(mac, windows)))
    }

    @Test
    fun `newerFor rien quand le compagnon est a jour ou plus recent`() {
        assertNull(InstallerPlan.newerFor("mac", "1.2.0", listOf(mac)))
        assertNull(InstallerPlan.newerFor("mac", "1.10.0", listOf(mac)))
    }

    @Test
    fun `newerFor version absente = ancien compagnon, dev = jamais`() {
        assertEquals(mac, InstallerPlan.newerFor("mac", null, listOf(mac)))
        assertNull(InstallerPlan.newerFor("mac", "dev", listOf(mac)))
    }

    @Test
    fun `newerFor rien sans installeur pour cet OS`() {
        assertNull(InstallerPlan.newerFor("windows", null, listOf(mac)))
        assertNull(InstallerPlan.newerFor("linux", null, listOf(mac, windows)))
    }
}
