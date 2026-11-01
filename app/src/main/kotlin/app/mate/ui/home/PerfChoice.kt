package app.mate.ui.home

import androidx.annotation.StringRes
import app.mate.R
import app.mate.net.PcPerf
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Groupe d'une info dans la liste à cocher des réglages. */
enum class PerfGroup(@StringRes val title: Int) {
    CPU(R.string.perf_group_cpu),
    GPU(R.string.perf_group_gpu),
    RAM(R.string.perf_group_ram),
    DISK(R.string.perf_group_disk),
    NET(R.string.perf_group_net),
    GAME(R.string.perf_group_game),
}

/**
 * Infos que le widget Performances peut montrer.
 */
enum class PerfOption(val key: String, val group: PerfGroup, @StringRes val label: Int) {
    CPU("cpu", PerfGroup.CPU, R.string.perf_opt_cpu),
    CPU_FREQ("cpu_freq", PerfGroup.CPU, R.string.perf_opt_cpu_freq),
    CPU_TEMP("cpu_temp", PerfGroup.CPU, R.string.perf_opt_cpu_temp),
    CPU_WATTS("cpu_watts", PerfGroup.CPU, R.string.perf_opt_watts),
    CPU_FAN("cpu_fan", PerfGroup.CPU, R.string.perf_opt_cpu_fan),
    GPU("gpu", PerfGroup.GPU, R.string.perf_opt_gpu),
    GPU_TEMP("gpu_temp", PerfGroup.GPU, R.string.perf_opt_gpu_temp),
    GPU_WATTS("gpu_watts", PerfGroup.GPU, R.string.perf_opt_watts),
    GPU_FAN("gpu_fan", PerfGroup.GPU, R.string.perf_opt_gpu_fan),
    GPU_VRAM("gpu_vram", PerfGroup.GPU, R.string.perf_opt_gpu_vram),
    RAM("ram", PerfGroup.RAM, R.string.perf_opt_ram),
    DISK_FREE("disk_free", PerfGroup.DISK, R.string.perf_opt_disk_free),
    DISK_ACTIVITY("disk_activity", PerfGroup.DISK, R.string.perf_opt_disk_activity),
    NET_UP("net_up", PerfGroup.NET, R.string.perf_opt_net_up),
    NET_DOWN("net_down", PerfGroup.NET, R.string.perf_opt_net_down),
    FPS("fps", PerfGroup.GAME, R.string.perf_opt_fps);

    /** Vrai si l'ordinateur a donné cette mesure dans `perf`. */
    fun readable(perf: PcPerf): Boolean = when (this) {
        CPU, RAM -> true
        CPU_FREQ -> perf.cpuMhz != null
        GPU -> perf.gpu != null
        GPU_TEMP -> perf.gpu?.tempC != null
        GPU_FAN -> perf.gpu?.fanRpm != null
        GPU_VRAM -> perf.gpu?.vramUsedMb != null && perf.gpu.vramTotalMb != null
        DISK_FREE -> perf.disk != null
        DISK_ACTIVITY -> perf.disk?.activity != null
        NET_UP, NET_DOWN -> perf.net != null
        CPU_TEMP -> perf.cpuTempC != null
        CPU_WATTS -> perf.cpuWatts != null
        CPU_FAN -> perf.cpuFanRpm != null
        GPU_WATTS -> perf.gpuWatts != null
        FPS -> perf.fpsReadable
    }

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/**
 * Choix des infos du widget, gardé dans la config du widget posé (`perf` : un tableau de clés par ordinateur, car les
 * mesures lisibles diffèrent d'un ordinateur à l'autre). Sans choix, l'affichage d'avant l'option.
 */
object PerfChoice {
    private const val KEY = "perf"

    val DEFAULT: Set<PerfOption> = setOf(PerfOption.CPU, PerfOption.GPU, PerfOption.RAM, PerfOption.GPU_TEMP, PerfOption.GPU_FAN)

    fun read(config: JSONObject, computerId: String): Set<PerfOption> {
        val keys = config.optJSONObject(KEY)?.optJSONArray(computerId) ?: return DEFAULT
        return buildSet { for (i in 0 until keys.length()) PerfOption.of(keys.optString(i))?.let(::add) }
    }

    /** Écrit dans `config` (comme les autres réglages d'un widget posé) ; le choix des autres ordinateurs reste. */
    fun write(config: JSONObject, computerId: String, choice: Set<PerfOption>) {
        val all = config.optJSONObject(KEY) ?: JSONObject().also { config.put(KEY, it) }
        all.put(computerId, JSONArray(PerfOption.entries.filter { it in choice }.map { it.key }))
    }
}

/** Nombre court : une décimale (« 20,1 »), aucune pour une valeur ronde ou de trois chiffres (« 32 », « 120 »). */
fun perfNumber(value: Double): String =
    if (value % 1.0 == 0.0 || value >= 99.5) Math.round(value).toString() else String.format(Locale.FRANCE, "%.1f", value)

/** Débit à écrire : `value` en Ko/s, ou en Mo/s si `mega`. */
data class PerfRate(val value: Double, val mega: Boolean)

fun perfRate(kBps: Int): PerfRate = if (kBps >= 1000) PerfRate(kBps / 1000.0, true) else PerfRate(kBps.toDouble(), false)
