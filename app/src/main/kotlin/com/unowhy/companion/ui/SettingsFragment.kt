package com.unowhy.companion.ui

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
import com.unowhy.companion.device.Screen
import com.unowhy.companion.device.Tablet
import com.unowhy.companion.kiosk.Kiosk
import com.unowhy.companion.kiosk.Updater
import com.unowhy.companion.net.AppUpdate
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.Link
import com.unowhy.companion.net.Wifi
import kotlinx.coroutines.launch

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
    private lateinit var tabletInfo: TextView
    private lateinit var updatesStatus: TextView
    private lateinit var updates: LinearLayout
    private var installing = false
    private val main get() = requireActivity() as MainActivity
    private val adder by lazy { ComputerAdder(requireActivity(), main.lifecycleScope) { main.onComputerChanged(it) } }
    private val wifi by lazy { WifiPicker(requireActivity(), main.lifecycleScope) { if (view != null) renderWifi() } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        computers = view.findViewById(R.id.settings_computers)
        view.findViewById<Button>(R.id.settings_add_computer).setOnClickListener { adder.search() }
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
        updatesStatus = view.findViewById(R.id.settings_updates_status)
        updates = view.findViewById(R.id.settings_updates)
        view.findViewById<Button>(R.id.settings_updates_check).setOnClickListener { checkUpdates() }
        battery = view.findViewById(R.id.settings_battery)
        batteryHint = view.findViewById(R.id.settings_battery_hint)
        brightness = view.findViewById(R.id.settings_brightness)
        volume = view.findViewById(R.id.settings_volume)
        nightDim = view.findViewById(R.id.settings_night_dim)
        tabletInfo = view.findViewById(R.id.settings_tablet_info)
        setUpTablet()
        setUpThemes(view.findViewById(R.id.settings_themes))
        renderComputers()
        renderWifi()

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
            loadAutoJoin()
        }
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
                text = getString(R.string.computers_empty, BuildConfig.INSTALL_URL)
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
                    link.state == Link.State.UNAUTHORIZED -> adder.pair(c.url, c.name)
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
        val name = Wifi.current(requireContext())
        wifiStatus.text = when {
            name == null -> getString(R.string.wifi_off)
            name.isEmpty() -> getString(R.string.wifi_current_unnamed)
            else -> getString(R.string.wifi_current, name)
        }
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
        renderTablet()
    }

    private fun renderTablet() {
        val ctx = requireContext()
        Tablet.battery(ctx)?.let(::onBattery)
        val chosen = Prefs.brightness(ctx)
        // Pas encore réglée ici : position de la luminosité du système (0 à 255).
        brightness.progress = if (chosen >= 0) (chosen * 100).toInt()
        else Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255
        volume.progress = ctx.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_MUSIC)
        nightDim.isChecked = Prefs.nightDim(ctx)
        tabletInfo.text = getString(
            R.string.tablet_info, BuildConfig.VERSION_NAME, Tablet.ipAddress() ?: getString(R.string.tablet_no_address), android.os.Build.MODEL,
        )
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

    private fun checkUpdates() {
        if (installing) return
        updatesStatus.setText(R.string.updates_checking)
        updates.removeAllViews()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val found = Updater.check(requireContext())
                updatesStatus.setText(if (found.isEmpty()) R.string.updates_none else R.string.updates_hint)
                for ((update, info) in found) updates.addView(updateRow(update, info))
            } catch (e: Exception) {
                updatesStatus.text = errorText(e)
            }
        }
    }

    private fun updateRow(update: AppUpdate, info: android.content.pm.PackageInfo?): View {
        val ctx = requireContext()
        val block = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val current = info?.versionName ?: getString(R.string.updates_not_installed)
        val label = TextView(ctx).apply {
            textSize = 16f
            text = if (Updater.isNewer(update, info)) getString(R.string.updates_row, update.label, current, update.versionName)
            else getString(R.string.updates_row_current, update.label, current)
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        block.addView(row)
        if (Updater.isNewer(update, info)) {
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
            button.setOnClickListener { install(update, button, bar, cancel) }
            row.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { marginEnd = dp(8) })
            row.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)))
            block.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(10) })
        }
        return block
    }

    private fun install(update: AppUpdate, button: Button, bar: android.widget.ProgressBar, cancel: Button) {
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
}
