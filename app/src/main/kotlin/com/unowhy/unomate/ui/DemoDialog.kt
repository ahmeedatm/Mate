package com.unowhy.unomate.ui

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.unowhy.unomate.Computers
import com.unowhy.unomate.R
import com.unowhy.unomate.net.BackendClient
import com.unowhy.unomate.net.runCatchingCancellable
import com.unowhy.unomate.ui.home.Demo
import com.unowhy.unomate.ui.home.KeyRef
import com.unowhy.unomate.ui.home.Persona
import kotlinx.coroutines.launch

/**
 * Démo produit (Réglages > Démo) : la liste des personas ; en choisir un remplace les écrans de la barre par les siens,
 * change la taille du texte, le thème et le mot d'éveil, et pose ses données fictives sur l'ordinateur (touches, pages
 * de touches, plugins, tâches, agenda). « Quitter la démo » rend l'état d'avant (src/demo.ts). L'activité est recréée
 * ensuite et arrive sur le premier écran du persona.
 */
class DemoDialog(private val activity: FragmentActivity) {

    fun show() {
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(24), activity.dp(8), activity.dp(24), activity.dp(8))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.demo_title)
            .setView(ScrollView(activity).apply { addView(list) })
            .setNegativeButton(R.string.cancel, null)
            .create()
        val active = Demo.active(activity)
        if (active != null) list.addCard(R.string.demo_quit, null, selected = false) { dialog.dismiss(); quit() }
        for (persona in Persona.entries) {
            list.addCard(persona.title, persona.description, selected = persona == active) { dialog.dismiss(); choose(persona) }
        }
        dialog.showImmersive()
    }

    private fun LinearLayout.addCard(title: Int, description: Int?, selected: Boolean, onClick: () -> Unit) {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.row_bg)
            isSelected = selected
            setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12))
            addView(TextView(activity).apply {
                setText(title)
                textSize = 18f
                setTextColor(themeColor(activity, R.attr.c_text))
            })
            if (description != null) {
                addView(TextView(activity).apply {
                    setText(description)
                    textSize = 14f
                    setTextColor(themeColor(activity, R.attr.c_text_dim))
                })
            }
            setOnClickListener { onClick() }
        }
        addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (childCount > 0) topMargin = activity.dp(8)
        })
    }

    private fun fail(e: Throwable) = Toast.makeText(activity, activity.errorText(e), Toast.LENGTH_LONG).show()

    private fun choose(persona: Persona) {
        val computer = Computers.active(activity) ?: return Toast.makeText(activity, R.string.demo_no_computer, Toast.LENGTH_LONG).show()
        activity.lifecycleScope.launch {
            runCatchingCancellable { BackendClient(activity).demo.setPersona(persona.id) }
                .onSuccess { setup ->
                    val pages = setup.pages.mapValues { (_, keys) -> keys.map { KeyRef(it.id, it.label, it.glyph, it.color, it.symbol) } }
                    val layout = persona.layout(persona.profiles.map { activity.getString(it.name) }, activity.getString(R.string.profile_call), pages, computer.id)
                    Demo.start(activity, persona, layout)
                    activity.recreate()
                }
                .onFailure(::fail)
        }
    }

    private fun quit() {
        activity.lifecycleScope.launch {
            runCatchingCancellable { BackendClient(activity).demo.quit() }
                .onSuccess {
                    Demo.quit(activity)
                    activity.recreate()
                }
                .onFailure(::fail)
        }
    }
}
