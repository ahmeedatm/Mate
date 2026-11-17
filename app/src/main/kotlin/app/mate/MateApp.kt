package app.mate

import android.app.Application

/** Démarrage du processus, avec ou sans écran : la langue choisie dans les Réglages est posée avant tout le reste. */
class MateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.applyToApp(this)
    }
}
