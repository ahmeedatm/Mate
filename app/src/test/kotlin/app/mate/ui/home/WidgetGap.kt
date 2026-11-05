package app.mate.ui.home

/**
 * Ce qu'un widget n'a pas encore ([WidgetRulesTest]) : `shapes` = il lui manque des formes, `preview` = pas d'aperçu
 * ([WidgetSamples]). `reason` dit pourquoi, ou quelle issue s'en charge.
 */
class WidgetGap(val type: WidgetType, val shapes: Boolean, val preview: Boolean, val reason: String)
