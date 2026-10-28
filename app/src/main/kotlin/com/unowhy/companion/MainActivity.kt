package com.unowhy.companion

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.agents.AgentAlerts
import com.unowhy.companion.audio.Speaker
import com.unowhy.companion.device.Screen
import com.unowhy.companion.device.Tablet
import com.unowhy.companion.focus.FocusDnd
import com.unowhy.companion.focus.FocusTimer
import com.unowhy.companion.focus.NoisePlayer
import com.unowhy.companion.kiosk.AutoUpdater
import com.unowhy.companion.kiosk.Kiosk
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.DocEntry
import com.unowhy.companion.net.Link
import com.unowhy.companion.remote.RemoteLink
import com.unowhy.companion.ui.AgentAskOverlay
import com.unowhy.companion.ui.AgentLight
import com.unowhy.companion.ui.AppTheme
import com.unowhy.companion.ui.Banner
import com.unowhy.companion.ui.CallOverlay
import com.unowhy.companion.ui.CameraFragment
import com.unowhy.companion.ui.ComputerAdder
import com.unowhy.companion.ui.DeckFragment
import com.unowhy.companion.ui.DocumentPanel
import com.unowhy.companion.ui.FocusFragment
import com.unowhy.companion.ui.FromComputerFragment
import com.unowhy.companion.ui.Grain
import com.unowhy.companion.ui.HomeFragment
import com.unowhy.companion.ui.IconCache
import com.unowhy.companion.ui.KeyboardControl
import com.unowhy.companion.ui.MenuPanel
import com.unowhy.companion.ui.Navigation
import com.unowhy.companion.ui.Rail
import com.unowhy.companion.ui.SettingsFragment
import com.unowhy.companion.ui.TabletAlerts
import com.unowhy.companion.ui.TabletStatusView
import com.unowhy.companion.ui.WelcomeView
import com.unowhy.companion.ui.dp
import com.unowhy.companion.ui.home.Down
import com.unowhy.companion.ui.home.LinkWatch
import com.unowhy.companion.ui.showImmersive
import com.unowhy.companion.ui.themeBool
import com.unowhy.companion.voice.VoiceControl
import kotlinx.coroutines.launch

/** Les écrans qui veulent les boutons de volume de la tablette (ex. le deck pilote le son de l'ordinateur). */
interface VolumeKeyHandler {
    fun onVolumeKey(up: Boolean): Boolean
}

/** Unique Activity : plein écran, écran toujours allumé, un Fragment par mode + panneau document. */
class MainActivity : AppCompatActivity() {

    private val rehide = Runnable { hideSystemBars() }
    private var current = Mode.HOME
    /** Barre latérale, ou menu caché (thèmes Cadran). */
    private lateinit var nav: Navigation
    private var menu: MenuPanel? = null
    private lateinit var documentPanel: DocumentPanel
    private lateinit var banner: Banner
    private lateinit var alerts: TabletAlerts
    private lateinit var welcome: WelcomeView
    private lateinit var noComputer: View
    private val keyboard = KeyboardControl(this)
    private val focusDnd = FocusDnd(this)
    private val remote = RemoteLink(this, ::showLink) { computer -> onComputerChanged(computer, automatic = true) }
    private val computerDialogs by lazy { ComputerAdder(this, lifecycleScope) { onComputerChanged(it) } }
    /** Dernier état de la liaison, repris par les Réglages et le sélecteur d'ordinateur. */
    var link = Link(null, Link.State.NONE)
        private set
    private val linkWatch = LinkWatch()
    private lateinit var voice: VoiceControl
    lateinit var callOverlay: CallOverlay
        private set
    private lateinit var agentAlerts: AgentAlerts
    private lateinit var agentAsk: AgentAskOverlay
    /** Micro refusé hors kiosque (en kiosque, il est accordé d'office) : la commande repart une fois accepté. */
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start() else showBanner(getString(R.string.voice_no_mic), 6)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        Grain.applyTo(findViewById(android.R.id.content))
        Speaker.init(this)
        AutoUpdater.start(this)

