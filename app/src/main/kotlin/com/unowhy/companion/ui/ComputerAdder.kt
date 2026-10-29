package com.unowhy.companion.ui

import android.content.Context
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.unowhy.companion.Computer
import com.unowhy.companion.Computers
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.Discovery
import com.unowhy.companion.net.HttpException
import com.unowhy.companion.net.InsecureComputerException
import com.unowhy.companion.net.Pairing
import com.unowhy.companion.net.PairingCode
import com.unowhy.companion.net.PinnedTrust
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.net.ssl.SSLException

/**
 * Boîtes de dialogue des ordinateurs. Ajout : recherche sur le WiFi (ou adresse saisie), puis code à 6 chiffres affiché par
 * la tablette et saisi sur l'ordinateur. Rien à recopier d'autre : le jeton arrive une fois le code accepté. `onAdded` : enregistré et devenu l'actif.
 * `scope` : celui de l'activité, pour survivre à la recréation des écrans quand l'ordinateur change.
 */
class ComputerAdder(private val context: Context, private val scope: CoroutineScope, private val onAdded: (Computer) -> Unit) {

    private class Form(val root: View, val body: LinearLayout, val message: TextView)

    fun search() {
        val form = form()
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        form.body.addView(list)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.computer_add)
            .setView(form.root)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.computer_search_again, null)
            .setPositiveButton(R.string.computer_add_manual, null)
            .create()
        val run = {
            form.message.setText(R.string.computer_searching)
            list.removeAllViews()
            scope.launch {
                val found = runCatching { Discovery.find(timeoutMs = 2_000) }.getOrDefault(emptyList())
                if (!dialog.isShowing) return@launch
                form.message.setText(if (found.isEmpty()) R.string.computer_none_found else R.string.computer_pick)
                val known = Computers.all(context).map { it.id }.toSet()
                for (f in found) {
                    val meta = listOfNotNull(
                        osLabel(context, f.os), f.url.substringAfter("://"),
                        context.getString(R.string.computer_known).takeIf { f.id in known },
                    ).joinToString(" · ")
                    list.addView(row(list, f.name, meta) { dialog.dismiss(); pair(f.url, f.name) })
                }
            }
            Unit
        }
        dialog.setOnShowListener {
            // Boutons qui ne ferment pas la boîte.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { run() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dialog.dismiss(); manual() }
            run()
        }
        dialog.showImmersive()
    }

    /** Adresse saisie : l'ordinateur n'est pas trouvé (autre réseau, diffusion bloquée, émulateur : 10.0.2.2). */
    fun manual() {
        val form = form()
        form.message.setText(R.string.computer_manual_hint)
        val input = context.inputField(form.body, R.string.computer_address_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.computer_add_manual)
            .setView(form.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.computer_connect, null)
            .create()
        var busy = false
        val connect = {
            val text = input.text.toString().trim()
            if (text.isNotEmpty() && !busy) {
                busy = true
                val url = Computers.normalizeUrl(text)
                form.message.setText(R.string.computer_connecting)
                scope.launch {
                    try {
                        // Même certificat retenu du premier contact jusqu'au jeton.
                        val trust = PinnedTrust(null)
                        val identity = BackendClient(url, connectTimeoutMs = 4_000, trust = trust).pairing.identity()
                        dialog.dismiss()
                        pair(url, identity.name, trust)
                    } catch (e: Exception) {
                        form.message.text = context.getString(R.string.computer_unreachable, url.substringAfter("://"))
                    } finally {
                        busy = false
                    }
                }
            }
        }
        onDone(input) { connect() }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { connect() } }
        showWithKeyboard(dialog, input)
    }

    /**
     * Appairage (protocole : backend/src/pairing.ts) : la tablette affiche un code, l'utilisateur le saisit sur
     * l'ordinateur, la tablette reçoit alors le jeton. Le code mêle l'empreinte du certificat vu par la tablette :
     * s'il passe par un intercepteur, l'ordinateur refuse le code et le jeton ne part jamais. `trust` : certificat
     * déjà vu (adresse saisie), sinon retenu à la première requête.
     */
    fun pair(url: String, name: String, trust: PinnedTrust = PinnedTrust(null)) {
        val form = form()
        val code = TextView(context).apply {
            textSize = 44f
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            typeface = themeFont(context, R.attr.c_font_display)
            setTextColor(themeColor(context, R.attr.c_text))
            visibility = View.GONE
        }
        form.body.addView(code)
        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.computer_pair_title, name))
            .setView(form.root)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.computer_new_code, null)
            .create()
        val client = BackendClient(url, connectTimeoutMs = 4_000, trust = trust)
        var job: Job? = null
        val start = {
            job?.cancel()
            code.visibility = View.GONE
            form.message.text = context.getString(R.string.computer_requesting, name)
            job = scope.launch {
                try {
                    val secret = PairingCode.newSecret()
                    val (request, computerNonce) = client.pairing.request(context.getString(R.string.tablet_name, Build.MODEL), secret.commit)
                    client.pairing.reveal(request, secret.nonce)
                    // En HTTP local (USB), pas de certificat : "" comme sur l'ordinateur.
                    val channel = if (url.startsWith("https://")) trust.seen.orEmpty() else ""
                    code.text = PairingCode.spaced(PairingCode.code(channel, secret.nonce, computerNonce))
                    code.visibility = View.VISIBLE
                    form.message.text = context.getString(R.string.computer_code_show, name)
                    var pairing: Pairing? = null
                    while (pairing == null) {
                        delay(1_000)
                        if (!dialog.isShowing) return@launch
                        pairing = client.pairing.confirm(request)
                    }
                    // En WiFi, l'ordinateur annonce le certificat que la tablette a vu ; en USB, elle le découvre ici.
                    if (channel.isNotEmpty() && pairing.fingerprint != channel) throw InsecureComputerException()
                    val known = Computers.all(context).firstOrNull { it.id == pairing.id }
                    val computer = Computer(
                        pairing.id, known?.name ?: pairing.name, url, pairing.token, pairing.os,
                        pin = channel.ifEmpty { pairing.fingerprint.ifEmpty { null } },
                    )
                    Computers.save(context, computer, makeActive = true)
                    dialog.dismiss()
                    onAdded(Computers.active(context) ?: computer)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    code.visibility = View.GONE
                    form.message.text = reason(e)
                }
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { start() }
            start()
        }
        dialog.setOnDismissListener { job?.cancel() }
        dialog.showImmersive()
    }

    /** Renommer un ordinateur : nom affiché sur la tablette seulement (barre latérale, liste). */
    fun rename(computer: Computer, onRenamed: () -> Unit) {
        val form = form()
        form.message.visibility = View.GONE
        val input = context.inputField(form.body, R.string.computer_name_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        input.setText(computer.name)
        input.setSelectAllOnFocus(true)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.computer_rename)
            .setView(form.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                Computers.rename(context, computer.id, input.text.toString())
                onRenamed()
            }
            .create()
        onDone(input) {
            Computers.rename(context, computer.id, input.text.toString())
            dialog.dismiss()
            onRenamed()
        }
        showWithKeyboard(dialog, input)
    }

    /** Touche OK du clavier : une seule fois (un clavier physique envoie l'appui puis le relâchement). */
    private fun onDone(input: EditText, action: () -> Unit) {
        input.setOnEditorActionListener { _, _, event ->
            if (event == null || event.action == KeyEvent.ACTION_DOWN) action()
            true
        }
    }

    private fun reason(e: Exception): String = when (e) {
        is HttpException -> e.reason
        is InsecureComputerException, is SSLException -> context.getString(R.string.computer_insecure)
        else -> context.getString(R.string.computer_lost)
    }

    private fun form(): Form {
        val root = LayoutInflater.from(context).inflate(R.layout.view_dialog_form, null)
        return Form(root, root.findViewById(R.id.dialog_body), root.findViewById(R.id.dialog_message))
    }

    private fun row(parent: LinearLayout, title: String, meta: String, onClick: () -> Unit): View {
        val row = LayoutInflater.from(context).inflate(R.layout.item_window, parent, false)
        row.findViewById<TextView>(R.id.window_title).text = title
        row.findViewById<TextView>(R.id.window_meta).text = meta
        row.setOnClickListener { onClick() }
        (row.layoutParams as? LinearLayout.LayoutParams)?.topMargin = context.dp(8)
        return row
    }

    private fun showWithKeyboard(dialog: AlertDialog, input: EditText) = dialog.showForTyping(input)

    companion object {
        fun osLabel(context: Context, os: String): String? = when (os) {
            "mac" -> context.getString(R.string.os_mac)
            "windows" -> context.getString(R.string.os_windows)
            else -> null
        }
    }
}
