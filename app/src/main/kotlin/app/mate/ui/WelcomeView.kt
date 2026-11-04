package app.mate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.mate.BuildConfig
import app.mate.Computer
import app.mate.MainActivity
import app.mate.R
import app.mate.installers.InstallerCard
import app.mate.installers.InstallerService
import app.mate.net.Discovery
import app.mate.net.FoundComputer
import app.mate.net.Wifi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Premier lancement, par-dessus tout l'écran tant qu'aucun ordinateur n'est appairé : 1. WiFi, 2. adresse de la tablette
 * (avec son code) où télécharger Mate sur l'ordinateur (lancé, il ouvre sa page locale), 3. ordinateurs trouvés sur le WiFi (recherche répétée),
 * touchés pour afficher le code à saisir dans cette page.
 * « Plus tard » laisse l'heure et le Focus utilisables ; les autres modes proposent d'y revenir.
 */
class WelcomeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private lateinit var activity: MainActivity
    private lateinit var adder: ComputerAdder
    private lateinit var wifiPicker: WifiPicker
    private val wifiStep: TextView
    private val wifiStatus: TextView
    private val installStep: TextView
    private val installText: TextView
    private val installUrl: TextView
    private val connectStep: TextView
    private val found: LinearLayout
    private val searchStatus: TextView
    private var search: Job? = null
    private var progressWatch: Job? = null
    private var installerReady = false
    private var shown: List<FoundComputer> = emptyList()

    init {
        LayoutInflater.from(context).inflate(R.layout.view_welcome, this, true)
        setBackgroundColor(themeColor(context, R.attr.c_bg))
        // Rien ne doit passer à l'écran du dessous.
        isClickable = true
        wifiStep = findViewById(R.id.welcome_step_wifi)
        wifiStatus = findViewById(R.id.welcome_wifi_status)
        installStep = findViewById(R.id.welcome_step_install)
        installText = findViewById(R.id.welcome_install_text)
        installUrl = findViewById(R.id.welcome_install_url)
        connectStep = findViewById(R.id.welcome_step_connect)
        found = findViewById(R.id.welcome_found)
        searchStatus = findViewById(R.id.welcome_search_status)
    }

    /** `onAdded` : ordinateur appairé (MainActivity ferme cet écran et recrée les modes). */
    fun attach(activity: MainActivity, onAdded: (Computer) -> Unit) {
        this.activity = activity
        adder = ComputerAdder(activity, activity.lifecycleScope, onAdded)
        wifiPicker = WifiPicker(activity, activity.lifecycleScope) {
            renderWifi()
            renderInstaller()
        }
        findViewById<Button>(R.id.welcome_wifi).setOnClickListener { wifiPicker.show() }
        findViewById<Button>(R.id.welcome_manual).setOnClickListener { adder.manual() }
        findViewById<Button>(R.id.welcome_later).setOnClickListener { hide() }
    }

    fun show() {
        // Un nouvel affichage = un nouveau code ; rappelé alors qu'il est déjà là, le code lu ne change pas.
        if (visibility != View.VISIBLE && !BuildConfig.TRIAL) InstallerService.newCode()
        visibility = View.VISIBLE
        renderWifi()
        startSearch()
        watchInstaller()
    }

    fun hide() {
        visibility = View.GONE
        search?.cancel()
        progressWatch?.cancel()
        // Le code lu sur cet écran ne vaut plus rien : un ordinateur est appairé, ou « Plus tard ».
        InstallerService.retireCode()
    }

    /** Recherche toutes les 4 s tant que l'écran est affiché : l'installation sur l'ordinateur peut prendre du temps. */
    private fun startSearch() {
        search?.cancel()
        search = activity.lifecycleScope.launch {
            var round = 0
            while (true) {
                renderWifi()
                // Installeur pas encore là (WiFi arrivé depuis, GitHub injoignable) : nouvel essai toutes les ~20 s.
                if (!installerReady && !BuildConfig.TRIAL && round % 5 == 4) syncInstaller()
                round++
                if (!BuildConfig.TRIAL) {
                    installerReady = InstallerService.ready(context)
                    renderInstaller()
                }
                if (Wifi.current(context) == null) {
                    showFound(emptyList())
                    searchStatus.setText(R.string.welcome_need_wifi)
                } else {
                    if (shown.isEmpty()) searchStatus.setText(R.string.welcome_searching)
                    showFound(runCatching { Discovery.find(timeoutMs = 2_000) }.getOrDefault(emptyList()))
                    searchStatus.text = if (shown.isEmpty()) context.getString(R.string.welcome_searching) else ""
                }
                delay(4_000)
            }
        }
    }

    private fun showFound(computers: List<FoundComputer>) {
        // Liste inchangée : on ne reconstruit pas (un toucher en cours serait perdu).
        if (computers.map { it.id to it.url } == shown.map { it.id to it.url }) return
        shown = computers
        found.removeAllViews()
        for (c in computers) {
            val row = LayoutInflater.from(context).inflate(R.layout.item_window, found, false)
            row.findViewById<TextView>(R.id.window_title).text = c.name
            row.findViewById<TextView>(R.id.window_meta).text =
                listOfNotNull(
                    ComputerAdder.osLabel(context, c.os),
                    context.getString(if (c.outdated) R.string.computer_outdated else R.string.welcome_tap),
                ).joinToString(" · ")
            row.setOnClickListener { adder.pair(c.url, c.name) }
            (row.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
            found.addView(row)
        }
        // Un ordinateur répond : le logiciel y est installé.
        markStep(installStep, "2", done = computers.isNotEmpty())
        markStep(connectStep, "3", done = computers.isNotEmpty())
    }

    /** Téléchargement de l'installeur lancé tout de suite, avancement suivi à l'écran ; l'appli d'essai ne sert pas le port : elle renvoie à la stable. */
    private fun watchInstaller() {
        progressWatch?.cancel()
        if (BuildConfig.TRIAL) {
            installText.setText(R.string.welcome_install_trial)
            installUrl.visibility = View.GONE
            return
        }
        syncInstaller()
        progressWatch = activity.lifecycleScope.launch {
            InstallerService.progress.collect { percent ->
                if (percent == null) installerReady = InstallerService.ready(context)
                renderInstaller(percent)
            }
        }
    }

    /** Dans la portée de l'activité : fermer Bienvenue (« Plus tard ») n'interrompt pas le téléchargement. */
    private fun syncInstaller() {
        activity.lifecycleScope.launch { InstallerService.sync(context) }
    }

    private fun renderInstaller(percent: Int? = InstallerService.progress.value) {
        if (BuildConfig.TRIAL) return
        val card = InstallerCard.of(Wifi.current(context) != null, installerReady, percent, InstallerService.link())
        installUrl.visibility = View.VISIBLE
        installUrl.text = when (card) {
            is InstallerCard.NoWifi -> context.getString(R.string.welcome_need_wifi)
            is InstallerCard.Preparing ->
                if (card.percent == null) context.getString(R.string.welcome_install_preparing)
                else context.getString(R.string.welcome_install_preparing_percent, card.percent)
            is InstallerCard.Ready -> card.link
        }
        installUrl.setTextColor(themeColor(context, if (card is InstallerCard.Ready) R.attr.c_accent else R.attr.c_text_dim))
    }

    private fun renderWifi() {
        val name = Wifi.current(context)
        wifiStatus.text = when {
            name == null -> context.getString(R.string.wifi_off)
            name.isEmpty() -> context.getString(R.string.wifi_current_unnamed)
            else -> context.getString(R.string.wifi_current, name)
        }
        val color = themeColor(context, if (name == null) R.attr.c_text_dim else R.attr.c_ok)
        wifiStatus.setTextColor(color)
        wifiStatus.compoundDrawablesRelative[0]?.mutate()?.setTint(color)
        markStep(wifiStep, "1", done = name != null)
    }

    /** Étape faite : pastille verte avec ✓ à la place du numéro. */
    private fun markStep(step: TextView, number: String, done: Boolean) {
        step.text = if (done) "✓" else number
        val color = themeColor(context, if (done) R.attr.c_ok else R.attr.c_accent)
        step.setTextColor(color)
        step.backgroundTintList = ColorStateList.valueOf(themeColor(context, if (done) R.attr.c_ok_soft else R.attr.c_accent_soft))
    }
}
