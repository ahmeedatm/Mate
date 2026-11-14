package app.mate.ui.cyberpunk

import android.graphics.Canvas
import android.graphics.Paint
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.mate.Computer
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.CyberpunkActionResult
import app.mate.net.CyberpunkApi
import app.mate.net.CyberpunkCallPhase
import app.mate.net.CyberpunkContact
import app.mate.net.CyberpunkContacts
import app.mate.net.CyberpunkItem
import app.mate.net.CyberpunkMap
import app.mate.net.CyberpunkMapStatus
import app.mate.net.CyberpunkPhoneStatus
import app.mate.net.CyberpunkPresence
import app.mate.net.CyberpunkQuest
import app.mate.net.CyberpunkServerPin
import app.mate.net.CyberpunkSnapshot
import app.mate.net.CyberpunkState
import app.mate.net.CyberpunkStatus
import app.mate.net.CyberpunkThread
import app.mate.net.CyberpunkThreadChoice
import app.mate.net.CyberpunkThreadDetail
import app.mate.net.runCatchingCancellable
import app.mate.ui.cyberpunk.CyberpunkKit.Companion.MATCH
import app.mate.ui.cyberpunk.CyberpunkKit.Companion.WRAP
import app.mate.ui.errorText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/**
 * Page Cyberpunk 2077 : calque plein écran par-dessus le Deck (comme PageOverlay), ouvert tout seul au lancement du jeu
 * (MainActivity, ui/CyberpunkSession). La DA du jeu, quel que soit le thème de Mate. Disposition « Carte d'abord » : la
 * zone centrale est réservée à la carte ([setMapView], un fond de grille en attendant), posés dessus un rail d'icônes à
 * gauche qui ouvre un tiroir de 420 dp, le HUD en haut, le bandeau d'appel, les actions Soin et Voiture, la quête suivie.
 * Le coin haut droit (72 × 72) reste à Fermer. État lu par `GET /cyberpunk` toutes les secondes page ouverte ; les blocs
 * lents (objets, contacts, quêtes) seulement quand leur compteur change ou à l'ouverture de leur tiroir.
 */
class CyberpunkOverlay(private val activity: FragmentActivity) {
    private enum class Block { INVENTORY, CONTACTS, QUESTS }

    private var root: FrameLayout? = null
    private var views: Views? = null
    private var api: CyberpunkApi? = null
    private var job: Job? = null
    private var computer: Computer? = null

    private var snapshot: CyberpunkSnapshot? = null
    private var error: String? = null

    /** Dernier état lu en partie, gardé pendant les sondages ratés ([CyberpunkLink]) ; `misses` : sondages de suite sans état en partie. */
    private var liveSnapshot: CyberpunkSnapshot? = null
    private var misses = 0
    private var mode = CyberpunkLink.Mode.AWAY
    private val blockErrors = mutableMapOf<Block, String>()
    private val failedVersions = mutableMapOf<Block, Int>()
    private var phone = PhoneState()
    private var threadJob: Job? = null
    private var lookJob: Job? = null
    private var inventory: List<CyberpunkItem>? = null
    private var contacts: CyberpunkContacts? = null
    private var quests: List<CyberpunkQuest>? = null
    private val versions = mutableMapOf<Block, Int>()
    private val loading = mutableSetOf<Block>()
    private var page: CyberpunkPage? = null
    private var drawn: Any? = null
    private var mapView: MapView? = null
    private var map: CyberpunkMap? = null
    private var mapLoading = false
    private var pins: List<CyberpunkServerPin>? = null
    private var pinsKey: Any? = null
    private var pinsLoading = false
    private var lastQuestId: String? = null

    val isOpen get() = root != null

    /** Ordinateur dont la page montre la partie (pas forcément l'actif : un autre peut avoir été choisi depuis). */
    val computerId: String? get() = computer?.id

    /** Ce que le sondage de la page dit du jeu, pour ui/CyberpunkSession (null : plus en partie). */
    fun presence(): CyberpunkPresence? =
        if (isOpen && mode.shown) CyberpunkPresence(available = true, gameFound = true, modInstalled = true, running = true, inGame = true) else null

    fun open(on: Computer) {
        if (isOpen) close()
        computer = on
        val client = BackendClient(on).cyberpunk
        api = client
        val ctx = ContextThemeWrapper(activity, R.style.Fonts_Cyberpunk)
        val kit = CyberpunkKit(ctx)
        val v = build(kit)
        views = v
        root = v.root
        mapView = MapView(kit, client, activity.lifecycleScope, onPinsChanged = { pinsKey = null; loadPins() }, onMapChanged = { map = null; loadMap() }, say = ::toast)
            .also { setMapView(it) }
        activity.findViewById<ViewGroup>(android.R.id.content).addView(v.root)
        render()
        resume()
    }

