package app.mate.ui.home

import android.content.Context
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.News
import app.mate.net.runCatchingCancellable
import app.mate.ui.errorText
import app.mate.ui.showForTyping
import kotlinx.coroutines.launch

/**
 * Les cinq derniers titres d'un flux RSS ou Atom (actualités, blog, sorties). Le flux se règle depuis la tuile (⚙ du
 * mode Modifier, ou un toucher tant qu'aucun n'est réglé) mais se garde sur l'ordinateur, qui le lit ; un toucher sur
 * un titre ouvre l'article sur l'ordinateur. Relu chaque minute (l'ordinateur retélécharge le flux à chaque lecture).
 */
class NewsWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val title = cardTitle(R.string.widget_news)
    private val status = label(14f, R.attr.c_text_dim)
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    override val view = LinearLayout(context).asCard(16).apply {
        orientation = LinearLayout.VERTICAL
        addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(rows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
    }

    override val hasSettings get() = true

    private var news: News? = null

    override fun onMinute() {
        host.scope.launch { load() }
    }

    override fun setActive(active: Boolean) {
        if (active) onMinute()
    }

    override suspend fun load() {
        runCatchingCancellable { BackendClient(context).news.latest() }
            .onSuccess { news = it; render(null) }
            .onFailure { render(context.errorText(it)) }
    }

    /** `error` : phrase de l'échec de la dernière lecture ; les derniers titres reçus restent affichés dessous. */
    private fun render(error: String?) {
        val current = news
        rows.removeAllViews()
        val note = when {
            error != null -> error
            current?.feed == null -> context.getString(R.string.widget_news_empty)
            current.items.isEmpty() -> context.getString(R.string.widget_news_none)
            else -> null
        }
        status.text = note
        status.visibility = if (note == null) View.GONE else View.VISIBLE
        current?.titles?.forEachIndexed { index, text -> rows.addView(row(index, text), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)) }
        // Aucun flux réglé : toucher la carte ouvre le réglage (sinon un toucher sur un titre ouvre l'article).
        view.setOnClickListener(if (current?.feed == null) View.OnClickListener { showSettings() } else null)
    }

    private fun row(index: Int, text: String) = TextView(context).apply {
        this.text = text
        textSize = if (placed.size == WidgetSize.M) 15f else 18f
        setTextColor(color(R.attr.c_text))
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48)
        setOnClickListener { open(index) }
    }

    private fun open(index: Int) {
        host.scope.launch {
            runCatchingCancellable { BackendClient(context).news.open(index) }
                .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
        }
    }

    override fun showSettings() {
        val input = (LayoutInflater.from(context).inflate(R.layout.item_input, null) as EditText).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setHint(R.string.widget_news_hint)
            setText(news?.feed.orEmpty())
            setSelection(text.length)
        }
        val box = LinearLayout(context).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.widget_news)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ -> saveFeed(input.text.toString()) }
            .create()
            .showForTyping(input)
    }

    private fun saveFeed(url: String) {
        host.scope.launch {
            runCatchingCancellable { BackendClient(context).news.setFeed(url) }
                .onSuccess { load() }
                .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
        }
    }
}
