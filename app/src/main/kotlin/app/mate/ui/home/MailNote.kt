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
