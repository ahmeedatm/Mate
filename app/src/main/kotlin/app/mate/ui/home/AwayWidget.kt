package app.mate.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.mate.Prefs
import app.mate.R
import app.mate.away.AwayEffect
import app.mate.away.AwayLogic
import app.mate.away.AwayState
import app.mate.focus.FocusTimer
import app.mate.net.BackendClient
import app.mate.net.runCatchingCancellable
import app.mate.ui.errorText
import app.mate.ui.themeFont
import kotlinx.coroutines.launch

/** Exemple de Partir pour le catalogue : avant de partir, avec ce que le bouton fera. Rien ne part vers l'ordinateur. */
class AwaySample(val state: AwayState?, val minutes: Int) : WidgetSample

/**
 * « Je m'absente / Reprendre ». Je m'absente : coupe le micro de l'ordinateur (s'il ne l'est pas déjà), met le Focus en
 * pause (pause, pas arrêt) et demande à l'ordinateur de garder ses fenêtres. Reprendre : rouvre le micro et relance le
 * Focus seulement si c'est l'absence qui les avait coupés, et remet les fenêtres. L'absence est gardée dans les
 * préférences ([Prefs.away]) : elle survit à la recréation de l'activité. Les minutes se mettent à jour chaque minute.
 * Une mise en page par famille de formes :
 * - Pastille : le bouton seul (absent : la durée au-dessus) ;
 * - Ligne : titre et état à gauche, le bouton à droite ;
 * - Colonne : la liste de ce que le bouton fera (absent : la durée et ce qui a été fait), le bouton en bas ;
 * - Bloc, Grand, Plein écran : la même liste, la durée de plus en plus grande ; plus large que haut, la liste à droite.
 */
class AwayWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view = LinearLayout(context).asCard(16).apply { orientation = LinearLayout.VERTICAL }

    private val title = cardTitle(R.string.widget_away)
    private val status = label(14f, R.attr.c_text_dim).apply { ellipsize = TextUtils.TruncateAt.END }
    private val duration = TextView(context).apply {
        typeface = themeFont(context, R.attr.c_font_display)
        includeFontPadding = false
        setTextColor(color(R.attr.c_text))
        maxLines = 1
    }
    private val listLabel = label(13f, R.attr.c_text_dim)
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val leave = button(R.style.ActionButton, R.string.away_leave) { leave() }
    private val back = button(R.style.PrimaryButton, R.string.away_resume) { resume() }

    private var busy = false
    private var preview: AwaySample? = null

    /** La liste de ce que fait le bouton est montrée dans cette forme (pas en pastille ni en ligne). */
    private var listed = false

    /** Dernière liste dessinée : rien n'est refait chaque minute tant qu'elle ne change pas. */
    private var drawnList: Pair<AwayState?, Boolean>? = null

    /** Empilé : la vue est assez haute pour la durée, la liste et le bouton à la fois (mesuré, suit le profil de rangées). */
    private var tallEnough = true

    /** Mise en page empilée (colonne, bloc carré, grand en hauteur, plein écran) : seule à manquer de hauteur. */
    private var stackedLayout = false

    init {
        view.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val tall = bottom - top >= dp(ROOM_DP)
            if (tall != tallEnough) {
                tallEnough = tall
                view.post { render() }
            }
        }
    }

    override fun applySize() {
        for (v in listOf(title, status, duration, listLabel, list, leave, back)) (v.parent as? ViewGroup)?.removeView(v)
        view.removeAllViews()
        view.orientation = LinearLayout.VERTICAL
        listed = family != ShapeFamily.PILL && family != ShapeFamily.ROW
        drawnList = null
        stackedLayout = false
        when (family) {
            ShapeFamily.PILL -> pill()
            ShapeFamily.ROW -> row()
            ShapeFamily.COLUMN -> stacked(durationSp = 32f, buttonDp = 56)
            ShapeFamily.BLOCK -> if (placed.size.w > placed.size.h) sideBySide(40f, 56) else stacked(40f, 56)
            ShapeFamily.LARGE -> if (placed.size.w > placed.size.h) sideBySide(56f, 64) else stacked(56f, 64)
            ShapeFamily.FULL -> stacked(80f, 72)
        }
        render()
    }

    override fun showPreview(sample: WidgetSample) {
        preview = sample as? AwaySample ?: return
        render()
    }

    /** Une case : le bouton seul ; absent, la durée en deux lignes au plus au-dessus. */
    private fun pill() {
        view.setPadding(dp(10), dp(10), dp(10), dp(10))
        status.textSize = 14f
        // Une ligne et un bouton bas : la case tient dans la rangée basse de l'Accueil (92 dp), même absent.
        status.maxLines = 1
        buttons(textSp = 14f, icon = false)
        for (b in listOf(leave, back)) b.minHeight = dp(44)
        view.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        for (b in listOf(leave, back)) view.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Une ligne : titre et état (ce que fait le bouton, ou la durée) à gauche, le bouton à droite ; tient en 92 dp. */
    private fun row() {
        view.orientation = LinearLayout.HORIZONTAL
        view.gravity = Gravity.CENTER_VERTICAL
        view.setPadding(dp(20), dp(10), dp(16), dp(10))
        status.textSize = 14f
        status.maxLines = if (placed.size.w >= 3) 2 else 1
        buttons(textSp = 18f, icon = true)
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }
        view.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(16) })
        for (b in listOf(leave, back)) view.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)))
    }

    /** Colonne, bloc carré, grand en hauteur, plein écran : titre, durée, la liste, puis le bouton en bas. */
    private fun stacked(durationSp: Float, buttonDp: Int) {
        stackedLayout = true
        view.gravity = Gravity.NO_GRAVITY
        view.setPadding(dp(20), dp(16), dp(20), dp(16))
        duration.textSize = durationSp
        // Colonne : une seule case de large, trop étroite pour l'icône et « Je m'absente » sur une ligne (polices larges).
        buttons(textSp = if (buttonDp > 56) 22f else 18f, icon = family != ShapeFamily.COLUMN)
        view.addView(title)
        view.addView(duration, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        view.addView(listLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        view.addView(list)
        view.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        for (b in listOf(leave, back)) view.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(buttonDp)).apply { topMargin = dp(8) })
    }

    /** Plus large que haut (3 × 2, 4 × 2, 4 × 3) : titre, durée et bouton à gauche, la liste à droite. */
    private fun sideBySide(durationSp: Float, buttonDp: Int) {
        view.orientation = LinearLayout.HORIZONTAL
        view.gravity = Gravity.NO_GRAVITY
        view.setPadding(dp(24), dp(16), dp(24), dp(16))
        duration.textSize = durationSp
        buttons(textSp = if (buttonDp > 56) 22f else 18f, icon = true)
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(duration, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            for (b in listOf(leave, back)) addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(buttonDp)))
        }
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(listLabel)
            addView(list)
        }
        view.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        view.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(24) })
    }

    private fun buttons(textSp: Float, icon: Boolean) {
        for (b in listOf(leave, back)) {
            b.minHeight = dp(56)
            b.textSize = textSp
            // Une case est trop étroite pour l'icône et les marges d'un bouton ordinaire.
            b.setPadding(dp(if (icon) 20 else 4), 0, dp(if (icon) 20 else 4), 0)
            b.compoundDrawablePadding = dp(if (icon) 10 else 0)
            b.setCompoundDrawablesRelativeWithIntrinsicBounds(if (!icon) 0 else if (b === leave) R.drawable.ic_cafe else R.drawable.ic_play, 0, 0, 0)
        }
    }

    override fun onMinute() = render()

    override suspend fun load() = render()

    override fun setActive(active: Boolean) {
        if (active) render()
    }

    private fun button(style: Int, text: Int, onClick: () -> Unit) = Button(context, null, 0, style).apply {
        setText(text)
        minHeight = dp(56)
        gravity = Gravity.CENTER
        maxLines = 2
        setOnClickListener { onClick() }
    }

    private fun render() {
        val sample = preview
        val away = if (sample != null) sample.state else Prefs.away(context)
        val minutes = if (sample != null) sample.minutes else away?.let { AwayLogic.minutes(it, System.currentTimeMillis()) } ?: 0
        leave.visibility = if (away == null) View.VISIBLE else View.GONE
        back.visibility = if (away == null) View.GONE else View.VISIBLE
        leave.isEnabled = !busy
        back.isEnabled = !busy
        status.text = if (away == null) context.getString(R.string.away_hint) else elapsed(minutes)
        // Pastille : hors absence, toute la place va au bouton.
        status.visibility = if (away == null && family == ShapeFamily.PILL) View.GONE else View.VISIBLE
        duration.text = durationText(minutes)
        duration.visibility = if (away == null) View.GONE else View.VISIBLE
        if (listed) renderList(away)
    }

    /** « En partant » et tout ce que fera le bouton ; pendant l'absence, ce qui a vraiment été fait. */
    private fun renderList(away: AwayState?) {
        // Empilé et trop bas pendant l'absence : la durée et Reprendre prennent la place de la liste.
        val room = !(stackedLayout && away != null && !tallEnough)
        if (drawnList == away?.copy(sinceMs = 0) to room) return
        drawnList = away?.copy(sinceMs = 0) to room
        listLabel.setText(if (away == null) R.string.away_will_label else R.string.away_done_label)
        list.removeAllViews()
        val effects = AwayLogic.effects(away)
        listLabel.visibility = if (room) View.VISIBLE else View.GONE
        if (!room) return
        if (effects.isEmpty()) list.addView(effectRow(0, context.getString(R.string.away_nothing_done), away != null))
        for (effect in effects) {
            val (icon, text) = when (effect) {
                AwayEffect.MIC -> R.drawable.ic_mic_off to context.getString(R.string.away_does_mic)
                AwayEffect.FOCUS -> R.drawable.ic_timer to context.getString(R.string.away_does_focus)
                AwayEffect.WINDOWS -> R.drawable.ic_tabs to (
                    away?.windows?.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.away_does_windows_n, it, it) }
                        ?: context.getString(R.string.away_does_windows)
                    )
            }
            list.addView(effectRow(icon, text, away != null))
        }
    }

    /** Une ligne de la liste : icône et texte ; l'icône prend l'accent quand c'est fait. */
    private fun effectRow(icon: Int, text: String, done: Boolean) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(EFFECT_ROW_DP)
        if (icon != 0) {
            addView(
                ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(color(if (done) R.attr.c_accent else R.attr.c_text_dim))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) },
            )
        }
        addView(label(15f).apply { this.text = text; maxLines = 2 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun durationText(minutes: Int) =
        if (minutes < 60) context.getString(R.string.away_duration_min, minutes) else context.getString(R.string.away_duration_hour, minutes / 60, minutes % 60)

    private fun elapsed(minutes: Int) = when {
        minutes < 1 -> context.getString(R.string.away_since_now)
        minutes < 60 -> context.getString(R.string.away_since_min, minutes)
        else -> context.getString(R.string.away_since_hour, minutes / 60, minutes % 60)
    }

    private fun fail(e: Throwable) = Toast.makeText(context, context.errorText(e), Toast.LENGTH_LONG).show()

    private fun leave() {
        if (busy || preview != null) return
        busy = true
        render()
        host.scope.launch {
            try {
                val client = BackendClient(context)
                // Le micro d'abord : sans réponse de l'ordinateur, rien ne change et l'absence ne commence pas.
                val micMuted = runCatchingCancellable { client.system.audio().micMuted }.getOrElse { return@launch fail(it) }
                if (!micMuted) runCatchingCancellable { client.system.setAudio(micMuted = true) }.onFailure { return@launch fail(it) }
                val focusRunning = FocusTimer.state.running
                if (focusRunning) FocusTimer.toggle()
                Prefs.setAway(context, AwayLogic.begin(System.currentTimeMillis(), micMuted, focusRunning))
                busy = false
                render()
                // Les fenêtres se lisent en quelques secondes sur Mac : l'absence a déjà commencé. Leur nombre ne sert qu'à l'affichage.
                runCatchingCancellable { client.away.start() }
                    .onSuccess { saved -> Prefs.away(context)?.let { Prefs.setAway(context, it.copy(windows = saved)) } }
                    .onFailure { fail(it) }
            } finally {
                busy = false
                render()
            }
        }
    }

    private fun resume() {
        if (preview != null) return
        val away = Prefs.away(context) ?: return render()
        if (busy) return
        busy = true
        render()
        host.scope.launch {
            try {
                val client = BackendClient(context)
                // Les erreurs se disent mais ne retiennent pas l'absence : sinon le bouton resterait bloqué ordinateur éteint.
                if (AwayLogic.shouldUnmute(away)) runCatchingCancellable { client.system.setAudio(micMuted = false) }.onFailure { fail(it) }
                val focus = FocusTimer.state
                if (AwayLogic.shouldResumeFocus(away, focus.running, focus.remainingMs, focus.durationMs)) FocusTimer.toggle()
                Prefs.setAway(context, null)
                busy = false
                render()
                runCatchingCancellable { client.away.resume() }.onFailure { fail(it) }
            } finally {
                busy = false
                render()
            }
        }
    }

    private companion object {
        const val EFFECT_ROW_DP = 36

        /** Hauteur où la durée, la liste de trois lignes et le bouton tiennent ensemble. */
        const val ROOM_DP = 320
    }
}
