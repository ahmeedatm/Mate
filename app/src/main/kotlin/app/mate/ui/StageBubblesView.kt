package app.mate.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import app.mate.R
import app.mate.net.GitLabStage
import app.mate.net.Pipeline

/** Couleur (attribut de thème) d'un état de pipeline ou de stage : réussi, en cours, échoué ; le reste en gris. */
internal fun pipelineColorAttr(p: Pipeline) = when (p) {
    Pipeline.SUCCESS -> R.attr.c_ok
    Pipeline.RUNNING -> R.attr.c_warn
    Pipeline.FAILED -> R.attr.c_ko
    Pipeline.CANCELED, Pipeline.NONE -> R.attr.c_text_faint
}

private fun stateLabel(p: Pipeline) = when (p) {
    Pipeline.SUCCESS -> R.string.stage_success
    Pipeline.RUNNING -> R.string.stage_running
    Pipeline.FAILED -> R.string.stage_failed
    Pipeline.CANCELED -> R.string.stage_canceled
    Pipeline.NONE -> R.string.stage_none
}

/**
 * Les stages d'un pipeline en petites bulles, à la suite et passant à la ligne : le nom du stage, un point et un filet de la
 * couleur de son état. TalkBack lit « nom : état » (la couleur seule ne dit rien). Cachée sans stage. Statique : pas
 * d'animation (tablette lente).
 */
class StageBubblesView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private val gap = dp(GAP_DP)

    init {
        visibility = GONE
    }

    fun show(stages: List<GitLabStage>) {
        removeAllViews()
        visibility = if (stages.isEmpty()) GONE else VISIBLE
        for (stage in stages) addView(bubble(stage))
    }

    private fun bubble(stage: GitLabStage) = TextView(context).apply {
        val color = themeColor(context, pipelineColorAttr(stage.status))
        text = stage.name
        textSize = 12f
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(themeColor(context, R.attr.c_text))
        setPadding(dp(8), dp(3), dp(10), dp(3))
        compoundDrawablePadding = dp(5)
        setCompoundDrawablesRelativeWithIntrinsicBounds(
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setSize(dp(8), dp(8))
            },
            null,
            null,
            null,
        )
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(ColorUtils.setAlphaComponent(color, FILL_ALPHA))
            setStroke(dp(1), color)
        }
        contentDescription = context.getString(R.string.stage_cd, stage.name, context.getString(stateLabel(stage.status)))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        var x = 0
        var y = 0
        var rowHeight = 0
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + child.measuredWidth > width) {
                y += rowHeight + gap
                x = 0
                rowHeight = 0
            }
            x += child.measuredWidth + gap
            widest = maxOf(widest, x - gap)
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(resolveSize(widest, widthMeasureSpec), y + rowHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (x > 0 && x + child.measuredWidth > width) {
                y += rowHeight + gap
                x = 0
                rowHeight = 0
            }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + gap
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }

    private companion object {
        const val GAP_DP = 6
        const val FILL_ALPHA = 0x26
    }
}
