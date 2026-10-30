package app.mate.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import app.mate.Prefs
import app.mate.R
import app.mate.focus.FocusPresets
import app.mate.focus.FocusTimer

/**
 * Pastille « Perso » de Focus : durées de concentration et de pause par − / + (pas de clavier : la tablette est
 * lue de loin). Part des durées en cours si elles sont déjà perso, sinon des dernières enregistrées.
 */
object FocusPresetDialog {
    fun show(context: Context, onChosen: (FocusPresets.Preset) -> Unit) {
        val current = FocusPresets.Preset(FocusTimer.focusMinutes, FocusTimer.breakMinutes)
        val start = if (FocusPresets.isFixed(current)) Prefs.focusCustom(context) else current
        var focus = start.focus
        var pause = start.pause
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val inflater = LayoutInflater.from(context)

        fun stepper(@StringRes label: Int, value: () -> Int, change: (Boolean) -> Unit): View {
            val row = inflater.inflate(R.layout.item_focus_stepper, box, false)
            row.findViewById<TextView>(R.id.focus_stepper_label).setText(label)
            val count = row.findViewById<TextView>(R.id.focus_stepper_value)
            val render = { count.text = context.getString(R.string.focus_minutes, value()) }
            row.findViewById<View>(R.id.focus_stepper_minus).setOnClickListener { change(false); render() }
            row.findViewById<View>(R.id.focus_stepper_plus).setOnClickListener { change(true); render() }
            render()
            return row
        }

        box.addView(stepper(R.string.focus_phase_work, { focus }) { up -> focus = FocusPresets.stepFocus(focus, up) })
        box.addView(
            stepper(R.string.focus_phase_break, { pause }) { up -> pause = FocusPresets.stepPause(pause, up) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) },
        )
        showCadranDialog(
            context,
            title = context.getString(R.string.focus_custom_title),
            subtitle = context.getString(R.string.focus_custom_subtitle),
            icon = Look.badge(context, R.drawable.ic_timer, themeColor(context, R.attr.c_hue_red), sizeDp = 48, iconDp = 26),
            body = box,
            primary = R.string.focus_custom_apply,
            widthDp = WIDTH_DP,
        ) { onChosen(FocusPresets.Preset(focus, pause)) }
    }

    /** Assez large pour le titre, l'aide et les deux lignes − / + sans tasser les chiffres. */
    private const val WIDTH_DP = 560
}
