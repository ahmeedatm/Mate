package app.mate

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Langue de l'appli, choisie dans Réglages > Langue (français par défaut). Android 10 n'a pas de langue par appli :
 * MainActivity l'applique elle-même ([configure]) ; les textes viennent alors de `values-en`, les dates de [locale],
 * et les erreurs du compagnon arrivent dans cette langue ([acceptLanguage], en-tête de chaque appel).
 */
enum class AppLanguage(val key: String, val locale: Locale) {
    FRENCH("fr", Locale.FRANCE),
    ENGLISH("en", Locale.UK),
    ;

    companion object {
        /** Langue en cours, posée par [configure] avant tout écran ; lue par les formats de dates et le client HTTP. */
        @Volatile
        var current: AppLanguage = FRENCH

        val locale: Locale get() = current.locale

        fun fromKey(key: String?): AppLanguage = entries.firstOrNull { it.key == key } ?: FRENCH

        fun of(context: Context): AppLanguage = fromKey(Prefs.language(context))

        fun acceptLanguage(): String = current.key

        /** Retient la langue choisie, la donne à [config] et en fait la locale par défaut (formats sans locale explicite). */
        fun configure(context: Context, config: Configuration) {
            current = of(context)
            Locale.setDefault(current.locale)
            config.setLocale(current.locale)
        }
    }
}
