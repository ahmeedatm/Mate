package app.mate.ui.home

import app.mate.net.VoiceMember

/** Rangée de photos d'un appel : `shown` dans l'ordre d'affichage, puis un rond « +more » s'il reste des participants. */
data class MemberRowPlan(val shown: List<VoiceMember>, val more: Int)

/**
 * Ordre de la rangée. Tous tiennent : ordre d'arrivée, personne ne bouge quand quelqu'un parle. Salon plein (`max` :
 * ronds qui tiennent en largeur) : les derniers à avoir parlé d'abord (`lastSpoke` : identifiant → instant), les autres
 * dans l'ordre d'arrivée, et la dernière place devient « +N ».
 */
fun planMembers(members: List<VoiceMember>, lastSpoke: Map<String, Long>, max: Int): MemberRowPlan {
    if (members.isEmpty() || max <= 0) return MemberRowPlan(emptyList(), 0)
    if (members.size <= max) return MemberRowPlan(members, 0)
    val ordered = members.withIndex()
        .sortedWith(compareByDescending<IndexedValue<VoiceMember>> { lastSpoke[it.value.id] ?: Long.MIN_VALUE }.thenBy { it.index })
        .map { it.value }
    val shown = ordered.take(max - 1)
    return MemberRowPlan(shown, ordered.size - shown.size)
}

private val WORD_BREAK = Regex("[\\s_\\-.]+")

/** Initiales d'un pseudo, pour un participant sans photo : première lettre des deux premiers mots (« player_678 » → « P6 »). */
fun initials(name: String): String {
    val letters = name.split(WORD_BREAK).mapNotNull { word ->
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            if (Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.OTHER_SYMBOL.toInt()) {
                return@mapNotNull String(Character.toChars(cp)).uppercase()
            }
            i += Character.charCount(cp)
        }
        null
    }
    return letters.take(2).joinToString("").ifEmpty { "?" }
}
