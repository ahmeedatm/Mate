package app.mate.ui

import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.mate.BuildConfig
import app.mate.Computers
import app.mate.MainActivity
import app.mate.Prefs
import app.mate.R
import app.mate.lock.LockAttempts
import app.mate.lock.LockKind
import app.mate.lock.LockLogic

/**
 * Tablette inutilisable : écran plein et opaque dans sa propre fenêtre, donc par-dessus tout, boîtes de dialogue
 * comprises ; aucun toucher ne passe, retour sans effet. Deux verrous indépendants (lock/LockLogic), gardés dans les
 * préférences (tenus hors ligne et après une relance de l'appli) :
 * - à la main, par le cadenas de la barre : pavé à 4 chiffres, levé seulement par le code ;
 * - ordinateur actif verrouillé : ordre `lock` (backend/src/screen-lock.ts) ou réponse 423, levé quand l'ordinateur se
 *   dit déverrouillé. Ce blocage se coupe dans Réglages > Tablette (Prefs.blockOnComputerLock) : la tablette reste
 *   utilisable, la pastille de la barre dit « verrouillé ».
 * Sortie de secours (code oublié, ordinateur disparu) : appui long, puis code technicien, qui lève les deux.
 */
class LockScreen(private val activity: MainActivity) {
    private var dialog: Dialog? = null
    private var shownKind = LockKind.NONE
    private val attempts = LockAttempts()
    private var typed = ""
    private var dots: TextView? = null
    private var message: TextView? = null

    val isShown get() = dialog?.isShowing == true

    /** Création de l'activité : l'écran revient si un verrou était posé. */
    fun restore() = render()

    fun setComputerLocked(locked: Boolean) {
        // Chaque 423 redit « verrouillé » : préférence et pastille (ui/Navigation.renderLink) touchées au seul changement.
        if (locked != Prefs.computerLocked(activity)) {
            Prefs.setComputerLocked(activity, locked)
            activity.refreshLink()
        }
        render()
    }

    /** Réglages > Tablette : coupé, l'écran plein de l'ordinateur verrouillé part tout de suite ; remis, il revient. */
    fun setBlockOnComputerLock(block: Boolean) {
        Prefs.setBlockOnComputerLock(activity, block)
        render()
    }

    /** Cadenas de la barre : verrouille, après avoir fait choisir le code s'il n'y en a pas encore. */
    fun lockTablet() {
        if (Prefs.lockCodeHash(activity) == null) return chooseCode { lockTablet() }
        Prefs.setTabletLocked(activity, true)
        render()
    }