    fun close() {
        pause()
        mapView?.release()
        root?.let { (it.parent as? ViewGroup)?.removeView(it) }
        root = null
        views = null
        api = null
        computer = null
        snapshot = null
        error = null
        liveSnapshot = null
        misses = 0
        mode = CyberpunkLink.Mode.AWAY
        blockErrors.clear()
        failedVersions.clear()
        threadJob?.cancel()
        lookJob?.cancel()
        phone = PhoneState()
        inventory = null
        contacts = null
        quests = null
        versions.clear()
        loading.clear()
        page = null
        drawn = null
        mapView = null
        map = null
        mapLoading = false
        pins = null
        pinsKey = null
        pinsLoading = false
        lastQuestId = null
    }

    /** Retour : ferme la conversation ouverte, puis le tiroir, puis la page. */
    fun onBack() {
        when {
            phone.thread != null -> closeThread()
            page != null -> showDrawer(null)
            else -> close()
        }
    }

    /** Écran éteint ou appli en arrière-plan : plus de sondage. */
    fun pause() {
        job?.cancel()
        job = null
    }

    fun resume() {
        if (!isOpen || job?.isActive == true) return
        job = activity.lifecycleScope.launch {
            while (isActive) {
                tick()
                delay(POLL_MS)
            }
        }
    }

