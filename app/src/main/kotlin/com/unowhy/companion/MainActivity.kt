package com.unowhy.companion

import com.unowhy.companion.ui.AppTheme
import com.unowhy.companion.ui.Look
import com.unowhy.companion.ui.themeColor
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.unowhy.companion.audio.Speaker
import com.unowhy.companion.device.Screen
import com.unowhy.companion.device.Tablet
import com.unowhy.companion.focus.FocusTimer
import com.unowhy.companion.focus.NoisePlayer
import com.unowhy.companion.focus.Water
import com.unowhy.companion.kiosk.Kiosk
import com.unowhy.companion.remote.RemoteChannel
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.DocEntry
import com.unowhy.companion.net.Link
import com.unowhy.companion.ui.CallOverlay
import com.unowhy.companion.ui.CameraFragment
import com.unowhy.companion.ui.ComputerAdder
import com.unowhy.companion.ui.ChatFragment
import com.unowhy.companion.ui.DeckFragment
import com.unowhy.companion.ui.DocsFragment
import com.unowhy.companion.ui.DocumentPanel
import com.unowhy.companion.ui.HomeFragment
import com.unowhy.companion.ui.FocusFragment
import com.unowhy.companion.ui.IconCache
import com.unowhy.companion.ui.SettingsFragment
import com.unowhy.companion.ui.TabletStatusView
import com.unowhy.companion.ui.WelcomeView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import com.unowhy.companion.ui.showImmersive
import com.unowhy.companion.voice.VoiceControl

/** Les écrans qui veulent les boutons de volume de la tablette (ex. le deck pilote le son de l'ordinateur). */
interface VolumeKeyHandler {
    fun onVolumeKey(up: Boolean): Boolean
}

/** Unique Activity : plein écran, écran toujours allumé, un Fragment par mode + panneau document. */
class MainActivity : AppCompatActivity() {

    /**
     * Les tâches sont sur l'Accueil : l'ancien mode "tasks" (ordinateur, extension Claude) y mène.
     * `connect` : sans ordinateur, le mode est remplacé par « Aucun ordinateur connecté » et ce texte.
     */
    private enum class Mode(val icon: Int, val label: Int, val connect: Int? = null) {
        HOME(R.drawable.ic_home, R.string.mode_home),
        DECK(R.drawable.ic_deck, R.string.mode_deck, R.string.connect_deck),
        CHAT(R.drawable.ic_chat, R.string.mode_chat, R.string.connect_chat),
        DOCS(R.drawable.ic_folder, R.string.mode_docs, R.string.connect_docs),
        FOCUS(R.drawable.ic_timer, R.string.mode_focus),
        CAMERA(R.drawable.ic_videocam, R.string.mode_camera, R.string.connect_camera),
        SETTINGS(R.drawable.ic_settings, R.string.mode_settings),
    }

    private class RailItem(val view: View, val label: TextView, val badge: TextView)

    private val railItems = mutableMapOf<Mode, RailItem>()
    private val rehide = Runnable { hideSystemBars() }
    private var current = Mode.HOME
    private lateinit var documentPanel: DocumentPanel
    private lateinit var statusDot: View
    private lateinit var statusName: TextView
    private lateinit var banner: TextView
    private lateinit var welcome: WelcomeView
    private lateinit var noComputer: View
    /** Ordinateur dont on a déjà demandé s'il a un vrai assistant (mode live). */
    private var assistantCheckedFor: String? = null
    /** Bandeau « batterie faible » déjà montré (de nouveau après une recharge). */
    private var lowBatteryWarned = false
    private var channel: Job? = null
    private val computerDialogs by lazy { ComputerAdder(this, lifecycleScope) { onComputerChanged(it) } }
    /** Dernier état de la liaison, repris par les Réglages et le sélecteur d'ordinateur. */
    var link = Link(null, Link.State.NONE)
        private set
    private val hideBanner = Runnable { banner.visibility = View.GONE }
    private lateinit var voice: VoiceControl
    lateinit var callOverlay: CallOverlay
        private set
    /** Micro refusé hors kiosque (en kiosque, il est accordé d'office) : la commande repart une fois accepté. */
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start() else showBanner(getString(R.string.voice_no_mic), 6)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        Speaker.init(this)

