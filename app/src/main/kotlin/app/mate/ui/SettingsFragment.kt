package app.mate.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.mate.AnimFamily
import app.mate.BuildConfig
import app.mate.Computer
import app.mate.Computers
import app.mate.MainActivity
import app.mate.Mode
import app.mate.Prefs
import app.mate.R
import app.mate.audio.Speaker
import app.mate.audio.VoiceChoice
import app.mate.device.Accessibility
import app.mate.device.Bluetooth
import app.mate.device.Screen
import app.mate.device.Tablet
import app.mate.installers.BackendPlan
import app.mate.installers.BackendRelease
import app.mate.installers.CompanionUpdater
import app.mate.installers.InstallerService
import app.mate.kiosk.FreshStart
import app.mate.kiosk.Kiosk
import app.mate.kiosk.UpdatePlan
import app.mate.kiosk.Updater
import app.mate.net.AppUpdate
import app.mate.net.BackendClient
import app.mate.net.Essai
import app.mate.net.Identity
import app.mate.net.Link
import app.mate.net.UpdateSource
import app.mate.net.Wifi
import app.mate.trial.EssaiBackend
import app.mate.trial.EssaiPlan
import app.mate.trial.Trial
import app.mate.ui.topbar.TopHeader
import app.mate.voice.WakeModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment :
    Fragment(),
    TopHeader {
    /** Le titre (appui long : code technicien), dans le bandeau du haut. */
    override var topHeader: View? = null
        private set

    private lateinit var kioskCard: View
    private lateinit var tabs: List<Pair<View, View>>

    /** Onglet Personnalisation affiché (sinon Configuration) ; gardé quand un thème recrée l'écran. */
    private var optionsTab = true
    private lateinit var kioskStatus: TextView
    private lateinit var kioskActions: View
    private lateinit var allowInstalls: SwitchCompat
    private lateinit var allowInstallsHint: View
    private lateinit var bluetooth: SwitchCompat
    private lateinit var bluetoothHint: View
    private lateinit var landscapeLocked: SwitchCompat
    private lateinit var kioskSecurity: View
    private lateinit var exitLock: SwitchCompat
    private lateinit var powerMenu: SwitchCompat
    private lateinit var meetingsCard: View
    private lateinit var autoJoin: SwitchCompat
    private lateinit var autoJoinHint: TextView
    private lateinit var computers: LinearLayout
    private lateinit var wifiStatus: TextView
    private lateinit var battery: TextView
    private lateinit var batteryHint: TextView
    private lateinit var brightness: SeekBar
    private lateinit var volume: SeekBar
    private lateinit var nightDim: SwitchCompat
    private lateinit var notifDiscreet: SwitchCompat
    private lateinit var meetingAlert: SwitchCompat
    private lateinit var blockOnComputerLock: SwitchCompat
    private lateinit var lockCode: Button
    private lateinit var wakeWord: SwitchCompat
    private lateinit var wakeStatus: TextView
    private lateinit var weatherLocation: SwitchCompat
    private lateinit var talkBack: SwitchCompat
    private lateinit var tabletInfo: TextView
    private lateinit var updatesStatus: TextView
    private lateinit var updates: LinearLayout
    private lateinit var essaisTitle: TextView
    private lateinit var essais: LinearLayout
    private lateinit var switchApp: Button
    private lateinit var companionStatus: TextView
    private lateinit var companionUpdate: Button
    private var installing = false
    private var companionUpdating = false
    private val main get() = requireActivity() as MainActivity
    private val adder by lazy { ComputerAdder(requireActivity(), main.lifecycleScope) { main.onComputerChanged(it) } }
    private val wifi by lazy { WifiPicker(requireActivity(), main.lifecycleScope) { if (view != null) renderWifi() } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        // Interrupteurs rendus depuis Prefs ou l'appareil : sans état de vue gardé. Sinon, à la recréation (choix d'un
        // thème), Android y remet l'ancien état après le rendu et leur écouteur réécrit la valeur d'avant.
        forgetSwitchStates(view)
        tabs = listOf(
            view.findViewById<View>(R.id.settings_tab_setup) to view.findViewById(R.id.settings_setup),
            view.findViewById<View>(R.id.settings_tab_options) to view.findViewById(R.id.settings_options),
        )
        tabs.forEachIndexed { i, (tab, _) -> tab.setOnClickListener { showTab(options = i == 1) } }
        // Personnalisation d'abord : c'est l'onglet qu'on ouvre le plus.
        showTab(options = state?.getBoolean(KEY_OPTIONS_TAB) ?: true)
        computers = view.findViewById(R.id.settings_computers)
        view.findViewById<Button>(R.id.settings_add_computer).setOnClickListener { adder.search() }
        view.findViewById<Button>(R.id.settings_plugins).setOnClickListener { main.showPlugins() }
        view.findViewById<Button>(R.id.settings_add_manual).setOnClickListener { adder.manual() }
        kioskCard = view.findViewById(R.id.settings_kiosk_card)
        topHeader = view.findViewById<View>(R.id.settings_title).apply { setOnLongClickListener { askTechCode(); true } }
        kioskStatus = view.findViewById(R.id.settings_kiosk_status)
        kioskActions = view.findViewById(R.id.settings_kiosk_actions)
        allowInstalls = view.findViewById(R.id.settings_allow_installs)
        allowInstallsHint = view.findViewById(R.id.settings_allow_installs_hint)
        bluetooth = view.findViewById(R.id.settings_bluetooth)
        bluetoothHint = view.findViewById(R.id.settings_bluetooth_hint)
        landscapeLocked = view.findViewById(R.id.settings_landscape_locked)
        kioskSecurity = view.findViewById(R.id.settings_kiosk_security)
        exitLock = view.findViewById(R.id.settings_exit_lock)
        powerMenu = view.findViewById(R.id.settings_power_menu)
        meetingsCard = view.findViewById(R.id.settings_meetings_card)
        autoJoin = view.findViewById(R.id.settings_autojoin)
        autoJoinHint = view.findViewById(R.id.settings_autojoin_hint)
        wifiStatus = view.findViewById(R.id.settings_wifi_status)
        view.findViewById<Button>(R.id.settings_wifi).setOnClickListener { wifi.show() }
        view.findViewById<Button>(R.id.settings_tablet_remote).setOnClickListener { openOnComputer() }
        view.findViewById<ImageView>(R.id.settings_remote_icon)
            .setImageDrawable(Look.badge(requireContext(), R.drawable.ic_laptop, themeColor(requireContext(), R.attr.c_hue_violet), sizeDp = 48, iconDp = 26))
        renderRemote()
        updatesStatus = view.findViewById(R.id.settings_updates_status)
        updates = view.findViewById(R.id.settings_updates)
        essaisTitle = view.findViewById(R.id.settings_essais_title)
        essais = view.findViewById(R.id.settings_essais)
        companionStatus = view.findViewById(R.id.settings_companion_status)
        companionUpdate = view.findViewById(R.id.settings_companion_update)
        view.findViewById<Button>(R.id.settings_updates_check).setOnClickListener { checkUpdates() }
        switchApp = view.findViewById(R.id.settings_switch_app)
        switchApp.setText(if (BuildConfig.TRIAL) R.string.trial_back else R.string.trial_open)
        switchApp.setOnClickListener { if (BuildConfig.TRIAL) backToStable() else openTrial() }
        view.findViewById<View>(R.id.settings_trial_hint).visibility = if (BuildConfig.TRIAL) View.VISIBLE else View.GONE
        battery = view.findViewById(R.id.settings_battery)
        batteryHint = view.findViewById(R.id.settings_battery_hint)
        brightness = view.findViewById(R.id.settings_brightness)
        volume = view.findViewById(R.id.settings_volume)
        nightDim = view.findViewById(R.id.settings_night_dim)
        notifDiscreet = view.findViewById(R.id.settings_notif_discreet)
        meetingAlert = view.findViewById(R.id.settings_meeting_alert)
        blockOnComputerLock = view.findViewById(R.id.settings_block_on_computer_lock)
        lockCode = view.findViewById(R.id.settings_lock_code)
        wakeWord = view.findViewById(R.id.settings_wake_word)
        wakeStatus = view.findViewById(R.id.settings_wake_word_status)
        weatherLocation = view.findViewById(R.id.settings_weather_location)
        tabletInfo = view.findViewById(R.id.settings_tablet_info)
        setUpTablet()
        setUpThemes(view.findViewById(R.id.settings_themes))
        setUpRail(view)
        setUpAccessibility(view)
        setUpReadingAids(view)
        setUpAnimations(view)
        renderComputers()
        renderWifi()
        renderSwitchApp()
        checkCompanion()

        view.findViewById<Button>(R.id.settings_licences).setOnClickListener { showText(R.string.about_licences, "licences.txt", monospace = true) }
        view.findViewById<Button>(R.id.settings_privacy).setOnClickListener { showText(R.string.about_privacy, "confidentialite.txt", monospace = false) }
        view.findViewById<Button>(R.id.settings_kiosk_reboot).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.kiosk_reboot_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    if (!Kiosk.reboot(requireContext())) main.showBanner(getString(R.string.kiosk_reboot_failed), 6)
                }
                .showImmersive()
        }
        view.findViewById<Button>(R.id.settings_kiosk_exit).setOnClickListener {
            Kiosk.exit(requireActivity())
            renderKiosk()
        }
        view.findViewById<Button>(R.id.settings_kiosk_remove).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.kiosk_remove_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm) { _, _ -> Kiosk.remove(requireActivity()); renderKiosk() }
                .showImmersive()
        }
        view.findViewById<Button>(R.id.settings_fresh_start).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.fresh_start)
                .setMessage(R.string.fresh_start_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.fresh_start) { _, _ -> freshStart() }
                .showImmersive()
        }
        renderKiosk()
        loadAutoJoin()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_OPTIONS_TAB, optionsTab)
    }

    /** Configuration (ordinateurs, WiFi, plugins, démo, kiosque…) ou Personnalisation (thème, options) ; remonte en haut. */
    private fun showTab(options: Boolean) {
        optionsTab = options
        tabs.forEachIndexed { i, (tab, cards) ->
            val on = (i == 1) == options
            tab.isSelected = on
            cards.visibility = if (on) View.VISIBLE else View.GONE
        }
        view?.findViewById<ScrollView>(R.id.settings_scroll)?.scrollTo(0, 0)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        // Carte Kiosque : seulement le temps de la visite du technicien.
        if (hidden) kioskCard.visibility = View.GONE
        if (!hidden) {
            renderComputers()
            renderWifi()
            renderTablet()
            renderKiosk()
            renderSwitchApp()
            loadAutoJoin()
            renderTalkBack()
            renderRemote()
        }
    }

    override fun onResume() {
        super.onResume()
        renderTalkBack()
    }

    /** État de la liaison changé (MainActivity) : ligne de l'ordinateur actif à jour. */
    fun onLinkChanged() {
        if (view == null) return
        renderComputers()
        if (main.link.state == Link.State.ONLINE && !autoJoin.isEnabled) loadAutoJoin()
    }

    // --- Ordinateurs

    private fun renderComputers() {
        val ctx = requireContext()
        computers.removeAllViews()
        val all = Computers.all(ctx)
        if (all.isEmpty()) {
            computers.addView(TextView(ctx).apply {
                text = getString(R.string.computers_empty, InstallerService.address() ?: getString(R.string.install_address_no_wifi))
                textSize = 16f
                setPadding(0, dp(8), 0, 0)
            })
            return
        }
        val active = Computers.active(ctx)?.id
        val link = main.link
        for (c in all) {
            val row = layoutInflater.inflate(R.layout.item_computer, computers, false)
            val isActive = c.id == active
            val name = row.findViewById<TextView>(R.id.computer_name)
            name.text = c.name
            name.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
            ImageViewCompat.setImageTintList(
                row.findViewById<ImageView>(R.id.computer_icon),
                themeColorList(ctx, if (isActive) R.attr.c_accent else R.attr.c_text_dim),
            )
            val state = if (isActive) getString(stateLabel(link.state)) else null
            row.findViewById<TextView>(R.id.computer_meta).text =
                listOfNotNull(ComputerAdder.osLabel(ctx, c.os), c.address, state).joinToString(" · ")
            row.setOnClickListener {
                when {
                    !isActive -> {
                        Computers.setActive(ctx, c.id)
                        main.onComputerChanged(Computers.active(ctx))
                    }
                    // Jeton refusé (ordinateur réinstallé) : nouvel appairage, même ordinateur.
                    // Appairé en HTTP avant le WiFi chiffré : réappairage en HTTPS (`pairUrl`).
                    link.state == Link.State.UNAUTHORIZED -> adder.pair(c.pairUrl, c.name)
                }
            }
            val rename = { adder.rename(c) { main.refreshLink(); renderComputers() } }
            row.setOnLongClickListener { rename(); true }
            row.findViewById<View>(R.id.computer_rename).setOnClickListener { rename() }
            row.findViewById<View>(R.id.computer_delete).setOnClickListener { forget(c) }
            computers.addView(row)
        }
    }

    private fun stateLabel(state: Link.State) = when (state) {
        Link.State.ONLINE -> R.string.computer_online
        Link.State.UNAUTHORIZED -> R.string.computer_unauthorized
        else -> R.string.computer_offline
    }

    private fun forget(c: Computer) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.computer_forget_confirm, c.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.computer_forget) { _, _ ->
                val wasActive = Computers.active(requireContext())?.id == c.id
                Computers.remove(requireContext(), c.id)
                if (wasActive) main.onComputerChanged(Computers.active(requireContext())) else renderComputers()
            }
            .showImmersive()
    }

    /** L'option vit sur l'ordinateur : c'est lui qui ouvre Teams, même tablette en veille. */
    /** Carte Réunions Teams : seulement avec un ordinateur qui a Teams (sinon l'option ne sert à rien). */
    private fun loadAutoJoin() {
        if (Computers.active(requireContext()) == null) {
            meetingsCard.visibility = View.GONE
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val client = BackendClient(requireContext())
                if (!client.deck.homeFeatures().meetings) {
                    meetingsCard.visibility = View.GONE
                    return@launch
                }
                meetingsCard.visibility = View.VISIBLE
                showAutoJoin(client.calendar.autoJoin())
                autoJoin.isEnabled = true
                autoJoinHint.setText(R.string.autojoin_hint)
            } catch (e: Exception) {
                // Ordinateur injoignable : la carte reste telle quelle (affichée si elle l'était).
                autoJoin.isEnabled = false
                autoJoinHint.text = errorText(e)
            }
        }
    }

    /** Coche sans déclencher l'enregistrement (seul un toucher enregistre). */
    private fun showAutoJoin(enabled: Boolean) {
        autoJoin.setOnCheckedChangeListener(null)
        autoJoin.isChecked = enabled
        autoJoin.setOnCheckedChangeListener { _, checked -> saveAutoJoin(checked) }
    }

    private fun saveAutoJoin(enabled: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                BackendClient(requireContext()).calendar.setAutoJoin(enabled)
                autoJoinHint.setText(R.string.autojoin_hint)
            } catch (e: Exception) {
                showAutoJoin(!enabled)
                autoJoinHint.text = errorText(e)
            }
        }
    }

    private fun renderWifi() {
        val ctx = requireContext()
        // Wifi.current interroge ConnectivityManager et WifiManager (binder) : hors thread principal.
        viewLifecycleOwner.lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { Wifi.current(ctx) }
            if (view == null) return@launch
            wifiStatus.text = when {
                name == null -> getString(R.string.wifi_off)
                name.isEmpty() -> getString(R.string.wifi_current_unnamed)
                else -> getString(R.string.wifi_current, name)
            }
        }
    }

    // --- Accessibilité (COMP-50) : TalkBack et taille du texte, que le kiosque empêche de régler dans Android

    private fun setUpAccessibility(view: View) {
        val ctx = requireContext()
        talkBack = view.findViewById(R.id.settings_talkback)
        val hint = view.findViewById<TextView>(R.id.settings_talkback_hint)
        val installed = Accessibility.talkBackInstalled(ctx)
        val allowed = Accessibility.canConfigure(ctx)
        talkBack.isEnabled = installed && allowed
        if (!installed) hint.setText(R.string.a11y_talkback_missing) else if (!allowed) hint.setText(R.string.a11y_talkback_locked)
        renderTalkBack()

        val row = view.findViewById<LinearLayout>(R.id.settings_text_sizes)
        val current = Prefs.textScale(ctx)
        for ((scale, label) in TEXT_SIZES) {
            val chip = Button(ctx, null, 0, R.style.Chip).apply {
                setText(label)
                isSelected = scale == current
                setOnClickListener {
                    if (scale == current) return@setOnClickListener
                    Prefs.setTextScale(context, scale)
                    // En plus grand, les noms ne tiennent plus sous les icônes de la barre : retirés (Barre de gauche les rend).
                    if (scale == TEXT_SIZES.last().first) Prefs.setRailLabels(context, false)
                    requireActivity().recreate()
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (row.childCount > 0) marginStart = dp(10) })
        }
    }

    /**
     * État réel de TalkBack : il change aussi sans l'interrupteur (raccourci des touches de volume, Paramètres hors
     * kiosque), relu à chaque retour sur les Réglages. Écouteur retiré le temps de la relecture : rien n'est réécrit.
     */
    private fun renderTalkBack() {
        if (!::talkBack.isInitialized) return
        val ctx = requireContext()
        talkBack.setOnCheckedChangeListener(null)
        talkBack.isChecked = Accessibility.talkBackOn(ctx)
        talkBack.setOnCheckedChangeListener { _, checked -> Accessibility.setTalkBack(ctx, checked) }
    }

    // --- Tablette : luminosité, volume, batterie (cachés par le kiosque)

    /** Barre de gauche : noms sous les icônes et outils affichés, appliqués tout de suite. */
    private fun setUpRail(view: View) {
        val main = activity as? MainActivity
        view.findViewById<SwitchCompat>(R.id.settings_rail_labels).apply {
            isChecked = Prefs.railLabels(context)
            setOnCheckedChangeListener { _, on ->
                Prefs.setRailLabels(context, on)
                main?.applyRailSettings()
            }
        }
        view.findViewById<SwitchCompat>(R.id.settings_rail_talk).apply {
            isChecked = Prefs.railTalk(context)
            setOnCheckedChangeListener { _, on ->
                Prefs.setRailTalk(context, on)
                main?.applyRailSettings()
            }
        }
        val shown = Prefs.railTools(requireContext())
        mapOf(R.id.settings_rail_focus to Mode.FOCUS, R.id.settings_rail_computer to Mode.COMPUTER)
            .forEach { (id, tool) ->
                view.findViewById<SwitchCompat>(id).apply {
                    isChecked = tool in shown
                    setOnCheckedChangeListener { _, on ->
                        Prefs.setRailTool(context, tool, on)
                        main?.applyRailSettings()
                    }
                }
            }
    }

    /**
     * Thèmes en vignettes (ui/ThemePreview : chacune peinte de ses couleurs), sur une rangée. Toucher une vignette
     * applique le thème (l'activité est recréée).
     */
    private fun setUpThemes(rows: LinearLayout) {
        val ctx = requireContext()
        val current = AppTheme.current(ctx)
        val row = LinearLayout(ctx)
        for (theme in AppTheme.entries) row.addView(themeTile(theme, theme == current), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rows.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
    }

    private fun themeTile(theme: AppTheme, chosen: Boolean): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), 0, dp(4), 0)
            isClickable = true
            isFocusable = true
            isSelected = chosen
            stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(ctx, R.animator.press)
            contentDescription = getString(if (chosen) R.string.theme_tile_chosen else R.string.theme_tile, getString(theme.label))
            addView(ThemePreview(ctx, theme, chosen), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96)))
            addView(TextView(ctx).apply {
                setText(theme.label)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 14f
                setTextColor(themeColor(ctx, if (chosen) R.attr.c_accent else R.attr.c_text))
                typeface = themeFont(ctx, if (chosen) R.attr.c_font_medium else R.attr.c_font_regular)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            setOnClickListener {
                if (chosen) return@setOnClickListener
                Prefs.setTheme(ctx, theme.name)
                requireActivity().recreate()
            }
        }
    }

    /**
     * Accessibilité, aides à la lecture (ui/ReadingAids.kt) : interrupteurs sous la taille du texte. Police et animations
     * s'appliquent à la création de l'activité (AppTheme.apply, vues animées) : les changer la recrée, comme le thème.
     * Les bandeaux lus sont relus à chaque bandeau.
     */
    private fun setUpReadingAids(view: View) {
        val ctx = requireContext()
        fun recreating(id: Int, get: (android.content.Context) -> Boolean, set: (android.content.Context, Boolean) -> Unit) {
            view.findViewById<SwitchCompat>(id).apply {
                isChecked = get(ctx)
                setOnCheckedChangeListener { _, checked ->
                    set(ctx, checked)
                    requireActivity().recreate()
                }
            }
        }
        recreating(R.id.settings_legible_font, Prefs::legibleFont, Prefs::setLegibleFont)
        recreating(R.id.settings_reduce_motion, Prefs::reduceMotion, Prefs::setReduceMotion)
        view.findViewById<SwitchCompat>(R.id.settings_read_banners).apply {
            isChecked = Prefs.readBanners(ctx)
            setOnCheckedChangeListener { _, checked -> Prefs.setReadBanners(ctx, checked) }
        }
        view.findViewById<SwitchCompat>(R.id.settings_speak_actions).apply {
            isChecked = Prefs.speakActions(ctx)
            setOnCheckedChangeListener { _, checked -> Prefs.setSpeakActions(ctx, checked) }
        }
        Speaker.whenStarted { if (getView() === view) setUpVoice(view) }
    }

    /**
     * Voix et débit de la synthèse vocale (audio/Speaker), une fois le moteur démarré : une pastille par voix française
     * hors ligne, nommée par son rang (le nom du moteur ne dit rien d'utile) ; toucher enregistre et fait écouter.
     */
    private fun setUpVoice(view: View) {
        val ctx = requireContext()
        val hint = view.findViewById<TextView>(R.id.settings_voice_hint)
        if (!Speaker.ready) {
            hint.setText(R.string.voice_unavailable)
            return
        }
        val voices = Speaker.voices
        val voiceRow = view.findViewById<LinearLayout>(R.id.settings_voices)
        if (voices.isEmpty()) {
            hint.setText(R.string.voice_none_offline)
        } else {
            hint.setText(R.string.voice_hint)
            val saved = VoiceChoice.voiceToApply(Prefs.ttsVoice(ctx), voices)
            val choices = listOf<String?>(null) + voices
            choices.forEachIndexed { index, name ->
                val label = if (name == null) getString(R.string.voice_auto) else getString(R.string.voice_numbered, index)
                addChoice(voiceRow, label, name == saved) {
                    Prefs.setTtsVoice(ctx, name)
                    Speaker.preview(ctx, name, Prefs.ttsRate(ctx))
                }
            }
            view.findViewById<View>(R.id.settings_voices_scroll).visibility = View.VISIBLE
        }
        val rateRow = view.findViewById<LinearLayout>(R.id.settings_voice_rates)
        val rate = VoiceChoice.rateChoice(Prefs.ttsRate(ctx))
        for ((value, label) in VoiceChoice.RATES.zip(VOICE_RATE_LABELS)) {
            addChoice(rateRow, getString(label), value == rate) {
                Prefs.setTtsRate(ctx, value)
                Speaker.preview(ctx, Prefs.ttsVoice(ctx), value)
            }
        }
        rateRow.visibility = View.VISIBLE
        view.findViewById<View>(R.id.settings_voice_rate_title).visibility = View.VISIBLE
    }

    /** Pastille d'un choix exclusif de la ligne : la toucher la sélectionne seule, puis lance [onPick] (même choix : réécoute). */
    private fun addChoice(row: LinearLayout, label: String, selected: Boolean, onPick: () -> Unit) {
        val chip = Button(requireContext(), null, 0, R.style.Chip).apply {
            text = label
            isSelected = selected
            setOnClickListener { button ->
                for (i in 0 until row.childCount) row.getChildAt(i).isSelected = row.getChildAt(i) === button
                onPick()
            }
        }
        row.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48), 1f).apply { if (row.childCount > 0) marginStart = dp(10) })
    }

    /**
     * Carte Animations : un interrupteur par famille (lu au moment de jouer, rien à relancer). Avec Réduire les
     * animations, ils sont grisés : tout est déjà coupé.
     */
    private fun setUpAnimations(view: View) {
        val ctx = requireContext()
        val card = view.findViewById<LinearLayout>(R.id.settings_animations)
        val reduced = Prefs.reduceMotion(ctx)
        if (reduced) view.findViewById<TextView>(R.id.settings_animations_hint).setText(R.string.anim_hint_reduced)
        val rows = listOf(
            AnimFamily.NAVIGATION to (R.string.anim_navigation to R.string.anim_navigation_hint),
            AnimFamily.HOME to (R.string.anim_home to R.string.anim_home_hint),
            AnimFamily.DIALOGS to (R.string.anim_dialogs to R.string.anim_dialogs_hint),
            AnimFamily.ALERTS to (R.string.anim_alerts to R.string.anim_alerts_hint),
            AnimFamily.DETAILS to (R.string.anim_details to R.string.anim_details_hint),
        )
        for ((family, texts) in rows) {
            card.addView(SwitchCompat(ctx).apply {
                setText(texts.first)
                textSize = 16f
                minHeight = ctx.dp(48)
                isChecked = Prefs.animation(ctx, family)
                isEnabled = !reduced
                setOnCheckedChangeListener { _, checked -> Prefs.setAnimation(ctx, family, checked) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(12) })
            card.addView(TextView(ctx).apply {
                setText(texts.second)
                textSize = 14f
                setTextColor(themeColor(ctx, R.attr.c_text_dim))
            })
        }
    }

    private fun setUpTablet() {
        val ctx = requireContext()
        brightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                Prefs.setBrightness(ctx, (progress / 100f).coerceAtLeast(Screen.MIN_LEVEL))
                Screen.apply(requireActivity())
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        val audio = ctx.getSystemService(AudioManager::class.java)
        volume.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            // Petit son de contrôle au lâcher : on entend le niveau choisi.
            override fun onStopTrackingTouch(bar: SeekBar) = audio.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
        })
        nightDim.setOnCheckedChangeListener { _, checked ->
            Prefs.setNightDim(ctx, checked)
            Screen.apply(requireActivity())
        }
        // Mode discret : lu à chaque bandeau et à chaque affichage de l'historique, rien d'autre à prévenir.
        notifDiscreet.setOnCheckedChangeListener { _, checked -> Prefs.setNotificationsDiscreet(ctx, checked) }
        // Alerte de réunion : lue à la vérification de chaque minute, rien d'autre à prévenir.
        meetingAlert.setOnCheckedChangeListener { _, checked -> Prefs.setMeetingAlert(ctx, checked) }
        // Relu à chaque affichage des Réglages (isChecked) : l'écran du verrou ne bouge que sur un vrai changement.
        blockOnComputerLock.setOnCheckedChangeListener { _, checked ->
            if (checked != Prefs.blockOnComputerLock(ctx)) main.lockScreen.setBlockOnComputerLock(checked)
        }
        lockCode.setOnClickListener { main.lockScreen.chooseCode { renderLockCode() } }
        // Mot de réveil : l'activer télécharge le modèle s'il manque ; l'écoute suit tout de suite.
        wakeWord.setOnCheckedChangeListener { _, checked ->
            Prefs.setWakeWord(ctx, checked)
            if (checked) WakeModel.ensure(ctx)
            main.onWakeWordChanged()
            renderWake()
        }
        viewLifecycleOwner.lifecycleScope.launch { WakeModel.state.collect { renderWake() } }
        // Ville par la position : lu à la prochaine lecture de la météo d'un widget sans ville.
        weatherLocation.setOnCheckedChangeListener { _, checked -> Prefs.setWeatherLocation(ctx, checked) }
        renderTablet()
    }

    private fun forgetSwitchStates(view: View) {
        if (view is android.widget.CompoundButton) view.isSaveEnabled = false
        if (view is ViewGroup) for (i in 0 until view.childCount) forgetSwitchStates(view.getChildAt(i))
    }

    private fun renderTablet() {
        val ctx = requireContext()
        val chosen = Prefs.brightness(ctx)
        // Pas encore réglée ici : position de la luminosité du système (0 à 255).
        brightness.progress = if (chosen >= 0) (chosen * 100).toInt()
        else Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255
        volume.progress = ctx.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_MUSIC)
        nightDim.isChecked = Prefs.nightDim(ctx)
        notifDiscreet.isChecked = Prefs.notificationsDiscreet(ctx)
        meetingAlert.isChecked = Prefs.meetingAlert(ctx)
        blockOnComputerLock.isChecked = Prefs.blockOnComputerLock(ctx)
        renderLockCode()
        wakeWord.isChecked = Prefs.wakeWord(ctx)
        renderWake()
        weatherLocation.isChecked = Prefs.weatherLocation(ctx)
        // Batterie (registerReceiver) et adresse IP (NetworkInterface) : appels système lents, hors thread principal.
        viewLifecycleOwner.lifecycleScope.launch {
            val battery = withContext(Dispatchers.IO) { Tablet.battery(ctx) }
            val ip = withContext(Dispatchers.IO) { Tablet.ipAddress() }
            if (view == null) return@launch
            battery?.let(::onBattery)
            tabletInfo.text = getString(
                R.string.tablet_info, BuildConfig.VERSION_NAME, ip ?: getString(R.string.tablet_no_address), android.os.Build.MODEL,
            )
        }
    }

    /** Ouvre sur l'ordinateur la page du compagnon, carte Réglages de la tablette en avant. */
    /** « Régler depuis l'ordi » en tête de Personnalisation, seulement avec un ordinateur appairé. */
    private fun renderRemote() {
        view?.findViewById<View>(R.id.settings_remote_card)?.visibility =
            if (Computers.active(requireContext()) != null) View.VISIBLE else View.GONE
    }

    private fun freshStart() {
        val activity = requireActivity()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                FreshStart.forgetTrial(activity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                main.showBanner(errorText(e), 6)
                return@launch
            }
            FreshStart.run(activity)
        }
    }

    private fun openOnComputer() {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                BackendClient(ctx).system.openLocalPage("tablette")
                main.showBanner(getString(R.string.tablet_remote_opened), 6)
            } catch (e: Exception) {
                main.showBanner(errorText(e), 6)
            }
        }
    }

    /** Réglages changés depuis la page de l'ordinateur : curseurs et interrupteurs à jour. */
    fun onRemoteSettings() {
        if (view != null) renderTablet()
    }

    /** Ligne d'état du mot de réveil : micro d'abord (sans lui rien ne sert), puis le modèle ; rien quand il est coupé. */
    private fun renderWake() {
        val ctx = context ?: return
        if (view == null) return
        val on = Prefs.wakeWord(ctx)
        wakeStatus.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        val mic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        wakeStatus.text = if (!mic) getString(R.string.wake_no_mic) else when (val model = WakeModel.refresh(ctx)) {
            WakeModel.State.Ready -> getString(R.string.wake_ready)
            is WakeModel.State.Downloading -> getString(R.string.wake_downloading, model.percent)
            WakeModel.State.Absent -> getString(R.string.wake_downloading, 0)
            WakeModel.State.Failed -> getString(R.string.wake_failed)
        }
    }

    /** Batterie changée (MainActivity, barre latérale) : détail à jour si les Réglages sont affichés. */
    fun onBattery(state: Tablet.Battery) {
        if (view == null) return
        val plug = when (state.plug) {
            BatteryManager.BATTERY_PLUGGED_AC -> getString(R.string.tablet_plug_ac)
            BatteryManager.BATTERY_PLUGGED_USB -> getString(R.string.tablet_plug_usb)
            else -> getString(R.string.tablet_plug_other)
        }
        battery.text = if (state.charging) getString(R.string.tablet_battery_charging, state.level, plug)
        else getString(R.string.tablet_battery_discharging, state.level)
        battery.setTextColor(themeColor(requireContext(), if (state.low) R.attr.c_ko else R.attr.c_text))
        val health = when (state.health) {
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> getString(R.string.tablet_health_overheat)
            BatteryManager.BATTERY_HEALTH_DEAD -> getString(R.string.tablet_health_dead)
            BatteryManager.BATTERY_HEALTH_COLD -> getString(R.string.tablet_health_cold)
            else -> getString(R.string.tablet_health_other)
        }
        val hint = when {
            state.healthIssue -> getString(R.string.tablet_battery_health, health)
            state.plug == BatteryManager.BATTERY_PLUGGED_USB -> getString(R.string.tablet_battery_slow)
            else -> null
        }
        batteryHint.text = hint
        batteryHint.setTextColor(themeColor(requireContext(), if (state.healthIssue) R.attr.c_warn else R.attr.c_text_dim))
        batteryHint.visibility = if (hint == null) View.GONE else View.VISIBLE
    }

    // --- Mises à jour

    /** Bouton vers l'autre appli (essai ou stable), seulement si elle est installée. */
    private fun renderSwitchApp() {
        switchApp.visibility = if (Trial.isInstalled(requireContext(), Trial.other)) View.VISIBLE else View.GONE
    }

    private fun checkUpdates() {
        if (installing) return
        updatesStatus.setText(R.string.updates_checking)
        updates.removeAllViews()
        essais.removeAllViews()
        essaisTitle.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val check = Updater.check(requireContext())
                // Une ligne par source en erreur, sous la phrase d'état : l'autre source reste utilisable.
                val lines = listOf(getString(if (check.updates.isEmpty()) R.string.updates_none else R.string.updates_hint)) +
                    check.errors.map { (source, e) -> getString(R.string.updates_source_error, sourceName(source), errorText(e)) }
                updatesStatus.text = lines.joinToString("\n")
                for ((update, info) in check.updates) updates.addView(updateRow(update, info))
            } catch (e: Exception) {
                updatesStatus.text = errorText(e)
            }
            // Essais de MR : seulement dans la stable (device owner, qui les installe).
            if (!BuildConfig.TRIAL) showEssais()
        }
        checkCompanion()
    }

    /** Compagnon de l'ordinateur actif : sa version et, si une plus récente est publiée, le bouton qui l'y met. */
    private fun checkCompanion() {
        if (companionUpdating) return
        viewLifecycleOwner.lifecycleScope.launch {
            val status = CompanionUpdater.check(requireContext())
            if (view == null || companionUpdating) return@launch
            companionUpdate.visibility = View.GONE
            companionStatus.visibility = if (status == null) View.GONE else View.VISIBLE
            val identity = status?.identity
            if (status == null) return@launch
            if (identity == null) {
                companionStatus.setText(R.string.companion_state_unreachable)
                return@launch
            }
            val name = identity.name
            val version = identity.version ?: getString(R.string.companion_version_old)
            companionStatus.text = when (val d = status.decision) {
                BackendPlan.Decision.Unreachable -> getString(R.string.companion_state_unreachable)
                BackendPlan.Decision.FromRepo -> getString(R.string.companion_state_repo, name)
                BackendPlan.Decision.NothingPublished -> getString(R.string.companion_state_nothing, name, version)
                BackendPlan.Decision.OnEssai -> getString(R.string.companion_state_essai, name)
                BackendPlan.Decision.UpToDate -> getString(R.string.companion_state_uptodate, name, version)
                is BackendPlan.Decision.ComputerNewer -> getString(R.string.companion_state_newer, name, version, d.release.version)
                is BackendPlan.Decision.NeedsInstaller -> {
                    showCompanionButton(R.string.companion_update_steps) { main.companionUpdate.showInstallSteps(identity) }
                    getString(R.string.companion_state_installer, name, version, d.release.version)
                }
                is BackendPlan.Decision.Available -> {
                    showCompanionButton(R.string.companion_update_button) { updateCompanion(identity, d.release) }
                    getString(R.string.companion_state_available, name, version, d.release.version)
                }
            }
        }
    }

    private fun showCompanionButton(text: Int, onClick: () -> Unit) {
        companionUpdate.setText(text)
        companionUpdate.isEnabled = true
        companionUpdate.visibility = View.VISIBLE
        companionUpdate.setOnClickListener { onClick() }
    }

    /** Le travail tourne dans la portée de l'activité : les vues ne sont touchées que si les Réglages sont encore là. */
    private fun updateCompanion(identity: Identity, release: BackendRelease) {
        val showing = { isAdded && view != null }
        main.companionUpdate.confirm(identity, release, onState = { text ->
            companionUpdating = true
            if (showing()) {
                companionUpdate.isEnabled = false
                companionUpdate.text = text
            }
        }, onDone = {
            companionUpdating = false
            if (showing()) checkCompanion()
        })
    }

    /** Une ligne par essai publié, sous les mises à jour. Une erreur s'ajoute à la phrase d'état, sans masquer le reste. */
    private suspend fun showEssais() {
        try {
            val list = Updater.essais(requireContext())
            essais.removeAllViews()
            essaisTitle.visibility = View.VISIBLE
            essaisTitle.setText(if (list.isEmpty()) R.string.essais_none else R.string.essais_title)
            val installed = Updater.installed(requireContext(), Trial.TRIAL)
            for (essai in list) essais.addView(updateRow(essai.update, installed, essai))
        } catch (e: Exception) {
            updatesStatus.text = listOf(updatesStatus.text, getString(R.string.updates_source_error, getString(R.string.updates_source_trials), errorText(e))).joinToString("\n")
        }
    }

    /**
     * « Ouvrir l'essai » : l'ordinateur d'abord sur le backend de la MR installée (ou d'origine si elle ne le touche pas),
     * puis on passe à l'essai. Erreur : affichée, l'essai ne s'ouvre pas.
     */
    private fun openTrial() {
        val ctx = requireContext().applicationContext
        switchApp.isEnabled = false
        updatesStatus.setText(R.string.trial_syncing)
        val activity = requireActivity()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val mr = Prefs.trialMr(ctx)
                if (mr != 0) EssaiBackend.sync(ctx, mr, Prefs.trialHasBackend(ctx))
                Trial.switch(activity)
                if (view != null) updatesStatus.setText(R.string.updates_hint)
            } catch (e: Exception) {
                if (view != null) updatesStatus.text = errorText(e)
            } finally {
                if (view != null) switchApp.isEnabled = true
            }
        }
    }

    /** « Revenir à la stable » : le backend d'origine de l'ordinateur d'abord, la main rendue même s'il ne répond pas. */
    private fun backToStable() {
        val ctx = requireContext().applicationContext
        val activity = requireActivity()
        switchApp.isEnabled = false
        main.lifecycleScope.launch {
            EssaiBackend.restoreOrigin(ctx)
            Trial.switch(activity)
        }
    }

    private fun updateRow(update: AppUpdate, info: android.content.pm.PackageInfo?, essai: Essai? = null): View {
        val ctx = requireContext()
        val block = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val installedCode = info?.let { Updater.versionCode(it) }
        val state = UpdatePlan.state(update, installedCode)
        val installedMr = Prefs.trialMr(ctx)
        val plan = essai?.let { EssaiPlan.install(update.versionCode, installedCode, essai.mr, installedMr) }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            textSize = 16f
            text = if (essai != null) getString(R.string.essai_row_title, essai.mr, essai.title)
            else getString(R.string.updates_row_title, update.label, update.versionName)
        })
        texts.addView(TextView(ctx).apply {
            textSize = 14f
            setTextColor(themeColor(ctx, if (plan == EssaiPlan.Install.UNINSTALL_FIRST || (essai == null && state == UpdatePlan.State.OLDER)) R.attr.c_warn else R.attr.c_text_dim))
            text = if (essai != null) essaiState(essai, plan!!, info, installedMr) else when (state) {
                UpdatePlan.State.NEWER -> if (info == null) getString(R.string.updates_row_absent, sourceName(update.source))
                else getString(R.string.updates_row_newer, info.versionName, sourceName(update.source))
                UpdatePlan.State.INSTALLED -> getString(R.string.updates_row_installed)
                UpdatePlan.State.OLDER -> getString(R.string.updates_row_older, info?.versionName)
            }
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        block.addView(row)
        if (if (plan != null) plan != EssaiPlan.Install.NONE else state == UpdatePlan.State.NEWER) {
            val button = Button(ctx, null, 0, R.style.PrimaryButton).apply { setText(R.string.updates_install) }
            // Barre épaisse sous la ligne, montrée pendant le téléchargement.
            val bar = android.widget.ProgressBar(ctx, null, 0, android.R.style.Widget_Material_ProgressBar_Horizontal).apply {
                progressDrawable = ContextCompat.getDrawable(ctx, R.drawable.progress_bar)
                max = 100
                visibility = View.GONE
            }
            // Annuler : pendant le téléchargement seulement (Android ne permet plus d'annuler une fois le fichier confié).
            val cancel = Button(ctx, null, 0, R.style.ActionButton).apply {
                setText(R.string.cancel)
                visibility = View.GONE
            }
            button.setOnClickListener { install(update, button, bar, cancel, essai) }
            row.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { marginEnd = dp(8) })
            row.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)))
            block.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(10) })
        }
        return block
    }

    private fun sourceName(source: UpdateSource) =
        getString(if (source == UpdateSource.COMPUTER) R.string.updates_source_computer else R.string.updates_source_server)

    /** Version, backend, et ce que l'installation fait de l'essai déjà installé. */
    private fun essaiState(essai: Essai, plan: EssaiPlan.Install, info: android.content.pm.PackageInfo?, installedMr: Int): String {
        val backend = getString(if (essai.hasBackend) R.string.essai_row_backend else R.string.essai_row_no_backend)
        val state = when (plan) {
            EssaiPlan.Install.NONE -> getString(R.string.essai_row_installed)
            EssaiPlan.Install.INSTALL -> if (info == null) getString(R.string.essai_row_absent)
            else if (installedMr != 0) getString(R.string.essai_row_replace_mr, installedMr, info.versionName)
            else getString(R.string.essai_row_replace, info.versionName)
            EssaiPlan.Install.UNINSTALL_FIRST -> if (installedMr != 0) getString(R.string.essai_row_downgrade_mr, installedMr, info?.versionName)
            else getString(R.string.essai_row_downgrade, info?.versionName)
        }
        return getString(R.string.essai_row_version, essai.update.versionName, backend) + "\n" + state
    }

    private fun install(update: AppUpdate, button: Button, bar: android.widget.ProgressBar, cancel: Button, essai: Essai? = null) {
        if (installing) return
        installing = true
        button.isEnabled = false
        button.backgroundTintList = null
        button.setText(R.string.updates_downloading_short)
        bar.progress = 0
        bar.isIndeterminate = false
        bar.visibility = View.VISIBLE
        val ctx = requireContext().applicationContext
        cancel.visibility = View.VISIBLE
        val started = android.os.SystemClock.elapsedRealtime()
        // Le fragment peut disparaître pendant l'installation : la portée de l'activité la mène à son terme.
        // Elle ne touche alors ni au fragment (plus de contexte) ni à ses vues : `ctx` pour les textes, `showing()` pour les vues.
        val showing = { isAdded && view != null }
        val job = main.lifecycleScope.launch {
            try {
                Updater.install(ctx, update, progress = { percent ->
                    bar.post {
                        if (percent >= 100) {
                            // Vérification puis installation : durée inconnue.
                            bar.isIndeterminate = true
                            button.setText(R.string.updates_installing)
                        } else {
                            bar.progress = percent
                            button.text = ctx.getString(R.string.updates_downloading, percent)
                            // Temps restant d'après la vitesse mesurée depuis le début (affiché à partir de 3 %).
                            if (percent >= 3 && showing()) {
                                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                                updatesStatus.text = ctx.getString(R.string.updates_eta, duration(elapsed * (100 - percent) / percent))
                            }
                        }
                    }
                }, committed = {
                    cancel.post {
                        cancel.visibility = View.GONE
                        if (showing()) updatesStatus.text = ctx.getString(R.string.updates_committed, installMinutes(update))
                    }
                })
                if (essai != null) {
                    Prefs.setTrial(ctx, essai.mr, essai.hasBackend)
                    if (showing()) updatesStatus.setText(R.string.trial_syncing)
                    EssaiBackend.sync(ctx, essai.mr, essai.hasBackend, essai)
                }
                showResult(button, bar, ok = true)
                if (showing()) updatesStatus.setText(R.string.updates_hint)
                // Les lignes reflètent le nouvel essai installé (« Installée », MR remplacée).
                if (essai != null && showing()) showEssais()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Annulé : retour à l'état de départ, rien d'installé.
                bar.visibility = View.GONE
                button.isEnabled = true
                button.setText(R.string.updates_install)
                if (showing()) updatesStatus.setText(R.string.updates_cancelled)
            } catch (e: Exception) {
                showResult(button, bar, ok = false)
                if (showing()) updatesStatus.text = ctx.errorText(e)
            } finally {
                cancel.visibility = View.GONE
                installing = false
            }
        }
        cancel.setOnClickListener { job.cancel() }
    }

    /** « 40 s », « 3 min » : arrondi, l'estimation est grossière. */
    private fun duration(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(1)
        return if (seconds < 60) getString(R.string.duration_seconds, ((seconds + 9) / 10 * 10).toInt())
        else getString(R.string.duration_minutes, ((seconds + 30) / 60).toInt())
    }

    /**
     * Durée d'installation par Android, estimée d'après la taille : le navigateur (235 Mo) a pris environ 5 min sur
     * la tablette, préparation du code et file d'attente (Play Store) comprises.
     */
    private fun installMinutes(update: AppUpdate) = (update.size / (50L * 1024 * 1024) + 1).toInt()

    /** Vert si c'est installé, rouge en cas d'échec (le bouton permet alors de réessayer). */
    private fun showResult(button: Button, bar: android.widget.ProgressBar, ok: Boolean) {
        bar.visibility = View.GONE
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(themeColor(button.context, if (ok) R.attr.c_ok else R.attr.c_ko))
        button.setText(if (ok) R.string.updates_done else R.string.updates_failed)
        button.isEnabled = !ok
        // Essai tout juste installé : « Ouvrir l'essai » apparaît.
        if (ok && view != null) renderSwitchApp()
    }

    /** Code technicien : montre la carte Kiosque (quitter, retirer, autoriser les installations). */
    private fun askTechCode() {
        val ctx = requireContext()
        val input = (layoutInflater.inflate(R.layout.item_input, null, false) as EditText).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val box = android.widget.FrameLayout(ctx).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.tech_code_title)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm, null)
            .create()
        dialog.showForTyping(input)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (input.text.toString() == BuildConfig.TECH_CODE) {
                dialog.dismiss()
                showTab(options = false)
                kioskCard.visibility = View.VISIBLE
                renderKiosk()
            } else {
                input.setText("")
                input.error = getString(R.string.tech_code_wrong)
            }
        }
    }

    private fun renderLockCode() {
        lockCode.setText(if (Prefs.lockCodeHash(requireContext()) == null) R.string.lock_code_choose else R.string.lock_code_change)
    }

    private fun renderKiosk() {
        val ctx = requireContext()
        val owner = Kiosk.isDeviceOwner(ctx)
        kioskActions.visibility = if (owner) View.VISIBLE else View.GONE
        allowInstalls.visibility = kioskActions.visibility
        allowInstallsHint.visibility = kioskActions.visibility
        allowInstalls.setOnCheckedChangeListener(null)
        allowInstalls.isChecked = Prefs.allowInstalls(ctx)
        allowInstalls.setOnCheckedChangeListener { _, checked ->
            Prefs.setAllowInstalls(ctx, checked)
            Kiosk.lockApps(ctx, !checked)
        }
        kioskSecurity.visibility = kioskActions.visibility
        renderKioskSwitches(ctx)
        kioskStatus.setText(
            when {
                !owner -> R.string.kiosk_off
                Kiosk.suspended -> R.string.kiosk_paused
                else -> R.string.kiosk_on
            },
        )
    }

    /** Interrupteurs de la carte Kiosque, posés sans déclencher leur écouteur. */
    private fun renderKioskSwitches(ctx: android.content.Context) {
        val hasBluetooth = Bluetooth.available()
        bluetooth.visibility = if (hasBluetooth) View.VISIBLE else View.GONE
        bluetoothHint.visibility = bluetooth.visibility
        bluetooth.setOnCheckedChangeListener(null)
        bluetooth.isChecked = Bluetooth.isOn()
        bluetooth.setOnCheckedChangeListener { _, checked ->
            if (!Bluetooth.set(checked)) {
                main.showBanner(getString(R.string.kiosk_bluetooth_failed), 6)
                renderKioskSwitches(ctx)
            }
        }
        landscapeLocked.setOnCheckedChangeListener(null)
        landscapeLocked.isChecked = Prefs.landscapeLocked(ctx)
        landscapeLocked.setOnCheckedChangeListener { _, checked ->
            Prefs.setLandscapeLocked(ctx, checked)
            Screen.applyOrientation(requireActivity())
        }
        exitLock.setOnCheckedChangeListener(null)
        exitLock.isChecked = Prefs.exitLock(ctx)
        exitLock.setOnCheckedChangeListener { _, checked ->
            Prefs.setExitLock(ctx, checked)
            Kiosk.lockExit(ctx, checked)
        }
        powerMenu.setOnCheckedChangeListener(null)
        powerMenu.isChecked = Prefs.powerMenu(ctx)
        powerMenu.setOnCheckedChangeListener { _, checked ->
            Prefs.setPowerMenu(ctx, checked)
            Kiosk.applyLockTaskFeatures(ctx)
        }
    }

    /**
     * Texte de l'appli en plein écran : mentions de licences (assets/licences.txt, généré par
     * scripts/licences-android.mjs, obligatoires pour les bibliothèques Apache 2.0 et les polices OFL) ou politique de
     * confidentialité (assets/confidentialite.txt, copie de backend/config/confidentialite.txt, docs/confidentialite.md).
     */
    private fun showText(title: Int, asset: String, monospace: Boolean) {
        val context = requireContext()
        val text = runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }.getOrDefault("")
        val body = TextView(context).apply {
            this.text = text
            textSize = if (monospace) 12f else 14f
            if (monospace) typeface = Typeface.MONOSPACE else setLineSpacing(0f, 1.15f)
            setTextColor(themeColor(context, R.attr.c_text))
            setTextIsSelectable(false)
            setPadding(context.dp(24), context.dp(8), context.dp(24), context.dp(8))
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(android.widget.ScrollView(context).apply { addView(body) })
            .setPositiveButton(R.string.close, null)
            .showImmersive()
    }

    private companion object {
        const val KEY_OPTIONS_TAB = "settings_options_tab"

        /** Facteurs de la taille du texte, en plus de celle d'Android : jusqu'à une fois et demie. */
        val TEXT_SIZES = listOf(
            1f to R.string.a11y_size_normal,
            1.15f to R.string.a11y_size_large,
            1.3f to R.string.a11y_size_larger,
            1.5f to R.string.a11y_size_largest,
        )

        /** Noms des débits de VoiceChoice.RATES, dans le même ordre. */
        val VOICE_RATE_LABELS = listOf(R.string.voice_rate_slow, R.string.voice_rate_soft, R.string.voice_rate_normal)
    }
}
