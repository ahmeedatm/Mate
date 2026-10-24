package com.unowhy.companion

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
import com.unowhy.companion.focus.FocusTimer
import com.unowhy.companion.focus.NoisePlayer
import com.unowhy.companion.kiosk.Kiosk
import com.unowhy.companion.remote.RemoteChannel
import com.unowhy.companion.net.DocEntry
import com.unowhy.companion.net.Link
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/** Les écrans qui veulent les boutons de volume de la tablette (ex. le deck pilote le son de l'ordinateur). */
interface VolumeKeyHandler {
    fun onVolumeKey(up: Boolean): Boolean
}

/** Unique Activity : plein écran, écran toujours allumé, un Fragment par mode + panneau document. */
class MainActivity : AppCompatActivity() {

    /** Les tâches sont sur l'Accueil : l'ancien mode "tasks" (ordinateur, extension Claude) y mène. */
    private enum class Mode(val icon: Int, val label: Int) {
        HOME(R.drawable.ic_home, R.string.mode_home),
        DECK(R.drawable.ic_deck, R.string.mode_deck),
        CHAT(R.drawable.ic_chat, R.string.mode_chat),
        DOCS(R.drawable.ic_folder, R.string.mode_docs),
        FOCUS(R.drawable.ic_timer, R.string.mode_focus),
        CAMERA(R.drawable.ic_videocam, R.string.mode_camera),
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
    private var channel: Job? = null
    private val computerDialogs by lazy { ComputerAdder(this, lifecycleScope) { onComputerChanged(it) } }
    /** Dernier état de la liaison, repris par les Réglages et le sélecteur d'ordinateur. */
    var link = Link(null, Link.State.NONE)
        private set
    private val hideBanner = Runnable { banner.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
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
            findViewById<View>(R.id.mode_container).visibility = if (expanded) View.GONE else View.VISIBLE
        }
        statusDot = findViewById(R.id.status_dot)
        statusName = findViewById(R.id.status_name)
        findViewById<View>(R.id.status).setOnClickListener { showComputerPicker() }
        banner = findViewById(R.id.remote_banner)
        banner.setOnClickListener { hideBanner.run() }

        // Retour ne quitte jamais l'appli : il ferme le document ouvert, sinon ramène à l'accueil.
        onBackPressedDispatcher.addCallback(this) {
            when {
                documentPanel.visibility == View.VISIBLE -> documentPanel.close()
                current != Mode.HOME -> show(Mode.HOME)
            }
        }

        val restored = savedInstanceState?.getString(STATE_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
        // Premier lancement : aucun ordinateur, on commence par l'appairage.
        show(restored ?: if (Computers.all(this).isEmpty()) Mode.SETTINGS else Mode.HOME)
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
            Link.State.ONLINE -> R.color.ok
            Link.State.UNAUTHORIZED -> R.color.warn
            else -> R.color.ko
        }
        statusDot.background.mutate().setTint(ContextCompat.getColor(this, color))
        statusName.text = link.computer?.name ?: getString(R.string.no_computer_short)
        val state = when (link.state) {
            Link.State.ONLINE -> R.string.online
            Link.State.UNAUTHORIZED -> R.string.unauthorized
            Link.State.OFFLINE -> R.string.offline
            Link.State.NONE -> R.string.no_computer
        }
        findViewById<View>(R.id.status).contentDescription = getString(state)
        (supportFragmentManager.findFragmentByTag(Mode.SETTINGS.name) as? SettingsFragment)?.onLinkChanged()
    }

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
        startChannel()
        if (automatic && computer != null) showBanner(getString(R.string.computer_switched, computer.name), 6)
    }

    /** Toucher la pastille de connexion : changer d'ordinateur (ou renommer l'actif) sans passer par les Réglages. */
    private fun showComputerPicker() {
        val computers = Computers.all(this)
        if (computers.isEmpty()) return show(Mode.SETTINGS)
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
            .show()
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MODE, current.name)
    }

    override fun onDestroy() {
        documentPanel.close()
        if (isFinishing) NoisePlayer.stop()
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
        show(if (name == "tasks") Mode.HOME else Mode.valueOf(name.uppercase(Locale.ROOT)))
    }

    /** Deck sur sa page Réunion, pour couper le micro ou raccrocher sans chercher. */
    fun showMeetingControls() {
        show(Mode.DECK)
        // Le deck vient peut-être d'être créé : sa vue doit exister avant de choisir la page.
        supportFragmentManager.executePendingTransactions()
        (supportFragmentManager.findFragmentByTag(Mode.DECK.name) as? DeckFragment)?.openMeetingPage()
    }

    fun closeDocument() {
        documentPanel.close()
    }

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
        current = mode
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        fm.fragments.forEach { tx.hide(it) }
        val existing = fm.findFragmentByTag(mode.name)
        if (existing != null) tx.show(existing) else tx.add(R.id.mode_container, create(mode), mode.name)
        tx.commit()

        railItems.forEach { (m, item) -> item.view.isSelected = m == mode }
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