    /** La carte prend la place du fond de grille. */
    fun setMapView(map: View) {
        views?.mapSlot?.let {
            it.removeAllViews()
            it.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    // --- Données

    private suspend fun tick() {
        val client = api ?: return
        val result = runCatchingCancellable { client.state() }
        // Page fermée ou rouverte sur un autre ordinateur pendant la requête : le résultat est perdu.
        if (api !== client) return
        result
            .onSuccess {
                snapshot = it
                error = null
                if (it.status == CyberpunkStatus.INGAME && it.state != null) {
                    liveSnapshot = it
                    misses = 0
                } else {
                    misses++
                }
                syncMap()
                syncPins(it.state)
                it.state?.blocks?.let { b ->
                    sync(Block.INVENTORY, b.inv)
                    sync(Block.CONTACTS, b.contacts)
                    sync(Block.QUESTS, b.quests)
                }
            }
            .onFailure {
                error = activity.errorText(it)
                misses++
            }
        render()
    }

    /** L'état de la carte se relit tant qu'elle n'est pas prête (progression) ; absente ou en erreur, au bouton « Préparer ». */
    private fun syncMap() {
        val m = map
        if (m == null || m.status == CyberpunkMapStatus.LOADING) loadMap()
    }

    private fun loadMap() {
        val client = api ?: return
        if (mapLoading) return
        mapLoading = true
        activity.lifecycleScope.launch {
            try {
                runCatchingCancellable { client.map() }
                    .onSuccess { if (api === client) { map = it; mapView?.setMap(it) } }
                    .onFailure { if (api === client) mapView?.setMapError(activity.errorText(it)) }
            } finally {
                mapLoading = false
            }
        }
    }

    /** Les repères se relisent quand leur liste ou le suivi change dans l'état du mod. */
    private fun syncPins(st: CyberpunkState?) {
        st ?: return
        val key = listOf(st.pins.map { it.pid }, st.tracked?.kind, st.tracked?.id)
        if (key != pinsKey) {
            pinsKey = key
            loadPins()
        }
    }

    private fun loadPins() {
        val client = api ?: return
        if (pinsLoading) {
            pinsKey = null // une lecture est en cours : la relancer au prochain sondage
            return
        }
        pinsLoading = true
        activity.lifecycleScope.launch {
            try {
                runCatchingCancellable { client.pins() }
                    .onSuccess { if (api === client) { pins = it; mapView?.setPins(it); render() } }
                    .onFailure { pinsKey = null }
            } finally {
                pinsLoading = false
            }
        }
    }

    /** Lit le bloc s'il n'a jamais été lu ou si son compteur a changé. */
    private fun sync(block: Block, version: Int) {
        // Un échec n'est pas relancé chaque seconde : au prochain changement de compteur ou à la réouverture du tiroir.
        if (versions[block] != version && failedVersions[block] != version) load(block, version)
    }

    private fun load(block: Block, version: Int) {
        val client = api ?: return
        if (!loading.add(block)) return
        activity.lifecycleScope.launch {
            try {
                runCatchingCancellable {
                    // Page fermée ou rouverte sur un autre ordinateur pendant la requête : le résultat est perdu.
                    when (block) {
                        Block.INVENTORY -> client.inventory().also { if (api === client) inventory = it }
                        Block.CONTACTS -> client.contacts().also { if (api === client) contacts = it }
                        Block.QUESTS -> client.quests().also { if (api === client) quests = it }
                    }
                }.onSuccess {
                    if (api !== client) return@onSuccess
                    versions[block] = version
                    blockErrors.remove(block)
                    failedVersions.remove(block)
                    render()
                }.onFailure {
                    if (api !== client) return@onFailure
                    blockErrors[block] = activity.errorText(it)
                    failedVersions[block] = version
                    render()
                }
            } finally {
                loading.remove(block)
            }
        }
    }

    // --- Actions

    private fun perform(okText: String, call: suspend CyberpunkApi.() -> CyberpunkActionResult) {
        val client = api ?: return
        activity.lifecycleScope.launch {
            runCatchingCancellable { client.call() }
                .onSuccess { toast(if (it.ok) okText else activity.getString(R.string.cp_refused)) }
                .onFailure { toast(activity.errorText(it)) }
        }
    }

    private fun heal() {
        val item = CyberpunkItems.healItem(inventory.orEmpty())
        if (item == null) toast(activity.getString(R.string.cp_no_heal)) else use(item)
    }

    private fun use(item: CyberpunkItem) = perform(activity.getString(R.string.cp_used, item.name)) { action("use", tdb = item.tdb) }

    private fun install() {
        val client = api ?: return
        activity.lifecycleScope.launch {
            runCatchingCancellable { client.install() }
                .onSuccess { toast(activity.getString(if (it.deferred) R.string.cp_install_deferred else R.string.cp_install_done)) }
                .onFailure { toast(activity.errorText(it)) }
        }
    }

    private val drawerActions = object : CyberpunkDrawerActions {
        override fun trackQuest(quest: CyberpunkQuest) {
            val id = quest.id ?: return
            perform(activity.getString(R.string.cp_tracked_quest, quest.title)) { action("track", kind = "quest", id = id) }
        }

        override fun call(contact: CyberpunkContact) =
            perform(activity.getString(R.string.cp_calling, contact.name)) { action("call", contact = contact.id ?: contact.name) }

        override fun use(item: CyberpunkItem) = this@CyberpunkOverlay.use(item)

        override fun selectTab(tab: PhoneTab) {
            phone = phone.copy(tab = tab)
            renderDrawer()
        }

        override fun openThread(thread: CyberpunkThread) = this@CyberpunkOverlay.openThread(thread)

        override fun closeThread() = this@CyberpunkOverlay.closeThread()

        override fun reply(choice: CyberpunkThreadChoice) = this@CyberpunkOverlay.reply(choice)

        override fun prepareLook() = this@CyberpunkOverlay.prepareLook()
    }

    // --- Téléphone : fils et look du jeu

    private fun freshPhone() = PhoneState(lookStatus = phone.lookStatus, look = phone.look)

    private fun closeThread() {
        threadJob?.cancel()
        phone = phone.copy(thread = null)
        renderDrawer()
    }

    private fun openThread(summary: CyberpunkThread) {
        phone = phone.copy(thread = PhoneThread(summary), opened = phone.opened + summary.hash)
        renderDrawer()
        loadThread(summary.hash)
    }

    /** Relit le fil ; ne touche l'écran que s'il est toujours ouvert sur ce fil. */
    private fun loadThread(hash: String, then: suspend (CyberpunkThreadDetail) -> Unit = {}) {
        val client = api ?: return
        threadJob?.cancel()
        threadJob = activity.lifecycleScope.launch {
            runCatchingCancellable { client.thread(hash) }
                .onSuccess {
                    if (api !== client || phone.thread?.summary?.hash != hash) return@onSuccess
                    updateThread { t -> t.copy(detail = it, error = null) }
                    then(it)
                }
                .onFailure {
                    if (api !== client || phone.thread?.summary?.hash != hash) return@onFailure
                    updateThread { t -> t.copy(error = activity.errorText(it), sending = false) }
                }
        }
    }

    private fun updateThread(change: (PhoneThread) -> PhoneThread) {
        val t = phone.thread ?: return
        phone = phone.copy(thread = change(t))
        renderDrawer()
    }

    /** Un appui : les choix s'éteignent jusqu'à ce que le fil ait bougé (jamais deux envois), puis il se relit toutes les 2 s pendant 12 s (ou jusqu'aux nouveaux choix). */
    private fun reply(choice: CyberpunkThreadChoice) {
        val client = api ?: return
        val thread = phone.thread ?: return
        val sent = thread.detail ?: return
        if (thread.sending) return
        val hash = thread.summary.hash
        updateThread { it.copy(sending = true) }
        threadJob?.cancel()
        threadJob = activity.lifecycleScope.launch {
            val sentAt = android.os.SystemClock.elapsedRealtime()
            val sentResult = runCatchingCancellable { client.reply(hash, choice.hash, sent.version) }
            if (api !== client || phone.thread?.summary?.hash != hash) return@launch
            sentResult.onFailure {
                // Choix périmé : message clair, fil rechargé, choix de nouveau proposés tels que le jeu les montre.
                if (CyberpunkApi.isStaleChoice(it)) toast(activity.getString(R.string.cp_phone_stale)) else toast(activity.errorText(it))
                updateThread { t -> t.copy(sending = false) }
                loadThread(hash)
            }
            if (sentResult.isFailure) return@launch
            while (true) {
                val read = runCatchingCancellable { client.thread(hash) }.getOrNull()
                if (api !== client || phone.thread?.summary?.hash != hash) return@launch
                if (read != null) updateThread { t -> t.copy(detail = read) }
                val elapsed = android.os.SystemClock.elapsedRealtime() - sentAt
                val now = read ?: phone.thread?.detail
                if (!PhoneInbox.shouldReload(sent.version, now?.version ?: sent.version, now?.choices?.isNotEmpty() == true, elapsed)) break
                delay(PhoneInbox.RELOAD_MS)
            }
            updateThread { t -> t.copy(sending = false) }
        }
    }

    /** Statut du look du jeu à l'ouverture du tiroir ; prêt : pièces chargées (une fois) ; en préparation : relu toutes les 2 s. */
    private fun loadLook() {
        val client = api ?: return
        lookJob?.cancel()
        lookJob = activity.lifecycleScope.launch {
            while (isActive) {
                // Compagnon plus ancien ou erreur de lecture : pas de look, ni bouton ni message (repli sur les cadres).
                val look = runCatchingCancellable { client.phoneStatus() }.getOrNull() ?: return@launch
                if (api !== client) return@launch
                if (look.status == CyberpunkPhoneStatus.READY && phone.look == null) {
                    val pieces = runCatchingCancellable { PhoneLook.load(activity, client, look.assets) }.getOrNull()
                    if (api !== client) return@launch
                    phone = phone.copy(look = pieces)
                }
                phone = phone.copy(lookStatus = look.status)
                renderDrawer()
                if (look.status != CyberpunkPhoneStatus.LOADING) return@launch
                delay(PHONE_LOOK_POLL_MS)
            }
        }
    }

    private fun prepareLook() {
        val client = api ?: return
        activity.lifecycleScope.launch {
            runCatchingCancellable { client.preparePhone() }
                .onSuccess {
                    if (api !== client) return@onSuccess
                    PhoneLook.clearCache(activity)
                    phone = phone.copy(lookStatus = CyberpunkPhoneStatus.LOADING, look = null)
                    renderDrawer()
                    loadLook()
                }
                .onFailure { toast(activity.errorText(it)) }
        }
    }

    private fun toast(text: String) {
        val t = views?.toast ?: return
        t.text = text
        t.animate().cancel()
        t.alpha = 1f
        t.removeCallbacks(t.tag as? Runnable)
        val hide = Runnable { t.animate().alpha(0f).setDuration(TOAST_FADE_MS).start() }
        t.tag = hide
        t.postDelayed(hide, TOAST_MS)
    }

    // --- Affichage

    private fun showDrawer(next: CyberpunkPage?) {
        val v = views ?: return
        page = if (next == page) null else next
        threadJob?.cancel()
        lookJob?.cancel()
        phone = freshPhone()
        drawn = null
        v.railButtons.forEach { (p, b) -> b.setSelectedPage(p == page) }
        page?.let {
            blockOf(it)?.let { b -> failedVersions.remove(b) }
            when (it) {
                CyberpunkPage.JOURNAL -> load(Block.QUESTS, versions[Block.QUESTS] ?: snapshot?.state?.blocks?.quests ?: 0)
                CyberpunkPage.PHONE -> {
                    load(Block.CONTACTS, versions[Block.CONTACTS] ?: snapshot?.state?.blocks?.contacts ?: 0)
                    loadLook()
                }
                CyberpunkPage.ITEMS -> load(Block.INVENTORY, versions[Block.INVENTORY] ?: snapshot?.state?.blocks?.inv ?: 0)
                else -> Unit
            }
        }
        renderDrawer()
        val open = page != null
        v.drawer.animate().translationX(if (open) 0f else -v.drawerHidden).setDuration(DRAWER_MS).start()
        val shift = if (open) v.kit.dp(HUD_SHIFT_DP).toFloat() else 0f
        v.hud.animate().translationX(shift).setDuration(DRAWER_MS).start()
        v.callHolder.animate().translationX(shift).setDuration(DRAWER_MS).start()
    }

    private fun blockOf(p: CyberpunkPage): Block? = when (p) {
        CyberpunkPage.JOURNAL -> Block.QUESTS
        CyberpunkPage.PHONE -> Block.CONTACTS
        CyberpunkPage.ITEMS -> Block.INVENTORY
        else -> null
    }

    private fun renderDrawer() {
        val v = views ?: return
        val p = page ?: return
        val st = liveSnapshot?.state
        val data: Any? = when (p) {
            CyberpunkPage.JOURNAL -> quests
            CyberpunkPage.PHONE -> contacts
            CyberpunkPage.ITEMS -> inventory
            CyberpunkPage.CYBER -> st?.cyberware
            CyberpunkPage.PERSO -> st?.player
        }
        // Lecture échouée et rien à montrer : la phrase d'erreur à la place de « Chargement ».
        val failure = if (data == null) blockOf(p)?.let { blockErrors[it] } else null
        // Rien n'est refait (ni le défilement perdu) tant que la donnée n'a pas changé.
        val key = Triple(p, data ?: failure, phone)
        if (drawn == key) return
        drawn = key
        val content = when {
            failure != null -> v.drawers.failed(p.labelRes, failure)
            else -> pageContent(v, p, st)
        }
        v.drawerBody.removeAllViews()
        v.drawerBody.addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
        // Un fil ouvert se lit par le bas : les derniers messages et les choix.
        if (p == CyberpunkPage.PHONE && phone.thread?.detail != null) (v.drawerBody.parent as? ScrollView)?.let { sv -> sv.post { sv.fullScroll(View.FOCUS_DOWN) } }
    }

    private fun pageContent(v: Views, p: CyberpunkPage, st: CyberpunkState?): View =
        when (p) {
            CyberpunkPage.JOURNAL -> v.drawers.journal(quests)
            CyberpunkPage.PHONE -> v.drawers.phone(contacts, phone)
            CyberpunkPage.ITEMS -> v.drawers.items(inventory)
            CyberpunkPage.CYBER -> v.drawers.cyber(st)
            CyberpunkPage.PERSO -> v.drawers.perso(st?.player)
        }

    private fun render() {
        val v = views ?: return
        mode = CyberpunkLink.decide(liveSnapshot != null, misses)
        val live = mode.shown
        val snap = snapshot
        val st = liveSnapshot?.state
        v.liveGroup.forEach { it.visibility = if (live) View.VISIBLE else View.GONE }
        v.message.visibility = if (live) View.GONE else View.VISIBLE
        v.link.visibility = if (mode == CyberpunkLink.Mode.DEGRADED) View.VISIBLE else View.GONE
        mapView?.interactive = live
        if (!live) {
            // Le tiroir se ferme quand la partie est vraiment finie, jamais pour une erreur réseau (il revient avec la liaison).
            if (page != null && error == null) showDrawer(null)
            renderMessage(v, snap)
            return
        }
        v.link.text = error ?: activity.getString(R.string.cp_link_lost)
        st ?: return
        renderHud(v, st)
        renderCall(v, st)
        renderQuest(v, st)
        st.tracked?.takeIf { it.kind == "quest" && it.id != null }?.let { lastQuestId = it.id }
        mapView?.update(st.pos, st.tracked, lastQuestId ?: quests?.firstOrNull { it.tracked }?.id)
        val unread = contacts?.unread ?: 0
        v.unread.visibility = if (unread > 0) View.VISIBLE else View.GONE
        v.unread.text = unread.toString()
        v.unread.contentDescription = activity.resources.getQuantityString(R.plurals.cp_unread_count, unread, unread)
        renderDrawer()
    }

    private fun renderMessage(v: Views, snap: CyberpunkSnapshot?) {
        val install = snap?.install
        val needsMod = snap != null && snap.status != CyberpunkStatus.UNAVAILABLE && install?.gameFound == true && !install.modInstalled
        val res = when {
            error != null -> null
            snap == null -> R.string.cp_loading
            snap.status == CyberpunkStatus.UNAVAILABLE -> R.string.cp_state_unavailable
            install?.gameFound != true -> R.string.cp_state_no_game
            needsMod -> R.string.cp_state_no_mod
            snap.status == CyberpunkStatus.OFF -> R.string.cp_state_off
            else -> R.string.cp_state_menu
        }
        v.messageText.text = error ?: activity.getString(res ?: R.string.cp_loading)
        v.installButton.visibility = if (needsMod && error == null) View.VISIBLE else View.GONE
    }

    private fun renderHud(v: Views, st: CyberpunkState) {
        val p = st.player
        v.hud.visibility = if (p == null) View.GONE else View.VISIBLE
        p ?: return
        v.level.text = activity.getString(R.string.cp_level, p.level)
        v.health.percent = p.health
        v.money.text = activity.getString(R.string.cp_money, NumberFormat.getIntegerInstance(Locale.FRANCE).format(p.money))
    }

    private fun renderCall(v: Views, st: CyberpunkState) {
        val c = st.call
        v.callHolder.visibility = if (c == null) View.GONE else View.VISIBLE
        c ?: return
        val who = c.contact ?: activity.getString(R.string.cp_call_unknown)
        val incoming = c.phase == CyberpunkCallPhase.INCOMING
        v.callText.text = activity.getString(if (incoming) R.string.cp_call_incoming else R.string.cp_call_active, who)
        val label = activity.getString(if (incoming) R.string.cp_answer else R.string.cp_hangup)
        v.callButton.text = label
        v.callButton.contentDescription = label
        v.callButton.setOnClickListener {
            if (incoming) perform(activity.getString(R.string.cp_answered)) { action("answer") }
            else perform(activity.getString(R.string.cp_hung_up)) { action("hangup") }
        }
    }

    private fun renderQuest(v: Views, st: CyberpunkState) {
        val t = st.tracked
        v.questTitle.text = when {
            t == null -> activity.getString(R.string.cp_no_tracked)
            t.quest != null -> t.quest
            else -> activity.getString(R.string.cp_pin_tracked)
        }
        v.questDesc.text = t?.desc.orEmpty()
        v.questDesc.visibility = if (t?.desc.isNullOrEmpty()) View.GONE else View.VISIBLE
        v.pins.text = (pins?.size ?: st.pins.size).let { activity.resources.getQuantityString(R.plurals.cp_pins, it, it) }
    }

    private fun View.setSelectedPage(selected: Boolean) {
        val kit = views?.kit ?: return
        isSelected = selected
        background = kit.panel(if (selected) R.color.cp_rouge else R.color.cp_panneau, cutDp = 8, both = false)
        val tint = kit.color(if (selected) R.color.cp_sur_rouge else R.color.cp_gris)
        val inner = (this as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return
        (inner.getChildAt(0) as? ImageView)?.setColorFilter(tint)
        (inner.getChildAt(1) as? TextView)?.setTextColor(tint)
    }

    // --- Construction

    private class Views(
        val kit: CyberpunkKit,
        val root: FrameLayout,
        val mapSlot: FrameLayout,
        val message: LinearLayout,
        val messageText: TextView,
        val installButton: TextView,
        val railButtons: Map<CyberpunkPage, View>,
        val rail: View,
        val unread: TextView,
        val drawer: FrameLayout,
        val drawerBody: LinearLayout,
        val drawerHidden: Float,
        val drawers: CyberpunkDrawers,
        val hud: FrameLayout,
        val level: TextView,
        val health: CyberpunkBar,
        val money: TextView,
        val callHolder: FrameLayout,
        val callText: TextView,
        val callButton: TextView,
        val questCard: View,
        val questTitle: TextView,
        val questDesc: TextView,
        val pins: TextView,
        val actions: View,
        val toast: TextView,
        val link: TextView,
    ) {
        /** Ce qui n'a de sens qu'en partie : tout sauf le message d'état, la carte et Fermer. */
        val liveGroup get() = listOf(rail, drawer, hud, callHolder, questCard, actions)
    }

    private fun build(kit: CyberpunkKit): Views {
        val ctx = kit.context
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(kit.color(R.color.cp_fond))
            isClickable = true
            isFocusable = true
        }
        val mapSlot = FrameLayout(ctx).apply { addView(CyberpunkGridView(ctx, kit.color(R.color.cp_grille), kit.dp(GRID_DP)), FrameLayout.LayoutParams(MATCH, MATCH)) }
        root.addView(mapSlot, FrameLayout.LayoutParams(MATCH, MATCH))

        val rail = buildRail(kit)
        val unread = rail.second
        val drawerBody = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val drawer = FrameLayout(ctx).apply {
            background = kit.panel(R.color.cp_tiroir, R.color.cp_rouge, 3)
            addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(drawerBody, ViewGroup.LayoutParams(MATCH, WRAP)) }, FrameLayout.LayoutParams(MATCH, MATCH))
            setPadding(kit.dp(22), kit.dp(22), kit.dp(22), kit.dp(22))
        }
        val hidden = kit.dp(DRAWER_DP + DRAWER_LEFT_DP).toFloat()
        drawer.translationX = -hidden

        val level = kit.text("", 24f, R.color.cp_rouge, kit.bold)
        val health = CyberpunkBar(ctx, kit.color(R.color.cp_rouge), kit.color(R.color.cp_barre_vide))
        val money = kit.text("", 22f, R.color.cp_jaune, kit.mono, spacing = 0.04f)
        val hudPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = kit.panel()
            setPadding(kit.dp(22), kit.dp(12), kit.dp(22), kit.dp(12))
            addView(kit.text(ctx.getString(R.string.cp_v), 32f, R.color.cp_jaune, kit.bold))
            addView(level, kit.lp(WRAP, WRAP).apply { marginStart = kit.dp(22) })
            addView(health, kit.lp(kit.dp(180), kit.dp(8)).apply { marginStart = kit.dp(22) })
            addView(money, kit.lp(WRAP, WRAP).apply { marginStart = kit.dp(22) })
        }
        val hud = FrameLayout(ctx).apply { addView(hudPanel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL)) }

        val callText = kit.text("", 18f, R.color.cp_sur_cyan, kit.bold)
        val callButton = kit.button("", "", R.color.cp_sur_cyan, R.color.cp_cyan).apply { minHeight = kit.dp(48) }
        val callHolder = FrameLayout(ctx).apply {
            val banner = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = kit.panel(R.color.cp_cyan)
                setPadding(kit.dp(20), kit.dp(10), kit.dp(12), kit.dp(10))
                addView(callText)
                addView(callButton, kit.lp(WRAP, kit.dp(48)).apply { marginStart = kit.dp(18) })
            }
            addView(banner, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL))
        }

        val heal = kit.button(ctx.getString(R.string.cp_heal), ctx.getString(R.string.cp_heal), minWidthDp = ACTION_WIDTH_DP).apply { setOnClickListener { heal() } }
        val car = kit.button(ctx.getString(R.string.cp_car), ctx.getString(R.string.cp_car), R.color.cp_panneau, R.color.cp_rouge, ACTION_WIDTH_DP)
            .apply { setOnClickListener { perform(ctx.getString(R.string.cp_car_called)) { action("summon") } } }
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(heal, kit.lp(WRAP, kit.dp(52)))
            addView(car, kit.lp(WRAP, kit.dp(52)).apply { topMargin = kit.dp(10) })
        }

        val questTitle = kit.text("", 24f, R.color.cp_jaune, kit.bold)
        val questDesc = kit.text("", 17f, R.color.cp_cyan)
        val pins = kit.label("", R.color.cp_gris)
        val questCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.panel(R.color.cp_panneau, R.color.cp_jaune, 4)
            setPadding(kit.dp(24), kit.dp(18), kit.dp(20), kit.dp(18))
            addView(kit.label(ctx.getString(R.string.cp_followed)))
            addView(questTitle, kit.lp(MATCH, WRAP).apply { topMargin = kit.dp(4) })
            addView(questDesc, kit.lp(MATCH, WRAP).apply { topMargin = kit.dp(4) })
            addView(pins, kit.lp(MATCH, WRAP).apply { topMargin = kit.dp(10) })
        }

        val messageText = kit.text("", 22f, R.color.cp_texte, kit.medium).apply { gravity = Gravity.CENTER }
        val installButton = kit.button(ctx.getString(R.string.cp_install), ctx.getString(R.string.cp_install)).apply { setOnClickListener { install() } }
        val message = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = kit.panel()
            setPadding(kit.dp(36), kit.dp(28), kit.dp(36), kit.dp(28))
            addView(messageText, kit.lp(WRAP, WRAP))
            addView(installButton, kit.lp(WRAP, kit.dp(52)).apply { topMargin = kit.dp(22) })
        }

        val close = ImageButton(ctx).apply {
            background = kit.panel(cutDp = 8, both = false)
            setImageResource(R.drawable.ic_close)
            setColorFilter(kit.color(R.color.cp_texte))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = ctx.getString(R.string.close)
            setOnClickListener { close() }
            with(kit) { pressable() }
        }
        val toast = kit.text("", 16f, R.color.cp_sur_jaune, kit.bold, spacing = 0.1f, upper = true).apply {
            background = kit.panel(R.color.cp_jaune, cutDp = 8, both = false)
            setPadding(kit.dp(22), kit.dp(12), kit.dp(22), kit.dp(12))
            alpha = 0f
        }

        val link = kit.text("", 15f, R.color.cp_sur_jaune, kit.medium).apply {
            background = kit.panel(R.color.cp_jaune, cutDp = 8, both = false)
            setPadding(kit.dp(16), kit.dp(6), kit.dp(16), kit.dp(6))
            visibility = View.GONE
        }
        val m = kit.dp(MARGIN_DP)
        root.addView(hud, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply { topMargin = m })
        root.addView(callHolder, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply { topMargin = kit.dp(CALL_TOP_DP) })
        root.addView(actions, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { topMargin = kit.dp(ACTIONS_TOP_DP); marginEnd = m })
        root.addView(questCard, FrameLayout.LayoutParams(kit.dp(QUEST_WIDTH_DP), WRAP, Gravity.BOTTOM or Gravity.END).apply { bottomMargin = m; marginEnd = m })
        root.addView(drawer, FrameLayout.LayoutParams(kit.dp(DRAWER_DP), MATCH).apply { marginStart = kit.dp(DRAWER_LEFT_DP); topMargin = m; bottomMargin = m })
        root.addView(rail.first, FrameLayout.LayoutParams(kit.dp(RAIL_DP), WRAP).apply { marginStart = m; topMargin = m })
        root.addView(message, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        root.addView(close, FrameLayout.LayoutParams(kit.dp(48), kit.dp(48), Gravity.TOP or Gravity.END).apply { topMargin = kit.dp(CLOSE_MARGIN_DP); marginEnd = kit.dp(CLOSE_MARGIN_DP) })
        root.addView(link, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = kit.dp(LINK_BOTTOM_DP) })
        root.addView(toast, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = kit.dp(28) })

        val drawers = CyberpunkDrawers(kit, drawerActions)
        val buttons = rail.third
        buttons.forEach { (p, b) -> b.setOnClickListener { showDrawer(p) } }
        return Views(
            kit, root, mapSlot, message, messageText, installButton, buttons, rail.first, unread, drawer, drawerBody, hidden, drawers,
            hud, level, health, money, callHolder, callText, callButton, questCard, questTitle, questDesc, pins, actions, toast, link,
        )
    }

    /** Rail : colonne de cinq boutons carrés de 76 dp ; rend la colonne, la pastille « non lus » et les boutons. */
    private fun buildRail(kit: CyberpunkKit): Triple<LinearLayout, TextView, Map<CyberpunkPage, View>> {
        val ctx = kit.context
        val unread = kit.text("", 12f, R.color.cp_sur_cyan, kit.bold).apply {
            background = kit.panel(R.color.cp_cyan, cutDp = 0, both = false)
            gravity = Gravity.CENTER
            minWidth = kit.dp(UNREAD_DP)
            minHeight = kit.dp(UNREAD_DP)
            setPadding(kit.dp(4), 0, kit.dp(4), 0)
            visibility = View.GONE
        }
        val buttons = CyberpunkPage.entries.associateWith { p ->
            val label = ctx.getString(p.labelRes)
            val b = FrameLayout(ctx).apply {
                background = kit.panel(cutDp = 8, both = false)
                contentDescription = label
                isClickable = true
                isFocusable = true
                with(kit) { pressable() }
                val inner = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(ImageView(ctx).apply { setImageResource(p.iconRes); setColorFilter(kit.color(R.color.cp_gris)) }, kit.lp(kit.dp(30), kit.dp(30)))
                    // 13 sp, en minuscules : « TÉLÉPHONE » en capitales ne tient pas dans 76 dp.
                    addView(kit.text(label, 13f, R.color.cp_gris, kit.medium, spacing = 0.02f), kit.lp(WRAP, WRAP).apply { topMargin = kit.dp(4) })
                }
                addView(inner, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            b
        }
        buttons[CyberpunkPage.PHONE]?.let {
            (it as FrameLayout).addView(
                unread,
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { topMargin = kit.dp(6); marginEnd = kit.dp(6) },
            )
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            buttons.values.forEach { addView(it, kit.lp(kit.dp(RAIL_DP), kit.dp(RAIL_DP)).apply { bottomMargin = kit.dp(8) }) }
        }
        return Triple(column, unread, buttons)
    }

    private companion object {
        const val POLL_MS = 1_000L
        const val PHONE_LOOK_POLL_MS = 2_000L
        const val DRAWER_MS = 280L
        const val TOAST_MS = 1_600L
        const val TOAST_FADE_MS = 250L
        const val MARGIN_DP = 16
        const val RAIL_DP = 76
        const val DRAWER_DP = 420
        const val DRAWER_LEFT_DP = 104
        /** Le HUD et l'appel se recentrent sur la place laissée par le tiroir (centre à 880 sur 1280). */
        const val HUD_SHIFT_DP = 240
        const val CALL_TOP_DP = 88

        /** Fermer finit à 60 dp du haut : 36 dp d'écart avec Soin (24 au minimum). */
        const val ACTIONS_TOP_DP = 96
        const val LINK_BOTTOM_DP = 76
        const val ACTION_WIDTH_DP = 150
        const val QUEST_WIDTH_DP = 360
        /** Bouton Fermer centré dans le coin libre de 72 dp. */
        const val CLOSE_MARGIN_DP = 12
        const val GRID_DP = 64
        const val UNREAD_DP = 20
    }
}

/** Fond de carte provisoire : grille sombre. La carte (tuiles) le remplace par [CyberpunkOverlay.setMapView]. */
internal class CyberpunkGridView(context: android.content.Context, color: Int, private val step: Int) : View(context) {
    private val paint = Paint().apply {
        this.color = color
        strokeWidth = 1f
    }

    override fun onDraw(canvas: Canvas) {
        var x = 0
        while (x < width) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), paint)
            x += step
        }
        var y = 0
        while (y < height) {
            canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), paint)
            y += step
        }
    }
}
