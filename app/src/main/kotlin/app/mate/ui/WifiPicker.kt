package app.mate.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.mate.R
import app.mate.net.Wifi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Choix du réseau WiFi depuis Réglages (en kiosque, Paramètres d'Android est inaccessible) : réseaux autour,
 * mot de passe, réseau masqué. Hors kiosque, ouvre le panneau WiFi du système.
 */
class WifiPicker(private val activity: Activity, private val scope: CoroutineScope, private val onChanged: () -> Unit) {

    private val context get() = activity

    fun show() {
        if (!Wifi.canConfigure(context)) {
            val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            runCatching { activity.startActivity(Intent(action)) }
            return
        }
        Wifi.prepare(context)
        val form = form()
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        form.body.addView(list)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.wifi_choose)
            .setView(form.root)
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.wifi_refresh, null)
            .setPositiveButton(R.string.wifi_other, null)
            .create()
        var busy = false
        fun scan() {
            if (busy) return
            busy = true
            form.message.setText(R.string.wifi_searching)
            scope.launch {
                val networks = runCatching { Wifi.scan(context) }.getOrDefault(emptyList())
                busy = false
                // La localisation vient peut-être d'être activée : le nom du réseau actuel devient lisible.
                onChanged()
                list.removeAllViews()
                form.message.text = when {
                    networks.isNotEmpty() -> context.getString(R.string.wifi_hint)
                    !Wifi.locationOn(context) -> context.getString(R.string.wifi_no_location)
                    else -> context.getString(R.string.wifi_none)
                }
                for (network in networks) {
                    list.addView(row(list, network) {
                        dialog.dismiss()
                        when {
                            network.connected -> Unit
                            network.saved || !network.secure -> connect(network.ssid, null, network.sae)
                            else -> askPassword(network)
                        }
                    })
                }
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { scan() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dialog.dismiss(); askHidden() }
        }
        dialog.showImmersive()
        scan()
    }

    private fun row(parent: LinearLayout, network: Wifi.Network, onClick: () -> Unit): View {
        val row = LayoutInflater.from(context).inflate(R.layout.item_window, parent, false)
        row.findViewById<TextView>(R.id.window_title).text = network.ssid
        row.findViewById<TextView>(R.id.window_meta).text = listOfNotNull(
            context.getString(R.string.wifi_connected).takeIf { network.connected },
            context.getString(R.string.wifi_saved).takeIf { network.saved && !network.connected },
            context.getString(if (network.secure) R.string.wifi_secure else R.string.wifi_open),
            context.resources.getStringArray(R.array.wifi_levels)[network.level.coerceIn(0, 3)],
        ).joinToString(" · ")
        row.setOnClickListener { onClick() }
        // Appui long sur un réseau enregistré : l'oublier (mot de passe changé, ancien réseau).
        if (network.saved) row.setOnLongClickListener { forget(network.ssid); true }
        (row.layoutParams as? LinearLayout.LayoutParams)?.topMargin = context.dp(8)
        return row
    }

    private fun askPassword(network: Wifi.Network) {
        val form = form()
        form.message.text = context.getString(R.string.wifi_password_for, network.ssid)
        val password = context.inputField(form.body, R.string.wifi_password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        showPasswordToggle(form.body, password)
        val dialog = AlertDialog.Builder(context)
            .setTitle(network.ssid)
            .setView(form.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.wifi_connect, null)
            .create()
        val go = {
            val text = password.text.toString()
            if (text.length >= 8) {
                dialog.dismiss()
                connect(network.ssid, text, network.sae)
            } else {
                form.message.setText(R.string.wifi_password_short)
            }
        }
        onDone(password) { go() }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { go() } }
        showWithKeyboard(dialog, password)
    }

    /** Réseau masqué (absent de la liste) : nom et mot de passe tapés. */
    private fun askHidden() {
        val form = form()
        form.message.setText(R.string.wifi_other_hint)
        val name = context.inputField(form.body, R.string.wifi_name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        name.imeOptions = EditorInfo.IME_ACTION_NEXT
        val password = context.inputField(form.body, R.string.wifi_password_optional, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        showPasswordToggle(form.body, password)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.wifi_other)
            .setView(form.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.wifi_connect, null)
            .create()
        val go = {
            val ssid = name.text.toString().trim()
            val key = password.text.toString()
            when {
                ssid.isEmpty() -> form.message.setText(R.string.wifi_name_missing)
                key.isNotEmpty() && key.length < 8 -> form.message.setText(R.string.wifi_password_short)
                else -> {
                    dialog.dismiss()
                    connect(ssid, key.ifEmpty { null }, sae = false, hidden = true)
                }
            }
        }
        onDone(password) { go() }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { go() } }
        showWithKeyboard(dialog, name)
    }

    private fun connect(ssid: String, password: String?, sae: Boolean, hidden: Boolean = false) {
        val form = form()
        form.message.text = context.getString(R.string.wifi_connecting, ssid)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.wifi_title)
            .setView(form.root)
            .setPositiveButton(R.string.close, null)
            .create()
        dialog.showImmersive()
        scope.launch {
            val ok = runCatching { Wifi.connect(context, ssid, password, sae, hidden) }
            form.message.text = when {
                ok.getOrNull() == true -> context.getString(R.string.wifi_done, ssid)
                ok.isFailure -> context.errorText(ok.exceptionOrNull())
                password != null -> context.getString(R.string.wifi_failed_password, ssid)
                else -> context.getString(R.string.wifi_failed, ssid)
            }
            onChanged()
        }
    }

    private fun forget(ssid: String) {
        AlertDialog.Builder(context)
            .setMessage(context.getString(R.string.wifi_forget_confirm, ssid))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.wifi_forget) { _, _ ->
                Wifi.forget(context, ssid)
                onChanged()
            }
            .showImmersive()
    }

    private class Form(val root: View, val body: LinearLayout, val message: TextView)

    private fun form(): Form {
        val root = LayoutInflater.from(context).inflate(R.layout.view_dialog_form, null)
        return Form(root, root.findViewById(R.id.dialog_body), root.findViewById(R.id.dialog_message))
    }

    /** Mot de passe lisible sur demande : une clé WiFi se tape mal à l'aveugle. */
    private fun showPasswordToggle(parent: LinearLayout, password: EditText) {
        parent.addView(CheckBox(context).apply {
            setText(R.string.wifi_show_password)
            textSize = 16f
            setOnCheckedChangeListener { _, checked ->
                password.inputType = InputType.TYPE_CLASS_TEXT or
                    if (checked) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
                password.setSelection(password.text.length)
            }
        })
    }

    private fun onDone(input: EditText, action: () -> Unit) {
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { action(); true } else false
        }
    }

    private fun showWithKeyboard(dialog: AlertDialog, input: EditText) = dialog.showForTyping(input)
}
