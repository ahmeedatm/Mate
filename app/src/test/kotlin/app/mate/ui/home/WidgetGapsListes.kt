package app.mate.ui.home

/** #181 Listes et notes : ce qui manque encore à ses widgets ([WidgetRulesTest]). Retirer une ligne quand le widget est fini. */
val GAPS_LISTES = listOf(
    WidgetGap(WidgetType.CHECKLIST, shapes = true, preview = true, reason = "formes et aperçu à faire (#181)"),
    WidgetGap(WidgetType.NOTE, shapes = true, preview = false, reason = "formes à faire (#181)"),
    WidgetGap(WidgetType.MEALS, shapes = true, preview = true, reason = "formes et aperçu à faire (#181)"),
    WidgetGap(WidgetType.NEWS, shapes = true, preview = true, reason = "formes et aperçu à faire (#181)"),
    WidgetGap(WidgetType.MAIL, shapes = true, preview = true, reason = "formes et aperçu à faire (#181)"),
)
