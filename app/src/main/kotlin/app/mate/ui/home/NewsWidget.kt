package app.mate.ui.home

import android.content.Context
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.News
import app.mate.net.NewsItem
import app.mate.net.runCatchingCancellable
import app.mate.ui.errorText
import app.mate.ui.showForTyping
import app.mate.ui.themeFont
import kotlinx.coroutines.launch
import org.json.JSONArray

/** Exemple des Actus pour le catalogue : le nom du flux et ses titres, dont les [seen] derniers déjà vus. */
class NewsSample(@StringRes val feed: Int, @ArrayRes val titles: Int, val seen: Int) : WidgetSample

/**
 * Les cinq derniers titres d'un flux RSS ou Atom (actualités, blog, sorties). Le flux se règle depuis la tuile (⚙ du
 * mode Modifier, ou un toucher tant qu'aucun n'est réglé) mais se garde sur l'ordinateur, qui le lit ; un toucher sur
 * un titre ouvre l'article sur l'ordinateur. Relu chaque minute (l'ordinateur retélécharge le flux à chaque lecture).
 * Les titres déjà ouverts d'ici sont estompés (gardés dans la config du widget) ; la forme posée ([NewsShape]) dit
 * combien en montrer et sur combien de lignes. En Pastille, le nombre de titres pas encore vus ; un toucher ouvre le
 * premier d'entre eux.
 */
class NewsWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val disc = hueDisc(context, R.drawable.ic_tile_description, R.attr.c_hue_cyan)
    private val title = cardTitle(R.string.widget_news).apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private val head = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(disc, LinearLayout.LayoutParams(dp(HUE_DISC_DP), dp(HUE_DISC_DP)))
        addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
    }
    private val status = label(14f, R.attr.c_text_dim)
    private val rows = LinearLayout(context)
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(rows, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    override val view = LinearLayout(context).asCard(16).apply {
        addView(head)
        addView(content)
    }

    override val hasSettings get() = true

    private var news: News? = null
    private var shape = NewsShape.of(placed.size)

    /** Phrase de l'échec de la dernière lecture ; les derniers titres reçus restent affichés dessous. */
    private var error: String? = null

    /** Ce qui est dessiné : une relecture qui ne change rien ne refait pas les vues. */
    private var drawn: List<Any?>? = null

    /** Aperçu du catalogue : ses titres restent affichés, sans réseau ni ouverture. */
    private var preview = false

    override fun setActive(active: Boolean) {
        if (active) host.scope.launch { load() }
    }

    override suspend fun load() {
        if (preview) return
        runCatchingCancellable { BackendClient(context).news.latest() }
            .onSuccess { news = it; error = null; forgetGone(it); render() }
            .onFailure { error = context.errorText(it); render() }
    }

    override fun applySize() {
        shape = NewsShape.of(placed.size)
        view.orientation = if (shape.side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        view.gravity = if (shape.side) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
        title.isVisible = shape.title
        head.layoutParams = LinearLayout.LayoutParams(if (shape.side) WRAP_CONTENT else MATCH_PARENT, WRAP_CONTENT)
        content.layoutParams = if (shape.side) {
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dp(16) }
        } else {
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) }
        }
        rows.orientation = if (shape.side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        render()
    }

    override fun showPreview(sample: WidgetSample) {
        val example = sample as? NewsSample ?: return
        val titles = context.resources.getStringArray(example.titles).toList()
        preview = true
        previewSeen = titles.takeLast(example.seen).toSet()
        news = News(feed = "", title = context.getString(example.feed), items = titles.map { NewsItem(it) })
        render()
    }

    /** Titres vus de l'aperçu : il n'écrit pas dans la config. */
    private var previewSeen: Set<String> = emptySet()

    /**
     * Titres déjà ouverts d'ici, relus dans la config du widget à chaque fois : le zoom de la tuile est un second
     * widget sur la même config, une copie gardée ici se périmerait.
     */
    private val seen: Set<String> get() = if (preview) previewSeen else readSeen()

    private fun readSeen(): Set<String> {
        val array = placed.config.optJSONArray(SEEN) ?: return emptySet()
        return (0 until array.length()).mapNotNullTo(LinkedHashSet()) { array.optString(it).takeIf(String::isNotEmpty) }
    }

    private fun keepSeen(next: Set<String>) {
        if (next == seen) return
        if (preview) {
            previewSeen = next
            return
        }
        placed.config.put(SEEN, JSONArray(next.toList()))
        host.save()
    }

    /** Un titre sorti du flux n'a plus besoin d'être retenu (un flux vu vide une fois n'efface rien). */
    private fun forgetGone(current: News) {
        if (current.titles.isNotEmpty()) keepSeen(seenAfter(seen, current.titles, opened = null))
    }

    private fun render() {
        val current = news
        val next = listOf(current, seen, shape, placed.size, error)
        if (next == drawn) return
        drawn = next
        rows.removeAllViews()
        title.text = current?.title ?: context.getString(R.string.widget_news)
        val note = when (newsNote(current, error != null)) {
            NewsNote.ERROR -> error
            NewsNote.NO_FEED -> context.getString(R.string.widget_news_empty)
            NewsNote.EMPTY -> context.getString(R.string.widget_news_none)
            null -> null
        }
        status.text = note
        status.isVisible = note != null
        val titles = current?.titles.orEmpty()
        if (shape.titles == 0) {
            if (note == null) rows.addView(unseenView(titles), LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            // La carte est l'élément touché : c'est elle qui dit le nombre et le geste.
            view.contentDescription = if (note == null) rows.getChildAt(0)?.contentDescription else note
        } else {
            // Chaque titre se dit lui-même.
            view.contentDescription = null
            titles.take(shape.titles).forEachIndexed { index, text ->
                val lp = if (shape.side) {
                    LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { if (index > 0) marginStart = dp(16) }
                } else {
                    LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
                }
                rows.addView(row(index, text), lp)
            }
        }
        // Aucun flux réglé : toucher la carte ouvre le réglage ; en Pastille, il ouvre le premier titre pas encore vu.
        view.setOnClickListener(
            when {
                current?.feed == null -> View.OnClickListener { showSettings() }
                shape.titles == 0 -> View.OnClickListener { titles.indexOfFirst { it !in seen }.takeIf { it >= 0 }?.let(::open) }
                else -> null
            },
        )
        view.isClickable = current?.feed == null || shape.titles == 0
    }

    /** Pastille : le nombre de titres pas encore vus, ou « À jour ». */
    private fun unseenView(titles: List<String>) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        val count = unseenCount(titles, seen)
        addView(label(34f).apply {
            text = count.toString()
            typeface = themeFont(context, R.attr.c_font_light)
            isVisible = count > 0
        })
        addView(label(14f, R.attr.c_text_dim).apply {
            text = if (count > 0) context.resources.getQuantityString(R.plurals.widget_news_unseen, count) else context.getString(R.string.widget_news_read)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        contentDescription = if (count > 0) context.resources.getQuantityString(R.plurals.widget_news_unseen_cd, count, count) else context.getString(R.string.widget_news_read)
    }

    /** Un titre : estompé une fois ouvert d'ici ; plus de lignes quand la forme est grande. */
    private fun row(index: Int, text: String) = TextView(context).apply {
        this.text = text
        textSize = shape.sp
        setTextColor(color(if (text in seen) R.attr.c_text_dim else R.attr.c_text))
        maxLines = shape.maxLines
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { open(index) }
    }

    private fun open(index: Int) {
        val current = news ?: return
        keepSeen(seenAfter(seen, current.titles, opened = current.titles.getOrNull(index)))
        render()
        if (preview) return
        host.scope.launch {
            runCatchingCancellable { BackendClient(context).news.open(index) }
                .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
        }
    }

    override fun showSettings() {
        // Lecture pas encore faite : un champ vide effacerait le flux réglé.
        if (news == null || preview) return Toast.makeText(context, R.string.widget_news_loading, Toast.LENGTH_SHORT).show()
        val input = (LayoutInflater.from(context).inflate(R.layout.item_input, null) as EditText).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setHint(R.string.widget_news_hint)
            setText(news?.feed.orEmpty())
            setSelection(text.length)
        }
        val box = LinearLayout(context).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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

    private companion object {
        const val SEEN = "seen"
    }
}
