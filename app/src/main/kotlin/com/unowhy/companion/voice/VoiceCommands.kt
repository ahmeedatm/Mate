package com.unowhy.companion.voice

import com.unowhy.companion.net.DeckTile
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/** Ce qu'une phrase demande. Pas d'IA : une liste de tournures connues, puis les touches du Deck par leur nom. */
sealed class VoiceCommand {
    /** Nom d'un mode de MainActivity (home, deck, docs, focus, camera, settings). */
    data class ShowMode(val mode: String) : VoiceCommand()
    data class Volume(val level: Int? = null, val delta: Int? = null) : VoiceCommand()
    data class Mute(val muted: Boolean) : VoiceCommand()
    data class Mic(val muted: Boolean) : VoiceCommand()
    /** `pause`, `play`, `next`, `previous` : musique ou vidéo de l'ordinateur. */
    data class Media(val action: String) : VoiceCommand()
    /** `start` (avec la durée dite, sinon celle réglée), `pause`, `reset`. */
    data class Focus(val action: String, val minutes: Int? = null) : VoiceCommand()
    /** Nom de NoisePlayer.Kind, ou null pour arrêter l'ambiance. */
    data class Noise(val kind: String?) : VoiceCommand()
    data class AddTask(val label: String) : VoiceCommand()
    data class Tile(val tile: DeckTile) : VoiceCommand()
    /**
     * Fenêtre de l'ordinateur : `focus` (devant), `minimize`, `max`, `close`, `place` (`layout` left/right, ou
     * `display` = numéro d'écran dit, 0 = « l'autre écran »). `target` : appli ou titre dit, vide = celle de devant.
     * `launch` : « ouvre X » sans touche X dans le Deck : sa fenêtre si X est ouvert, sinon l'appli installée.
     */
    data class Window(val action: String, val target: String, val layout: String? = null, val display: Int? = null, val launch: Boolean = false) : VoiceCommand()
    /** Rendez-vous du jour (`days` = 0) ou de demain (1) ; `withTasks` : « qu'est-ce que j'ai aujourd'hui ». */
    data class Day(val days: Int, val withTasks: Boolean) : VoiceCommand()
    data class DoneTask(val target: String) : VoiceCommand()
    /** Tâche avec rappel parlé à `at` ("HH:MM", aujourd'hui). */
    data class Remind(val label: String, val at: String) : VoiceCommand()
    data class Search(val query: String) : VoiceCommand()
    /** Dictée : texte écrit dans l'appli au premier plan de l'ordinateur. */
    data class Type(val text: String) : VoiceCommand()
    data object Tasks : VoiceCommand()
    data object Time : VoiceCommand()
    data object NextEvent : VoiceCommand()
    data object Join : VoiceCommand()
    data object Drink : VoiceCommand()
    data object Help : VoiceCommand()
    /** Appel entrant (carte d'appel) : répondre, en vidéo, ou ignorer. */
    data class Call(val action: String) : VoiceCommand()
    data object HangUp : VoiceCommand()
    /**
     * Agent Claude : `allow` (autorise), `deny` (refuse la commande), `choice` (option `choice`, à partir de 1), `send`
     * (valide, suivant), `continue` (relance la dernière session finie). N'agit que sur la carte ouverte à l'écran.
     */
    data class Agent(val action: String, val choice: Int? = null) : VoiceCommand()
}

object VoiceCommands {

