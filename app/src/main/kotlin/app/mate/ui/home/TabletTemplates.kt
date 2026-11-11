package app.mate.ui.home

import android.content.Context
import androidx.annotation.StringRes
import app.mate.Mode
import app.mate.Prefs
import app.mate.R
import app.mate.net.HomeFeatures
import app.mate.ui.topbar.TopBarModel
import app.mate.ui.topbar.TopItem
import org.json.JSONObject

/**
 * Un écran d'un template : son nom, ses widgets (page 0, recopiés sur la sienne) et son icône ([ProfileIcons], null :
 * celle du rôle). `optional` : posé seulement si l'ordinateur propose tous ses widgets (Agents, GitLab, Jira).
 */
class TemplateScreen(
    @StringRes val name: Int,
    val widgets: List<Placed>,
    val icon: String? = null,
    val optional: Boolean = false,
)

/**
 * Templates de la tablette : une configuration complète prête à l'emploi (profils et widgets, bandeau du haut, barre de
 * gauche, thème), sur les vraies données de l'ordinateur. Rien n'est gardé : le template remplace la config, on la
 * modifie ensuite. Choisi dans Réglages, depuis la page de l'ordinateur (ordre
 * `template`) ou après le premier appairage. `id`, ordre, titre et description : ceux de
 * `TEMPLATES` dans backend/src/tablet.ts (vérifié par backend/test/templates-concordance.test.ts).
 */
enum class TabletTemplate(
    val id: String,
    @StringRes val title: Int,
    @StringRes val description: Int,
    val screens: List<TemplateScreen>,
    val theme: String?,
    val topBar: List<TopItem> = TopBarModel.DEFAULT,
    val railLabels: Boolean = true,
    val railTools: List<Mode> = Prefs.RAIL_TOOLS,
) {
    // La config d'une tablette neuve : l'Accueil Travail, Réunion, le thème par défaut (Instrument).
    BUREAU("bureau", R.string.template_bureau, R.string.template_bureau_desc, listOf(
        TemplateScreen(R.string.profile_first, Template.WORK_CADRAN.widgets(0)),
    ), theme = null),

    // Développeur : l'Accueil du jour, puis un écran plein par outil (agents Claude, MR, pipelines, tickets), Cadran nuit.
    DEV("dev", R.string.template_dev, R.string.template_dev_desc, listOf(
        TemplateScreen(R.string.profile_first, listOf(
            Placed(WidgetType.DAY, WidgetSize.W, 0, 0, 0),
            Placed(WidgetType.DAYLINE, WidgetSize.W, 0, 0, 1),
            Placed(WidgetType.NEXT, WidgetSize.V, 0, 0, 2),
            Placed(WidgetType.TASKS, WidgetSize.V, 0, 1, 2),
            Placed(WidgetType.APPS, WidgetSize.V, 0, 2, 2),
            Placed(WidgetType.KEYS, WidgetSize.V, 0, 3, 2, JSONObject().put(KeyRef.DEFAULT_PAGE, "Raccourcis")),
        )),
        TemplateScreen(R.string.template_screen_agents, listOf(Placed(WidgetType.AGENTS, WidgetSize.FULL, 0, 0, 0)), "agents", optional = true),
        TemplateScreen(R.string.template_screen_gitlab, listOf(Placed(WidgetType.GITLAB, WidgetSize.FULL, 0, 0, 0)), "present", optional = true),
        TemplateScreen(R.string.template_screen_pipelines, listOf(Placed(WidgetType.PIPELINES, WidgetSize.FULL, 0, 0, 0)), "bolt", optional = true),
        TemplateScreen(R.string.template_screen_jira, listOf(Placed(WidgetType.JIRA, WidgetSize.FULL, 0, 0, 0)), "doc", optional = true),
    ), theme = "CADRAN_NUIT");

    /**
     * La disposition du template : ses écrans dans l'ordre (le premier est l'Accueil), nommés par `name`, puis Réunion
     * (`callName`). Un écran `optional` est sauté si `features` (ce que l'ordinateur propose) n'offre pas l'un de ses
     * widgets ; `features` null (ordinateur absent ou injoignable) : tous les écrans sont posés.
     */
    fun layout(name: (Int) -> String, callName: String, features: HomeFeatures?): HomeLayout {
        val layout = HomeLayout(mutableListOf(), mutableListOf())
        screens
            .filter { screen -> !screen.optional || features == null || screen.widgets.all { it.type.offered(features) } }
            .forEach { screen -> layout.setIcon(layout.addProfile(name(screen.name), screen.widgets), screen.icon) }
        layout.ensureRoles(callName)
        return layout
    }

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** Pose un template sur la tablette. L'activité doit être recréée ensuite (thème, barres, profils). */
object TabletTemplates {
    private const val FILE = "companion"
    private const val KEY_ARRIVAL = "template_arrival"

    /** Au-delà, la recréation attendue n'a pas eu lieu : l'arrivée sur l'Accueil est périmée. */
    const val ARRIVAL_MAX_MS = 60_000L

    /** Vrai pendant la pose d'un template (lecture de ce que l'ordinateur propose comprise) : pas de seconde pose en même temps. Fil principal. */
    var busy = false

    /** Enregistre `layout` (de [TabletTemplate.layout]), le thème, le bandeau et la barre du template. */
    fun apply(context: Context, template: TabletTemplate, layout: HomeLayout, now: Long = System.currentTimeMillis()) {
        HomeLayout.save(context, layout)
        Prefs.setTheme(context, template.theme)
        Prefs.setTopBar(context, TopBarModel.serialize(template.topBar))
        Prefs.setRailLabels(context, template.railLabels)
        Prefs.RAIL_TOOLS.forEach { Prefs.setRailTool(context, it, it in template.railTools) }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putLong(KEY_ARRIVAL, now).apply()
    }

    /** Vrai une seule fois après [apply], dans la minute : l'activité recréée arrive sur l'Accueil. */
    fun takeArrival(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_ARRIVAL, -1)
        if (at < 0) return false
        prefs.edit().remove(KEY_ARRIVAL).apply()
        return now - at <= ARRIVAL_MAX_MS
    }
}
