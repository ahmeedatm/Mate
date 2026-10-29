package com.unowhy.companion.ui

import android.animation.AnimatorInflater
import android.graphics.drawable.GradientDrawable
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
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.Computer
import com.unowhy.companion.Computers
import com.unowhy.companion.MainActivity
import com.unowhy.companion.R
import com.unowhy.companion.installers.BackendPlan
import com.unowhy.companion.installers.CompanionUpdater
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.Link
import com.unowhy.companion.net.runCatchingCancellable
import kotlinx.coroutines.launch

/**
 * Ordinateurs (toucher l'ordinateur en pied de barre) : une carte par ordinateur appairé, celui piloté en tête avec
 * son état (pastille de la barre), son adresse et, lus à l'ouverture, la version du compagnon et ce qui l'attend
 * (Accessibilité à autoriser sur un Mac, nouvelle version). Toucher une carte = piloter cet ordinateur, ✎ = renommer ;
 * en bas, ajouter un ordinateur (recherche sur le WiFi ou adresse) et les Réglages.
 */
class ComputersDialog(
    private val activity: MainActivity,
    private val link: Link,
    private val adder: ComputerAdder,
    private val onSwitch: (Computer) -> Unit,
    private val onRename: (Computer) -> Unit,
    private val onSettings: () -> Unit,
) {

    private lateinit var dialog: AlertDialog
    private val fontTitle = themeFont(activity, R.attr.c_font_title)
    private val fontMedium = themeFont(activity, R.attr.c_font_medium)

    /** Ligne d'infos de l'ordinateur piloté, complétée quand /health répond. */
    private var activeMeta: TextView? = null

    /** Place des alertes de l'ordinateur piloté (Accessibilité, mise à jour), vide tant que rien n'est à faire. */
    private var activeAlerts: LinearLayout? = null

    fun show() {
        val active = Computers.active(activity)
        // L'ordinateur piloté en tête, les autres du plus récemment utilisé au plus ancien.
        val computers = Computers.all(activity).sortedWith(compareBy<Computer> { it.id != active?.id }.thenByDescending { it.lastUsedMs })
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(28), activity.dp(24), activity.dp(28), activity.dp(24))
            addView(header())
            computers.forEachIndexed { n, computer ->
                addView(card(computer, computer.id == active?.id), wide(top = if (n == 0) 20 else 8))
            }
            addView(TextView(activity, null, 0, R.style.CardTitle).apply { setText(R.string.computers_add) }, wide(top = 24))
            addView(addRow(), wide(top = 10))
            addView(settingsLink(), wide(top = 12))
        }
        dialog = AlertDialog.Builder(activity)
            .setView(ScrollView(activity).apply { addView(content) })
            .create()
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(themeColor(activity, R.attr.c_bg))
            cornerRadius = activity.dp(28).toFloat()
        })
        dialog.showImmersive()
        dialog.window?.setLayout(activity.dp(WIDTH_DP).coerceAtMost(activity.resources.displayMetrics.widthPixels - activity.dp(48)), ViewGroup.LayoutParams.WRAP_CONTENT)
        if (active != null) readActive(active)
    }

    private fun wide(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = activity.dp(top)
    }

    private fun header() = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                setText(R.string.computers_title)
                textSize = 28f
                typeface = fontTitle
                setTextColor(themeColor(activity, R.attr.c_text))
            })
            addView(TextView(activity).apply {
                setText(R.string.computers_subtitle)
                textSize = 14f
                setTextColor(themeColor(activity, R.attr.c_text_dim))
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(iconButton(R.drawable.ic_close, R.string.close) { dialog.dismiss() })
    }

    /** Carte d'un ordinateur : pastille Mac/Windows, nom, « Piloté », état, adresse ; toucher = le piloter. */
    private fun card(computer: Computer, active: Boolean) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.row_bg)
        setPadding(activity.dp(14), activity.dp(12), activity.dp(4), activity.dp(12))
        val os = ComputerAdder.osLabel(activity, computer.os)
        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(activity).apply {
                val hue = if (computer.os == "windows") R.attr.c_hue_blue else R.attr.c_hue_violet
                setImageDrawable(Look.badge(activity, if (computer.os == "windows") R.drawable.ic_display else R.drawable.ic_laptop, themeColor(activity, hue), sizeDp = 44, iconDp = 24))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(activity.dp(44), activity.dp(44)))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(activity).apply {
                        text = computer.name
                        textSize = 18f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        if (active) typeface = fontMedium
                        setTextColor(themeColor(activity, R.attr.c_text))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f))
                    if (active) addView(tag(R.string.computer_driven), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = activity.dp(10)
                    })
                })
                addView(TextView(activity).apply {
                    text = listOfNotNull(os, computer.address).joinToString(" · ")
                    textSize = 13f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(themeColor(activity, R.attr.c_text_dim))
                    if (active) activeMeta = this
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = activity.dp(14) })
            if (active) addView(state(link.state)) else addView(TextView(activity).apply {
                setText(R.string.computer_drive)
                textSize = 13f
                typeface = fontMedium
                setTextColor(themeColor(activity, R.attr.c_text_dim))
            })
            addView(iconButton(R.drawable.ic_edit, R.string.computer_rename) {
                dialog.dismiss()
                onRename(computer)
            })
        })
        if (active) {
            activeAlerts = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            addView(activeAlerts, wide(top = 4))
        }
        isClickable = true
        isFocusable = true
        contentDescription = listOfNotNull(computer.name, activity.getString(R.string.computer_driven).takeIf { active }, os).joinToString(", ")
        setOnClickListener {
            dialog.dismiss()
            if (!active) onSwitch(computer)
        }
    }

    /** État de l'ordinateur piloté : même pastille (forme et couleur) que la barre, et le mot. */
    private fun state(state: Link.State) = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        val mark = linkMark(state)
        val color = themeColor(
            activity,
            when (state) {
                Link.State.ONLINE -> R.attr.c_ok
                Link.State.UNAUTHORIZED -> R.attr.c_warn
                else -> R.attr.c_text_faint
            },
        )
        addView(View(activity).apply {
            setBackgroundResource(mark.shape.drawable)
            background.mutate().setTint(color)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(activity.dp(10), activity.dp(10)))
        addView(TextView(activity).apply {
            text = activity.getString(mark.label ?: mark.spoken)
            textSize = 14f
            setTextColor(themeColor(activity, R.attr.c_text_dim))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = activity.dp(8) })
    }

    /** Pilule de l'accent atténué (comme « Affiché » des profils). */
    private fun tag(@StringRes text: Int) = TextView(activity).apply {
        setText(text)
        textSize = 12f
        typeface = fontMedium
        val accent = themeColor(activity, R.attr.c_accent)
        setTextColor(accent)
        setPadding(activity.dp(10), activity.dp(3), activity.dp(10), activity.dp(3))
        background = GradientDrawable().apply {
            setColor(ColorUtils.setAlphaComponent(accent, 0x24))
            cornerRadius = activity.dp(12).toFloat()
        }
    }

    /**
     * Lu à l'ouverture, hors du fil principal : version du compagnon (/health), Accessibilité refusée sur un Mac et
     * nouvelle version du compagnon. Rien ne s'affiche si l'ordinateur ne répond pas (l'état le dit déjà).
     */
    private fun readActive(computer: Computer) = activity.lifecycleScope.launch {
        val status = runCatchingCancellable { CompanionUpdater.check(activity) }.getOrNull() ?: return@launch
        val identity = status.identity ?: return@launch
        if (!dialog.isShowing) return@launch
        identity.version?.takeIf { it != "dev" }?.let { version ->
            activeMeta?.let { it.text = activity.getString(R.string.computer_version, it.text, version) }
        }
        if (identity.accessibility == false) {
            alert(R.drawable.ic_accessibility, R.string.computer_alert_accessibility, R.string.computer_alert_accessibility_action) {
                activity.lifecycleScope.launch { runCatchingCancellable { BackendClient(activity).system.openAccessibility() } }
            }
        }
        val decision = status.decision
        if (decision is BackendPlan.Decision.Available) {
            alert(R.drawable.ic_refresh, activity.getString(R.string.computer_alert_update, decision.release.version), R.string.computer_alert_update_action) {
                dialog.dismiss()
                activity.companionUpdate.confirm(identity, decision.release)
            }
        }
        if (computer.id != Computers.active(activity)?.id) activeAlerts?.visibility = View.GONE
    }

    private fun alert(@DrawableRes icon: Int, @StringRes text: Int, @StringRes action: Int, onClick: () -> Unit) = alert(icon, activity.getString(text), action, onClick)

    /** Une chose à faire pour l'ordinateur piloté : icône, phrase, bouton. */
    private fun alert(@DrawableRes icon: Int, text: String, @StringRes action: Int, onClick: () -> Unit) {
        val box = activeAlerts ?: return
        box.visibility = View.VISIBLE
        box.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(58), activity.dp(4), activity.dp(10), 0)
            addView(ImageView(activity).apply {
                setImageResource(icon)
                setColorFilter(themeColor(activity, R.attr.c_warn))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(activity.dp(18), activity.dp(18)))
            addView(TextView(activity).apply {
                this.text = text
                textSize = 14f
                setTextColor(themeColor(activity, R.attr.c_text))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = activity.dp(8) })
            addView(TextView(activity, null, 0, R.style.Chip).apply {
                setText(action)
                gravity = Gravity.CENTER
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(40)))
        }, wide(top = 4))
    }

    /** Ajouter un ordinateur : recherche sur le WiFi ou adresse saisie (la suite est celle des Réglages). */
    private fun addRow() = LinearLayout(activity).apply {
        addView(addTile(R.drawable.ic_wifi, R.attr.c_hue_green, R.string.computer_add_search, R.string.computer_add_search_desc) { adder.search() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(addTile(R.drawable.ic_tile_keyboard, R.attr.c_hue_orange, R.string.computer_add_manual, R.string.computer_add_manual_desc) { adder.manual() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = activity.dp(10)
        })
    }

    private fun addTile(@DrawableRes icon: Int, @AttrRes hue: Int, @StringRes title: Int, @StringRes description: Int, onClick: () -> Unit) = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.row_bg)
        setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(14))
        isClickable = true
        isFocusable = true
        stateListAnimator = AnimatorInflater.loadStateListAnimator(activity, R.animator.press)
        addView(ImageView(activity).apply {
            setImageDrawable(Look.badge(activity, icon, themeColor(activity, hue), sizeDp = 40, iconDp = 22))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(activity.dp(40), activity.dp(40)))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                setText(title)
                textSize = 16f
                typeface = fontMedium
                setTextColor(themeColor(activity, R.attr.c_text))
            })
            addView(TextView(activity).apply {
                setText(description)
                textSize = 12f
                setTextColor(themeColor(activity, R.attr.c_text_dim))
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = activity.dp(12) })
        contentDescription = activity.getString(title)
        setOnClickListener {
            dialog.dismiss()
            onClick()
        }
    }

    /** Lien discret vers Réglages (retirer un ordinateur, réappairer). */
    private fun settingsLink() = TextView(activity).apply {
        setText(R.string.computers_settings)
        textSize = 14f
        typeface = fontMedium
        gravity = Gravity.CENTER_VERTICAL
        minHeight = activity.dp(48)
        setTextColor(themeColor(activity, R.attr.c_text_dim))
        setOnClickListener {
            dialog.dismiss()
            onSettings()
        }
    }

    private fun iconButton(@DrawableRes icon: Int, @StringRes description: Int, onClick: () -> Unit) = ImageButton(activity).apply {
        setBackgroundResource(R.drawable.flat_bg)
        setImageResource(icon)
        setColorFilter(themeColor(activity, R.attr.c_text_dim))
        contentDescription = activity.getString(description)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(12))
        layoutParams = LinearLayout.LayoutParams(activity.dp(48), activity.dp(48))
        setOnClickListener { onClick() }
    }

    private companion object {
        /** Largeur de la boîte, comme celle des profils. */
        const val WIDTH_DP = 760
    }
}
