package app.mate.ui.home

/** Bouton d'un widget en mode Modifier, chacun dans un coin du widget. */
enum class EditButton(val corner: Corner) {
    REMOVE(Corner.TOP_LEFT),
    SETTINGS(Corner.TOP_RIGHT),
    RESIZE(Corner.BOTTOM_RIGHT),
    EXPAND(Corner.BOTTOM_LEFT),
    ;

    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
}

object EditButtons {
    /** Boutons montrés sur un widget : retirer toujours, les autres selon ce qu'il sait faire. */
    fun of(expandable: Boolean, hasSettings: Boolean, resizable: Boolean): List<EditButton> = buildList {
        add(EditButton.REMOVE)
        if (hasSettings) add(EditButton.SETTINGS)
        if (resizable) add(EditButton.RESIZE)
        if (expandable) add(EditButton.EXPAND)
    }
}
