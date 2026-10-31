package app.mate.agents

import app.mate.net.AgentAnswer
import app.mate.net.AgentQuestion

/**
 * Réponses en cours à une question à choix de Claude : une page par question. Une question simple prend une seule
 * réponse (une option, ou du texte libre « Autre » qui remplace l'option) ; une question multiple, plusieurs options et
 * un texte libre en plus. Ce que le compagnon reçoit : numéros d'options et texte, jamais autre chose que les options proposées.
 */
class AnswerDraft(private val questions: List<AgentQuestion>) {
    private val chosen = List(questions.size) { linkedSetOf<Int>() }
    private val other = MutableList<String?>(questions.size) { null }

    val size get() = questions.size

    fun choices(page: Int): Set<Int> = chosen[page]

    fun other(page: Int): String? = other[page]

    /** Choisit ou retire une option ; sur une question simple, elle remplace la précédente et efface le texte libre. */
    fun toggle(page: Int, option: Int) {
        require(option in questions[page].options.indices)
        if (questions[page].multi) {
            if (!chosen[page].remove(option)) chosen[page].add(option)
        } else {
            chosen[page].clear()
            chosen[page].add(option)
            other[page] = null
        }
    }

    /** Texte libre (vide : retiré) ; sur une question simple il remplace l'option choisie. */
    fun setOther(page: Int, text: String?) {
        val clean = text?.trim()?.takeIf { it.isNotEmpty() }
        other[page] = clean
        if (clean != null && !questions[page].multi) chosen[page].clear()
    }

    fun isAnswered(page: Int) = chosen[page].isNotEmpty() || other[page] != null

    fun isComplete() = questions.indices.all(::isAnswered)

    fun build(): List<AgentAnswer> = questions.indices.map { AgentAnswer(chosen[it].sorted(), other[it]) }
}