    private val MODES = mapOf(
        "accueil" to "home", "agenda" to "home", "deck" to "deck", "raccourcis" to "deck", "touches" to "deck",
        "partage" to "docs", "documents" to "docs", "fichiers" to "docs", "focus" to "focus", "pomodoro" to "focus", "camera" to "camera",
        "visualiseur" to "camera", "reglages" to "settings", "parametres" to "settings",
    )
    private val OPEN_MODE = Regex("^(ouvre|ouvrir|va|aller|affiche|afficher|montre|montrer|retour|reviens|retourne)( (sur|dans|a|au|aux|vers))? (.+)$")
    private val TILE_VERBS = Regex("^(ouvre|ouvrir|lance|lancer|demarre|demarrer|appuie sur|appuyer sur|touche|toucher|active|activer|fais|faire|mets|mettre)\\b ?")
    private val FILLER = setOf("fenetre", "fenetres", "appli", "application", "ca", "cette", "ce", "sur", "a")
    private val ARTICLES = setOf("le", "la", "les", "l", "un", "une", "des", "du", "de", "d", "mon", "ma", "mes")
    /** « l'écran 2 » est souvent entendu « l'écran de ». */
    private val SCREEN_NUMBERS = mapOf("un" to 1, "une" to 1, "deux" to 2, "de" to 2, "trois" to 3, "quatre" to 4)
    private val TASK = Regex("^(?:ajoute|ajouter|note|noter|nouvelle|cree|creer)(?: une)? (?:tache|a faire)(?: :)? (.+)$")
    private val TASK_ORIGINAL = Regex("^\\s*\\S+(?:\\s+une)?\\s+(?:t[âa]che|[àa] faire)\\s*:?\\s*", RegexOption.IGNORE_CASE)

    private val ORDINALS = mapOf(
        "un" to 1, "une" to 1, "premier" to 1, "premiere" to 1, "deux" to 2, "de" to 2, "deuxieme" to 2, "second" to 2, "seconde" to 2,
        "trois" to 3, "troisieme" to 3, "quatre" to 4, "quatrieme" to 4, "cinq" to 5, "cinquieme" to 5, "six" to 6, "sixieme" to 6,
    )

