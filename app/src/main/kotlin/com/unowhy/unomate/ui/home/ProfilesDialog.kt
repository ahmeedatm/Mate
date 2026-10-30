package com.unowhy.unomate.ui.home

import android.animation.AnimatorInflater
import android.content.Context
import android.graphics.drawable.GradientDrawable
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
import android.widget.Space
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import androidx.core.graphics.ColorUtils
import com.unowhy.unomate.R
import com.unowhy.unomate.ui.Look
import com.unowhy.unomate.ui.dismissKeyboard
import com.unowhy.unomate.ui.dp
import com.unowhy.unomate.ui.inputField
import com.unowhy.unomate.ui.showForTyping
import com.unowhy.unomate.ui.showImmersive
import com.unowhy.unomate.ui.themeColor
import com.unowhy.unomate.ui.themeFont

/**
 * Liste des profils de l'Accueil : afficher, changer l'icône, modifier les widgets, monter, descendre, renommer, supprimer, en ajouter un
 * (vide ou depuis un modèle). `current` : le profil affiché ; `onChanged(show, structure)` est appelé après chaque
 * changement, la disposition est déjà modifiée, avec le profil à afficher. `structure` : des profils ont été ajoutés,
 * déplacés ou retirés (les widgets sont à recréer) ; faux pour un renommage. `onEdit(index)` :
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
    private val fontTitle = themeFont(context, R.attr.c_font_title)
    private val fontMedium = themeFont(context, R.attr.c_font_medium)

    /**
     * Une carte large aux formes de Cadran : en-tête (titre, fermer), les profils, puis « Nouveau profil » en tuiles
     * (vide ou un modèle, un seul toucher).
     */
    fun show() {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(28), context.dp(24), context.dp(28), context.dp(24))
            addView(header())
            addView(section(R.string.profiles_yours), wide(top = 20))
            addView(list, wide(top = 10))
            addView(section(R.string.profiles_add), wide(top = 24))
            addView(templates(), wide(top = 10))
        }
        dialog = AlertDialog.Builder(context)
            .setView(ScrollView(context).apply { addView(content) })
            .create()
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(themeColor(context, R.attr.c_bg))
            cornerRadius = context.dp(28).toFloat()
        })
        dialog.showImmersive()
        dialog.window?.setLayout(context.dp(WIDTH_DP).coerceAtMost(context.resources.displayMetrics.widthPixels - context.dp(48)), ViewGroup.LayoutParams.WRAP_CONTENT)
        render()
    }

    private fun wide(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = context.dp(top)
    }

    /** Titre, phrase d'aide et croix pour fermer. */
    private fun header() = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                setText(R.string.profiles_title)
                textSize = 28f
                typeface = fontTitle
                setTextColor(themeColor(context, R.attr.c_text))
            })
            addView(TextView(context).apply {
                setText(R.string.profiles_subtitle)
                textSize = 14f
                setTextColor(themeColor(context, R.attr.c_text_dim))
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(iconButton(R.drawable.ic_close, R.string.close, R.attr.c_text_dim) { dialog.dismiss() })
    }

    /** Petit titre de section, comme ceux des cartes. */
    private fun section(@StringRes text: Int) = TextView(context, null, 0, R.style.CardTitle).apply { setText(text) }

    private fun render() {
        list.removeAllViews()
        // Dans l'ordre de la barre, Réunion (caché hors appel) à la fin.
        val shown = layout.profilesInOrder(inCall = false) + listOfNotNull(layout.callProfile.takeIf { it >= 0 })
        shown.forEachIndexed { n, i ->
            list.addView(row(i), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(64)).apply { if (n > 0) topMargin = context.dp(8) })
        }
    }

    /** Pastille de l'écran : son icône ([ProfileIcons], choisie sinon celle du rôle), teinte du rôle. */
    private fun profileIcon(i: Int, choice: String? = layout.iconOf(i)): Pair<Int, Int> = ProfileIcons.of(layout, i, choice) to when (i) {
        layout.callProfile -> R.attr.c_hue_violet
        layout.homeProfile -> R.attr.c_hue_blue
        else -> R.attr.c_hue_green
    }

    private fun row(i: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.row_bg)
        setPadding(context.dp(12), 0, context.dp(4), 0)
        val shown = i == current
        val (icon, hue) = profileIcon(i)
        // Le nom (avec sa pastille) prend la place restante : le toucher affiche le profil.
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            // La pastille (icône de la barre de gauche) : la toucher en choisit une autre.
            addView(ImageView(context).apply {
                setImageDrawable(Look.badge(context, icon, themeColor(context, hue), sizeDp = 40, iconDp = 22))
                contentDescription = context.getString(R.string.profile_icon_row, name(i))
                setOnClickListener {
                    chooseIcon(i, ProfileIcons.keyOf(layout, i)) { key ->
                        layout.setIcon(i, key)
                        changed(structure = false)
                    }
                }
            }, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
            addView(TextView(context).apply {
                text = name(i)
                textSize = 18f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                typeface = if (shown) fontMedium else typeface
                setTextColor(themeColor(context, R.attr.c_text))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f).apply {
                marginStart = context.dp(14)
            })
            if (shown) addView(shownTag(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.dp(10)
            })
            contentDescription = if (shown) "${name(i)}, ${context.getString(R.string.profile_shown)}" else name(i)
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
        addView(iconButton(R.drawable.ic_edit, R.string.profile_rename, R.attr.c_text_dim) { editNameAndIcon(i) })
        slot(if (layout.canRemove(i)) iconButton(R.drawable.ic_delete, R.string.profile_delete, R.attr.c_text_dim) { confirmDelete(i) } else null)
    }

    /** Le bouton, ou une place vide de même taille. */
    private fun LinearLayout.slot(button: ImageButton?) = addView(button ?: Space(context).apply { layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48)) })

    /** Étiquette « Affiché » du profil à l'écran : texte de l'accent sur une pilule de l'accent atténué. */
    private fun shownTag() = TextView(context).apply {
        setText(R.string.profile_shown)
        textSize = 12f
        typeface = fontMedium
        val accent = themeColor(context, R.attr.c_accent)
        setTextColor(accent)
        setPadding(context.dp(10), context.dp(3), context.dp(10), context.dp(3))
        background = GradientDrawable().apply {
            setColor(ColorUtils.setAlphaComponent(accent, 0x24))
            cornerRadius = context.dp(12).toFloat()
        }
    }

    /** Tuiles de création : vide, puis les modèles proposés ; un toucher crée le profil et l'affiche. */
    private fun templates() = LinearLayout(context).apply {
        val choices = listOf<Triple<Int, Int, () -> Unit>>(
            Triple(R.string.profile_blank, R.string.profile_blank_desc) { added(layout.addProfile(null)) },
        ) + Template.entries.filter { it.cadran && it.listed }.map { template ->
            Triple(template.title, template.description) {
                added(layout.addProfile(layout.uniqueName(context.getString(template.title)), template.widgets(layout.pages)))
            }
        }
        val icons = listOf(R.drawable.ic_add to R.attr.c_hue_green) + Template.entries.filter { it.cadran && it.listed }.map(::templateIcon)
        choices.forEachIndexed { n, (title, description, create) ->
            addView(tile(icons[n].first, icons[n].second, title, description, create), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (n > 0) marginStart = context.dp(10)
            })
        }
    }

    private fun templateIcon(template: Template): Pair<Int, Int> = when (template) {
        Template.WORK_CADRAN, Template.WORK -> R.drawable.ic_tasks to R.attr.c_hue_blue
        Template.STANDBY_CADRAN, Template.STANDBY -> R.drawable.ic_clock to R.attr.c_hue_orange
        Template.DECK_CADRAN -> R.drawable.ic_deck to R.attr.c_hue_red
        else -> R.drawable.ic_widgets to R.attr.c_hue_violet
    }

    private fun tile(@DrawableRes icon: Int, @AttrRes hue: Int, @StringRes title: Int, @StringRes description: Int, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.row_bg)
        setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
        isClickable = true
        isFocusable = true
        stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.animator.press)
        addView(ImageView(context).apply {
            setImageDrawable(Look.badge(context, icon, themeColor(context, hue), sizeDp = 40, iconDp = 22))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
        addView(TextView(context).apply {
            setText(title)
            textSize = 16f
            typeface = fontMedium
            setTextColor(themeColor(context, R.attr.c_text))
        }, wide(top = 10))
        addView(TextView(context).apply {
            setText(description)
            textSize = 12f
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(themeColor(context, R.attr.c_text_dim))
        }, wide(top = 2))
        contentDescription = "${context.getString(R.string.profiles_add)} : ${context.getString(title)}"
        setOnClickListener { onClick() }
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

    /**
     * Grille des icônes ([ProfileIcons]), centrée : `shown` (la choisie, sinon celle du rôle) en rouge ; « Par défaut »
     * remet celle du rôle (maison pour l'Accueil). Le choix est rendu à `onPick` (null = par défaut), rien n'est enregistré ici.
     */
    private fun chooseIcon(i: Int, shown: String?, onPick: (String?) -> Unit) {
        val grid = android.widget.GridLayout(context).apply { columnCount = ICON_COLUMNS }
        lateinit var picker: AlertDialog
        for (icon in ProfileIcons.ALL) {
            val isShown = icon.key == shown
            val label = context.getString(icon.label)
            grid.addView(
                iconButton(icon.drawable, icon.label, if (isShown) R.attr.c_accent else R.attr.c_text) {
                    picker.dismiss()
                    onPick(icon.key)
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
            .setNeutralButton(R.string.profile_icon_default) { _, _ -> onPick(null) }
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

    /** Crayon : le nom et l'icône dans la même boîte ; Enregistrer applique les deux, Annuler aucun. */
    private fun editNameAndIcon(i: Int) {
        var icon = layout.iconOf(i)
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(24), context.dp(8), context.dp(24), context.dp(8))
        }
        val input = context.inputField(form, R.string.profile_rename_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        input.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(16) }
        input.filters = arrayOf(InputFilter.LengthFilter(NAME_MAX))
        input.setText(name(i))
        input.selectAll()
        // La pastille montre l'icône en attente ; la toucher ouvre la grille.
        val badge = ImageView(context).apply {
            contentDescription = context.getString(R.string.profile_icon_row, name(i))
            setOnClickListener {
                input.dismissKeyboard()
                chooseIcon(i, ProfileIcons.keyOf(layout, i, icon)) { key ->
                    icon = key
                    showBadge(this, i, icon)
                }
            }
        }
        showBadge(badge, i, icon)
        form.addView(badge, 0, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        AlertDialog.Builder(context)
            .setTitle(R.string.profile_rename)
            .setView(form)
            .setNegativeButton(R.string.cancel) { _, _ -> input.dismissKeyboard() }
            .setPositiveButton(R.string.save) { _, _ ->
                input.dismissKeyboard()
                val value = input.text.toString().trim()
                val renamed = value.isNotEmpty() && value != name(i)
                val reiconed = icon != layout.iconOf(i)
                if (renamed) layout.renameProfile(i, value)
                if (reiconed) layout.setIcon(i, icon)
                if (renamed || reiconed) changed(structure = false)
            }
            .create()
            .showForTyping(input)
    }

    private fun showBadge(view: ImageView, i: Int, choice: String?) {
        val (drawable, hue) = profileIcon(i, choice)
        view.setImageDrawable(Look.badge(context, drawable, themeColor(context, hue), sizeDp = 48, iconDp = 26))
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

    companion object {
        /** Longueur maximale d'un nom de profil (aussi vérifiée pour ceux envoyés depuis l'ordinateur, LayoutConfig). */
        const val NAME_MAX = 30

        /** Icônes par ligne dans la grille de choix. */
        private const val ICON_COLUMNS = 8

        /** Largeur de la boîte : les cinq tuiles de création tiennent sur une ligne. */
        private const val WIDTH_DP = 820
    }
}