        val bar = findViewById<LinearLayout>(R.id.mode_bar)
        for (mode in Mode.entries) {
            val view = layoutInflater.inflate(R.layout.item_rail, bar, false)
            view.findViewById<ImageView>(R.id.rail_icon).setImageResource(mode.icon)
            val label = view.findViewById<TextView>(R.id.rail_label).apply { setText(mode.label) }
            view.contentDescription = getString(mode.label)
            view.setOnClickListener { show(mode) }
            bar.addView(view)
            railItems[mode] = RailItem(view, label, view.findViewById(R.id.rail_badge))
        }
        // Assistant : affiché seulement quand l'ordinateur actif a un vrai assistant (voir refreshAssistant).
        railItems.getValue(Mode.CHAT).view.visibility = View.GONE
        // Commandes vocales : sous les modes, au-dessus de l'état de la tablette.
        val rail = findViewById<LinearLayout>(R.id.rail)
        val talk = layoutInflater.inflate(R.layout.item_rail, rail, false)
        talk.findViewById<ImageView>(R.id.rail_icon).setImageResource(R.drawable.ic_mic)
        talk.findViewById<TextView>(R.id.rail_label).setText(R.string.voice_button)
        talk.contentDescription = getString(R.string.voice_button)
        rail.addView(talk, rail.indexOfChild(bar) + 1)
        voice = VoiceControl(this, talk, talk.findViewById(R.id.rail_icon))
        callOverlay = CallOverlay(this)
        findViewById<android.view.ViewGroup>(android.R.id.content).addView(callOverlay, android.view.ViewGroup.LayoutParams(-1, -1))

