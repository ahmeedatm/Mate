package app.mate.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import app.mate.R
import app.mate.ui.showForTyping

/** Exemple de la Note pour le catalogue : un texte. */
class NoteSample(@StringRes val text: Int) : WidgetSample

/**
 * Note libre, écrite depuis la tablette (« Penser au pain », un code de salle…). L'icône dans son disque jaune, puis le
 * texte à la plus grande taille qui tient dans la forme posée ([NoteShape]) ; un toucher ouvre la saisie.
 */
class NoteWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val disc = ImageView(context).apply {
        setImageResource(R.drawable.ic_edit)
        imageTintList = ColorStateList.valueOf(color(R.attr.c_hue_yellow))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(color(R.attr.c_hue_yellow), DISC_ALPHA))
        }
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val title = cardTitle(R.string.widget_note)
    private val head = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(disc, LinearLayout.LayoutParams(dp(DISC_DP), dp(DISC_DP)))
        addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
    }
    private val text = label(22f).apply { gravity = Gravity.CENTER_VERTICAL }
    override val view = LinearLayout(context).asCard().apply {
        setPadding(dp(20), dp(16), dp(20), dp(16))
        addView(head, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(text)
        setOnClickListener { showSettings() }
    }

    override val hasSettings get() = true

    override fun applySize() {
        val shape = NoteShape.of(placed.size)
        view.orientation = if (shape.side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        view.gravity = if (shape.side) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
        title.isVisible = shape.title
        text.layoutParams = if (shape.side) {
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dp(16) }
        } else {
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) }
        }
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(text, shape.minSp, shape.maxSp, 1, TypedValue.COMPLEX_UNIT_SP)
        render()
    }

    /** Aperçu du catalogue : son texte reste affiché, même si [applySize] repasse. */
    private var preview: NoteSample? = null

    override fun showPreview(sample: WidgetSample) {
        preview = sample as? NoteSample ?: return
        render()
    }

    private fun render() {
        val note = preview?.let { context.getString(it.text) } ?: placed.config.optString("text")
        text.text = note.ifBlank { context.getString(R.string.widget_note_empty) }
        text.setTextColor(color(if (note.isBlank()) R.attr.c_text_faint else R.attr.c_text))
    }

    override fun showSettings() {
        val input = LayoutInflater.from(context).inflate(R.layout.item_input, null) as EditText
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.setText(placed.config.optString("text"))
        input.setSelection(input.text.length)
        input.setHint(R.string.widget_note_hint)
        val box = LinearLayout(context).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.widget_note)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                placed.config.put("text", input.text.toString().trim().take(MAX_CHARS))
                host.save()
                render()
            }
            .create()
            .showForTyping(input)
    }

    private companion object {
        const val DISC_DP = 44

        /** Fond du disque : la teinte à 18 %. */
        const val DISC_ALPHA = 0x2E
        const val MAX_CHARS = 200
    }
}
