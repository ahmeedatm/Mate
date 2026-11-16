package app.mate.ui.home

import app.mate.net.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Info trafic des lignes d'un arrêt (perturbé, interrompu, travaux à venir), lue chez IDFM avec la même clé que les
 * passages : API « general-message » au format SIRI, un appel de quelques Ko par ligne. IDFM ne donne ni gravité ni date
 * de début : la gravité et le moment se lisent dans son texte ([level]). Les pannes d'ascenseur n'y sont pas (elles sont dans navitia,
 * 1,8 Mo par ligne, trop lourd pour la tablette).
 */
object TransportTraffic {
    /** Du moins grave au plus grave : l'ordre sert à [worst] et à [sorted]. */
    enum class Level { SOON, DISRUPTED, INTERRUPTED }

    class Message(val lineRef: String, val level: Level, val text: String)

    /** Lignes lues au plus : une grande gare en voit plus de quinze, un appel chacune toutes les 5 minutes. */
    const val MAX_LINES = 6
    private const val REFRESH_MS = 5 * 60_000L

    private val SPACES = Regex("\\s+")
    private const val WEEKDAY = "(?:lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)"

    /**
     * Un futur qui annonce la gêne (« sera interrompu », « sera fermée »), pas sa fin (« sera rétabli »). Pas « seront
     * supprimés » : IDFM l'écrit aussi pendant un incident (« des trains seront supprimés »).
     */
    private val FUTURE = Regex("\\bser(a|ont)\\s+(\\S+\\s+)?(interromp|perturb|ferm|dévi|modifi)", RegexOption.IGNORE_CASE)
    private val INTERRUPTED = Regex("interromp", RegexOption.IGNORE_CASE)

    /**
     * Premier jour d'une période (« du 21 novembre », « du 21 au 22 novembre », « du lundi 14 au vendredi 18 décembre »,
     * « Le 1er décembre, … » en tête de phrase). Pas un « le 18 novembre » au milieu : « retour prévu le 18 novembre »
     * parle de la fin.
     */
    private val START_DAY = Regex(
        "(?:\\bdu|(?:^|[.;:]\\s*)le)\\s+(?:$WEEKDAY\\s+)?(\\d{1,2})(?:er)?\\s+(?:au\\s+(?:$WEEKDAY\\s+)?\\d{1,2}(?:er)?\\s+)?" +
            "(janvier|février|mars|avril|mai|juin|juillet|août|septembre|octobre|novembre|décembre)",
        RegexOption.IGNORE_CASE,
    )
    private val MONTHS = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")

    /** Heure de début des travaux de soirée (« dès 22:45 », « à partir de 21h30 »). */
    private val START_HOUR = Regex("(?:dès|à partir de)\\s+(\\d{1,2})\\s*[:h]\\s*(\\d{2})", RegexOption.IGNORE_CASE)

    /** Fin du service de nuit : avant cette heure, les travaux de la veille au soir sont encore en cours. */
    private const val NIGHT_END_MIN = 5 * 60
    private val PARIS: TimeZone = TimeZone.getTimeZone("Europe/Paris")

    /**
     * Réponse SIRI d'IDFM pour [lineRef] → ses perturbations encore valides à [now] : canal « Perturbation » seulement
     * (pas les informations commerciales), texte court sinon long, à plat, sans doublon.
     */
    fun parse(root: JSONObject, lineRef: String, now: Long): List<Message> {
        val infos = root.optJSONObject("Siri")
            ?.optJSONObject("ServiceDelivery")
            ?.optJSONArray("GeneralMessageDelivery")
            ?.optJSONObject(0)
            ?.optJSONArray("InfoMessage")
            ?: return emptyList()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
        val out = LinkedHashMap<String, Message>()
        for (i in 0 until infos.length()) {
            val info = infos.optJSONObject(i) ?: continue
            if (info.optJSONObject("InfoChannelRef")?.optString("value") != "Perturbation") continue
            val until = Transport.epoch(iso, info.optString("ValidUntilTime"))
            if (until != null && until < now) continue
            val text = text(info.optJSONObject("Content")).replace(SPACES, " ").trim()
            if (text.isNotEmpty()) out.getOrPut(text) { Message(lineRef, level(text, now), text) }
        }
        return out.values.toList()
    }

