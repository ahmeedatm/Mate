package app.mate.net

import android.content.Context
import app.mate.Prefs
import app.mate.ui.home.Demo

/**
 * Point d'accès au backend (agent sur l'ordinateur) : un client par domaine, sur une même connexion
 * (adresse + jeton). `BackendClient(context).deck.runTile(id)`.
 */
class BackendClient(
    baseUrl: String, token: String = "", connectTimeoutMs: Int = 5_000, trust: PinnedTrust? = null,
    persona: () -> String? = { null },
) {

    /** Connexion de la tablette : elle annonce son persona de démo (`Demo.active`), jamais celui d'une autre. */
    constructor(context: Context) : this(
        Prefs.backendUrl(context), Prefs.token(context),
        trust = Prefs.pin(context)?.let(PinnedTrust::forPin),
        persona = { Demo.active(context)?.id },
    )

    /** Ordinateur appairé : son certificat épinglé (`pin`), s'il en a un. */
    constructor(computer: app.mate.Computer, connectTimeoutMs: Int = 5_000) :
        this(computer.url, computer.token, connectTimeoutMs, computer.pin?.let(PinnedTrust::forPin))

    private val http = Http(baseUrl, token, connectTimeoutMs, trust = trust, persona = persona)

    val pairing = PairingApi(http)
    val deck = DeckApi(http)
    val windows = WindowsApi(http)
    val system = SystemApi(http)
    val pointer = PointerApi(http)
    val docs = DocsApi(http)
    val calendar = CalendarApi(http)
    val tasks = TasksApi(http)
    val camera = CameraApi(http)
    val updates = UpdatesApi(http)
    val tablet = TabletApi(http)
    val agents = AgentsApi(http)
    val gitlab = GitLabApi(http)
    val jira = JiraApi(http)
    val plugins = PluginsApi(http)
    val present = PresentApi(http)
    val demo = DemoApi(http)
    val notifications = NotificationsApi(http)
    val away = AwayApi(http)
}
