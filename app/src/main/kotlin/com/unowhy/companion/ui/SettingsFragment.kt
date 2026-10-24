package com.unowhy.companion.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.Computer
import com.unowhy.companion.Computers
import com.unowhy.companion.MainActivity
import com.unowhy.companion.Prefs
import com.unowhy.companion.R
import com.unowhy.companion.kiosk.Kiosk
import com.unowhy.companion.kiosk.Updater
import com.unowhy.companion.net.AppUpdate
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.Link
import com.unowhy.companion.net.Wifi
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private lateinit var kioskStatus: TextView
    private lateinit var kioskActions: View
    private lateinit var allowInstalls: SwitchCompat
    private lateinit var allowInstallsHint: View
    private lateinit var autoJoin: SwitchCompat
    private lateinit var autoJoinHint: TextView
    private lateinit var computers: LinearLayout
    private lateinit var wifiStatus: TextView
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
        kioskStatus = view.findViewById(R.id.settings_kiosk_status)
        kioskActions = view.findViewById(R.id.settings_kiosk_actions)
        allowInstalls = view.findViewById(R.id.settings_allow_installs)
        allowInstallsHint = view.findViewById(R.id.settings_allow_installs_hint)
        autoJoin = view.findViewById(R.id.settings_autojoin)
        autoJoinHint = view.findViewById(R.id.settings_autojoin_hint)
        wifiStatus = view.findViewById(R.id.settings_wifi_status)
        view.findViewById<Button>(R.id.settings_wifi).setOnClickListener { wifi.show() }
        updatesStatus = view.findViewById(R.id.settings_updates_status)
        updates = view.findViewById(R.id.settings_updates)
        view.findViewById<Button>(R.id.settings_updates_check).setOnClickListener { checkUpdates() }
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
                .show()
        }
        renderKiosk()
        loadAutoJoin()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        if (!hidden) {
            renderComputers()
            renderWifi()
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
                setText(R.string.computers_empty)
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
                ContextCompat.getColorStateList(ctx, if (isActive) R.color.accent else R.color.text_dim),
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
            .show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** L'option vit sur l'ordinateur : c'est lui qui ouvre Teams, même tablette en veille. */
    private fun loadAutoJoin() {
        if (Computers.active(requireContext()) == null) {
            autoJoin.isEnabled = false
            autoJoinHint.setText(R.string.no_computer)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                showAutoJoin(BackendClient(requireContext()).autoJoin())
                autoJoin.isEnabled = true
                autoJoinHint.setText(R.string.autojoin_hint)
            } catch (e: Exception) {
                autoJoin.isEnabled = false
                autoJoinHint.text = getString(R.string.error_prefix, e.message ?: e.javaClass.simpleName)
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
                BackendClient(requireContext()).setAutoJoin(enabled)
                autoJoinHint.setText(R.string.autojoin_hint)
            } catch (e: Exception) {
                showAutoJoin(!enabled)
                autoJoinHint.text = getString(R.string.error_prefix, e.message ?: e.javaClass.simpleName)
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
                updatesStatus.text = getString(R.string.error_prefix, e.message ?: e.javaClass.simpleName)
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
                            if (percent >= 3 && view != null) {
                                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                                updatesStatus.text = getString(R.string.updates_eta, duration(elapsed * (100 - percent) / percent))
                            }
                        }
                    }
                }, committed = {
                    cancel.post {
                        cancel.visibility = View.GONE
                        if (view != null) updatesStatus.text = getString(R.string.updates_committed, installMinutes(update))
                    }
                })
                showResult(button, bar, ok = true)
                if (view != null) updatesStatus.setText(R.string.updates_hint)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Annulé : retour à l'état de départ, rien d'installé.
                bar.visibility = View.GONE
                button.isEnabled = true
                button.setText(R.string.updates_install)
                if (view != null) updatesStatus.setText(R.string.updates_cancelled)
            } catch (e: Exception) {
                showResult(button, bar, ok = false)
                if (view != null) updatesStatus.text = getString(R.string.error_prefix, e.message ?: e.javaClass.simpleName)
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
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(button.context, if (ok) R.color.ok else R.color.ko))
        button.setText(if (ok) R.string.updates_done else R.string.updates_failed)
        button.isEnabled = !ok
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