        val cadran = themeBool(this, R.attr.c_cadran)
        if (cadran) {
            findViewById<View>(R.id.rail).visibility = View.GONE
            // Plus de barre à contourner : le bandeau garde une marge ordinaire.
            findViewById<View>(R.id.remote_banner).apply { layoutParams = (layoutParams as android.widget.FrameLayout.LayoutParams).also { it.marginStart = dp(24) } }
            nav = MenuPanel(this, ::show).also { menu = it }
        } else {
            nav = Rail(this, ::show)
        }
        voice = VoiceControl(this, nav.talk, nav.talk.findViewById(R.id.rail_icon), onPressed = { menu?.close() })
        // Lumière des agents Claude sous la carte d'appel : un appel prime sur tout.
        val agentLight = AgentLight(this)
        agentAsk = AgentAskOverlay(this)
        agentAlerts = AgentAlerts(agentLight, openAsk = ::openAgentAsk, openList = ::showAgents)
        agentLight.onTouched = agentAlerts::onTouched
        findViewById<android.view.ViewGroup>(android.R.id.content).addView(agentLight, android.view.ViewGroup.LayoutParams(-1, -1))
        findViewById<android.view.ViewGroup>(android.R.id.content).addView(agentAsk, android.view.ViewGroup.LayoutParams(-1, -1))
        callOverlay = CallOverlay(this)
        findViewById<android.view.ViewGroup>(android.R.id.content).addView(callOverlay, android.view.ViewGroup.LayoutParams(-1, -1))

        keyboard.setUp()

        @Suppress("DEPRECATION") // seul moyen fiable d'être prévenu sur API 24
        window.decorView.setOnSystemUiVisibilityChangeListener { flags ->
            if (flags and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                window.decorView.removeCallbacks(rehide)
                window.decorView.postDelayed(rehide, 2_000)
            }
        }

        documentPanel = findViewById(R.id.document_panel)
        documentPanel.attach(lifecycleScope)
        documentPanel.onExpandedChanged = { expanded ->
            findViewById<View>(R.id.mode_area).visibility = if (expanded) View.GONE else View.VISIBLE
        }
        nav.status.setOnClickListener {
            menu?.close()
            showComputerPicker()
        }
        banner = Banner(findViewById(R.id.remote_banner))
        alerts = TabletAlerts(this, banner)
        findViewById<TabletStatusView>(R.id.tablet_status).apply {
            setOnClickListener {
                menu?.close()
                show(Mode.SETTINGS)
            }
            onMinute = {
                Screen.apply(this@MainActivity)
                alerts.remindWater()
            }
            onBattery = ::onBattery
        }
        noComputer = findViewById(R.id.no_computer)
        noComputer.findViewById<View>(R.id.connect_button).setOnClickListener { showWelcome() }
        welcome = findViewById(R.id.welcome)
        welcome.attach(this) { computer ->
            welcome.hide()
            onComputerChanged(computer)
            show(Mode.HOME)
            showBanner(getString(R.string.welcome_done, computer.name), 6)
        }

        // Retour ne quitte jamais l'appli : il ferme le document ouvert, sinon ramène à l'accueil.
        onBackPressedDispatcher.addCallback(this) {
            when {
                welcome.visibility == View.VISIBLE -> Unit
                agentAsk.isOpen -> agentAsk.close()
                menu?.isOpen == true -> menu?.close()
                documentPanel.visibility == View.VISIBLE -> documentPanel.close()
                current != Mode.HOME -> show(Mode.HOME)
            }
        }

