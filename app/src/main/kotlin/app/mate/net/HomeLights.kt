package app.mate.net

import android.content.Context
import app.mate.Computers
import app.mate.Prefs
import app.mate.R
import app.mate.UserFacingException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ce que le widget Maison montre sur Maison, choisi sur la tablette (Réglages > Maison). Sans choix : les WLED en
 * direct (elles marchent sans le NAS) et les lumières de Home Assistant qui ne sont pas des WLED (déjà là en direct) ;
 * jamais une prise, une scène ni les dizaines d'interrupteurs techniques de WLED (gel, synchro…).
 */
object HomeChoice {
    private const val KEY = "home_shown"

    fun defaultShown(id: String): Boolean = isWledDirect(id) || (id.startsWith("light.") && !id.startsWith("light.wled_"))

    fun isWledDirect(id: String) = id.startsWith("wled:")

    private fun prefs(context: Context) = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)

    private fun choices(context: Context): JSONObject =
        prefs(context).getString(KEY, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    fun isShown(context: Context, id: String): Boolean = choices(context).let { if (it.has(id)) it.optBoolean(id) else defaultShown(id) }

    fun set(context: Context, id: String, shown: Boolean) {
        prefs(context).edit().putString(KEY, choices(context).put(id, shown).toString()).apply()
    }

    fun shown(context: Context, entities: List<HaEntity>): List<HaEntity> {
        val c = choices(context)
        return entities.filter { if (c.has(it.id)) c.optBoolean(it.id) else defaultShown(it.id) }
    }
}

/** WLED retenues par la tablette (trouvées sur le WiFi ou saisies), avec leur adresse du moment. */
object WledDevices {
    private const val KEY = "home_wled"

    private fun prefs(context: Context) = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)

    fun all(context: Context): List<WledDevice> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { WledDevice(it.optString("id"), it.optString("host"), it.optString("name")) }?.takeIf { it.id.isNotEmpty() && it.host.isNotEmpty() }
        }
    }

    /** Ajoute ou met à jour (même MAC, nouvelle adresse IP). */
    fun save(context: Context, device: WledDevice) {
        val list = all(context).filterNot { it.id == device.id } + device
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("id", it.id).put("host", it.host).put("name", it.name)) }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }
}

/**
 * Lumières de Maison : Home Assistant en direct (`ha`, null s'il n'est pas réglé) et chaque WLED en direct
 * (`wled`, par identifiant). L'une en panne n'empêche pas l'autre : HA éteint (NAS arrêté), les WLED répondent encore.
 */
class HomeLightSource(
    private val context: Context,
    private val ha: LightSource?,
    private val wled: Map<String, LightSource>,
) : LightSource {

    /** Tout ce que la tablette voit, avant le choix de ce qui s'affiche (liste à cocher des Réglages). */
    suspend fun all(): List<HaEntity> = coroutineScope {
        val reads = (listOfNotNull(ha) + wled.values).map { async { runCatchingCancellable { it.entities() } } }.awaitAll()
        val ok = reads.mapNotNull { it.getOrNull() }
        if (ok.isEmpty() && reads.isNotEmpty()) {
            val e = reads.first().exceptionOrNull()!!
            // « Aucun ordinateur » serait faux ici (errorText d'une IOException) : on dit ce qui ne répond pas.
            throw if (e is java.io.IOException) UserFacingException(R.string.home_unreachable) else e
        }
        ok.flatten()
    }

    override suspend fun entities(): List<HaEntity> = HomeChoice.shown(context, all())

    override suspend fun toggle(id: String) = owner(id).toggle(id)

    override suspend fun setLight(id: String, brightness: Int?, rgb: Int?) = owner(id).setLight(id, brightness, rgb)

    private fun owner(id: String): LightSource =
        (if (HomeChoice.isWledDirect(id)) wled[id] else ha) ?: throw UserFacingException(R.string.home_unreachable)
}

/**
 * Lumières avec un ordinateur : son Home Assistant tel qu'avant (`pc`), plus les WLED cochées en direct (`wled`). Une
 * lumière de l'ordinateur au nom d'une WLED en direct est cachée (la même, vue deux fois). L'ordinateur injoignable
 * n'empêche pas les WLED de répondre.
 */
class ComputerLightSource(
    private val pc: LightSource,
    private val wled: Map<String, LightSource>,
) : LightSource {

    override suspend fun entities(): List<HaEntity> = coroutineScope {
        val direct = wled.values.map { async { runCatchingCancellable { it.entities() } } }
        val fromPc = runCatchingCancellable { pc.entities() }
        val lights = direct.awaitAll().mapNotNull { it.getOrNull() }.flatten()
        if (lights.isEmpty()) return@coroutineScope fromPc.getOrThrow()
        val names = lights.map { it.name.trim().lowercase() }.toSet()
        fromPc.getOrDefault(emptyList()).filterNot { it.id.startsWith("light.") && it.name.trim().lowercase() in names } + lights
    }

    override suspend fun toggle(id: String) = owner(id).toggle(id)

    override suspend fun setLight(id: String, brightness: Int?, rgb: Int?) = owner(id).setLight(id, brightness, rgb)

    private fun owner(id: String): LightSource = if (HomeChoice.isWledDirect(id)) wled[id] ?: throw UserFacingException(R.string.home_unreachable) else pc
}

/** Maison : Home Assistant et WLED en direct ; un ordinateur : son Home Assistant à lui, plus les WLED cochées en direct. */
fun lightSource(context: Context): LightSource =
    if (Computers.homeActive(context)) homeLightSource(context) else ComputerLightSource(BackendClient(context).ha, shownWled(context))

/** WLED retenues et cochées dans Maison, pilotées en direct : on n'interroge pas celles qui ne s'affichent pas. */
fun shownWled(context: Context): Map<String, LightSource> =
    WledDevices.all(context).filter { HomeChoice.isShown(context, it.id) }.associate { it.id to WledApi(it) }

fun homeLightSource(context: Context): HomeLightSource =
    HomeLightSource(context, HaConfig.load(context)?.let(::HaDirectApi), WledDevices.all(context).associate { it.id to WledApi(it) })

/** Une WLED cochée fait proposer le widget Maison, même sur un ordinateur sans Home Assistant. */
fun HomeFeatures.withShownWled(context: Context): HomeFeatures =
    if (ha || WledDevices.all(context).none { HomeChoice.isShown(context, it.id) }) this else copy(ha = true)
