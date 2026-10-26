package com.unowhy.companion

/**
 * Modes de la barre latérale, un Fragment chacun (tag = `name`, aussi gardé dans l'état de l'appli).
 * Les tâches sont sur l'Accueil : l'ancien mode "tasks" (ordinateur, extension Claude) y mène.
 * `connect` : sans ordinateur, le mode est remplacé par « Aucun ordinateur connecté » et ce texte.
 */
enum class Mode(val icon: Int, val label: Int, val connect: Int? = null) {
    HOME(R.drawable.ic_home, R.string.mode_home),
    DECK(R.drawable.ic_deck, R.string.mode_deck, R.string.connect_deck),
    DOCS(R.drawable.ic_folder, R.string.mode_docs, R.string.connect_docs),
    FOCUS(R.drawable.ic_timer, R.string.mode_focus),
    CAMERA(R.drawable.ic_videocam, R.string.mode_camera, R.string.connect_camera),
    SETTINGS(R.drawable.ic_settings, R.string.mode_settings),
}
