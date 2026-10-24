package com.unowhy.companion.ui

import android.content.Context
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.unowhy.companion.Computer
import com.unowhy.companion.Computers
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.Discovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Boîtes de dialogue des ordinateurs. Ajout : recherche sur le WiFi (ou adresse saisie), puis code à 6 chiffres affiché sur
 * l'ordinateur. Rien à recopier d'autre : le jeton arrive avec le code. `onAdded` : enregistré et devenu l'actif.
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
        val input = input(form.body, R.string.computer_address_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
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
                        val identity = BackendClient(url, connectTimeoutMs = 4_000).identity()
                        dialog.dismiss()
                        pair(url, identity.name)
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

    /** Demande un code à l'ordinateur (il l'affiche), puis l'envoie : la réponse contient le jeton. */
    fun pair(url: String, name: String) {
        val form = form()
        val input = input(form.body, R.string.computer_code_hint, InputType.TYPE_CLASS_NUMBER).apply {
            textSize = 30f
            letterSpacing = 0.3f
            gravity = Gravity.CENTER
            filters = arrayOf(InputFilter.LengthFilter(6))
            isEnabled = false
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.computer_pair_title, name))
            .setView(form.root)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.computer_new_code, null)
            .setPositiveButton(R.string.computer_pair, null)
            .create()
        val client = BackendClient(url, connectTimeoutMs = 4_000)
        var request: String? = null
        var busy = false
        val ask = {
            request = null
            input.isEnabled = false
            form.message.text = context.getString(R.string.computer_requesting, name)
            scope.launch {
                try {
                    request = client.requestPairing(context.getString(R.string.tablet_name, Build.MODEL))
                    input.setText("")
                    input.isEnabled = true
                    input.requestFocus()
                    form.message.text = context.getString(R.string.computer_code_prompt, name)
                } catch (e: Exception) {
                    form.message.text = reason(e)
                }
            }
            Unit
        }
        val confirm = confirm@{
            val req = request ?: return@confirm
            if (busy) return@confirm
            val code = input.text.toString().filter(Char::isDigit)
            if (code.length != 6) return@confirm form.message.setText(R.string.computer_code_short)
            form.message.setText(R.string.computer_checking)
            busy = true
            scope.launch {
                try {
                    val p = client.confirmPairing(req, code)
                    // Déjà connu (nouvel appairage) : on garde le nom choisi sur la tablette.
                    val known = Computers.all(context).firstOrNull { it.id == p.id }
                    val computer = Computer(p.id, known?.name ?: p.name, url, p.token, p.os)
                    Computers.save(context, computer, makeActive = true)
                    dialog.dismiss()
                    onAdded(Computers.active(context) ?: computer)
                } catch (e: Exception) {
                    form.message.text = reason(e)
                    // Code expiré ou trop d'essais : il faut en redemander un.
                    if (e is BackendClient.HttpException && e.code != 403) request = null
                    else if (e is BackendClient.HttpException) input.setText("")
                } finally {
                    busy = false
                }
            }
            Unit
        }
        onDone(input) { confirm() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { ask() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { confirm() }
            ask()
        }
        showWithKeyboard(dialog, input)
    }

    /** Renommer un ordinateur : nom affiché sur la tablette seulement (barre latérale, liste). */
    fun rename(computer: Computer, onRenamed: () -> Unit) {
        val form = form()
        form.message.visibility = View.GONE
        val input = input(form.body, R.string.computer_name_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
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
        is BackendClient.HttpException -> e.reason
        else -> context.getString(R.string.computer_lost)
    }

    private fun form(): Form {
        val root = LayoutInflater.from(context).inflate(R.layout.view_dialog_form, null)
        return Form(root, root.findViewById(R.id.dialog_body), root.findViewById(R.id.dialog_message))
    }

    /** Champ gonflé depuis item_input.xml (un EditText construit avec un style perd focus et curseur). */
    private fun input(parent: LinearLayout, hint: Int, type: Int): EditText {
        val input = LayoutInflater.from(context).inflate(R.layout.item_input, parent, false) as EditText
        input.setHint(hint)
        input.inputType = type
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        parent.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (16 * context.resources.displayMetrics.density).toInt()
        })
        return input
    }

    private fun row(parent: LinearLayout, title: String, meta: String, onClick: () -> Unit): View {
        val row = LayoutInflater.from(context).inflate(R.layout.item_window, parent, false)
        row.findViewById<TextView>(R.id.window_title).text = title
        row.findViewById<TextView>(R.id.window_meta).text = meta
        row.setOnClickListener { onClick() }
        (row.layoutParams as? LinearLayout.LayoutParams)?.topMargin = (8 * context.resources.displayMetrics.density).toInt()
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
