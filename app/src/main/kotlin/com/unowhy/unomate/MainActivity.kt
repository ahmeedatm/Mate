package com.unowhy.unomate

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
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
import com.unowhy.unomate.agents.AgentAlerts
import com.unowhy.unomate.audio.Speaker
import com.unowhy.unomate.device.Accessibility
import com.unowhy.unomate.device.Screen
import com.unowhy.unomate.device.Tablet
import com.unowhy.unomate.device.TabletSignals
import com.unowhy.unomate.focus.Breathing
import com.unowhy.unomate.focus.FocusDnd
import com.unowhy.unomate.focus.FocusTimer
import com.unowhy.unomate.focus.NoisePlayer
import com.unowhy.unomate.frame.FrameCache
import com.unowhy.unomate.frame.FrameLogic
import com.unowhy.unomate.installers.InstallerService
import com.unowhy.unomate.kiosk.AutoUpdater
import com.unowhy.unomate.kiosk.Kiosk
import com.unowhy.unomate.net.BackendClient
import com.unowhy.unomate.net.CalendarEvent
import com.unowhy.unomate.net.ComputerLocked
import com.unowhy.unomate.net.DocEntry
import com.unowhy.unomate.net.Link
import com.unowhy.unomate.net.PcNotification
import com.unowhy.unomate.net.runCatchingCancellable
import com.unowhy.unomate.remote.RemoteLink
import com.unowhy.unomate.trial.EssaiBackend
import com.unowhy.unomate.trial.Trial
import com.unowhy.unomate.ui.AgentAskOverlay
import com.unowhy.unomate.ui.AgentLight
import com.unowhy.unomate.ui.AppTheme
import com.unowhy.unomate.ui.Banner
import com.unowhy.unomate.ui.BannerContent
import com.unowhy.unomate.ui.BannerIcon
import com.unowhy.unomate.ui.CallOverlay
import com.unowhy.unomate.ui.CallSession
import com.unowhy.unomate.ui.CameraFragment
import com.unowhy.unomate.ui.CompanionUpdate
import com.unowhy.unomate.ui.ComputerAdder
import com.unowhy.unomate.ui.ComputersDialog
import com.unowhy.unomate.ui.DocumentPanel
import com.unowhy.unomate.ui.FocusFragment
import com.unowhy.unomate.ui.FromComputerFragment
import com.unowhy.unomate.ui.Grain
import com.unowhy.unomate.ui.HomeFragment
import com.unowhy.unomate.ui.IconCache
import com.unowhy.unomate.ui.KeyboardControl
import com.unowhy.unomate.ui.LockScreen
import com.unowhy.unomate.ui.Look
import com.unowhy.unomate.ui.Navigation
import com.unowhy.unomate.ui.PhotoFrameView
import com.unowhy.unomate.ui.PluginsView
import com.unowhy.unomate.ui.ProfileRail
import com.unowhy.unomate.ui.RailSwipeLayout
import com.unowhy.unomate.ui.ReadingAids
import com.unowhy.unomate.ui.SettingsFragment
import com.unowhy.unomate.ui.TabletAlerts
import com.unowhy.unomate.ui.ThemePattern
import com.unowhy.unomate.ui.WelcomeView
import com.unowhy.unomate.ui.dp
import com.unowhy.unomate.ui.errorText
import com.unowhy.unomate.ui.home.Demo
import com.unowhy.unomate.ui.home.Down
import com.unowhy.unomate.ui.home.FlowBandView
import com.unowhy.unomate.ui.home.HomeLayout
import com.unowhy.unomate.ui.home.LinkWatch
import com.unowhy.unomate.ui.notificationBanner
import com.unowhy.unomate.ui.notificationBannerText
import com.unowhy.unomate.ui.notificationBody
import com.unowhy.unomate.ui.showImmersive
import com.unowhy.unomate.ui.themeBool
import com.unowhy.unomate.ui.topbar.TopBar
import com.unowhy.unomate.ui.topbar.TopBarHost
import com.unowhy.unomate.ui.topbar.TopHeader
import com.unowhy.unomate.voice.VoiceControl
import com.unowhy.unomate.voice.WakeWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Les écrans qui veulent les boutons de volume de la tablette (ex. le deck pilote le son de l'ordinateur). */
interface VolumeKeyHandler {
    fun onVolumeKey(up: Boolean): Boolean
}

/** Unique Activity : plein écran, écran toujours allumé, un Fragment par mode + panneau document. */
class MainActivity : AppCompatActivity() {

    private val rehide = Runnable { hideSystemBars() }
    private var current = Mode.HOME
    /** Barre fixe à gauche : Parler, profils, outils, ordinateur, Réglages. */
    private lateinit var nav: Navigation
    private lateinit var flow: FlowBandView
    private lateinit var documentPanel: DocumentPanel
    private lateinit var banner: Banner
    private lateinit var alerts: TabletAlerts
    private lateinit var welcome: WelcomeView
    private lateinit var plugins: PluginsView
    /** Ordinateurs dont l'écran des plugins a été fermé par « Plus tard » : plus proposé avant le prochain lancement. */
    private val pluginsLater = mutableSetOf<String>()

    /** Écran de démarrage encore affiché : retiré au premier affichage de l'appli (onWindowFocusChanged) puis oublié. */
    private var splashPending = false
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
    /** Mot de réveil « Mate » : lance la même commande vocale que « Parler », sans toucher l'écran. */
    private lateinit var wake: WakeWord

    /** Bandeau du haut : en-tête de l'écran, lecture en cours, heure, batterie… (ui/topbar). */
    lateinit var topBar: TopBar
        private set

    /** Minute, batterie et WiFi de la tablette, pour les tâches de chaque minute et les éléments du bandeau. */
    private val signals by lazy { TabletSignals(this) }
    lateinit var callOverlay: CallOverlay
    /** Tablette verrouillée, à la main ou parce que l'ordinateur l'est (ui/LockScreen). */
    lateinit var lockScreen: LockScreen
        private set
    private lateinit var agentAlerts: AgentAlerts
    private lateinit var agentAsk: AgentAskOverlay
    private lateinit var meetingAlert: com.unowhy.unomate.ui.MeetingAlertOverlay
    private lateinit var frame: PhotoFrameView
    /** Dernier toucher (ou touche de volume) : le cadre photo se lance après Prefs.frameDelayMin sans toucher. */
    private var lastTouch = SystemClock.elapsedRealtime()
    /** Toucher qui a fermé le cadre : avalé jusqu'au lever du doigt, l'écran dessous ne le reçoit pas. */
    private var swallowTouch = false
    /** Dernier état de la batterie : cadre photo, et état de la tablette envoyé à l'ordinateur. */
    var battery: Tablet.Battery? = null
        private set
    val companionUpdate = CompanionUpdate(this)
    /** Micro refusé hors kiosque (en kiosque, il est accordé d'office) : la commande repart une fois accepté. */
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start() else showBanner(getString(R.string.voice_no_mic), 6)
    }

    /** Localisation hors kiosque (en kiosque, accordée d'office) : demandée seulement par « Ma position » de la météo. */
    private var locationAnswer: CompletableDeferred<Boolean>? = null
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationAnswer?.complete(granted)
        locationAnswer = null
    }

    suspend fun requestLocationPermission(): Boolean {
        val answer = CompletableDeferred<Boolean>()
        locationAnswer = answer
        locationPermission.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        return answer.await()
    }

    /** Taille du texte choisie dans Réglages > Accessibilité, en plus de celle d'Android (COMP-50). */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        val scale = Prefs.textScale(newBase)
        if (scale != 1f) applyOverrideConfiguration(Configuration().apply { fontScale = newBase.resources.configuration.fontScale * scale })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        // Raccourci des touches de volume réglé sur TalkBack (kiosque : seul moyen de le lancer sans voir l'écran).
        Accessibility.prepareShortcut(this)
        // Appli d'essai : ordinateurs de la stable repris avant de construire les écrans, sans réappairer.
        Trial.importComputers(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        Grain.applyTo(findViewById(android.R.id.content))
        ThemePattern.applyTo(findViewById(android.R.id.content))
        Speaker.init(this)
        Prefs.focusPreset(this).let { FocusTimer.restore(it.focus, it.pause) }
        NoisePlayer.init(this)
        AutoUpdater.start(this)
        InstallerService.start(this)

        nav = ProfileRail(this, ::showFromRail, ::showProfile, ::openProfiles)
        findViewById<RailSwipeLayout>(R.id.mode_container).apply {
            onSwipe = { delta -> railStep(delta) }
            canSwipe = { delta -> railCanStep(delta) }
        }
        // Tubes du Rétro derrière tous les écrans : voilés derrière les widgets sans fond de l'Accueil affiché.
        flow = findViewById(R.id.flow)
        flow.hidden = { homeFragment()?.bareWidgets().orEmpty() }
        wakeRail()
        voice = VoiceControl(this, nav.talk, nav.talk.findViewById(R.id.rail_icon))
        topBar = findViewById(R.id.top_bar)
        topBar.attach(topHost)
        topBar.onEditDone = { if (current == Mode.HOME && homeFragment()?.isEditing() == true) homeFragment()?.toggleEdit() }
        // Tablette verrouillée : le mot de réveil ne lance rien.
        wake = WakeWord(this, topBar.wakeMic) { if (!lockScreen.isShown) voice.start() }
        // Lumière des agents Claude sous la carte d'appel : un appel prime sur tout.
        // L'alerte de réunion est tout en bas des calques : lumière des agents, question et appel passent devant.
        meetingAlert = com.unowhy.unomate.ui.MeetingAlertOverlay(this)
        findViewById<android.view.ViewGroup>(android.R.id.content).addView(meetingAlert, android.view.ViewGroup.LayoutParams(-1, -1))
        meetingAlert.post { meetingAlert.onMinute() }
        val agentLight = AgentLight(this)
        agentAsk = AgentAskOverlay(this)
        agentAlerts = AgentAlerts(agentLight, openAsk = ::openAgentAsk, openList = ::showAgents)
        agentLight.onTouched = agentAlerts::onTouched
        findViewById<android.view.ViewGroup>(android.R.id.content).addView(agentLight, android.view.ViewGroup.LayoutParams(-1, -1))
        findViewById<android.view.ViewGroup>(android.R.id.content).addView(agentAsk, android.view.ViewGroup.LayoutParams(-1, -1))
        callOverlay = CallOverlay(this)
        findViewById<android.view.ViewGroup>(android.R.id.content).addView(callOverlay, android.view.ViewGroup.LayoutParams(-1, -1))
        tour = com.unowhy.unomate.ui.TourOverlay(this) {
            if (it == com.unowhy.unomate.ui.TourModel.Step.NOTIFICATIONS) topBar.notifications else nav.tourAnchor(it)
        }
        tour.onDone = { offerPlugins() }
        findViewById<android.view.ViewGroup>(android.R.id.content).addView(tour, android.view.ViewGroup.LayoutParams(-1, -1))
        // Cadre photo juste au-dessus des écrans : lumière des agents, question et appel restent devant.
        frame = PhotoFrameView(this)
        findViewById<android.view.ViewGroup>(android.R.id.content).addView(frame, 1, android.view.ViewGroup.LayoutParams(-1, -1))

        keyboard.setUp()

        // Par-dessus tout, boîtes comprises ; revient après une recréation. Un 423 de l'ordinateur le pose aussi.
        lockScreen = LockScreen(this)
        ComputerLocked.listener = { runOnUiThread { if (!isDestroyed) lockScreen.setComputerLocked(true) } }
        lockScreen.restore()

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
            showComputerPicker()
        }
        banner = Banner(findViewById(R.id.remote_banner), lifecycleScope)
        alerts = TabletAlerts(this, banner)
        findViewById<View>(R.id.rail_settings).setOnClickListener { show(Mode.SETTINGS) }
        findViewById<View>(R.id.rail_edit).setOnClickListener { editProfile() }
        findViewById<View>(R.id.rail_lock).setOnClickListener { lockScreen.lockTablet() }
        signals.onMinute {
            Screen.apply(this@MainActivity)
            alerts.remindWater()
            meetingAlert.onMinute()
            followFrame()
            companionUpdate.onMinute()
            // « Rejoindre » sans détection de fin : l'appel finit à l'heure de l'événement.
            if (call.tick()) afterCall()
        }
        signals.onBattery(::onBattery)
        signals.start()
        noComputer = findViewById(R.id.no_computer)
        noComputer.findViewById<View>(R.id.connect_button).setOnClickListener { showWelcome() }
        welcome = findViewById(R.id.welcome)
        welcome.attach(this) { computer ->
            welcome.hide()
            onComputerChanged(computer)
            show(Mode.HOME)
            showBanner(getString(R.string.welcome_done, computer.name), 6)
            maybeTour()
        }
        plugins = findViewById(R.id.plugins)
        plugins.attach(
            this,
            onLater = {
                Computers.active(this)?.let { pluginsLater += it.id }
                maybeTour()
            },
            onSaved = {
                recreateScreens()
                maybeTour()
            },
        )

        // Retour ne quitte jamais l'appli : il ferme le document ouvert, sinon ramène à l'accueil.
        onBackPressedDispatcher.addCallback(this) {
            when {
                welcome.visibility == View.VISIBLE || plugins.visibility == View.VISIBLE -> Unit
                agentAsk.isOpen -> agentAsk.close()
                current == Mode.HOME && homeFragment()?.cancelEdit() == true -> Unit
                current == Mode.HOME && homeFragment()?.closeZoom() == true -> Unit
                documentPanel.visibility == View.VISIBLE -> documentPanel.close()
                current != Mode.HOME || homeFragment()?.shownPage != shownLayout().homeProfile -> showProfile(shownLayout().homeProfile)
            }
        }

        // Recréation (changement de thème…) : on garde les ordinateurs dont les plugins ont été repoussés, sinon
        // l'écran « Tes plugins » reviendrait à chaque changement de thème.
        savedInstanceState?.getStringArrayList(STATE_PLUGINS_LATER)?.let { pluginsLater += it }
        val restored = savedInstanceState?.getString(STATE_MODE)?.let(Mode::of)
        // Un persona de la démo vient d'être choisi (Réglages > Démo) : on arrive sur son écran. Les écrans restaurés
        // n'ont pas encore leur vue : les cacher ou les montrer ici les ferait planter, ils sont recréés.
        val arrival = Demo.takeArrival(this)
        if (arrival != null && arrival != restored) {
            val tx = supportFragmentManager.beginTransaction()
            supportFragmentManager.fragments.forEach { tx.remove(it) }
            tx.commitNow()
        }
        show(arrival ?: restored ?: Mode.HOME)
        // Premier lancement (ou plus aucun ordinateur) : écran de bienvenue.
        if (savedInstanceState == null && Computers.all(this).isEmpty()) welcome.show()
        // Tablette déjà appairée qui n'a jamais vu la visite (mise à jour) : elle la voit une fois.
        if (savedInstanceState == null) maybeTour()
        // Écran de démarrage : logo + nom de l'appli sur le fond du thème, seulement à l'ouverture (cold start), pas à la
        // recréation (changement de thème). Retiré une fois l'appli dessinée (onWindowFocusChanged) ou par filet de sécurité.
        findViewById<android.widget.TextView>(R.id.splash_name).text = packageManager.getApplicationLabel(applicationInfo)
        val splash = findViewById<View>(R.id.splash)
        if (savedInstanceState == null) {
            splashPending = true
            Look.splash(splash, findViewById(R.id.splash_group))
            splash.postDelayed({ hideSplash() }, SPLASH_SAFETY_MS)
        } else {
            splash.visibility = View.GONE
        }
        showLink(Link(Computers.active(this), Link.State.OFFLINE))
        remote.restart()
        quitStrayDemo()
    }

    private fun showLink(link: Link) {
        if (link.state == Link.State.ONLINE && this.link.state != Link.State.ONLINE) {
            resyncAgents()
            resyncCall()
        }
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
        recreateScreens()
        showLink(Link(computer, Link.State.OFFLINE))
        remote.restart()
        topBar.restart()
        if (automatic && computer != null) showBanner(getString(R.string.computer_switched, computer.name), 6)
        offerPlugins()
        quitStrayDemo()
    }

    /**
     * Tablette sans persona (essai ↔ stable, réinstallation) : l'ordinateur ne doit pas garder une démo, ses touches,
     * tâches et agenda d'origine lui sont rendus. Sans effet s'il n'y en a pas ; silencieux si l'ordinateur ne répond pas.
     */
    private fun quitStrayDemo() {
        if (Demo.active(this) != null || Computers.active(this) == null) return
        lifecycleScope.launch { runCatchingCancellable { BackendClient(this@MainActivity).demo.quit() } }
    }

    /** Chaque écran est recréé : il relit le deck, l'agenda, les tâches et les documents de l'ordinateur actif. */
    private fun recreateScreens() {
        IconCache.clear()
        // L'appel suivi était celui de l'ordinateur quitté.
        endCall()
        documentPanel.close()
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        fm.fragments.forEach { tx.remove(it) }
        tx.commitNow()
        show(current)
    }

    /**
     * Plugins jamais proposés sur l'ordinateur actif (premier appairage, appli compatible installée depuis) : écran
     * des plugins. Silencieux si l'ordinateur ne répond pas ou n'est pas encore à jour.
     */
    private fun offerPlugins() {
        val computer = Computers.active(this) ?: return
        // Jamais par-dessus la visite guidée : proposé à sa fin (tour.onDone).
        if (computer.id in pluginsLater || welcome.visibility == View.VISIBLE || plugins.visibility == View.VISIBLE || tour.visibility == View.VISIBLE) return
        lifecycleScope.launch {
            val list = runCatchingCancellable { BackendClient(this@MainActivity).plugins.list() }.getOrNull() ?: return@launch
            if (list.any { it.fresh } && Computers.active(this@MainActivity)?.id == computer.id && welcome.visibility != View.VISIBLE && tour.visibility != View.VISIBLE) {
                banner.hideNow()
                plugins.show(computer.name, list, offered = true)
            }
        }
    }

    /** Depuis les Réglages : l'écran des plugins, même si tout a déjà été choisi. */
    fun showPlugins() {
        val computer = Computers.active(this) ?: return showWelcome()
        lifecycleScope.launch {
            try {
                val list = BackendClient(this@MainActivity).plugins.list()
                if (list.isEmpty()) {
                    showBanner(getString(R.string.plugins_unavailable), 6)
                } else {
                    banner.hideNow()
                    plugins.show(computer.name, list, offered = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showBanner(errorText(e), 6)
            }
        }
    }

    /** Toucher la pastille de connexion : changer d'ordinateur (ou renommer l'actif) sans passer par les Réglages. */
    /** Ordinateur en pied de barre : la liste des ordinateurs (ComputersDialog), ou la bienvenue s'il n'y en a aucun. */
    private fun showComputerPicker() {
        if (Computers.all(this).isEmpty()) return showWelcome()
        ComputersDialog(
            this,
            link,
            computerDialogs,
            onSwitch = { chosen ->
                Computers.setActive(this, chosen.id)
                onComputerChanged(Computers.active(this))
            },
            onRename = { computer ->
                computerDialogs.rename(computer) {
                    refreshLink()
                    settings()?.onLinkChanged()
                }
            },
            onSettings = { show(Mode.SETTINGS) },
            onClosed = {
                pickerOpen = false
                renderEditButton()
            },
        ).show()
        pickerOpen = true
        renderEditButton()
    }

    /** Liste des ordinateurs ouverte (pied de barre) : le crayon est grisé pendant ce temps. */
    private var pickerOpen = false

    /** Notifications de Depuis l'ordi affichées (la cloche est marquée). */
    private var notificationsShown = false

    /** Crayon affiché seulement sur l'Accueil (les profils à widgets) : ailleurs il n'y a rien à modifier, il est masqué. */
    private fun renderEditButton() {
        findViewById<View>(R.id.rail_edit).visibility = if (current == Mode.HOME && !pickerOpen) View.VISIBLE else View.GONE
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
        topBar.setRunning(true)
        // Après une recréation (thème), l'écran restauré n'a sa vue qu'ici : son en-tête n'était pas encore là dans show().
        showTopHeader()
        offerPlugins()
    }

    override fun onStop() {
        FocusTimer.unobserve(focusListener)
        topBar.setRunning(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        Kiosk.enter(this)
        Accessibility.applyAnimationScale(this, Prefs.reduceMotion(this))
        Screen.apply(this)
        Screen.applyOrientation(this)
        // Retour de l'essai : si l'ordinateur est resté sur le backend d'une MR (essai planté), on remet le sien.
        if (!BuildConfig.TRIAL && Prefs.trialOpened(this)) {
            Prefs.setTrialOpened(this, false)
            lifecycleScope.launch { EssaiBackend.restoreOrigin(applicationContext) }
        }
        wake.resume()
    }

    /** UnoMate quitte l'écran (écran éteint, autre appli) : le mot de réveil n'écoute plus. */
    override fun onPause() {
        wake.pause()
        super.onPause()
    }

    private fun onBattery(state: Tablet.Battery) {
        battery = state
        alerts.onBattery(state)
        settings()?.onBattery(state)
    }

    private fun hideSplash() {
        if (!splashPending) return
        splashPending = false
        Look.hideSplash(findViewById(R.id.splash))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MODE, current.tag)
        outState.putStringArrayList(STATE_PLUGINS_LATER, ArrayList(pluginsLater))
    }

    override fun onDestroy() {
        // Activité recréée (thème, mise à jour) pendant le cadre : sa luminosité réduite ne reste pas à l'écran suivant.
        if (::frame.isInitialized && frame.isShowing) Screen.frameCharging = null
        documentPanel.close()
        if (isFinishing) NoisePlayer.stop()
        voice.release()
        wake.release()
        lockScreen.dismiss()
        ComputerLocked.listener = null
        signals.stop()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Boîte de dialogue ouverte ou fermée (thème, mise à jour…) : ses touchers ne passent pas par dispatchTouchEvent,
        // ils comptent quand même pour le délai du cadre photo, qui ne part pas à la fermeture d'une longue boîte.
        lastTouch = SystemClock.elapsedRealtime()
        if (!hasFocus) return
        // L'appli est dessinée et prête : on laisse voir le logo un instant, puis on retire l'écran de démarrage.
        if (splashPending) findViewById<View>(R.id.splash).postDelayed({ hideSplash() }, SPLASH_HOLD_MS)
        hideSystemBars()
        keyboard.closeOrphan()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            Screen.touched(this)
            lastTouch = SystemClock.elapsedRealtime()
            wakeRail()
            if (frame.isShowing) {
                closeFrame()
                swallowTouch = true
            }
        }
        if (swallowTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) swallowTouch = false
            return true
        }
        keyboard.onTouch(event)
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastTouch = SystemClock.elapsedRealtime()
        if (frame.isShowing) closeFrame()
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

    /** Affiche une adresse web (lien copié sur l'ordinateur) dans le panneau latéral, comme « Page de l'ordi ». */
    fun openUrl(url: String) {
        documentPanel.openUrl(url, expand = current == Mode.COMPUTER, splittable = current != Mode.COMPUTER)
    }

    // --- Utilisé par RemoteChannel (ordres de l'ordinateur)

    val modeName: String
        get() = current.key

    val documentPath: String?
        get() = documentPanel.currentPath

    /**
     * Réglages changés depuis la page de l'ordinateur (ordre `settings`). Nouveau thème : écran recréé un peu après,
     * le temps de renvoyer le résultat ; s'il se perd, l'ordre revient et ne recrée plus rien (thème déjà en place).
     */
    fun onRemoteSettings(themeChanged: Boolean) {
        if (themeChanged) {
            window.decorView.postDelayed({ if (!isFinishing) recreate() }, 500)
            return
        }
        settings()?.onRemoteSettings()
        (supportFragmentManager.findFragmentByTag(Mode.FOCUS.tag) as? FocusFragment)?.onRemoteSettings()
        followFocusDnd(FocusTimer.state)
    }

    /**
     * Profils, bandeau du haut ou barre de gauche changés depuis la page de l'ordinateur (ordre `layout`, déjà enregistrés) :
     * l'Accueil relit sa disposition, le bandeau et la barre se redessinent.
     */
    fun onRemoteLayout() {
        homeFragment()?.reloadLayout()
        topBar.reload()
        nav.applySettings()
        refreshRail()
    }

    fun showMode(name: String) {
        // Ancien mode Deck : le profil Deck. Accueil ou nom inconnu (« tasks », ancien « chat ») : le profil Accueil.
        if (name.equals(Mode.DECK_KEY, ignoreCase = true)) return showProfile(shownLayout().deckTarget())
        val mode = Mode.of(name) ?: Mode.HOME
        if (mode == Mode.HOME) showProfile(shownLayout().homeProfile) else show(mode)
    }

    // --- Appel en cours : profil Réunion (Deck V2)

    private val call = CallSession()

    /** Écran et profil d'avant la bascule sur Réunion, pour y revenir à la fin de l'appel. */
    private var beforeCall: Pair<Mode, Int>? = null

    val inCall: Boolean get() = call.active

    /** Pas d'alerte de réunion pendant un appel, dans la Caméra plein écran ni pendant une saisie. */
    fun meetingAlertBlocked(): Boolean = com.unowhy.unomate.ui.MeetingAlertModel.blocked(
        inCall = call.active || callOverlay.app != null,
        camera = current == Mode.CAMERA,
        typing = com.unowhy.unomate.ui.Typing.active(focusIsEditText = currentFocus is android.widget.EditText),
    )

    /** Appli de l'appel en cours (widget d'appel du profil Réunion), null hors appel. */
    val callApp: String? get() = call.app

    /** Ordre `meeting` de l'ordinateur : un appel commence ou finit. */
    fun onMeeting(state: String, app: String) = if (state == "start") startCall(app) else endCall()

    /**
     * Un appel commence (ordre de l'ordinateur, ou « Rejoindre » avec la fin de l'événement) : Réunion entre dans la barre,
     * puis l'écran y passe, sauf pendant une saisie ou dans un écran plein (Caméra, mode Modifier).
     */
    fun startCall(app: String, endAtMs: Long? = null) {
        if (!call.start(app, endAtMs)) return
        val layout = shownLayout()
        refreshRail(layout)
        if (layout.callProfile < 0) return
        val fullScreen = current == Mode.CAMERA || homeFragment()?.editing == true
        val typing = com.unowhy.unomate.ui.Typing.active(focusIsEditText = currentFocus is android.widget.EditText)
        // État sauvegardé (appli en arrière-plan) : pas de changement d'écran, la barre suffit.
        if (supportFragmentManager.isStateSaved || !call.shouldSwitch(typing = typing, fullScreen = fullScreen)) return
        beforeCall = current to (homeFragment()?.shownPage ?: layout.homeProfile)
        showProfile(layout.callProfile)
    }

    /** Fin d'appel : retour à l'écran d'avant si Réunion est encore affiché, puis Réunion quitte la barre. */
    fun endCall() {
        if (!call.active) return
        call.end()
        afterCall()
    }

    private fun afterCall() {
        val layout = shownLayout()
        // État sauvegardé (appli en arrière-plan, appli d'essai devant) : pas de transaction, la barre seulement.
        if (supportFragmentManager.isStateSaved) {
            beforeCall = null
            refreshRail(layout)
            return
        }
        val before = beforeCall
        beforeCall = null
        val page = homeFragment()?.shownPage?.takeIf { current == Mode.HOME }
        val back = before?.let { call.returnTo(page, layout.callProfile, it.second) }
        when {
            back != null && before.first == Mode.HOME -> showProfile(back)
            back != null -> show(before.first)
            // Resté sur Réunion sans écran d'avant connu : il n'est plus dans la barre, on rentre à l'Accueil.
            page == layout.callProfile -> showProfile(layout.homeProfile)
        }
        refreshRail(layout)
    }

    /** La disposition que l'Accueil affiche (celle que la barre doit suivre), sinon celle enregistrée. */
    private fun shownLayout(): HomeLayout = homeFragment()?.shownLayout ?: HomeLayout.load(this)

    private fun refreshRail(layout: HomeLayout = shownLayout()) =
        nav.showProfiles(layout, homeFragment()?.shownPage?.takeIf { current == Mode.HOME } ?: layout.homeProfile, call.active)

    /** Appel entrant sur l'ordinateur (ordre `call`, voir CallOverlay). */
    fun onCall(state: String, app: String, caller: String?, video: Boolean) = callOverlay.onCall(state, app, caller, video)

    /**
     * Notification arrivée sur l'ordinateur (ordre `notification`) : bandeau quel que soit l'écran (sans le texte en
     * mode discret), lu à voix haute si Réglages > Accessibilité le demande, et liste de Depuis l'ordi relue si elle
     * est affichée.
     */
    fun onNotification(n: PcNotification) {
        val discreet = Prefs.notificationsDiscreet(this)
        // Icône et nom de l'appli, objet = le titre ; corps = le texte (coupé par le bandeau s'il dépasse), rien en mode discret.
        closeFrame()
        // Pas de bandeau par-dessus l'onboarding (il couvrirait le bouton « Plus tard ») : la notification reste dans la cloche.
        if (!onboardingUp()) {
            banner.show(notificationBanner(n, discreet), NOTIFICATION_SECONDS)
            readAloud(notificationBannerText(n, discreet))
        }
        val computer = supportFragmentManager.findFragmentByTag(Mode.COMPUTER.tag) as? FromComputerFragment
        computer?.onNotification()
        // Point sur la cloche du bandeau, sauf si l'historique est déjà sous les yeux.
        if (computer?.showingNotifications != true || current != Mode.COMPUTER) {
            notificationsUnread = true
            topBar.notificationsChanged(ring = true)
        }
    }

    /** Cloche du bandeau : Depuis l'ordi > Notifications, point retiré. On en sort par la barre de gauche. */
    private fun openNotifications() {
        notificationsUnread = false
        topBar.notificationsChanged()
        show(Mode.COMPUTER)
        // Le mode vient peut-être d'être créé : sa vue doit exister avant de choisir l'écran.
        supportFragmentManager.executePendingTransactions()
        (supportFragmentManager.findFragmentByTag(Mode.COMPUTER.tag) as? FromComputerFragment)?.openNotifications()
    }

    /** Depuis l'ordi entre dans ses notifications ou en sort : la barre marque la cloche à la place de « Depuis l'ordi ». */
    fun onNotificationsShown(shown: Boolean) {
        if (::nav.isInitialized) nav.showNotifications(shown)
        notificationsShown = shown
        renderEditButton()
        if (::topBar.isInitialized) topBar.notificationsChanged()
    }

    /** Notifications arrivées depuis la dernière ouverture : point sur la cloche du bandeau. */
    private var notificationsUnread = false

    /** Mode choisi dans la barre (toucher ou balayage) : « Depuis l'ordi » quitte ses notifications, ouvertes par la cloche. */
    private fun showFromRail(mode: Mode) {
        if (mode == Mode.COMPUTER) (supportFragmentManager.findFragmentByTag(Mode.COMPUTER.tag) as? FromComputerFragment)?.leaveNotifications()
        show(mode)
    }

    /** Un agent Claude attend, a fini ou a atteint sa limite (ordre `agent`, voir AgentAlerts). */
    fun onAgent(kind: String, askId: String?, waiting: Int) = agentAlerts.onOrder(kind, askId, waiting)

    /** Carte de la demande de Claude (permission, questions) à laquelle la tablette peut répondre. */
    fun openAgentAsk(id: String) = agentAsk.open(id)

    val agentAskOpen get() = agentAsk.isOpen

    /**
     * Retour en ligne (lancement, relance de l'appli, réseau revenu) : l'ordre `meeting` ne prévient qu'au changement,
     * on relit donc l'appel une fois. Un appel déjà en cours reprend ; un appel signalé par l'ordinateur et fini pendant
     * la coupure (son `end` perdu) se termine.
     */
    private fun resyncCall() {
        lifecycleScope.launch {
            val live = runCatching { BackendClient(this@MainActivity).deck.liveApps() }.getOrNull() ?: return@launch
            if (call.endsOnResync(live.live)) endCall() else live.live.firstOrNull()?.let { startCall(it) }
        }
    }

    /** Demande d'agent en attente depuis avant la connexion : lumière rallumée (voir AgentAlerts.resync). */
    private fun resyncAgents() {
        lifecycleScope.launch {
            val ask = runCatching { BackendClient(this@MainActivity).agents.state() }.getOrNull()?.sessions?.firstNotNullOfOrNull { it.askId }
            agentAlerts.resync(ask)
        }
    }

    /** Commande vocale lancée d'ailleurs que le bouton Parler (carte d'une demande d'agent). */
    fun startVoice() = voice.start()

    /** Réglage du mot de réveil changé : écoute ou non tout de suite. */
    fun onWakeWordChanged() = wake.sync()

    /**
     * Commande vocale pour les agents Claude ; rend la phrase à afficher. Elle n'agit que sur la carte ouverte (on voit ce
     * qu'on autorise) : sinon, elle ouvre la demande en attente. « continue » relance la dernière session finie qui le permet.
     */
    suspend fun agentVoice(action: String, choice: Int?): String {
        if (action == "continue") {
            val session = runCatching { BackendClient(this).agents.state() }.getOrNull()?.sessions
                ?.firstOrNull { it.canSend && it.state == com.unowhy.unomate.net.AgentState.DONE }
                ?: return getString(R.string.agent_voice_no_session)
            BackendClient(this).agents.send(session.id, "continue")
            return getString(R.string.agent_sent, session.name)
        }
        if (agentAsk.isOpen) return agentAsk.voice(action, choice)
        val id = agentAlerts.pendingAsk ?: return getString(R.string.agent_voice_none)
        agentAsk.open(id)
        return getString(R.string.agent_voice_opened)
    }

    /** Tableau des agents Claude, par-dessus le profil Deck. */
    fun showAgents() {
        showProfile(HomeLayout.load(this).deckTarget())
        homeFragment()?.showAgentsBoard()
    }

    /** Appel décroché sur la tablette : profil Réunion, avec les touches de l'appli (Discord : micro, sourdine, quitter). */
    fun showAppControls(app: String) = startCall(app)

    /**
     * Mode Modifier de l'Accueil changé (COMP-63) : la lecture en cours du header se cache pendant le mode Modifier, dont
     * la barre (Ajouter, Annuler, Terminé) est à sa place.
     */
    fun refreshMiniPlayer() {
        topBar.music?.update()
    }

    fun closeDocument() {
        documentPanel.close()
    }

    fun requestMicPermission() = micPermission.launch(android.Manifest.permission.RECORD_AUDIO)

    /** Écran de bienvenue (WiFi, installation, connexion), depuis un mode sans ordinateur. */
    fun showWelcome() {
        banner.hideNow()
        welcome.show()
    }

    /** Bandeau en haut de l'écran, par-dessus le mode courant ; un toucher le ferme. */
    fun showBanner(text: String, seconds: Int, icon: BannerIcon = BannerIcon.MESSAGE, onTap: (() -> Unit)? = null) {
        // Un message de l'ordinateur ou un rappel se lit : le cadre lui laisse la place. Pas de corps (objet seul).
        if (onboardingUp()) return
        closeFrame()
        banner.show(BannerContent(text, icon = icon), seconds, onTap)
    }

    /** Un écran modal d'accueil est ouvert (premier lancement, « Tes plugins », visite guidée) : le bandeau le couvrirait. */
    private fun onboardingUp() =
        welcome.visibility == View.VISIBLE || plugins.visibility == View.VISIBLE || tour.visibility == View.VISIBLE

    /**
     * Réglages > Accessibilité > Lire les bandeaux à voix haute : bandeau d'une notification ou d'un message de
     * l'ordinateur lu par la voix de la tablette. Coupé : rien.
     */
    fun readAloud(banner: String) {
        if (Prefs.readBanners(this)) Speaker.speak(ReadingAids.spoken(banner))
    }

    /**
     * Cadre photo, vérifié chaque minute : lancé après le délai sans toucher (photos synchronisées juste avant, au plus
     * tous les quarts d'heure), arrêté la nuit ou sur batterie faible, liste relue pendant l'affichage.
     */
    private fun followFrame() {
        val night = Prefs.nightDim(this) && Screen.isNight()
        val state = battery
        if (frame.isShowing && !FrameLogic.canRun(night, state?.level, state?.charging ?: true)) return closeFrame()
        val context = FrameLogic.Context(
            idleMs = SystemClock.elapsedRealtime() - lastTouch,
            delayMin = Prefs.frameDelayMin(this),
            night = night,
            batteryLevel = state?.level,
            charging = state?.charging ?: true,
            busy = frameBusy(),
        )
        if (!frame.isShowing && !FrameLogic.shouldStart(context)) return
        lifecycleScope.launch {
            FrameCache.sync(this@MainActivity)
            val photos = withContext(Dispatchers.IO) { FrameCache.photos(this@MainActivity) }
            when {
                frame.isShowing -> frame.update(photos)
                // Un toucher pendant la synchro : on reste sur l'écran.
                SystemClock.elapsedRealtime() - lastTouch >= context.idleMs && !frameBusy() -> openFrame(photos)
            }
        }
    }

    /** Ce qui sert encore, que le cadre ne doit pas cacher (dont une boîte de dialogue ouverte : la fenêtre perd le focus). */
    private fun frameBusy() = !hasWindowFocus() || callOverlay.visibility == View.VISIBLE || agentAsk.isOpen || welcome.visibility == View.VISIBLE ||
        plugins.visibility == View.VISIBLE ||
        documentPanel.visibility == View.VISIBLE || current == Mode.CAMERA || FocusTimer.state.running ||
        Breathing.running

    /** Lance le cadre tout de suite (Réglages > Cadre photo > Essayer, ou délai écoulé) ; sans photo, rien. */
    fun openFrame(photos: List<java.io.File>) {
        if (photos.isEmpty() || frame.isShowing) return
        keyboard.hide()
        frame.show(photos)
        // Écrans dessous masqués : rien à redessiner derrière la photo (horloges, animations de l'Accueil).
        findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0).visibility = View.INVISIBLE
        Screen.frameCharging = battery?.charging ?: true
        Screen.apply(this)
    }

    fun closeFrame() {
        if (!frame.isShowing) return
        frame.hide()
        findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0).visibility = View.VISIBLE
        Screen.frameCharging = null
        Screen.apply(this)
    }

    private fun settings() = supportFragmentManager.findFragmentByTag(Mode.SETTINGS.tag) as? SettingsFragment

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Balayage vertical (Accueil ou autre mode) : entrée voisine de la barre ; faux au bout. */
    fun railStep(delta: Int): Boolean = nav.step(delta)

    /** Une entrée de la barre attend dans ce sens (l'écran suit alors le doigt, sinon il résiste). */
    fun railCanStep(delta: Int): Boolean = nav.canStep(delta)

    private fun show(mode: Mode) {
        if (::frame.isInitialized) closeFrame()
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
        // L'écran quitté est masqué tout de suite : deux écrans en fondu l'un sur l'autre se mêlent (double image), et
        // remettre l'ancien à 100 % à la fin de son fondu le montrait en entier une image avant son masquage (flash).
        fm.fragments.forEach { tx.hide(it) }
        // Sans ordinateur, un mode qui en a besoin n'est pas créé (caméra, requêtes) : renderNoComputer le remplace.
        val needsComputer = mode.connect != null && Computers.all(this).isEmpty()
        val existing = fm.findFragmentByTag(mode.tag)
        if (existing != null) tx.show(existing) else if (!needsComputer) tx.add(R.id.mode_container, create(mode), mode.tag)
        tx.commit()

        nav.select(mode, changed)
        renderEditButton()
        if (::topBar.isInitialized) topBar.notificationsChanged()
        // L'Accueil a son propre balayage (WidgetGrid) ; Réglages n'est pas dans la barre.
        // Caméra : hors barre, et ses gestes (zoom) restent à elle.
        findViewById<RailSwipeLayout>(R.id.mode_container).swipeEnabled = mode != Mode.HOME && mode != Mode.SETTINGS && mode != Mode.CAMERA
        renderNoComputer()
        if (changed) slideTo(mode)
        fm.executePendingTransactions()
        showTopHeader()
    }

    /**
     * En-tête de l'écran affiché dans le bandeau du haut : celui de l'écran (titre, retour, boutons), le nom du profil
     * sur l'Accueil, sinon le nom du mode.
     */
    private fun showTopHeader() {
        val screen = supportFragmentManager.findFragmentByTag(current.tag)
        val header = (screen as? TopHeader)?.topHeader
        when {
            header != null -> topBar.setHeader(header)
            current == Mode.HOME -> topBar.setTitle(profileName.orEmpty())
            else -> topBar.setTitle(getString(current.label))
        }
    }

    /** Nom du profil affiché sur l'Accueil, pour le bandeau du haut. */
    private var profileName: String? = null

    /** Ce que les éléments du bandeau demandent à l'appli. */
    private val topHost = object : TopBarHost {
        override val context: Context get() = this@MainActivity
        override val scope get() = lifecycleScope
        override val signals get() = this@MainActivity.signals
        override fun hasComputer() = Computers.all(this@MainActivity).isNotEmpty()

        // Rien à relire sous le cadre photo ni sans ordinateur ; cachée en mode Modifier (COMP-63).
        override fun musicSuppressed() =
            frame.isShowing || !hasComputer() || (current == Mode.HOME && homeFragment()?.isEditing() == true)

        override fun callActive() = call.active
        override fun agentsOffered() = homeFragment()?.features?.agents == true
        override fun openAgents() = showAgents()
        override fun openNotifications() = this@MainActivity.openNotifications()
        override fun notificationsUnread() = notificationsUnread
        override fun notificationsShown() = current == Mode.COMPUTER && notificationsShown

        override fun joinMeeting(event: CalendarEvent) {
            lifecycleScope.launch {
                try {
                    val kind = BackendClient(this@MainActivity).calendar.joinMeeting(event.id)
                    showBanner(getString(R.string.join_opened, event.title), 6)
                    startCall(CallSession.appOfKind(kind), event.endMs)
                } catch (e: Exception) {
                    showBanner(errorText(e), 6)
                }
            }
        }

        override fun showError(e: Throwable) = showBanner(errorText(e), 6)

        override fun homeCity(): JSONObject? = HomeLayout.load(this@MainActivity).widgets
            .firstOrNull { it.config.has("lat") }?.config
    }

    /** Arrivée de l'écran `mode` dans le sens du déplacement dans la barre. Ressorts qui jouent aussi en kiosque. */
    private fun slideTo(mode: Mode) {
        supportFragmentManager.executePendingTransactions()
        val into = supportFragmentManager.findFragmentByTag(mode.tag)?.view ?: return
        // Kiosque : l'Accueil arrive par ses widgets (Look.enterSpring), pas en glissant tout entier par-dessus.
        if (mode == Mode.HOME && Look.cascadeOnly(this)) return
        Look.sharedAxis(into, fromBelow = nav.lastMove >= 0)
        flowTravel(nav.lastMove)
    }

    /** Écran ou profil voisin dans la barre ([move] > 0 : plus bas) : les tubes du Rétro défilent avec lui. */
    fun flowTravel(move: Int) = flow.travel(move)

    /** Entrée de profil de la barre : l'Accueil, sur ce profil (créé au besoin : sa vue doit exister avant). */
    private fun showProfile(page: Int) {
        // Déjà sur l'Accueil : seul le profil change (et glisse) ; sinon l'écran arrive en entier, sur ce profil.
        val within = current == Mode.HOME
        show(Mode.HOME)
        supportFragmentManager.executePendingTransactions()
        homeFragment()?.showProfile(page, animate = within)
    }

    /** Cases caméra d'Ordi : le mode Caméra, directement dans l'usage choisi. */
    fun openCamera(photo: Boolean) {
        show(Mode.CAMERA)
        supportFragmentManager.executePendingTransactions()
        (supportFragmentManager.findFragmentByTag(Mode.CAMERA.tag) as? CameraFragment)?.start(photo)
    }

    /** Caméra arrêtée : retour à Ordi, d'où elle s'ouvre. */
    fun backToComputer() = show(Mode.COMPUTER)

    /** Crayon de la barre : le profil affiché (ou le dernier vu) passe en mode Modifier, ou en sort. */
    private fun editProfile() {
        if (current != Mode.HOME) show(Mode.HOME)
        supportFragmentManager.executePendingTransactions()
        homeFragment()?.toggleEdit()
    }

    /** Mode Modifier commencé ou fini : le crayon de la barre est plein pendant ce temps. */
    fun onEditingChanged(editing: Boolean) {
        findViewById<View>(R.id.rail_edit).isSelected = editing
        // Le crayon modifie aussi le bandeau du haut ; Terminé de l'Accueil le referme.
        topBar.edit(editing)
    }

    /** Appui long sur un profil de la barre, ou « Plus » : la liste des profils, depuis l'Accueil. */
    private fun openProfiles() {
        show(Mode.HOME)
        supportFragmentManager.executePendingTransactions()
        homeFragment()?.openProfiles()
    }

    /** L'Accueil prévient à chaque profil affiché et à chaque changement des profils : la barre suit. */
    fun onProfileShown(layout: HomeLayout, page: Int) {
        nav.showProfiles(layout, page, inCall = call.active)
        profileName = layout.names.getOrNull(page)
        if (current == Mode.HOME) topBar.setTitle(profileName.orEmpty())
    }

    private fun homeFragment() = supportFragmentManager.findFragmentByTag(Mode.HOME.tag) as? HomeFragment

    private lateinit var tour: com.unowhy.unomate.ui.TourOverlay

    /**
     * Visite guidée, une fois, après l'appairage (laisse le temps aux écrans de se poser). Jamais par-dessus l'écran des
     * plugins : relancée à sa fermeture.
     */
    private fun maybeTour() {
        if (welcome.visibility == View.VISIBLE || tour.visibility == View.VISIBLE || plugins.visibility == View.VISIBLE) return
        if (!com.unowhy.unomate.ui.TourModel.shouldShow(this, Computers.all(this).isNotEmpty())) return
        window.decorView.postDelayed({
            if (welcome.visibility != View.VISIBLE && plugins.visibility != View.VISIBLE && tour.visibility != View.VISIBLE) tour.start()
        }, TOUR_DELAY_MS)
    }

    /** Réglages > Barre de gauche : noms et outils changés. */
    fun applyRailSettings() = nav.applySettings()

    /** Repos de la barre après [REST_MS] sans toucher ; [wakeRail] la ravive et relance l'attente. */
    private val rest = Runnable { nav.setResting(true) }

    private fun wakeRail() {
        nav.setResting(false)
        window.decorView.removeCallbacks(rest)
        window.decorView.postDelayed(rest, REST_MS)
    }

    /** Modes qui ont besoin de l'ordinateur : « Aucun ordinateur connecté » par-dessus, tant qu'il n'y en a pas. */
    private fun renderNoComputer() {
        val text = current.connect?.takeIf { Computers.all(this).isEmpty() }
        noComputer.visibility = if (text != null) View.VISIBLE else View.GONE
        if (text != null) noComputer.findViewById<TextView>(R.id.connect_text).setText(text)
    }

    private fun create(mode: Mode): Fragment = when (mode) {
        Mode.HOME -> HomeFragment()
        Mode.COMPUTER -> FromComputerFragment()
        Mode.FOCUS -> FocusFragment()
        Mode.CAMERA -> CameraFragment()
        Mode.SETTINGS -> SettingsFragment()
    }

    companion object {
        private const val STATE_MODE = "mode"
        private const val STATE_PLUGINS_LATER = "plugins_later"

        /** Écran de démarrage : tenue du logo après le premier affichage, et filet de sécurité qui le retire dans tous les cas. */
        private const val SPLASH_HOLD_MS = 600L
        private const val SPLASH_SAFETY_MS = 4000L

        /** Délai avant la visite guidée : l'Accueil a le temps d'arriver. */
        private const val TOUR_DELAY_MS = 900L

        /** Sans toucher pendant ce temps, la barre passe au repos (atténuée). */
        private const val REST_MS = 60_000L

        /** Bandeau d'une notification de l'ordinateur : le temps de lire l'appli, le titre et le début du texte. */
        private const val NOTIFICATION_SECONDS = 8
    }
}
