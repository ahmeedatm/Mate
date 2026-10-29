package com.unowhy.companion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.BuildConfig
import com.unowhy.companion.Computer
import com.unowhy.companion.Computers
import com.unowhy.companion.MainActivity
import com.unowhy.companion.Prefs
import com.unowhy.companion.R
import com.unowhy.companion.device.Accessibility
import com.unowhy.companion.device.Screen
import com.unowhy.companion.device.Tablet
import com.unowhy.companion.installers.BackendPlan
import com.unowhy.companion.installers.BackendRelease
import com.unowhy.companion.installers.CompanionUpdater
import com.unowhy.companion.installers.InstallerService
import com.unowhy.companion.kiosk.Kiosk
import com.unowhy.companion.kiosk.UpdatePlan
import com.unowhy.companion.kiosk.Updater
import com.unowhy.companion.net.AppUpdate
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.Essai
import com.unowhy.companion.net.Identity
import com.unowhy.companion.net.Link
import com.unowhy.companion.net.UpdateSource
import com.unowhy.companion.net.Wifi
import com.unowhy.companion.trial.EssaiBackend
import com.unowhy.companion.trial.EssaiPlan
import com.unowhy.companion.trial.Trial
import com.unowhy.companion.voice.WakeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private lateinit var kioskCard: View
    private lateinit var kioskStatus: TextView
    private lateinit var kioskActions: View
    private lateinit var allowInstalls: SwitchCompat
    private lateinit var allowInstallsHint: View
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
        computers = view.findViewById(R.id.settings_computers)
        view.findViewById<Button>(R.id.settings_add_computer).setOnClickListener { adder.search() }
        view.findViewById<Button>(R.id.settings_plugins).setOnClickListener { main.showPlugins() }
        view.findViewById<Button>(R.id.settings_add_manual).setOnClickListener { adder.manual() }
        kioskCard = view.findViewById(R.id.settings_kiosk_card)
        view.findViewById<View>(R.id.settings_title).setOnLongClickListener { askTechCode(); true }
        kioskStatus = view.findViewById(R.id.settings_kiosk_status)
        kioskActions = view.findViewById(R.id.settings_kiosk_actions)
        allowInstalls = view.findViewById(R.id.settings_allow_installs)
        allowInstallsHint = view.findViewById(R.id.settings_allow_installs_hint)
        meetingsCard = view.findViewById(R.id.settings_meetings_card)
        autoJoin = view.findViewById(R.id.settings_autojoin)
        autoJoinHint = view.findViewById(R.id.settings_autojoin_hint)
        wifiStatus = view.findViewById(R.id.settings_wifi_status)
        view.findViewById<Button>(R.id.settings_wifi).setOnClickListener { wifi.show() }
        view.findViewById<Button>(R.id.settings_tablet_remote).setOnClickListener { openOnComputer() }
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
        wakeWord = view.findViewById(R.id.settings_wake_word)
        wakeStatus = view.findViewById(R.id.settings_wake_word_status)
        weatherLocation = view.findViewById(R.id.settings_weather_location)
        tabletInfo = view.findViewById(R.id.settings_tablet_info)
        setUpTablet()
        setUpThemes(view.findViewById(R.id.settings_themes))
        setUpAccessibility(view)
        renderComputers()
        renderWifi()
        renderSwitchApp()
        checkCompanion()

        view.findViewById<Button>(R.id.settings_licences).setOnClickListener { showText(R.string.about_licences, "licences.txt", monospace = true) }
        view.findViewById<Button>(R.id.settings_privacy).setOnClickListener { showText(R.string.about_privacy, "confidentialite.txt", monospace = false) }
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
        renderKiosk()
        loadAutoJoin()
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

    /** Une pastille par thème, avec sa couleur ; en choisir un recrée l'activité (les écrans se redessinent). */
    private fun setUpThemes(row: LinearLayout) {
        val current = AppTheme.current(requireContext())
        for (theme in AppTheme.entries) {
            val chip = Button(requireContext(), null, 0, R.style.Chip).apply {
                setText(theme.label)
                isSelected = theme == current
                compoundDrawablePadding = dp(10)
                setCompoundDrawablesRelative(
                    ContextCompat.getDrawable(context, R.drawable.dot)!!.mutate().also {
                        it.setTint(theme.swatch)
                        it.setBounds(0, 0, dp(14), dp(14))
                    }, null, null, null,
                )
                setOnClickListener {
                    if (theme == current) return@setOnClickListener
                    Prefs.setTheme(context, theme.name)
                    requireActivity().recreate()
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (row.childCount > 0) marginStart = dp(10) })
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

    private fun renderTablet() {
        val ctx = requireContext()
        val chosen = Prefs.brightness(ctx)
        // Pas encore réglée ici : position de la luminosité du système (0 à 255).
        brightness.progress = if (chosen >= 0) (chosen * 100).toInt()
        else Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255
        volume.progress = ctx.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_MUSIC)
        nightDim.isChecked = Prefs.nightDim(ctx)
        notifDiscreet.isChecked = Prefs.notificationsDiscreet(ctx)
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
        val plan = essai?.let { EssaiPlan.install(update.versionCode, installedCode) }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            textSize = 16f
            text = if (essai != null) getString(R.string.essai_row_title, essai.mr, essai.title)
            else getString(R.string.updates_row_title, update.label, update.versionName)
        })
        texts.addView(TextView(ctx).apply {
            textSize = 14f
            setTextColor(themeColor(ctx, if (plan == EssaiPlan.Install.UNINSTALL_FIRST || (essai == null && state == UpdatePlan.State.OLDER)) R.attr.c_warn else R.attr.c_text_dim))
            text = if (essai != null) essaiState(essai, plan!!, info) else when (state) {
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
    private fun essaiState(essai: Essai, plan: EssaiPlan.Install, info: android.content.pm.PackageInfo?): String {
        val backend = getString(if (essai.hasBackend) R.string.essai_row_backend else R.string.essai_row_no_backend)
        val state = when (plan) {
            EssaiPlan.Install.NONE -> getString(R.string.updates_row_installed)
            EssaiPlan.Install.INSTALL -> if (info == null) getString(R.string.essai_row_absent) else getString(R.string.essai_row_replace, info.versionName)
            EssaiPlan.Install.UNINSTALL_FIRST -> getString(R.string.essai_row_downgrade, info?.versionName)
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
                kioskCard.visibility = View.VISIBLE
                renderKiosk()
            } else {
                input.setText("")
                input.error = getString(R.string.tech_code_wrong)
            }
        }
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
        kioskStatus.setText(
            when {
                !owner -> R.string.kiosk_off
                Kiosk.suspended -> R.string.kiosk_paused
                else -> R.string.kiosk_on
            },
        )
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
        /** Facteurs de la taille du texte, en plus de celle d'Android : jusqu'à une fois et demie. */
        val TEXT_SIZES = listOf(
            1f to R.string.a11y_size_normal,
            1.15f to R.string.a11y_size_large,
            1.3f to R.string.a11y_size_larger,
            1.5f to R.string.a11y_size_largest,
        )
    }
}
