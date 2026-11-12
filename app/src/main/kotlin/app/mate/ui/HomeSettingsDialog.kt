package app.mate.ui

import android.text.InputType
import android.view.LayoutInflater
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import app.mate.Computers
import app.mate.MainActivity
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.HaConfig
import app.mate.net.HaEntity
import app.mate.net.HomeChoice
import app.mate.net.WledApi
import app.mate.net.WledDevices
import app.mate.net.WledDiscovery
import app.mate.net.haUrlProblem
import app.mate.net.homeLightSource
import app.mate.net.isWledHost
import app.mate.net.normalizeHaUrl
import app.mate.net.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Réglages de Maison : Home Assistant en direct (adresse, jeton chiffré, case « Autoriser HTTP », ou copie depuis un
 * ordinateur appairé), WLED du WiFi (trouvées seules ou saisies) et ce que le widget Maison montre, coché à la main.
 * Le jeton n'est jamais réaffiché : le champ reste vide tant qu'on n'en saisit pas un nouveau.
 */
class HomeSettingsDialog(private val activity: MainActivity) {

    private val root = LayoutInflater.from(activity).inflate(R.layout.view_dialog_form, null)
    private val body: LinearLayout = root.findViewById(R.id.dialog_body)
    private val message: TextView = root.findViewById(R.id.dialog_message)
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private lateinit var dialog: AlertDialog

    fun show() {
        message.setText(R.string.home_settings_hint)
        body.addView(TitleView(activity).apply { setText(R.string.home_ha_title); setPadding(0, activity.dp(16), 0, 0) })
        val url = activity.inputField(body, R.string.home_ha_url_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        url.setText(HaConfig.url(activity).orEmpty())
        val token = activity.inputField(body, R.string.home_ha_token_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        if (HaConfig.url(activity) != null) token.setHint(R.string.home_ha_token_kept)
        // Le mode mot de passe d'Android pose une police à chasse fixe : on garde celle des autres champs.
        token.typeface = url.typeface
        val http = CheckBox(activity).apply {
            setText(R.string.home_ha_allow_http)
            textSize = 16f
            minHeight = activity.dp(44)
            isChecked = HaConfig.allowHttp(activity)
        }
        body.addView(http)
        body.addView(hint(R.string.home_ha_http_warning))
        val actions = row()
        actions.addView(button(R.string.home_ha_save) {
            val address = normalizeHaUrl(url.text.toString())
            val problem = haUrlProblem(address, http.isChecked)
            val secret = token.text.toString().trim()
            when {
                problem != null -> message.setText(problem)
                secret.isEmpty() -> message.setText(R.string.home_ha_token_missing)
                else -> if (save(address, secret, http.isChecked)) {
                    token.text.clear()
                    token.setHint(R.string.home_ha_token_kept)
                }
            }
        })
        if (Computers.all(activity).isNotEmpty()) actions.addView(button(R.string.home_ha_copy) { copyFromComputer(http.isChecked) }, margin())
        body.addView(actions)

        body.addView(TitleView(activity).apply { setText(R.string.home_wled_title); setPadding(0, activity.dp(20), 0, 0) })
        val wledHost = activity.inputField(body, R.string.home_wled_host_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val wledActions = row()
        wledActions.addView(button(R.string.home_wled_add) { addWled(wledHost.text.toString().trim()) })
        wledActions.addView(button(R.string.home_wled_search) { searchWled() }, margin())
        body.addView(wledActions)

        body.addView(TitleView(activity).apply { setText(R.string.home_shown_title); setPadding(0, activity.dp(20), 0, 0) })
        body.addView(list)
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.home_name)
            .setView(root)
            .setPositiveButton(R.string.close, null)
            .create()
        dialog.showForTyping(null, keyboard = false)
        reload()
    }

    private fun save(url: String, token: String, allowHttp: Boolean): Boolean {
        if (!HaConfig.save(activity, url, token, allowHttp)) {
            message.setText(R.string.home_ha_keystore_failed)
            return false
        }
        message.setText(R.string.home_ha_saved)
        reload()
        return true
    }

    /**
     * Ordinateur le plus récemment piloté : sur Maison, aucun n'est actif. Le HTTP n'est jamais accordé par l'ordinateur
     * (`HA_ALLOW_HTTP`) : seulement par la case cochée ici, sur la tablette (`allowHttp`).
     */
    private fun copyFromComputer(allowHttp: Boolean) {
        val computer = Computers.all(activity).maxByOrNull { it.lastUsedMs } ?: return
        message.setText(R.string.home_ha_copying)
        activity.lifecycleScope.launch {
            runCatchingCancellable { BackendClient(computer).ha.settings() }
                .onSuccess { s ->
                    val problem = haUrlProblem(s.url, allowHttp)
                    if (problem != null) message.setText(problem) else save(s.url, s.token, allowHttp)
                }
                .onFailure { message.text = activity.errorText(it) }
        }
    }

    private fun addWled(host: String) {
        if (host.isEmpty()) return
        if (!isWledHost(host)) return message.setText(R.string.home_wled_bad_host)
        message.setText(R.string.home_wled_adding)
        activity.lifecycleScope.launch {
            val device = runCatchingCancellable { withContext(Dispatchers.IO) { WledApi.info(host) } }.getOrNull()
            if (device == null) return@launch message.setText(R.string.home_wled_not_found)
            WledDevices.save(activity, device)
            message.text = activity.getString(R.string.home_wled_added, device.name)
            reload()
        }
    }

    private fun searchWled() {
        message.setText(R.string.home_wled_searching)
        activity.lifecycleScope.launch {
            WledDiscovery.refresh(activity)
            message.text = activity.getString(R.string.home_wled_count, WledDevices.all(activity).size)
            reload()
        }
    }

    /** Liste à cocher : chaque entité avec sa source, cochée selon le choix gardé (sinon le défaut de [HomeChoice]). */
    private fun reload() {
        activity.lifecycleScope.launch {
            val all = runCatchingCancellable { homeLightSource(activity).all() }
            list.removeAllViews()
            all.onFailure { list.addView(hint(0).apply { text = activity.errorText(it) }) }
            val entities = all.getOrNull().orEmpty().sortedBy { !HomeChoice.isWledDirect(it.id) }
            if (all.isSuccess && entities.isEmpty()) list.addView(hint(R.string.home_shown_empty))
            for (e in entities) list.addView(choice(e))
        }
    }

    private fun choice(e: HaEntity) = CheckBox(activity).apply {
        val source = activity.getString(if (HomeChoice.isWledDirect(e.id)) R.string.home_source_wled else R.string.home_source_ha)
        text = activity.getString(R.string.ha_entity_inline, e.name, source)
        textSize = 16f
        minHeight = activity.dp(44)
        isChecked = HomeChoice.isShown(activity, e.id)
        setOnCheckedChangeListener { _, on -> HomeChoice.set(activity, e.id, on) }
    }

    private fun hint(text: Int) = TextView(activity).apply {
        if (text != 0) setText(text)
        textSize = 14f
        setTextColor(themeColor(activity, R.attr.c_text_dim))
    }

    private fun row() = LinearLayout(activity).apply { setPadding(0, activity.dp(12), 0, 0) }

    private fun margin() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        marginStart = activity.dp(12)
    }

    private fun button(text: Int, onClick: () -> Unit) = Button(activity, null, 0, R.style.ActionButton).apply {
        setText(text)
        setOnClickListener {
            it.dismissKeyboard()
            onClick()
        }
    }
}
