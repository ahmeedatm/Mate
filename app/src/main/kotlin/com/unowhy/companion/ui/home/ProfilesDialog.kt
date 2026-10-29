package com.unowhy.companion.ui.home

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import com.unowhy.companion.R
import com.unowhy.companion.ui.dismissKeyboard
import com.unowhy.companion.ui.dp
import com.unowhy.companion.ui.inputField
import com.unowhy.companion.ui.showForTyping
import com.unowhy.companion.ui.showImmersive
import com.unowhy.companion.ui.themeColor

/**
 * Liste des profils de l'Accueil : afficher, modifier les widgets, marquer le profil de jeu, monter, renommer, supprimer, en ajouter un
 * (vide ou depuis un modèle). `current` : le profil affiché ; `onChanged(show, structure)` est appelé après chaque
 * changement, la disposition est déjà modifiée, avec le profil à afficher. `structure` : des profils ont été ajoutés,
 * déplacés ou retirés (les widgets sont à recréer) ; faux pour un renommage ou le marquage de jeu. `onEdit(index)` :
 * la boîte est fermée, le profil `index` est à afficher puis à passer en mode Modifier. `canEdit` faux (grille
 * étroite, sans mode Modifier) : le bouton n'est pas montré.
 */
class ProfilesDialog(
    private val context: Context,
    private val layout: HomeLayout,
    private var current: Int,
    private val name: (Int) -> String,
    private val canEdit: Boolean,
    private val onEdit: (Int) -> Unit,
    private val onChanged: (show: Int, structure: Boolean) -> Unit,
) {

    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private lateinit var dialog: AlertDialog

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), context.dp(8), context.dp(24), context.dp(8))
            addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(caption(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(12) })
        }
        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.profiles_title)
            .setView(root)
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.profile_new_template, null)
            .setPositiveButton(R.string.profile_new_empty, null)
            .create()
        dialog.showImmersive()
        // Posés après l'affichage : la boîte reste ouverte tant qu'un modèle n'est pas choisi.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { added(layout.addProfile(null)) }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { chooseTemplate() }
        render()
    }

    /** Explique l'icône du profil de jeu. */
    private fun caption() = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_gamepad)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setColorFilter(themeColor(context, R.attr.c_text_dim))
        }, LinearLayout.LayoutParams(context.dp(18), context.dp(18)))
        addView(TextView(context).apply {
            setText(R.string.profile_game_hint)
            textSize = 14f
            setTextColor(themeColor(context, R.attr.c_text_dim))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(8) })
    }

    private fun render() {
        list.removeAllViews()
        for (i in 0 until layout.pages) {
            list.addView(row(i), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(56)).apply { if (i > 0) topMargin = context.dp(8) })
        }
    }

    private fun row(i: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.row_bg)
        setPadding(context.dp(16), 0, context.dp(4), 0)
        // Le nom prend la place restante : le toucher affiche le profil.
        addView(TextView(context).apply {
            text = name(i)
            textSize = 18f
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(themeColor(context, if (i == current) R.attr.c_accent else R.attr.c_text))
            setOnClickListener {
                dialog.dismiss()
                onChanged(i, false)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val game = layout.gameProfile == i
        if (canEdit) {
            addView(iconButton(R.drawable.ic_widgets, R.string.profile_edit, R.attr.c_text_dim) {
                dialog.dismiss()
                onEdit(i)
            }.also { TooltipCompat.setTooltipText(it, context.getString(R.string.profile_edit)) })
        }
        addView(iconButton(R.drawable.ic_gamepad, R.string.profile_game, if (game) R.attr.c_accent else R.attr.c_text_faint) {
            layout.setGameProfile(if (game) -1 else i)
            changed(structure = false)
        })
        // Réunion ne se déplace pas : il n'est ni dans la barre ni dans le balayage hors appel.
        if (i > 0 && i != layout.callProfile) addView(iconButton(R.drawable.ic_arrow_up, R.string.profile_move_up, R.attr.c_text_dim) { moveUp(i) })
        addView(iconButton(R.drawable.ic_edit, R.string.profile_rename, R.attr.c_text_dim) { rename(i) })
        if (layout.canRemove(i)) addView(iconButton(R.drawable.ic_delete, R.string.profile_delete, R.attr.c_text_dim) { confirmDelete(i) })
    }

    private fun iconButton(@DrawableRes icon: Int, @StringRes description: Int, @AttrRes tint: Int, onClick: () -> Unit) = ImageButton(context).apply {
        setBackgroundResource(R.drawable.flat_bg)
        setImageResource(icon)
        setColorFilter(themeColor(context, tint))
        contentDescription = context.getString(description)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48))
        setOnClickListener { onClick() }
    }

    /** Relit la liste et prévient l'écran des profils : le profil affiché reste `current`. */
    private fun changed(structure: Boolean) {
        render()
        onChanged(current, structure)
    }

    /** Profil ajouté : la boîte se ferme, le nouveau profil s'affiche. */
    private fun added(index: Int) {
        dialog.dismiss()
        onChanged(index, true)
    }

    private fun moveUp(i: Int) {
        layout.moveProfile(i, i - 1)
        current = when (current) {
            i -> i - 1
            i - 1 -> i
            else -> current
        }
        changed(structure = true)
    }

    private fun rename(i: Int) {
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), 0, context.dp(24), context.dp(8))
        }
        val input = context.inputField(form, R.string.profile_rename_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        input.filters = arrayOf(InputFilter.LengthFilter(NAME_MAX))
        input.setText(name(i))
        input.selectAll()
        AlertDialog.Builder(context)
            .setTitle(R.string.profile_rename)
            .setView(form)
            .setNegativeButton(R.string.cancel) { _, _ -> input.dismissKeyboard() }
            .setPositiveButton(R.string.save) { _, _ ->
                input.dismissKeyboard()
                val value = input.text.toString().trim()
                if (value.isNotEmpty() && value != name(i)) {
                    layout.renameProfile(i, value)
                    changed(structure = false)
                }
            }
            .create()
            .showForTyping(input)
    }

    private fun confirmDelete(i: Int) {
        AlertDialog.Builder(context)
            .setMessage(context.getString(R.string.profile_delete_confirm, name(i)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.profile_delete) { _, _ ->
                val wasShown = current == i
                if (layout.removeProfile(i)) {
                    current = when {
                        wasShown -> i.coerceAtMost(layout.pages - 1)
                        current > i -> current - 1
                        else -> current
                    }
                    changed(structure = true)
                }
            }
            .showImmersive()
    }

    private fun chooseTemplate() {
        val choices = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), context.dp(8), context.dp(24), context.dp(8))
        }
        val picker = AlertDialog.Builder(context)
            .setTitle(R.string.profile_new_template)
            .setView(ScrollView(context).apply { addView(choices) })
            .setNegativeButton(R.string.cancel, null)
            .create()
        Template.entries.filter { it.cadran && it.listed }.forEachIndexed { n, template ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.row_bg)
                setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
                addView(TextView(context).apply {
                    setText(template.title)
                    textSize = 18f
                    setTextColor(themeColor(context, R.attr.c_text))
                })
                addView(TextView(context).apply {
                    setText(template.description)
                    textSize = 14f
                    setTextColor(themeColor(context, R.attr.c_text_dim))
                })
                setOnClickListener {
                    picker.dismiss()
                    added(layout.addProfile(context.getString(template.title), template.widgets(layout.pages)))
                }
            }
            choices.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (n > 0) topMargin = context.dp(8)
            })
        }
        picker.showImmersive()
    }

    private companion object {
        const val NAME_MAX = 30
    }
}