    /** Choix du code (Réglages > Tablette, ou premier verrouillage) : 4 chiffres, tapés deux fois. */
    fun chooseCode(then: (() -> Unit)? = null) {
        val number = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), 0, dp(24), 0) }
        val code = activity.inputField(column, R.string.lock_code_hint_new, number)
        val again = activity.inputField(column, R.string.lock_code_hint_again, number)
        val ask = AlertDialog.Builder(activity)
            .setTitle(R.string.lock_code_title)
            .setView(column)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm, null)
            .create()
        ask.showForTyping(code)
        ask.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = code.text.toString()
            when {
                !LockLogic.validCode(value) -> code.error = activity.getString(R.string.lock_code_invalid)
                value != again.text.toString() -> again.error = activity.getString(R.string.lock_code_mismatch)
                else -> {
                    Prefs.setLockCodeHash(activity, LockLogic.hash(value, Prefs.lockSalt(activity)))
                    ask.dismiss()
                    then?.invoke()
                }
            }
        }
    }

    /** Activité détruite (recréée par un thème, une mise à jour) : la fenêtre part avec elle, [restore] la remet. */
    fun dismiss() {
        dialog?.dismiss()
        dialog = null
        shownKind = LockKind.NONE
    }

    private fun render() {
        val kind = LockLogic.kind(Prefs.tabletLocked(activity), Prefs.computerLocked(activity), Prefs.blockOnComputerLock(activity))
        if (kind == LockKind.NONE) return dismiss()
        if (activity.isFinishing || activity.isDestroyed) return
        if (kind == shownKind && isShown) return
        typed = ""
        val window = dialog ?: Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setCancelable(false)
            window?.setBackgroundDrawable(ColorDrawable(themeColor(activity, R.attr.c_bg)))
        }.also { dialog = it }
        window.setContentView(content(kind))
        shownKind = kind
        if (!window.isShowing) {
            activity.currentFocus?.dismissKeyboard()
            window.showImmersive()
        }
    }

    /** Vues construites avec le contexte de l'activité : ses attributs de thème (`c_…`), pas ceux de la fenêtre. */
    private fun content(kind: LockKind): View {
        val ctx = activity
        val title = ctx.getString(if (kind == LockKind.TABLET) R.string.lock_tablet_title else R.string.lock_computer_title)
        val body = if (kind == LockKind.TABLET) {
            ctx.getString(R.string.lock_tablet_body)
        } else {
            ctx.getString(R.string.lock_computer_body, Computers.active(ctx)?.name ?: ctx.getString(R.string.lock_computer_fallback))
        }
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                TextClock(ctx).apply {
                    format12Hour = "HH:mm"
                    format24Hour = "HH:mm"
                    textSize = 96f
                    includeFontPadding = false
                    setTextColor(themeColor(ctx, R.attr.c_text))
                    typeface = themeFont(ctx, R.attr.c_font_display)
                    fontFeatureSettings = "tnum"
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
            )
            addView(
                ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_lock)
                    imageTintList = themeColorList(ctx, R.attr.c_text_dim)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)).apply { topMargin = ctx.dp(32) },
            )
            addView(text(32f, R.attr.c_text, R.attr.c_font_medium).apply { text = title; isFocusable = true }, wrap(16))
            addView(text(20f, R.attr.c_text_dim, R.attr.c_font_regular).apply { text = body }, wrap(8))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(ctx.dp(48), ctx.dp(24), ctx.dp(48), ctx.dp(24))
            addView(info)
            if (kind == LockKind.TABLET) addView(pad().apply { setPaddingRelative(ctx.dp(96), 0, 0, 0) })
        }
        return FrameLayout(ctx).apply {
            // Les touchers s'arrêtent ici ; l'appui long ouvre la sortie de secours.
            isClickable = true
            setOnLongClickListener { askTechCode(); true }
            addView(row, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    /** Points du code tapé, message (faux, attente), puis pavé 1-9, effacer, 0. */
    private fun pad(): View {
        val ctx = activity
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        dots = text(28f, R.attr.c_text, R.attr.c_font_regular).apply { letterSpacing = 0.3f }
        message = text(16f, R.attr.c_ko, R.attr.c_font_regular)
        column.addView(dots, wrap(0))
        column.addView(message, wrap(4))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", DELETE)
        keys.chunked(3).forEach { line ->
            val keyRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            line.forEach { key -> keyRow.addView(key(key), LinearLayout.LayoutParams(ctx.dp(KEY_DP), ctx.dp(KEY_DP)).apply { setMargins(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6)) }) }
            column.addView(keyRow, wrap(0))
        }
        showTyped()
        return column
    }

    private fun key(key: String): View = when (key) {
        "" -> View(activity)
        DELETE -> ImageButton(activity, null, 0, R.style.IconButton).apply {
            setImageResource(R.drawable.ic_back)
            // `tint` du style ne s'applique qu'aux vues AppCompat gonflées : teinte posée ici.
            imageTintList = themeColorList(activity, R.attr.c_text)
            contentDescription = activity.getString(R.string.lock_delete)
            setOnClickListener { typed = typed.dropLast(1); showTyped() }
        }
        else -> Button(activity, null, 0, R.style.Chip).apply {
            text = key
            textSize = 28f
            setOnClickListener { type(key) }
        }
    }

    private fun type(digit: String) {
        val wait = attempts.waitLeft(SystemClock.elapsedRealtime())
        if (wait > 0) return showTyped()
        typed += digit
        if (typed.length < LockLogic.CODE_LENGTH) return showTyped()
        val ok = LockLogic.hash(typed, Prefs.lockSalt(activity)) == Prefs.lockCodeHash(activity)
        typed = ""
        if (ok) {
            attempts.reset()
            Prefs.setTabletLocked(activity, false)
            return render()
        }
        attempts.fail(SystemClock.elapsedRealtime())
        showTyped(wrong = true)
    }

    /** Points pleins pour les chiffres tapés ; attente en cours : compte à rebours relu chaque seconde. */
    private fun showTyped(wrong: Boolean = false) {
        val d = dots ?: return
        d.text = "●".repeat(typed.length) + "○".repeat(LockLogic.CODE_LENGTH - typed.length)
        d.contentDescription = activity.getString(R.string.lock_typed, typed.length, LockLogic.CODE_LENGTH)
        val wait = attempts.waitLeft(SystemClock.elapsedRealtime())
        message?.text = when {
            wait > 0 -> activity.getString(R.string.lock_wait, ((wait + 999) / 1000).toInt())
            wrong -> activity.getString(R.string.lock_code_wrong)
            else -> ""
        }
        if (wrong || wait > 0) message?.announceForAccessibility(message?.text)
        if (wait > 0) d.postDelayed({ if (dots === d) showTyped() }, 1_000)
    }

    private fun text(size: Float, colorAttr: Int, fontAttr: Int) = TextView(activity).apply {
        textSize = size
        setTextColor(themeColor(activity, colorAttr))
        typeface = themeFont(activity, fontAttr)
        gravity = Gravity.CENTER
    }

    private fun wrap(top: Int) =
        LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(top) }

    /** Code technicien (celui de la carte Kiosque) : lève les deux verrous. */
    private fun askTechCode() {
        val input = (activity.layoutInflater.inflate(R.layout.item_input, null, false) as EditText).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val box = FrameLayout(activity).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        val ask = AlertDialog.Builder(activity)
            .setTitle(R.string.tech_code_title)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm, null)
            .create()
        ask.showForTyping(input)
        ask.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (input.text.toString() == BuildConfig.TECH_CODE) {
                ask.dismiss()
                Prefs.setTabletLocked(activity, false)
                setComputerLocked(false)
            } else {
                input.setText("")
                input.error = activity.getString(R.string.tech_code_wrong)
            }
        }
    }

    private companion object {
        const val DELETE = "⌫"
        const val KEY_DP = 72
    }
}