        setUpKeyboard()

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
        statusDot = findViewById(R.id.status_dot)
        statusName = findViewById(R.id.status_name)
        findViewById<View>(R.id.status).setOnClickListener { showComputerPicker() }
        findViewById<TabletStatusView>(R.id.tablet_status).apply {
            setOnClickListener { show(Mode.SETTINGS) }
            onMinute = {
                Screen.apply(this@MainActivity)
                remindWater()
            }
            onBattery = ::onBattery
        }
        banner = findViewById(R.id.remote_banner)
        banner.setOnClickListener { hideBanner.run() }
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
                documentPanel.visibility == View.VISIBLE -> documentPanel.close()
                current != Mode.HOME -> show(Mode.HOME)
            }
        }

        val restored = savedInstanceState?.getString(STATE_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
        show(restored ?: Mode.HOME)
        // Premier lancement (ou plus aucun ordinateur) : écran de bienvenue.
        if (savedInstanceState == null && Computers.all(this).isEmpty()) welcome.show()
        showLink(Link(Computers.active(this), Link.State.OFFLINE))
        startChannel()
    }

    /**
     * Canal avec l'ordinateur actif tant que l'appli est à l'écran : ordres de Claude (extension MCP),
     * pastille de connexion (la requête en attente prouve que l'ordinateur répond), bascule vers un autre
     * ordinateur enregistré quand l'actif ne répond plus. L'ancien canal n'est pas attendu : son poll
     * (jusqu'à 25 s) se termine dans le vide.
     */
    private fun startChannel() {
        channel?.cancel()
        channel = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RemoteChannel(this@MainActivity).run(::showLink) { computer -> onComputerChanged(computer, automatic = true) }
            }
        }
    }

    private fun showLink(link: Link) {
        this.link = link
        val color = when (link.state) {
            Link.State.ONLINE -> R.attr.c_ok
            Link.State.UNAUTHORIZED -> R.attr.c_warn
            else -> R.attr.c_ko
        }
        statusDot.background.mutate().setTint(themeColor(this, color))
        statusName.text = link.computer?.name ?: getString(R.string.no_computer_short)
        val state = when (link.state) {
            Link.State.ONLINE -> R.string.online
            Link.State.UNAUTHORIZED -> R.string.unauthorized
            Link.State.OFFLINE -> R.string.offline
            Link.State.NONE -> R.string.no_computer
        }
        findViewById<View>(R.id.status).contentDescription = getString(state)
        refreshAssistant(link)
        (supportFragmentManager.findFragmentByTag(Mode.SETTINGS.name) as? SettingsFragment)?.onLinkChanged()
    }

    /**
     * Mode Assistant seulement si l'ordinateur actif a un vrai assistant (`mode: live`) : en `mock`, il ne répondrait
     * que des phrases factices. Demandé une fois par ordinateur, dès qu'il répond.
     */
    private fun refreshAssistant(link: Link) {
        val computer = link.computer
        if (computer == null) {
            assistantCheckedFor = null
            return setAssistantAvailable(false)
        }
        if (link.state != Link.State.ONLINE || assistantCheckedFor == computer.id) return
        assistantCheckedFor = computer.id
        lifecycleScope.launch {
            val live = runCatching { BackendClient(this@MainActivity).identity().mode == "live" }.getOrDefault(false)
            setAssistantAvailable(live)
        }
    }

    private fun setAssistantAvailable(available: Boolean) {
        railItems.getValue(Mode.CHAT).view.visibility = if (available) View.VISIBLE else View.GONE
        if (!available && current == Mode.CHAT) show(Mode.HOME)
    }

    /** Ordinateur actif renommé : nom à jour dans la barre latérale. */
    fun refreshLink() = showLink(link.copy(computer = Computers.active(this)))

    /**
     * L'ordinateur piloté a changé (choisi dans les Réglages, ou bascule automatique) : chaque écran est
     * recréé pour relire deck, agenda, tâches et documents de cet ordinateur. Focus et sons continuent.
     */
    fun onComputerChanged(computer: Computer?, automatic: Boolean = false) {
        IconCache.clear()
        assistantCheckedFor = null
        documentPanel.close()
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        fm.fragments.forEach { tx.remove(it) }
        tx.commitNow()
        show(current)
        showLink(Link(computer, Link.State.OFFLINE))
        startChannel()
        if (automatic && computer != null) showBanner(getString(R.string.computer_switched, computer.name), 6)
    }

    /**
     * Clavier. Par défaut l'écran ne bouge pas quand il s'ouvre (adjustNothing) : les fenêtres de saisie s'affichent
     * au-dessus de lui (showForTyping) sans tasser l'Accueil derrière. Pour un champ de l'écran lui-même (Assistant,
     * recherche d'applis, éditeur de touches), l'écran se réduit au-dessus du clavier et la barre latérale se cache,
     * plutôt que d'être coupée.
     */
    private fun setUpKeyboard() {
        val rail = findViewById<View>(R.id.rail)
        val content = findViewById<View>(android.R.id.content)
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            window.setSoftInputMode(
                if (focused is EditText) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            )
        }
        content.viewTreeObserver.addOnGlobalLayoutListener {
            val keyboard = content.height in 1 until window.decorView.height - (150 * resources.displayMetrics.density).toInt()
            val visibility = if (keyboard) View.GONE else View.VISIBLE
            if (rail.visibility != visibility) rail.visibility = visibility
        }
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
                        (supportFragmentManager.findFragmentByTag(Mode.SETTINGS.name) as? SettingsFragment)?.onLinkChanged()
                    }
                }
            }
            .showImmersive()
    }

    // Temps restant du Pomodoro à la place du libellé Focus, visible depuis n'importe quel écran.
    private val focusListener: (FocusTimer.State) -> Unit = { state ->
        val label = railItems.getValue(Mode.FOCUS).label
        val active = state.running || state.remainingMs < state.durationMs
        label.text = if (active) FocusTimer.format(state.remainingMs) else getString(R.string.mode_focus)
    }

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

    /** « Pense à boire » (carte Eau de l'Accueil) : bandeau et voix, sur la tablette, ordinateur éteint compris. */
    private fun remindWater() {
        if (!Water.reminderDue(this)) return
        val state = Water.state(this)
        showBanner(getString(R.string.water_remind_banner, state.glasses, state.goal), 30)
        Speaker.speak(getString(R.string.water_remind_text))
    }

    private fun onBattery(state: Tablet.Battery) {
        if (state.low && !lowBatteryWarned) showBanner(getString(R.string.tablet_battery_low, state.level), 10)
        lowBatteryWarned = state.low || (lowBatteryWarned && !state.charging)
        (supportFragmentManager.findFragmentByTag(Mode.SETTINGS.name) as? SettingsFragment)?.onBattery(state)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MODE, current.name)
    }

    override fun onDestroy() {
        documentPanel.close()
        if (isFinishing) NoisePlayer.stop()
        voice.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /**
     * Toucher hors du champ en cours de saisie (et de sa ligne : bouton Envoyer, Ajouter…) ferme le clavier :
     * les barres système sont masquées, il n'y a pas de bouton Retour visible pour le faire.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) Screen.touched(this)
        val focused = currentFocus
        if (event.action == MotionEvent.ACTION_DOWN && focused is EditText) {
            val zone = Rect()
            ((focused.parent as? View) ?: focused).getGlobalVisibleRect(zone)
            if (!zone.contains(event.rawX.toInt(), event.rawY.toInt())) hideKeyboard()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val up = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            else -> return super.dispatchKeyEvent(event)
        }
        val handler = supportFragmentManager.findFragmentByTag(current.name) as? VolumeKeyHandler
            ?: return super.dispatchKeyEvent(event)
        // On consomme aussi le relâchement pour que la tablette ne change pas son propre volume.
        return if (event.action == KeyEvent.ACTION_DOWN) handler.onVolumeKey(up) else true
    }

    /** Nombre de tâches non faites, en pastille sur l'icône Accueil (mis à jour par la carte Tâches). */
    fun setPendingTasks(count: Int) {
        val badge = railItems.getValue(Mode.HOME).badge
        badge.text = count.toString()
        badge.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    /** Ouvre un document de l'ordinateur dans le panneau latéral, à côté du mode courant. */
    /**
     * Dans Partage : en grand, sans vue partagée (la liste des fichiers à côté ne sert à rien). Envoyé par l'ordinateur
     * pendant un autre mode (Assistant, Accueil…) : à côté, pour garder les deux sous les yeux.
     */
    fun openDocument(entry: DocEntry) {
        documentPanel.open(entry, expand = current == Mode.DOCS, splittable = current != Mode.DOCS)
    }

    /** Affiche dans le panneau latéral la page ouverte dans le navigateur de l'ordinateur. */
    fun openComputerPage() {
        documentPanel.openComputerPage(expand = current == Mode.DOCS, splittable = current != Mode.DOCS)
    }

    // --- Utilisé par RemoteChannel (ordres de l'ordinateur)

    val modeName: String
        get() = current.name.lowercase(Locale.ROOT)

    val documentPath: String?
        get() = documentPanel.currentPath

    fun showMode(name: String) {
        val mode = if (name == "tasks") Mode.HOME else Mode.valueOf(name.uppercase(Locale.ROOT))
        // Assistant masqué (pas de vrai assistant sur cet ordinateur) : l'Accueil à la place.
        show(if (mode == Mode.CHAT && railItems.getValue(Mode.CHAT).view.visibility != View.VISIBLE) Mode.HOME else mode)
    }

    /** Deck sur sa page Réunion, pour couper le micro ou raccrocher sans chercher. */
    fun showMeetingControls() {
        show(Mode.DECK)
        // Le deck vient peut-être d'être créé : sa vue doit exister avant de choisir la page.
        supportFragmentManager.executePendingTransactions()
        (supportFragmentManager.findFragmentByTag(Mode.DECK.name) as? DeckFragment)?.openMeetingPage()
    }

    /** Appel entrant sur l'ordinateur (ordre `call`, voir CallOverlay). */
    fun onCall(state: String, app: String, caller: String?, video: Boolean) = callOverlay.onCall(state, app, caller, video)

    /** Deck sur la page de l'appli (Discord) : micro, sourdine, Quitter l'appel. */
    fun showAppControls(app: String) {
        show(Mode.DECK)
        supportFragmentManager.executePendingTransactions()
        (supportFragmentManager.findFragmentByTag(Mode.DECK.name) as? DeckFragment)?.openAppPage(app)
    }

    fun closeDocument() {
        documentPanel.close()
    }

    fun requestMicPermission() = micPermission.launch(android.Manifest.permission.RECORD_AUDIO)

    /** Écran de bienvenue (WiFi, installation, connexion), depuis un mode sans ordinateur. */
    fun showWelcome() = welcome.show()

    /** Bandeau en haut de l'écran, par-dessus le mode courant ; un toucher le ferme. */
    fun showBanner(text: String, seconds: Int) {
        banner.text = text
        banner.visibility = View.VISIBLE
        banner.removeCallbacks(hideBanner)
        banner.postDelayed(hideBanner, seconds * 1_000L)
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Ferme le clavier et rend le focus au conteneur, pour qu'aucun champ ne le rouvre. */
    private fun hideKeyboard() {
        val focused = currentFocus ?: return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(focused.windowToken, 0)
        findViewById<View>(R.id.mode_container).requestFocus()
    }

    private fun show(mode: Mode) {
        // Le champ d'un écran masqué garderait sinon le clavier (et la saisie) par-dessus le nouvel écran.
        hideKeyboard()
        // Document ouvert : en grand dans Partage ; ailleurs à côté, pour laisser voir le mode choisi.
        if (::documentPanel.isInitialized && documentPanel.visibility == View.VISIBLE) {
            documentPanel.setExpanded(mode == Mode.DOCS, splittable = mode != Mode.DOCS)
        }
        val changed = current != mode
        current = mode
        val fm = supportFragmentManager
        // Un écran ajouté juste avant (ex. recréé par onComputerChanged) doit exister pour être masqué.
        fm.executePendingTransactions()
        val tx = fm.beginTransaction()
        if (changed) tx.setCustomAnimations(R.animator.mode_in, 0)
        fm.fragments.forEach { tx.hide(it) }
        // Sans ordinateur, un mode qui en a besoin n'est pas créé (caméra, requêtes) : renderNoComputer le remplace.
        val needsComputer = mode.connect != null && Computers.all(this).isEmpty()
        val existing = fm.findFragmentByTag(mode.name)
        if (existing != null) tx.show(existing) else if (!needsComputer) tx.add(R.id.mode_container, create(mode), mode.name)
        tx.commit()

        railItems.forEach { (m, item) -> item.view.isSelected = m == mode }
        if (changed) railItems[mode]?.let { Look.pop(it.view.findViewById(R.id.rail_icon)) }
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
        Mode.CHAT -> ChatFragment()
        Mode.DOCS -> DocsFragment()
        Mode.FOCUS -> FocusFragment()
        Mode.CAMERA -> CameraFragment()
        Mode.SETTINGS -> SettingsFragment()
    }

    companion object {
        private const val STATE_MODE = "mode"
    }
}
