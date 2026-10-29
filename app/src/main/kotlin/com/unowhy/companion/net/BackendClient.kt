package com.unowhy.companion.net

import android.content.Context
import com.unowhy.companion.Prefs

/**
 * Point d'accès au backend (agent sur l'ordinateur) : un client par domaine, sur une même connexion
 * (adresse + jeton). `BackendClient(context).deck.runTile(id)`.
 */
class BackendClient(baseUrl: String, token: String = "", connectTimeoutMs: Int = 5_000) {

    constructor(context: Context) : this(Prefs.backendUrl(context), Prefs.token(context))

    private val http = Http(baseUrl, token, connectTimeoutMs)

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
    val jira = JiraApi(http)
}
