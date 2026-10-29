package com.unowhy.companion.ui.home

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
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
 * Liste des profils de l'Accueil : afficher, changer l'icône, modifier les widgets, monter, descendre, renommer, supprimer, en ajouter un
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

    private fun render() {
        list.removeAllViews()
        // Dans l'ordre de la barre, Réunion (caché hors appel) à la fin.
        val shown = layout.profilesInOrder(inCall = false) + listOfNotNull(layout.callProfile.takeIf { it >= 0 })
        shown.forEachIndexed { n, i ->
            list.addView(row(i), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(56)).apply { if (n > 0) topMargin = context.dp(8) })
        }
    }

    private fun row(i: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.row_bg)
        setPadding(context.dp(4), 0, context.dp(4), 0)
        // L'icône de l'écran (barre de gauche) : la toucher en choisit une autre.
        addView(iconButton(ProfileIcons.of(layout, i), R.string.profile_icon, R.attr.c_text) { chooseIcon(i) }.apply {
            contentDescription = context.getString(R.string.profile_icon_row, name(i))
        })
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
        if (canEdit) {
            addView(iconButton(R.drawable.ic_widgets, R.string.profile_edit, R.attr.c_text_dim) {
                dialog.dismiss()
                onEdit(i)
            }.also { TooltipCompat.setTooltipText(it, context.getString(R.string.profile_edit)) })
        }
        // Monter / descendre parmi les écrans de la barre (Réunion, caché hors appel, est sauté et ne bouge pas). Colonnes
        // fixes : un bouton absent garde sa place, les boutons restent alignés d'une ligne à l'autre.
        val order = layout.profilesInOrder(inCall = false)
        val pos = order.indexOf(i)
        slot(if (pos > 0) iconButton(R.drawable.ic_arrow_up, R.string.profile_move_up, R.attr.c_text_dim) { move(i, order[pos - 1]) } else null)
        slot(
            if (pos in 0 until order.lastIndex) {
                iconButton(R.drawable.ic_arrow_up, R.string.profile_move_down, R.attr.c_text_dim) { move(i, order[pos + 1]) }.apply { rotation = 180f }
            } else {
                null
            },
        )
        addView(iconButton(R.drawable.ic_edit, R.string.profile_rename, R.attr.c_text_dim) { rename(i) })
        slot(if (layout.canRemove(i)) iconButton(R.drawable.ic_delete, R.string.profile_delete, R.attr.c_text_dim) { confirmDelete(i) } else null)
    }

    /** Le bouton, ou une place vide de même taille. */
    private fun LinearLayout.slot(button: ImageButton?) = addView(button ?: Space(context).apply { layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48)) })

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

    /**
     * Grille des icônes ([ProfileIcons]), centrée : l'actuelle (choisie, sinon celle du rôle) en rouge ; « Par défaut »
     * remet celle du rôle (maison pour l'Accueil).
     */
    private fun chooseIcon(i: Int) {
        val grid = android.widget.GridLayout(context).apply { columnCount = ICON_COLUMNS }
        lateinit var picker: AlertDialog
        val shown = ProfileIcons.keyOf(layout, i)
        for (icon in ProfileIcons.ALL) {
            val isShown = icon.key == shown
            val label = context.getString(icon.label)
            grid.addView(
                iconButton(icon.drawable, icon.label, if (isShown) R.attr.c_accent else R.attr.c_text) {
                    layout.setIcon(i, icon.key)
                    picker.dismiss()
                    changed(structure = false)
                }.apply {
                    contentDescription = if (isShown) context.getString(R.string.profile_icon_chosen, label) else label
                    setBackgroundResource(if (isShown) R.drawable.chip_bg else R.drawable.flat_bg)
                    isSelected = isShown
                    layoutParams = android.widget.GridLayout.LayoutParams().apply {
                        width = context.dp(52)
                        height = context.dp(52)
                        setMargins(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
                    }
                },
            )
        }
        picker = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.profile_icon_title, name(i)))
            .setView(
                ScrollView(context).apply {
                    addView(
                        LinearLayout(context).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                            setPadding(context.dp(16), context.dp(8), context.dp(16), 0)
                            addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                        },
                    )
                },
            )
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.profile_icon_default) { _, _ ->
                layout.setIcon(i, null)
                changed(structure = false)
            }
            .create()
        picker.showImmersive()
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

    /** Met le profil `from` à la place de `to` ; le profil affiché suit ([HomeLayout.moveProfile] décale ceux d'entre les deux). */
    private fun move(from: Int, to: Int) {
        layout.moveProfile(from, to)
        current = when {
            current == from -> to
            from < to && current in from + 1..to -> current - 1
            from > to && current in to until from -> current + 1
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

        /** Icônes par ligne dans la grille de choix. */
        const val ICON_COLUMNS = 8
    }
}