    /** Le texte court du message (`SHORT_MESSAGE`), sinon le premier texte venu. */
    private fun text(content: JSONObject?): String {
        val messages = content?.optJSONArray("Message") ?: return ""
        var first = ""
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val value = m.optJSONObject("MessageText")?.optString("value").orEmpty()
            if (value.isBlank()) continue
            if (m.optString("MessageType") == "SHORT_MESSAGE") return value
            if (first.isEmpty()) first = value
        }
        return first
    }

    /**
     * Gravité tirée du texte d'IDFM à [now] (heure de Paris) : « interrompu » = interrompu, le reste = perturbé, mais à venir
     * si le premier jour annoncé n'est pas arrivé, si l'heure de début du soir (« dès 22:45 ») n'est pas passée, ou, sans
     * date ni heure, si le texte annonce la gêne au futur.
     */
    fun level(text: String, now: Long): Level {
        val severity = if (INTERRUPTED.containsMatchIn(text)) Level.INTERRUPTED else Level.DISRUPTED
        val cal = Calendar.getInstance(PARIS, Locale.FRANCE).apply { timeInMillis = now }
        val start = startDay(text, cal)
        if (start != null && start.after(cal)) return Level.SOON
        val from = START_HOUR.find(text)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        if (from != null) {
            val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            // shortcut: « du lundi au vendredi » n'est pas lu, un samedi les travaux de semaine paraissent en cours le soir.
            return if (minute in NIGHT_END_MIN until from) Level.SOON else severity
        }
        if (start != null) return severity
        return if (FUTURE.containsMatchIn(text)) Level.SOON else severity
    }

    /**
     * Minuit du premier jour annoncé, dans l'année qui le met à moins de six mois d'aujourd'hui : « le 5 janvier » lu en
     * novembre est l'an prochain, « du 20 décembre » lu en janvier est l'an dernier.
     */
    private fun startDay(text: String, today: Calendar): Calendar? {
        val match = START_DAY.find(text) ?: return null
        val month = MONTHS.indexOf(match.groupValues[2].lowercase(Locale.FRANCE)).takeIf { it >= 0 } ?: return null
        val day = Calendar.getInstance(PARIS, Locale.FRANCE).apply {
            clear()
            set(today.get(Calendar.YEAR), month, match.groupValues[1].toInt())
        }
        val halfYearAgo = (today.clone() as Calendar).apply { add(Calendar.MONTH, -6) }
        val halfYearAhead = (today.clone() as Calendar).apply { add(Calendar.MONTH, 6) }
        if (day.before(halfYearAgo)) day.add(Calendar.YEAR, 1)
        if (day.after(halfYearAhead)) day.add(Calendar.YEAR, -1)
        return day
    }

    /** Le plus grave de [lineRef] (de tout l'arrêt si null) ; null s'il n'y a rien. */
    fun worst(messages: List<Message>, lineRef: String? = null): Level? =
        messages.filter { lineRef == null || it.lineRef == lineRef }.maxOfOrNull { it.level }

    /** Les plus graves d'abord, l'ordre d'IDFM ensuite. */
    fun sorted(messages: List<Message>): List<Message> = messages.sortedByDescending { it.level }

    /** Lignes à lire : la ligne choisie, sinon celles des passages vus à l'arrêt, dans leur ordre, [MAX_LINES] au plus. */
    fun watched(departures: List<Transport.Departure>, chosenLine: String?): List<String> {
        if (!chosenLine.isNullOrBlank()) return listOf(chosenLine)
        // shortcut: les lignes au-delà de MAX_LINES (grandes gares) restent sans info trafic ; à revoir si quelqu'un règle Châtelet.
        return departures.map { it.lineRef }.filter { it.isNotBlank() }.distinct().take(MAX_LINES)
    }

    /** Relire ? Toutes les 5 minutes, ou tout de suite si les lignes ont changé (nouvel arrêt) ; jamais sans ligne. */
    fun due(lastAt: Long, lastLines: List<String>, lines: List<String>, now: Long): Boolean =
        lines.isNotEmpty() && (lines != lastLines || now - lastAt >= REFRESH_MS)

    /**
     * Les perturbations de [lines], lues en parallèle. Une ligne en échec ne cache pas les autres ; toutes en échec :
     * l'erreur remonte (l'appelant garde alors l'info d'avant plutôt que d'afficher « tout va bien »).
     */
    suspend fun load(lines: List<String>, now: Long): List<Message> = coroutineScope {
        val results = lines.map { line ->
            async {
                runCatchingCancellable {
                    parse(Transport.get("${Transport.BASE}/general-message?LineRef=${URLEncoder.encode(line, "UTF-8")}"), line, now)
                }
            }
        }.awaitAll()
        if (results.isNotEmpty() && results.none { it.isSuccess }) results.first().getOrThrow()
        results.flatMap { it.getOrDefault(emptyList()) }
    }
}
