package app.mate.ui

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
import app.mate.BuildConfig
import app.mate.Computer
import app.mate.Computers
import app.mate.R
import app.mate.installers.InstallerHint
import app.mate.installers.InstallerService
import app.mate.net.BackendClient
import app.mate.net.Discovery
import app.mate.net.HttpException
import app.mate.net.InsecureComputerException
import app.mate.net.OutdatedCompanionException
import app.mate.net.Pairing
import app.mate.net.PairingCode
import app.mate.net.PinnedTrust
import app.mate.net.isLocalUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.net.ssl.SSLException

/**
 * Boîtes de dialogue des ordinateurs. Ajout : l'ordinateur dont la page de Mate est affichée est pris sur le WiFi (ou adresse
 * saisie), puis code à 6 chiffres affiché par la tablette et saisi dans cette page. Rien à recopier d'autre : le jeton arrive
 * une fois le code accepté. `onAdded` : enregistré et devenu l'actif.
 * `scope` : celui de l'activité, pour survivre à la recréation des écrans quand l'ordinateur change.
 */
class ComputerAdder(private val context: Context, private val scope: CoroutineScope, private val onAdded: (Computer) -> Unit) {

    private class Form(val root: View, val body: LinearLayout, val message: TextView)

    /**
     * Ajout sans liste : la boîte attend qu'un ordinateur « attende une tablette » (sa page de Mate est affichée), puis
     * montre le code à saisir dans cette page. Recherche répétée toutes les 4 s tant que la boîte est ouverte.
     */
    fun search() {
        val form = form()
        form.message.setText(R.string.computer_search_hint)
        val code = codeView()
        form.body.addView(code)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.computer_add)
            .setView(form.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.computer_add_manual, null)
            .create()
        val auto = AutoPairing(
            context, scope,
            onAdded = { dialog.dismiss(); onAdded(it) },
            onFound = {},
            onCode = { digits, name ->
                showCode(code, digits)
                form.message.text = context.getString(R.string.computer_code_show, name)
            },
            onMessage = { form.message.text = it },
            onFailed = {
                code.visibility = View.GONE
                form.message.text = it
            },
            onIdle = { form.message.setText(R.string.computer_search_hint) },
        )
        // Après un échec : toucher le message relance la recherche.
        form.message.setOnClickListener { auto.retry() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dialog.dismiss(); manual() }
            scope.launch {
                while (dialog.isShowing) {
                    auto.round()
                    delay(4_000)
                }
            }
        }
        dialog.setOnDismissListener { auto.cancel() }
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
     * Appairage d'un ordinateur choisi par son adresse (protocole : `PairingSession`) : la tablette affiche un code,
     * l'utilisateur le saisit dans la page de Mate de l'ordinateur, la tablette reçoit alors le jeton.
     */
    fun pair(url: String, name: String, trust: PinnedTrust = PinnedTrust(null)) {
        val form = form()
        val code = codeView()
        form.body.addView(code)
        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.computer_pair_title, name))
            .setView(form.root)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.computer_new_code, null)
            .create()
        val session = PairingSession(
            context, scope, url, name, trust,
            onCode = {
                showCode(code, it)
                form.message.text = context.getString(R.string.computer_code_show, name)
            },
            onMessage = { form.message.text = it },
            onFailed = { text, outdated ->
                code.visibility = View.GONE
                if (outdated) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = View.GONE
                form.message.text = text
            },
            onAdded = { dialog.dismiss(); onAdded(it) },
        )
        dialog.setOnShowListener {
            if (PairingSession.isOutdated(url)) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = View.GONE
                form.message.text = PairingSession.outdatedText(context, name)
                return@setOnShowListener
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { code.visibility = View.GONE; session.start() }
            session.start()
        }
        dialog.setOnDismissListener { session.cancel() }
        dialog.showImmersive()
    }

    private fun codeView() = TextView(context).apply {
        textSize = 44f
        letterSpacing = 0.15f
        gravity = Gravity.CENTER
        typeface = themeFont(context, R.attr.c_font_display)
        setTextColor(themeColor(context, R.attr.c_text))
        visibility = View.GONE
    }

    private fun showCode(view: TextView, digits: String) {
        view.text = digits
        view.contentDescription = PairingCode.spoken(digits)
        view.visibility = View.VISIBLE
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

    private fun form(): Form {
        val root = LayoutInflater.from(context).inflate(R.layout.view_dialog_form, null)
        return Form(root, root.findViewById(R.id.dialog_body), root.findViewById(R.id.dialog_message))
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