        val restored = savedInstanceState?.getString(STATE_MODE)?.let(Mode::of)
        show(restored ?: Mode.HOME)
        // Premier lancement (ou plus aucun ordinateur) : écran de bienvenue.
        if (savedInstanceState == null && Computers.all(this).isEmpty()) welcome.show()
        showLink(Link(Computers.active(this), Link.State.OFFLINE))
        remote.restart()
    }

    private fun showLink(link: Link) {
        if (link.state == Link.State.ONLINE && this.link.state != Link.State.ONLINE) resyncAgents()
        this.link = link
        linkWatch.onLink(link.computer?.id, link.state, System.currentTimeMillis())
        nav.showLink(link)
        settings()?.onLinkChanged()
        (supportFragmentManager.findFragmentByTag(Mode.HOME.tag) as? HomeFragment)?.onLinkChanged()
    }

    /** L'ordinateur actif ne répond plus (dit par l'Accueil Cadran), ou null. */
    fun unreachable(): Down? = linkWatch.down(link.computer?.name, System.currentTimeMillis())

    /** Ordinateur actif renommé : nom à jour dans la barre latérale. */
    fun refreshLink() = showLink(link.copy(computer = Computers.active(this)))

    /**
     * L'ordinateur piloté a changé (choisi dans les Réglages, ou bascule automatique) : chaque écran est
     * recréé pour relire deck, agenda, tâches et documents de cet ordinateur. Focus et sons continuent.
     */
    fun onComputerChanged(computer: Computer?, automatic: Boolean = false) {
        IconCache.clear()
        documentPanel.close()
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        fm.fragments.forEach { tx.remove(it) }
        tx.commitNow()
        show(current)
        showLink(Link(computer, Link.State.OFFLINE))
        remote.restart()
        if (automatic && computer != null) showBanner(getString(R.string.computer_switched, computer.name), 6)
    }

    /** Toucher la pastille de connexion : changer d'ordinateur (ou renommer l'actif) sans passer par les Réglages. */
    private fun showComputerPicker() {
        val computers = Computers.all(this)
        if (computers.isEmpty()) return showWelcome()
        val current = Computers.active(this)
        val active = current?.id
        val labels = computers.map { (if (it.id == active) "✓  " else "     ") + it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.computers_title)
            .setItems(labels) { _, which ->
                val chosen = computers[which]
                if (chosen.id != active) {
                    Computers.setActive(this, chosen.id)
                    onComputerChanged(Computers.active(this))
                }
            }
            .setNeutralButton(R.string.computers_manage) { _, _ -> show(Mode.SETTINGS) }
            .apply {
                if (current != null) setPositiveButton(R.string.computer_rename) { _, _ ->
                    computerDialogs.rename(current) {
                        refreshLink()
                        settings()?.onLinkChanged()
                    }
                }
            }
            .showImmersive()
    }

    private val focusListener: (FocusTimer.State) -> Unit = { state ->
        nav.showFocus(state)
        followFocusDnd(state)
    }

    /** Ne pas déranger sur l'ordinateur pendant la concentration ; aussi rappelé quand le réglage change. */
    fun followFocusDnd(state: FocusTimer.State) = focusDnd.follow(state)

    override fun onStart() {
        super.onStart()
        FocusTimer.observe(focusListener)
    }

    override fun onStop() {
        FocusTimer.unobserve(focusListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        Kiosk.enter(this)
        Screen.apply(this)
    }

    private fun onBattery(state: Tablet.Battery) {
        alerts.onBattery(state)
        settings()?.onBattery(state)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MODE, current.tag)
    }

    override fun onDestroy() {
        documentPanel.close()
        if (isFinishing) NoisePlayer.stop()
        voice.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        hideSystemBars()
        keyboard.closeOrphan()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) Screen.touched(this)
        menu?.touched()
        keyboard.onTouch(event)
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val up = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            else -> return super.dispatchKeyEvent(event)
        }
        val handler = supportFragmentManager.findFragmentByTag(current.tag) as? VolumeKeyHandler
            ?: return super.dispatchKeyEvent(event)
        // On consomme aussi le relâchement pour que la tablette ne change pas son propre volume.
        return if (event.action == KeyEvent.ACTION_DOWN) handler.onVolumeKey(up) else true
    }

    /** Nombre de tâches non faites, en pastille sur l'icône Accueil (mis à jour par la carte Tâches). */
    fun setPendingTasks(count: Int) = nav.setPendingTasks(count)

    /** Ouvre un document de l'ordinateur dans le panneau latéral, à côté du mode courant. */
    /**
     * Dans Depuis l'ordi : en grand, sans vue partagée (la liste des fichiers à côté ne sert à rien). Envoyé par l'ordinateur
     * pendant un autre mode (Accueil…) : à côté, pour garder les deux sous les yeux.
     */
    fun openDocument(entry: DocEntry) {
        documentPanel.open(entry, expand = current == Mode.COMPUTER, splittable = current != Mode.COMPUTER)
    }

    /** Affiche dans le panneau latéral la page ouverte dans le navigateur de l'ordinateur. */
    fun openComputerPage() {
        documentPanel.openComputerPage(expand = current == Mode.COMPUTER, splittable = current != Mode.COMPUTER)
    }

    // --- Utilisé par RemoteChannel (ordres de l'ordinateur)

    val modeName: String
        get() = current.key

    val documentPath: String?
        get() = documentPanel.currentPath

    fun showMode(name: String) {
        // Nom inconnu (« tasks », ancien « chat ») : l'Accueil.
        show(Mode.of(name) ?: Mode.HOME)
    }

    /** Deck sur sa page Réunion, pour couper le micro ou raccrocher sans chercher. */
    fun showMeetingControls() {
        show(Mode.DECK)
        // Le deck vient peut-être d'être créé : sa vue doit exister avant de choisir la page.
        supportFragmentManager.executePendingTransactions()
        (supportFragmentManager.findFragmentByTag(Mode.DECK.tag) as? DeckFragment)?.openMeetingPage()
    }

    /** Appel entrant sur l'ordinateur (ordre `call`, voir CallOverlay). */
    fun onCall(state: String, app: String, caller: String?, video: Boolean) = callOverlay.onCall(state, app, caller, video)

    /** Un agent Claude attend, a fini ou a atteint sa limite (ordre `agent`, voir AgentAlerts). */
    fun onAgent(kind: String, askId: String?, waiting: Int) = agentAlerts.onOrder(kind, askId, waiting)

    /** Carte de la demande de Claude (permission, questions) à laquelle la tablette peut répondre. */
    fun openAgentAsk(id: String) = agentAsk.open(id)

    val agentAskOpen get() = agentAsk.isOpen

    /** Demande d'agent en attente depuis avant la connexion : lumière rallumée (voir AgentAlerts.resync). */
    private fun resyncAgents() {
        lifecycleScope.launch {
            val ask = runCatching { BackendClient(this@MainActivity).agents.state() }.getOrNull()?.sessions?.firstNotNullOfOrNull { it.askId }
            agentAlerts.resync(ask)
        }
    }

    /** Commande vocale lancée d'ailleurs que le bouton Parler (carte d'une demande d'agent). */
    fun startVoice() = voice.start()

    /**
     * Commande vocale pour les agents Claude ; rend la phrase à afficher. Elle n'agit que sur la carte ouverte (on voit ce
     * qu'on autorise) : sinon, elle ouvre la demande en attente. « continue » relance la dernière session finie qui le permet.
     */
    suspend fun agentVoice(action: String, choice: Int?): String {
        if (action == "continue") {
            val session = runCatching { BackendClient(this).agents.state() }.getOrNull()?.sessions
                ?.firstOrNull { it.canSend && it.state == com.unowhy.companion.net.AgentState.DONE }
                ?: return getString(R.string.agent_voice_no_session)
            BackendClient(this).agents.send(session.id, "continue")
            return getString(R.string.agent_sent, session.name)
        }
        if (agentAsk.isOpen) return agentAsk.voice(action, choice)
        val id = agentAlerts.pendingAsk ?: return getString(R.string.agent_voice_none)
        agentAsk.open(id)
        return getString(R.string.agent_voice_opened)
    }

    /** Deck sur l'onglet Agents. */
    fun showAgents() {
        show(Mode.DECK)
        supportFragmentManager.executePendingTransactions()
        (supportFragmentManager.findFragmentByTag(Mode.DECK.tag) as? DeckFragment)?.openAgentsTab()
    }

    /** Deck sur la page de l'appli (Discord) : micro, sourdine, Quitter l'appel. */
    fun showAppControls(app: String) {
        show(Mode.DECK)
        supportFragmentManager.executePendingTransactions()
        (supportFragmentManager.findFragmentByTag(Mode.DECK.tag) as? DeckFragment)?.openAppPage(app)
    }

    fun closeDocument() {
        documentPanel.close()
    }

    fun requestMicPermission() = micPermission.launch(android.Manifest.permission.RECORD_AUDIO)

    /** Écran de bienvenue (WiFi, installation, connexion), depuis un mode sans ordinateur. */
    fun showWelcome() = welcome.show()

    /** Bandeau en haut de l'écran, par-dessus le mode courant ; un toucher le ferme. */
    fun showBanner(text: String, seconds: Int) = banner.show(text, seconds)

    private fun settings() = supportFragmentManager.findFragmentByTag(Mode.SETTINGS.tag) as? SettingsFragment

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun show(mode: Mode) {
        // Le champ d'un écran masqué garderait sinon le clavier (et la saisie) par-dessus le nouvel écran.
        keyboard.hide()
        // Document ouvert : en grand dans Depuis l'ordi ; ailleurs à côté, pour laisser voir le mode choisi.
        if (::documentPanel.isInitialized && documentPanel.visibility == View.VISIBLE) {
            documentPanel.setExpanded(mode == Mode.COMPUTER, splittable = mode != Mode.COMPUTER)
        }
        val changed = current != mode
        current = mode
        val fm = supportFragmentManager
        // Un écran ajouté juste avant (ex. recréé par onComputerChanged) doit exister pour être masqué.
        fm.executePendingTransactions()
        val tx = fm.beginTransaction()
        if (changed) {
            // Cadran : fondu enchaîné (sortie, puis entrée avec échelle) ; sinon fondu et légère montée.
            if (menu != null) tx.setCustomAnimations(R.animator.cadran_in, R.animator.cadran_out) else tx.setCustomAnimations(R.animator.mode_in, 0)
        }
        fm.fragments.forEach { tx.hide(it) }
        // Sans ordinateur, un mode qui en a besoin n'est pas créé (caméra, requêtes) : renderNoComputer le remplace.
        val needsComputer = mode.connect != null && Computers.all(this).isEmpty()
        val existing = fm.findFragmentByTag(mode.tag)
        if (existing != null) tx.show(existing) else if (!needsComputer) tx.add(R.id.mode_container, create(mode), mode.tag)
        tx.commit()

        nav.select(mode, changed)
        renderNoComputer()
    }

    /** Modes qui ont besoin de l'ordinateur : « Aucun ordinateur connecté » par-dessus, tant qu'il n'y en a pas. */
    private fun renderNoComputer() {
        val text = current.connect?.takeIf { Computers.all(this).isEmpty() }
        noComputer.visibility = if (text != null) View.VISIBLE else View.GONE
        if (text != null) noComputer.findViewById<TextView>(R.id.connect_text).setText(text)
    }

    private fun create(mode: Mode): Fragment = when (mode) {
        Mode.HOME -> HomeFragment()
        Mode.DECK -> DeckFragment()
        Mode.COMPUTER -> FromComputerFragment()
        Mode.FOCUS -> FocusFragment()
        Mode.CAMERA -> CameraFragment()
        Mode.SETTINGS -> SettingsFragment()
    }

    companion object {
        private const val STATE_MODE = "mode"
    }
}
