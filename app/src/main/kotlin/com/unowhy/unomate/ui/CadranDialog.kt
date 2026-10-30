package com.unowhy.unomate.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.unowhy.unomate.R

/**
 * Boîte aux formes de Cadran, comme Profils et Ordinateurs : carte au fond du thème arrondie à 28 dp, en-tête (pastille
 * d'icône facultative, grand titre, phrase d'aide, croix pour fermer), le contenu, puis les boutons en bas à droite
 * (Annuler en contour, le principal plein). Le contenu défile si la boîte dépasse l'écran.
 */
fun showCadranDialog(
    context: Context,
    title: CharSequence,
    body: View,
    subtitle: CharSequence? = null,
    icon: Drawable? = null,
    @StringRes cancel: Int? = R.string.cancel,
    @StringRes primary: Int? = null,
    widthDp: Int = 600,
    onPrimary: () -> Unit = {},
): AlertDialog {
    lateinit var dialog: AlertDialog
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(28), context.dp(24), context.dp(28), context.dp(24))
        addView(cadranHeader(context, title, subtitle, icon) { dialog.dismiss() })
        addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(20) })
        if (cancel != null || primary != null) {
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    if (cancel != null) addView(cadranButton(context, R.style.ActionButton, cancel) { dialog.dismiss() })
                    if (primary != null) {
                        addView(
                            cadranButton(context, R.style.PrimaryButton, primary) {
                                dialog.dismiss()
                                onPrimary()
                            },
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(52)).apply { marginStart = context.dp(12) },
                        )
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(24) },
            )
        }
    }
    dialog = AlertDialog.Builder(context)
        .setView(ScrollView(context).apply { addView(content) })
        .create()
    dialog.window?.setBackgroundDrawable(
        GradientDrawable().apply {
            setColor(themeColor(context, R.attr.c_bg))
            cornerRadius = context.dp(28).toFloat()
        },
    )
    dialog.showImmersive()
    dialog.window?.setLayout(context.dp(widthDp).coerceAtMost(context.resources.displayMetrics.widthPixels - context.dp(48)), ViewGroup.LayoutParams.WRAP_CONTENT)
    return dialog
}

/** En-tête des boîtes Cadran : pastille d'icône (si donnée), titre en police de titre, phrase d'aide, croix. */
fun cadranHeader(context: Context, title: CharSequence, subtitle: CharSequence?, icon: Drawable?, onClose: () -> Unit) = LinearLayout(context).apply {
    gravity = Gravity.CENTER_VERTICAL
    if (icon != null) {
        addView(
            ImageView(context).apply {
                setImageDrawable(icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply { marginEnd = context.dp(16) },
        )
    }
    addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(context).apply {
                    text = title
                    textSize = 28f
                    typeface = themeFont(context, R.attr.c_font_title)
                    setTextColor(themeColor(context, R.attr.c_text))
                },
            )
            if (subtitle != null) {
                addView(
                    TextView(context).apply {
                        text = subtitle
                        textSize = 14f
                        setTextColor(themeColor(context, R.attr.c_text_dim))
                    },
                )
            }
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    addView(
        ImageButton(context).apply {
            setBackgroundResource(R.drawable.flat_bg)
            setImageResource(R.drawable.ic_close)
            setColorFilter(themeColor(context, R.attr.c_text_dim))
            contentDescription = context.getString(R.string.close)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
            setOnClickListener { onClose() }
        },
        LinearLayout.LayoutParams(context.dp(48), context.dp(48)),
    )
}

private fun cadranButton(context: Context, style: Int, @StringRes text: Int, onClick: () -> Unit) = Button(context, null, 0, style).apply {
    setText(text)
    minHeight = context.dp(52)
    setOnClickListener { onClick() }
}
