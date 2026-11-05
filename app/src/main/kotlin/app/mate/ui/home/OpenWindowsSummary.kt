package app.mate.ui.home

import android.content.Context
import app.mate.R
import app.mate.net.ComputerDisplay
import app.mate.net.ComputerWindow
import app.mate.net.OpenApp
import app.mate.net.OpenWindows

/**
 * Logique pure du widget Fenêtres ouvertes en petit (Pastille, Ligne, Colonne, #183) : les applis qui ont des fenêtres,
 * celle du premier plan d'abord, et la fenêtre qu'un toucher met devant. Bloc, Grand et Plein écran gardent la vue
 * des écrans (`OpenWindowsView`).
 */
object OpenWindowsSummary {
    /** Une appli résumée : son nombre de fenêtres, et si l'une d'elles est au premier plan. */
    data class Entry(val app: OpenApp, val windows: Int, val front: Boolean)

    fun compact(family: ShapeFamily) = family == ShapeFamily.PILL || family == ShapeFamily.ROW || family == ShapeFamily.COLUMN

    /** Applis qui ont au moins une fenêtre, celle du premier plan en tête, les autres dans l'ordre reçu. */
    fun apps(data: OpenWindows): List<Entry> = data.apps
        .filter { it.windows.isNotEmpty() }
        .map { app -> Entry(app, app.windows.size, app.windows.any { it.front }) }
        .sortedByDescending { it.front }

    fun total(data: OpenWindows) = data.apps.sumOf { it.windows.size }

    /** Fenêtre qu'un toucher sur l'appli met devant : celle du premier plan, sinon la première non réduite, sinon la première. */
    fun target(app: OpenApp): ComputerWindow? =
        app.windows.firstOrNull { it.front } ?: app.windows.firstOrNull { !it.minimized } ?: app.windows.firstOrNull()
}

/** Exemple de Fenêtres ouvertes pour le catalogue : quatre applis sur un écran, Musique au premier plan, sans ordinateur. */
object OpenWindowsSample : WidgetSample {
    fun data(context: Context): OpenWindows {
        var index = 0
        fun app(name: Int, vararg titles: Int, front: Boolean = false) = OpenApp(
            context.getString(name),
            context.getString(name),
            titles.mapIndexed { i, title ->
                ComputerWindow(++index, context.getString(title), minimized = false, fullscreen = false, display = 1, front = front && i == 0)
            },
        )
        return OpenWindows(
            listOf(
                app(R.string.widget_open_sample_browser, R.string.widget_open_sample_browser_title),
                app(R.string.widget_open_sample_mail, R.string.widget_open_sample_mail_title, R.string.widget_open_sample_mail_title2),
                app(R.string.widget_open_sample_music, R.string.widget_open_sample_music_title, front = true),
                app(R.string.widget_open_sample_notes, R.string.widget_open_sample_notes_title),
            ),
            listOf(ComputerDisplay(1, context.getString(R.string.widget_open_sample_display), main = true, x = 0, y = 0, w = 1920, h = 1080)),
        )
    }
}