    /** Réponses à un agent Claude. « refuse » seul reste celui de l'appel : la commande vocale le reporte sur l'agent sans appel. */
    private fun agent(t: String): VoiceCommand.Agent? {
        if (Regex("^(autorise|autoriser|approuve|approuver|vas y|oui vas y)( la commande| la permission| le plan| une fois)?$").matches(t)) return VoiceCommand.Agent("allow")
        if (Regex("^(refuse|refuser|rejette|rejeter) (la commande|la permission|le plan)$").matches(t)) return VoiceCommand.Agent("deny")
        if (Regex("^(envoie|envoyer|valide|valider|suivant|question suivante)( les reponses| la reponse| le plan)?$").matches(t)) return VoiceCommand.Agent("send")
        if (Regex("^(continue|continuer)( claude| l agent)?$").matches(t)) return VoiceCommand.Agent("continue")
        Regex("^(?:l |le |la )?(?:option|choix|reponse) (\\w+)$").find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: ORDINALS[m.groupValues[1]]
            if (n != null && n in 1..6) return VoiceCommand.Agent("choice", n)
        }
        Regex("^(?:le |la )?(\\w+) (?:choix|option|reponse)$").find(t)?.let { m ->
            ORDINALS[m.groupValues[1]]?.takeIf { m.groupValues[1].endsWith("ieme") || m.groupValues[1].startsWith("premi") || m.groupValues[1].startsWith("second") }
                ?.let { return VoiceCommand.Agent("choice", it) }
        }
        return null
    }

    /** Minuscules, sans accents ni ponctuation : « Ouvre l'Accueil ! » → « ouvre l accueil ». */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9%]+"), " ")
            .trim()

    /** `now` : pour les rappels « dans 10 minutes ». */
    fun parse(text: String, tiles: List<DeckTile>, now: Calendar = Calendar.getInstance()): VoiceCommand? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        // Dictée en premier : le texte dicté peut contenir n'importe quels mots (« écris : coupe le micro »).
        Regex("^\\s*(?:écris|ecris|écrire|ecrire|tape|taper|dicte|saisis)\\s*:?\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)
            ?.let { return VoiceCommand.Type(it.groupValues[1].trim()) }
        fun has(vararg words: String) = words.any { Regex("\\b$it\\b").containsMatchIn(t) }
        val number = Regex("\\b(\\d{1,3})\\b").find(t)?.groupValues?.get(1)?.toInt()

        if (Regex("^(aide|que sais tu faire|qu est ce que (tu sais faire|je peux dire))").containsMatchIn(t)) return VoiceCommand.Help
        if (has("quelle heure")) return VoiceCommand.Time
        agent(t)?.let { return it }
        if (Regex("^(reponds|repond|repondre|decroche|decrocher|accepte|accepter)( l appel)?( en video)?$").matches(t)) {
            return VoiceCommand.Call(if (has("video")) "video" else "answer")
        }
        if (Regex("^(ignore|ignorer|refuse|refuser|rejette|rejeter)( l appel)?$").matches(t)) return VoiceCommand.Call("decline")
        if (Regex("^(raccroche|raccrocher|termine l appel|coupe l appel)$").matches(t)) return VoiceCommand.HangUp
        if (has("prochain rendez vous", "prochaine reunion", "prochain rdv", "c est quoi ma prochaine reunion")) return VoiceCommand.NextEvent
        if (Regex("^(rejoins|rejoindre|rejoint|lance|ouvre) (la |ma )?(reunion|visio|call)").containsMatchIn(t)) return VoiceCommand.Join
        TASK.find(t)?.let {
            // Libellé repris du texte reconnu, avec ses accents et majuscules.
            val label = text.replace(TASK_ORIGINAL, "").trim().ifEmpty { it.groupValues[1] }
            return VoiceCommand.AddTask(label.replaceFirstChar { c -> c.uppercase(Locale.FRENCH) })
        }
        if (has("j ai bu", "verre d eau")) return VoiceCommand.Drink
        if (t.startsWith("rappelle moi") || t.startsWith("rappelle toi")) remind(text, now)?.let { return it }
        Regex("^(?:cherche|recherche|google)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text.trim())
            ?.let { return VoiceCommand.Search(it.groupValues[1].trim()) }

        // Tâches et journée, avant les fenêtres (« affiche mes tâches »).
        Regex("^(coche|cocher|j ai fini|j ai termine) (.+)$").find(t)
            ?.let { return VoiceCommand.DoneTask(it.groupValues[2]) }
        if (has("taches", "tache", "a faire", "todo")) return VoiceCommand.Tasks
        val tomorrow = has("demain")
        if (has("agenda", "rendez vous", "rdv", "reunions", "programme", "planning") || Regex("^qu est ce que j ai (aujourd hui|demain)").containsMatchIn(t)) {
            if (has("aujourd hui", "demain", "journee", "agenda", "programme", "planning", "mes rendez vous", "mes reunions", "mes rdv")) {
                return VoiceCommand.Day(if (tomorrow) 1 else 0, withTasks = !tomorrow && !has("agenda", "rendez vous", "rdv", "reunions"))
            }
        }

        OPEN_MODE.find(t)?.let { m ->
            val rest = m.groupValues[4].split(" ").filter { it !in ARTICLES }.joinToString(" ")
            MODES[rest]?.let { return VoiceCommand.ShowMode(it) }
        }
        MODES[t.split(" ").filter { it !in ARTICLES }.joinToString(" ")]?.takeIf { t.split(" ").size <= 2 && it != "focus" }
            ?.let { return VoiceCommand.ShowMode(it) }

        window(t, tiles)?.let { return it }

        if (has("focus", "pomodoro", "minuteur", "chrono")) {
            return when {
                has("pause") -> VoiceCommand.Focus("pause")
                has("arrete", "stop", "annule", "termine", "remets a zero") -> VoiceCommand.Focus("reset")
                else -> VoiceCommand.Focus("start", number?.takeIf { it in 1..180 })
            }
        }
        if (has("ambiance", "bruit blanc", "bruit rose", "bruit brun", "bruit marron")) {
            return when {
                has("arrete", "coupe", "stop", "eteins", "enleve") -> VoiceCommand.Noise(null)
                has("blanc") -> VoiceCommand.Noise("WHITE")
                has("rose") -> VoiceCommand.Noise("PINK")
                else -> VoiceCommand.Noise("BROWN")
            }
        }
        if (has("micro")) {
            if (has("coupe", "couper", "desactive", "mute", "ferme", "eteins", "muet")) return VoiceCommand.Mic(true)
            if (has("active", "reactive", "remets", "rallume", "allume", "ouvre", "demute")) return VoiceCommand.Mic(false)
        }
        Regex("\\b(volume|son)( a| au)? (\\d{1,3})").find(t)?.let { return VoiceCommand.Volume(level = it.groupValues[3].toInt().coerceIn(0, 100)) }
        if (has("plus fort", "monte", "augmente")) return VoiceCommand.Volume(delta = 10)
        if (has("moins fort", "baisse", "diminue")) return VoiceCommand.Volume(delta = -10)
        if (has("coupe le son", "silence", "mute", "muet", "coupe le volume")) return VoiceCommand.Mute(true)
        if (has("remets le son", "reactive le son", "active le son", "remets du son", "remet le son")) return VoiceCommand.Mute(false)

        if (Regex("^(pause|mets (en )?pause|stop la musique|arrete la musique|pause la musique|stop)$").matches(t)) return VoiceCommand.Media("pause")
        if (Regex("^(lecture|play|reprends|reprend|reprise|remets la musique|relance la musique|lance la musique)$").matches(t)) return VoiceCommand.Media("play")
        if (has("suivant", "suivante", "next")) return VoiceCommand.Media("next")
        if (has("precedent", "precedente")) return VoiceCommand.Media("previous")

        matchTile(t, tiles)?.let { return VoiceCommand.Tile(it) }
        // « ouvre X » sans touche X : sa fenêtre si X est déjà ouvert, sinon l'appli installée.
        Regex("^(ouvre|ouvrir|lance|lancer|demarre|demarrer) (.+)$").find(t)?.let { return VoiceCommand.Window("focus", it.groupValues[2], launch = true) }
        return null
    }

    private fun window(t: String, tiles: List<DeckTile>): VoiceCommand? {
        // « réduis le son », « ferme le micro » : pas des fenêtres.
        if (Regex("\\b(son|volume|micro|musique|ambiance|focus|bruit)\\b").containsMatchIn(t)) return null
        Regex("^(?:mets|met|mais|mes|deplace|place|envoie|bouge) (.*?) ?(a gauche|a droite|en grand|en plein ecran|sur l autre ecran|sur l ecran (\\d|un|une|deux|de|trois|quatre)|sur l ecran (?:de |du |d |a )?(gauche|droite|droit|milieu))$").find(t)?.let { m ->
            val target = m.groupValues[1]
            return when (val where = m.groupValues[2]) {
                "a gauche" -> VoiceCommand.Window("place", target, layout = "left")
                "a droite" -> VoiceCommand.Window("place", target, layout = "right")
                "en grand", "en plein ecran" -> VoiceCommand.Window("max", target)
                "sur l autre ecran" -> VoiceCommand.Window("place", target, display = 0)
                // Écrans rangés de gauche à droite : 1 = le plus à gauche, -1 = le plus à droite, -2 = celui du milieu.
                else -> if (m.groupValues[4].isNotEmpty()) VoiceCommand.Window("place", target, display = when (m.groupValues[4]) {
                    "gauche" -> 1
                    "milieu" -> -2
                    else -> -1
                }) else VoiceCommand.Window("place", target, display = SCREEN_NUMBERS[m.groupValues[3]] ?: m.groupValues[3].toInt())
            }
        }
        Regex("^(reduis|reduire|reduit|minimise|minimiser|cache|cacher) ?(.*)$").find(t)?.let { return VoiceCommand.Window("minimize", it.groupValues[2]) }
        Regex("^(agrandis|agrandir|agrandit|maximise|maximiser) ?(.*)$").find(t)?.let { return VoiceCommand.Window("max", it.groupValues[2]) }
        Regex("^(ferme|fermer|quitte|quitter) ?(.*)$").find(t)?.let { m ->
            // « Quitter l'appel » est une touche : le verbe fait partie de son nom.
            val tile = matchTile(t, tiles)
            if (tile != null && normalize(tile.label).split(" ").any { sameWord(it, m.groupValues[1]) }) return VoiceCommand.Tile(tile)
            return VoiceCommand.Window("close", m.groupValues[2])
        }
        Regex("^(montre|montre moi|affiche|passe sur|passe a|va sur|bascule sur|mets devant|ramene|reviens sur) (.+)$").find(t)
            ?.let { return VoiceCommand.Window("focus", it.groupValues[2]) }
        return null
    }

    /**
     * « rappelle-moi d'appeler Paul à 15 h 30 », « rappelle-moi dans 10 minutes de sortir le linge ». Le libellé
     * est repris du texte reconnu (accents, majuscules).
     */
    private fun remind(text: String, now: Calendar): VoiceCommand? {
        // (?<!\S) plutôt que \b : sur la JVM (tests), \b ne voit pas « à » comme une lettre.
        val at = Regex("(?<!\\S)(?:à|a) (\\d{1,2}) ?(?:h|heures?|:) ?(\\d{2})?\\b", RegexOption.IGNORE_CASE)
        val delay = Regex("\\bdans (\\d+|une?) (minutes?|min|heures?|h)\\b", RegexOption.IGNORE_CASE)
        val time: String
        val rest: String
        val a = at.find(text)
        val d = delay.find(text)
        when {
            a != null -> {
                time = "%02d:%02d".format(a.groupValues[1].toInt().coerceIn(0, 23), a.groupValues[2].ifEmpty { "0" }.toInt().coerceIn(0, 59))
                rest = text.removeRange(a.range)
            }
            d != null -> {
                val n = d.groupValues[1].toIntOrNull() ?: 1
                val minutes = if (d.groupValues[2].startsWith("h")) n * 60 else n
                val c = (now.clone() as Calendar).apply { add(Calendar.MINUTE, minutes) }
                time = "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
                rest = text.removeRange(d.range)
            }
            else -> return null
        }
        val label = rest
            .replace(Regex("^\\s*rappelle[- ](?:moi|toi)\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:de |d'|d’|qu'il faut |que je dois |que je |qu'on )", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ").trim(' ', ',', '.')
        if (label.isEmpty()) return null
        return VoiceCommand.Remind(label.replaceFirstChar { it.uppercase(Locale.FRENCH) }, time)
    }

    /** Le nom dit désigne-t-il ce nom (appli, fenêtre, tâche) ? Tous les mots dits, ou le nom entier dans la phrase. */
    fun names(said: String, name: String): Boolean {
        val words = words(said)
        val target = words(name)
        if (words.isEmpty() || target.isEmpty()) return false
        return words.all { w -> target.any { sameWord(it, w) } } || target.all { w -> words.any { sameWord(it, w) } }
    }

    /** Appli installée la plus proche du nom dit : nom identique, sinon le plus court qui contient tous les mots dits. */
    fun bestApp(said: String, apps: List<String>): String? {
        val key = words(said).joinToString(" ")
        if (key.isEmpty()) return null
        return apps.firstOrNull { words(it).joinToString(" ") == key }
            ?: apps.filter { app -> words(said).all { w -> words(app).any { sameWord(it, w) } } }.minByOrNull { it.length }
    }

    /**
     * Nom le plus proche à l'oreille parmi `names` (« en cabane launcher » → « Ankama Launcher ») : la reconnaissance
     * d'Android 10 ne connaît pas les noms d'applis et n'accepte pas de liste de mots. Chaque phrase entendue
     * (`said` : les variantes du reconnaisseur) est comparée au nom entier et à son début. Null si rien n'est assez
     * proche, ou si deux noms le sont autant.
     */
    fun closest(said: List<String>, names: List<String>): String? {
        val scored = names.distinct().map { name ->
            name to said.maxOf { s ->
                val heard = phonetic(s)
                val k = words(s).size.coerceAtLeast(1)
                maxOf(similarity(heard, phonetic(name)), similarity(heard, phonetic(words(name).take(k).joinToString(" "))) * 0.95)
            }
        }.sortedByDescending { it.second }
        android.util.Log.i("Voice", "closest $said → ${scored.take(3).joinToString { "${it.first} %.2f".format(it.second) }}")
        val best = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)?.second ?: 0.0
        return best.first.takeIf { best.second >= 0.6 && best.second - second >= 0.1 }
    }

    private val SOUNDS = listOf(
        "eau" to "o", "au" to "o", "oi" to "wa", "ph" to "f", "qu" to "k", "ck" to "k", "sch" to "S", "ch" to "S", "sh" to "S",
        "gn" to "n", "th" to "t", "c(?=[eiy])" to "s", "c" to "k", "g(?=[ei])" to "j", "x" to "ks", "z" to "s", "w" to "v",
        "y" to "i", "h" to "", "(ain|ein|in|im|un|um)(?![aeiou])" to "I", "(an|am|en|em)(?![aeiou])" to "A",
        "(on|om)(?![aeiou])" to "O", "ou" to "U", "(ai|ei)" to "e", "(er|ez|et)$" to "e", "(?<=..)[estdxp]$" to "",
        "(.)\\1+" to "$1",
    ).map { (from, to) -> Regex(from) to to }

    /** Prononciation approximative à la française, sans espaces : « Ankama Launcher » → « AkamalOS ». */
    fun phonetic(text: String): String = normalize(text).split(" ").joinToString("") { word ->
        SOUNDS.fold(word) { w, (from, to) -> from.replace(w, to) }
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
    }

    /** « ouvre en cabane » → « en cabane » : l'appli ou la fenêtre désignée par une variante entendue. */
    fun target(text: String): String? =
        Regex("^(?:ouvre|ouvrir|lance|lancer|demarre|demarrer|reduis|reduire|agrandis|agrandir|ferme|fermer|montre|affiche|passe sur|va sur|mets|mais|mes|deplace) (.+?)(?: a gauche| a droite| en grand| sur l autre ecran| sur l ecran (?:\\d|un|une|deux|de|trois|quatre)| sur l ecran (?:de |du |d |a )?(?:gauche|droite|droit|milieu))?$")
            .find(normalize(text))?.groupValues?.get(1)

    fun words(text: String) = normalize(text).split(" ").filter { it.isNotEmpty() && it !in ARTICLES && it !in FILLER }

    /**
     * Touche du Deck dont tous les mots sont dans la phrase (« quitte l'appel » trouve « Quitter l'appel ») ;
     * la plus longue gagne (« Discord » perd contre « Discord : muet »).
     */
    private fun matchTile(t: String, tiles: List<DeckTile>): DeckTile? {
        val words = TILE_VERBS.replace(t, "").split(" ").filter { it.isNotEmpty() && it !in ARTICLES }
        if (words.isEmpty()) return null
        return tiles
            .map { it to normalize(it.label).split(" ").filter { w -> w.isNotEmpty() && w !in ARTICLES } }
            .filter { (_, label) -> label.isNotEmpty() && label.all { w -> words.any { sameWord(it, w) } } }
            .maxByOrNull { (_, label) -> label.size * 100 - (words.size - label.size).coerceAtLeast(0) }
            ?.first
    }

    /** Mêmes mots, ou même racine d'au moins 4 lettres (quitte / quitter, lance / lancer). */
    private fun sameWord(a: String, b: String): Boolean {
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return short.length >= 4 && long.startsWith(short) && long.length - short.length <= 2
    }
}
