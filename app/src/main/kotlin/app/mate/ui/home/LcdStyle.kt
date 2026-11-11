package app.mate.ui.home

/**
 * Style LCD d'Instrument (`c_lcd_overlay`) : l'heure, le prochain rendez-vous, l'agenda, le Focus, l'eau et la météo
 * se lisent comme une fenêtre d'afficheur noire aux chiffres orange ; les autres widgets gardent leur tuile d'alu.
 * Sans style dans le thème (`overlay` = 0, tous les autres thèmes), rien ne change.
 */
object LcdStyle {
    private val TYPES = setOf(WidgetType.CLOCK, WidgetType.NEXT, WidgetType.AGENDA, WidgetType.FOCUS, WidgetType.WATER, WidgetType.WEATHER)

    fun applies(type: WidgetType, overlay: Int) = overlay != 0 && type in TYPES
}
