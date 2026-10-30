package app.mate.ui.home

import org.json.JSONArray
import org.json.JSONObject

/** Une étape de la checklist : son texte et si elle est faite. */
data class ChecklistStep(val text: String, val done: Boolean = false)

/** Ce que dit le compte à rebours : jours restants, heures et minutes le dernier jour, échéance passée, ou rien. */
sealed interface Countdown {
    data object None : Countdown

    /** Au moins un jour d'écart : « J-3 ». */
    data class Days(val days: Int) : Countdown

    /** Moins de 24 h : « 5 h 20 ». */
    data class Hours(val hours: Int, val minutes: Int) : Countdown

    data object Passed : Countdown
}

/**
 * Checklist d'échéance du widget Clôture : titre (vide = celui par défaut, que l'interface fournit), échéance
 * (millisecondes, null = aucune) et étapes. Gardée dans la config du widget ([read] / [writeTo]) ; tout est immuable,
 * chaque changement rend une nouvelle liste.
 */
data class Checklist(val title: String = "", val dueMs: Long? = null, val steps: List<ChecklistStep> = emptyList()) {

    val total get() = steps.size
    val doneCount get() = steps.count { it.done }

    /** Part des étapes faites, 0 sans étape. */
    val fraction get() = if (total == 0) 0f else doneCount.toFloat() / total

    fun countdown(now: Long): Countdown {
        val due = dueMs ?: return Countdown.None
        val left = due - now
        if (left <= 0) return Countdown.Passed
        if (left < DAY_MS) return Countdown.Hours((left / HOUR_MS).toInt(), (left % HOUR_MS / MINUTE_MS).toInt())
        return Countdown.Days(daysFrom(now, due).coerceAtLeast(1))
    }

    /** Rouge : moins de 24 h avant l'échéance, ou échéance passée. */
    fun urgent(now: Long) = countdown(now).let { it is Countdown.Hours || it is Countdown.Passed }

    /** Coche ou décoche l'étape `index` ; un index hors liste ne change rien. */
    fun toggle(index: Int) = withStep(index) { it.copy(done = !it.done) }

    /** Change le texte de l'étape `index` ; un texte vide ne change rien. */
    fun rename(index: Int, text: String): Checklist {
        val clean = clean(text) ?: return this
        return withStep(index) { it.copy(text = clean) }
    }

    /** Ajoute une étape à faire en fin de liste ; texte vide ou liste pleine ([MAX_STEPS]) : rien ne change. */
    fun add(text: String): Checklist {
        val clean = clean(text) ?: return this
        return if (steps.size >= MAX_STEPS) this else copy(steps = steps + ChecklistStep(clean))
    }

    fun remove(index: Int) = if (index in steps.indices) copy(steps = steps.filterIndexed { i, _ -> i != index }) else this

    fun uncheckAll() = copy(steps = steps.map { it.copy(done = false) })

    fun withTitle(text: String) = copy(title = text.trim().take(MAX_TITLE))

    fun withDue(ms: Long?) = copy(dueMs = ms)

    private fun withStep(index: Int, change: (ChecklistStep) -> ChecklistStep) =
        if (index in steps.indices) copy(steps = steps.mapIndexed { i, s -> if (i == index) change(s) else s }) else this

    private fun clean(text: String) = text.trim().take(MAX_TEXT).takeIf { it.isNotEmpty() }

    /** Écrit la checklist dans la config du widget. */
    fun writeTo(config: JSONObject) {
        config.put(TITLE, title)
        if (dueMs != null) config.put(DUE, dueMs) else config.remove(DUE)
        config.put(STEPS, JSONArray().apply { steps.forEach { put(JSONObject().put(TEXT, it.text).put(DONE, it.done)) } })
    }

    companion object {
        const val MAX_STEPS = 30
        const val MAX_TEXT = 80
        const val MAX_TITLE = 40

        private const val TITLE = "title"
        private const val DUE = "due"
        private const val STEPS = "steps"
        private const val TEXT = "t"
        private const val DONE = "d"

        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS
        private const val DAY_MS = 24 * HOUR_MS

        /** Lit la config ; une valeur absente ou du mauvais type donne le défaut, une étape sans texte est ignorée. */
        fun read(config: JSONObject): Checklist {
            val steps = config.optJSONArray(STEPS)?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    val step = array.optJSONObject(i) ?: return@mapNotNull null
                    val text = step.optString(TEXT).trim().take(MAX_TEXT).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    ChecklistStep(text, step.optBoolean(DONE, false))
                }.take(MAX_STEPS)
            }.orEmpty()
            return Checklist(
                title = config.optString(TITLE).trim().take(MAX_TITLE),
                dueMs = config.optLong(DUE, 0L).takeIf { it > 0 },
                steps = steps,
            )
        }

        /** Config d'un widget posé avec ses étapes (tests). */
        fun config(title: String, dueMs: Long?, steps: List<ChecklistStep>) = JSONObject().also { Checklist(title, dueMs, steps).writeTo(it) }
    }
}
