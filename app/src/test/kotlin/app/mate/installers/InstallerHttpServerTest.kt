package app.mate.installers

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest

class InstallerHttpServerTest {

    private lateinit var dir: File
    private lateinit var store: InstallerStore
    private lateinit var server: InstallerHttpServer
    private val zip = ByteArray(300_000) { (it % 251).toByte() }

    @Before
    fun start() {
        dir = Files.createTempDirectory("installers").toFile()
        store = InstallerStore(dir)
        server = InstallerHttpServer(0, dir, store::onDisk)
        assertTrue(server.start())
    }

    @After
    fun stop() {
        server.stop()
        dir.deleteRecursively()
    }

    private fun installer(name: String, os: Os, data: ByteArray) =
        Installer(os, name, data.size.toLong(), MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }, "1.2.0")

    /** Dépose un installeur comme le fait la synchro : `.part` puis commit. */
    private fun publish(i: Installer, data: ByteArray) {
        store.part(i).writeBytes(data)
        store.commit(i)
    }

    private fun get(path: String, agent: String? = null, method: String = "GET"): Pair<Int, ByteArray> {
        val conn = URL("http://127.0.0.1:${server.boundPort}$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        agent?.let { conn.setRequestProperty("User-Agent", it) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream).use { it.readBytes() }
        return code to body
    }

    @Test
    fun `installers json decrit les installeurs telecharges pour le panneau de l'ordinateur`() {
        assertEquals(emptyList<Installer>(), InstallerPlan.parse(String(get("/installers.json").second)))
        val exe = installer("Mate-Windows.exe", Os.WINDOWS, zip)
        publish(exe, zip)
        val (code, body) = get("/installers.json")
        assertEquals(200, code)
        assertEquals(listOf(exe), InstallerPlan.parse(String(body)))
    }

    @Test
    fun `la page dit que les installeurs ne sont pas la tant qu'ils ne sont pas telecharges`() {
        val (code, body) = get("/")
        assertEquals(200, code)
        assertTrue(String(body).contains("pas encore sur la tablette"))
    }

    @Test
    fun `sert la page de l'OS du visiteur et le fichier octet pour octet`() {
        publish(installer("Mate-Mac.zip", Os.MAC, zip), zip)
        val exe = ByteArray(1_000) { 7 }
        publish(installer("Mate-Windows.exe", Os.WINDOWS, exe), exe)

        val page = String(get("/", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)").second)
        assertTrue(page.indexOf("Télécharger Mate-Windows.exe") in 0 until page.indexOf("Autre ordinateur"))

        val (code, body) = get("/Mate-Mac.zip")
        assertEquals(200, code)
        assertTrue(zip.contentEquals(body))
        assertEquals(1_000, get("/Mate-Windows.exe").second.size)
    }

    @Test
    fun `HEAD ne renvoie pas le corps`() {
        publish(installer("Mate-Mac.zip", Os.MAC, zip), zip)
        val (code, body) = get("/Mate-Mac.zip", method = "HEAD")
        assertEquals(200, code)
        assertEquals(0, body.size)
    }

    @Test
    fun `refuse ce qui n'est pas un installeur annonce`() {
        publish(installer("Mate-Mac.zip", Os.MAC, zip), zip)
        File(dir, "secret.txt").writeText("non")
        assertEquals(404, get("/secret.txt").first)
        assertEquals(404, get("/installed.json").first)
        assertEquals(404, get("/..%2F..%2Fetc%2Fpasswd").first)
        assertEquals(404, get("/Mate-Mac.zip.part").first)
        assertEquals(405, get("/", method = "POST").first)
    }

    @Test
    fun `un telechargement en cours n'est pas servi`() {
        val i = installer("Mate-Mac.zip", Os.MAC, zip)
        store.part(i).writeBytes(zip.copyOf(100))
        assertEquals(404, get("/Mate-Mac.zip").first)
        assertFalse(store.onDisk().any())
    }

    @Test
    fun `la nouvelle version remplace l'ancienne dans le store`() {
        val v1 = installer("Mate-Mac.zip", Os.MAC, zip)
        publish(v1, zip)
        val newer = ByteArray(500) { 1 }
        val v2 = installer("Mate-Mac.zip", Os.MAC, newer).copy(version = "1.3.0")
        publish(v2, newer)
        assertEquals(listOf(v2), store.onDisk())
        assertTrue(newer.contentEquals(get("/Mate-Mac.zip").second))
        store.remove(listOf(v2))
        assertEquals(emptyList<Installer>(), store.onDisk())
        assertFalse(File(dir, "Mate-Mac.zip").exists())
    }

    @Test
    fun `parse lit la requete et ignore une requete mal formee`() {
        val r = InstallerHttpServer.parse(listOf("GET /a.zip?x=1 HTTP/1.1", "Host: t", "user-agent: Test/1"))!!
        assertEquals(Triple("GET", "/a.zip", "Test/1"), Triple(r.method, r.path, r.userAgent))
        assertEquals(null, InstallerHttpServer.parse(listOf("n'importe quoi")))
        assertEquals(null, InstallerHttpServer.parse(emptyList()))
        assertEquals(null, InstallerHttpServer.parse(null))
    }
}
