package app.mate.ui.cyberpunk

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.mate.R
import app.mate.net.CyberpunkApi
import app.mate.net.CyberpunkPhoneStatus
import app.mate.net.CyberwareBlock
import app.mate.net.CyberwareImplant
import app.mate.net.runCatchingCancellable
import app.mate.ui.errorText
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Ce que montre le tiroir Cyber ; `block` null : pas encore lu (ou mod trop ancien : la liste d'avant s'affiche). */
data class CyberwareUi(
    val block: CyberwareBlock? = null,
    val look: PhoneLook? = null,
    val lookStatus: CyberpunkPhoneStatus? = null,
    val sending: Boolean = false,
)

/**
 * Données du tiroir Cyber pour la page Cyberpunk : bloc `cyberware.json` relu quand son compteur change, images du jeu
 * (corps de V, icônes des zones) chargées à l'ouverture, pose d'un implant. `changed` : à redessiner.
 */
class CyberwareController(private val activity: FragmentActivity, private val changed: () -> Unit, private val toast: (String) -> Unit) {
    var ui = CyberwareUi()
        private set
    private var api: CyberpunkApi? = null
    private var version: Int? = null
    private var loading = false
    private var lookJob: Job? = null

    fun attach(client: CyberpunkApi?) {
        api = client
        lookJob?.cancel()
        ui = CyberwareUi()
        version = null
        loading = false
    }

    /** Compteur `blocks.cyber` de l'état : relit le bloc s'il a changé. */
    fun sync(next: Int) {
        if (next != version) load(next)
    }

    private fun load(next: Int) {
        val client = api ?: return
        if (loading) return
        loading = true
        activity.lifecycleScope.launch {
            runCatchingCancellable { client.cyberware() }
                .onSuccess {
                    if (api !== client) return@onSuccess
                    version = next
                    val bodyChanged = ui.block?.body != it.body
                    ui = ui.copy(block = it.takeIf { b -> b.ready })
                    if (bodyChanged && ui.look != null) ui = ui.copy(look = null).also { loadLook() }
                    changed()
                }
            // Échec : la liste d'avant reste, nouvel essai au prochain compteur.
            if (api === client) loading = false
        }
    }

    /** Images du jeu pour ce corps, si l'ordinateur les a extraites ; sinon le statut (bouton « Préparer »). */
    fun loadLook() {
        val client = api ?: return
        val body = ui.block?.body ?: return
        if (ui.look != null) return
        lookJob?.cancel()
        lookJob = activity.lifecycleScope.launch {
            val status = runCatchingCancellable { client.phoneStatus() }.getOrNull() ?: return@launch
            if (api !== client) return@launch
            val look = if (status.status == CyberpunkPhoneStatus.READY) {
                runCatchingCancellable { PhoneLook.load(activity, client, status.assets, CyberwareView.pieces(body), CyberwareView.BODY_MIN_SIDE_PX) }.getOrNull()
            } else {
                null
            }
            if (api !== client) return@launch
            ui = ui.copy(look = look, lookStatus = status.status)
            changed()
        }
    }

    /** Une nouvelle extraction remplace les images : rechargées au prochain affichage. */
    fun forgetLook() {
        lookJob?.cancel()
        ui = ui.copy(look = null)
    }

    fun equip(area: String, slot: Int, implant: CyberwareImplant) {
        val client = api ?: return
        if (ui.sending) return
        ui = ui.copy(sending = true)
        changed()
        activity.lifecycleScope.launch {
            runCatchingCancellable { client.equip(area, slot, implant.key) }
                .onSuccess { toast(activity.getString(R.string.cp_cw_done, implant.name)) }
                // Refus du jeu (capacité, combat…) ou pas de réponse : la phrase, jamais un nouvel envoi tout seul.
                .onFailure { toast(activity.errorText(it)) }
            if (api !== client) return@launch
            ui = ui.copy(sending = false)
            // Le mod réécrit cyberware.json avant de répondre : relu tout de suite, succès comme refus.
            version = null
            load(-1)
        }
    }
}
