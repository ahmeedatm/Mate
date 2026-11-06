package app.mate.ui.home

import app.mate.net.Mail

/** Phrase à mettre sous le titre de la tuile Mails quand il n'y a pas de nombre à montrer (ou que la lecture a échoué). */
enum class MailNote { ERROR, NO_ACCOUNT, ACCOUNT_ERROR }

/** `mail` : dernière lecture réussie ; `hasError` : la lecture qui vient d'avoir lieu a échoué. Null : le nombre parle. */
fun mailNote(mail: Mail?, hasError: Boolean): MailNote? = when {
    hasError -> MailNote.ERROR
    mail == null || mail.accounts.isEmpty() -> MailNote.NO_ACCOUNT
    !mail.hasCount -> MailNote.ACCOUNT_ERROR
    else -> null
}

/**
 * Mise en page des Mails selon la forme posée (#181). `side` : l'icône à côté du nombre (Pastille, Ligne) ; `title` :
 * « Mails » à côté de l'icône (Bloc et plus grand) ; `accounts` : une ligne par boîte sous le nombre (Ligne de 3 cases et
 * plus, Colonne, Bloc et plus grand) ; `numberSp` : le nombre, très grand en plein écran.
 */
data class MailShape(val side: Boolean, val title: Boolean, val accounts: Boolean, val numberSp: Float) {
    companion object {
        fun of(size: WidgetSize): MailShape = when (size.family) {
            ShapeFamily.PILL -> MailShape(side = true, title = false, accounts = false, numberSp = 36f)
            ShapeFamily.ROW -> MailShape(side = true, title = false, accounts = size.w >= 3, numberSp = 36f)
            ShapeFamily.COLUMN -> MailShape(side = false, title = false, accounts = true, numberSp = 44f)
            ShapeFamily.BLOCK -> MailShape(side = false, title = true, accounts = true, numberSp = 52f)
            ShapeFamily.LARGE -> MailShape(side = false, title = true, accounts = true, numberSp = 72f)
            ShapeFamily.FULL -> MailShape(side = false, title = true, accounts = true, numberSp = 120f)
        }
    }
}

/** Une boîte sous le nombre : son nom, son nombre de non lus (null si elle n'a pas répondu) ou son erreur. */
data class MailBox(val label: String, val count: String?, val error: String?)

/** Les boîtes, la plus chargée d'abord, celles qui n'ont pas répondu à la fin. */
fun mailBoxes(mail: Mail): List<MailBox> = mail.accounts
    .sortedByDescending { it.unread ?: -1 }
    .map { MailBox(it.label, it.unread?.toString(), it.error) }
