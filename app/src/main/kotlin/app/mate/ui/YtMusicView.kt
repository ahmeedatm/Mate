package app.mate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.AttributeSet
import android.util.LruCache
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.HttpException
import app.mate.net.YtMusicState
import app.mate.net.YtQueueItem
import app.mate.net.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YouTube Music qui joue sur l'ordinateur (YTMDesktop ou onglet du navigateur, backend/src/ytmusic.ts) : pochette,
 * titre, avancement touchable, commandes, j'aime, file d'attente (toucher = jouer). Le son reste sur l'ordinateur.
 * Relue chaque seconde tant que l'écran est affiché ; l'icône d'un bouton change tout de suite, la relecture confirme.
 */
class YtMusicView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    private val player: View
    private val cover: ImageView
    private val title: TextView
    private val artist: TextView
    private val timeline: View
    private val progress: SeekBar
    private val time: TextView
    private val play: ImageButton
    private val like: ImageButton
    private val dislike: ImageButton
    private val repeat: ImageButton
    private val queueColumn: View
    private val queueScroll: ScrollView
    private val queueList: LinearLayout
    private val queueEmpty: TextView
    private val empty: View
    private val emptyTitle: TextView
    private val emptyHint: TextView
    private val emptyAction: Button

    private lateinit var scope: CoroutineScope
    private var poll: Job? = null
    private var active = false
    private var state: YtMusicState? = null
    private var seeking = false
    private var sending = false
    private var coverUrl: String? = null
    private var queueSign: List<String> = emptyList()
    /** File d'attente montrée (taille XL) ou non (L). */
    var showQueue = true
        set(value) {
            field = value
            render()
        }

    init {
        orientation = HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.view_ytmusic, this, true)
        player = findViewById(R.id.yt_player)
        cover = findViewById<ImageView>(R.id.yt_cover).apply { clipToOutline = true }
        title = findViewById(R.id.yt_title)
        artist = findViewById(R.id.yt_artist)
        timeline = findViewById(R.id.yt_timeline)
        progress = findViewById(R.id.yt_progress)
        time = findViewById(R.id.yt_time)
        play = findViewById(R.id.yt_play)
        like = findViewById(R.id.yt_like)
        dislike = findViewById(R.id.yt_dislike)
        repeat = findViewById(R.id.yt_repeat)
        queueColumn = findViewById(R.id.yt_queue_column)
        queueScroll = findViewById(R.id.yt_queue_scroll)
        queueList = findViewById(R.id.yt_queue)
        queueEmpty = findViewById(R.id.yt_queue_empty)
        empty = findViewById(R.id.yt_empty)
        emptyTitle = findViewById(R.id.yt_empty_title)
        emptyHint = findViewById(R.id.yt_empty_hint)
        emptyAction = findViewById(R.id.yt_empty_action)

        play.setOnClickListener {
            // Icône changée tout de suite : la relecture suit.
            state?.let { play.setImageResource(if (it.playing) R.drawable.ic_play else R.drawable.ic_pause) }
            send("play-pause")
        }
        findViewById<View>(R.id.yt_previous).setOnClickListener { send("previous") }
        findViewById<View>(R.id.yt_next).setOnClickListener { send("next") }
        findViewById<View>(R.id.yt_shuffle).setOnClickListener { send("shuffle") }
        repeat.setOnClickListener { send("repeat") }
        like.setOnClickListener {
            state?.track?.let { renderLike(if (it.like == "like") "none" else "like") }
            send("like")
        }
        dislike.setOnClickListener {
            state?.track?.let { renderLike(if (it.like == "dislike") "none" else "dislike") }
            send("dislike")
        }
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) time.text = context.getString(R.string.media_time, clock(value), clock(bar.max))
            }
            override fun onStartTrackingTouch(bar: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                send("seek", seconds = bar.progress.toDouble())
            }
        })
        emptyAction.setOnClickListener { connectDesktop() }
    }

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    /** Écran affiché ou non : on ne relit que s'il est visible. */
    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        poll?.cancel()
        if (!active) return
        poll = scope.launch {
            while (isActive) {
                refresh()
                delay(POLL_MS)
            }
        }
    }

    private suspend fun refresh() {
        try {
            state = BackendClient(context).ytmusic.state()
            render()
        } catch (e: HttpException) {
            // 404 : compagnon plus ancien, sans YouTube Music.
            if (e.code == 404) showEmpty(R.string.ytmusic_old_companion, null)
        } catch (e: Exception) {
            // Ordinateur injoignable un instant : on garde l'affichage (l'Accueil voile le widget s'il ne répond plus).
        }
    }

    private fun render() {
        val s = state ?: return
        val track = s.track
        if (!s.open || track == null) return renderEmpty(s)
        player.visibility = VISIBLE
        empty.visibility = GONE
        queueColumn.visibility = if (showQueue) VISIBLE else GONE

        title.text = track.title
        artist.text = listOf(track.artist, track.album).filter { it.isNotEmpty() }.joinToString(" · ")
        if (!sending) play.setImageResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
        renderLike(track.like)
        renderRepeat(s.repeat)
        if (track.duration > 0) {
            timeline.visibility = VISIBLE
            if (!seeking) {
                progress.max = track.duration.toInt()
                progress.progress = track.position.toInt()
                time.text = context.getString(R.string.media_time, clock(track.position.toInt()), clock(track.duration.toInt()))
            }
        } else {
            // Direct ou titre pas encore chargé : pas de durée.
            timeline.visibility = INVISIBLE
        }
        showCover(track.cover)
        if (showQueue) renderQueue(s.queue)
    }

    /** Rien à piloter : pourquoi, et quoi faire selon l'état de la connexion à YTMDesktop. */
    private fun renderEmpty(s: YtMusicState) {
        val d = s.desktop
        when {
            s.open -> showEmpty(R.string.ytmusic_idle, null)
            d.status == "connecting" -> showEmpty(context.getString(R.string.ytmusic_connecting, d.code.orEmpty()), null)
            d.status == "error" -> showEmpty(d.error ?: context.getString(R.string.ytmusic_empty_hint), R.string.ytmusic_retry)
            d.status == "connected" -> showEmpty(R.string.ytmusic_desktop_closed, null)
            else -> showEmpty(R.string.ytmusic_empty_hint, R.string.ytmusic_connect)
        }
    }

    private fun showEmpty(hint: Int, action: Int?) = showEmpty(context.getString(hint), action)

    private fun showEmpty(hint: String, action: Int?) {
        player.visibility = GONE
        queueColumn.visibility = GONE
        empty.visibility = VISIBLE
        emptyTitle.setText(R.string.ytmusic_empty)
        emptyHint.text = hint
        emptyAction.visibility = if (action == null) GONE else VISIBLE
        action?.let { emptyAction.setText(it) }
        coverUrl = null
        cover.setImageDrawable(null)
    }

    private fun renderLike(status: String) {
        like.setImageResource(if (status == "like") R.drawable.ic_thumb_up_filled else R.drawable.ic_thumb_up)
        dislike.setImageResource(if (status == "dislike") R.drawable.ic_thumb_down_filled else R.drawable.ic_thumb_down)
        like.imageTintList = themeColorList(context, if (status == "like") R.attr.c_accent else R.attr.c_text_dim)
        dislike.imageTintList = themeColorList(context, if (status == "dislike") R.attr.c_text else R.attr.c_text_dim)
        like.isSelected = status == "like"
        dislike.isSelected = status == "dislike"
    }

    private fun renderRepeat(mode: String?) {
        repeat.setImageResource(if (mode == "one") R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeat.isSelected = mode == "all" || mode == "one"
        repeat.imageTintList = themeColorList(context, if (repeat.isSelected) R.attr.c_accent else R.attr.c_text)
        repeat.contentDescription = context.getString(
            when (mode) {
                "all" -> R.string.ytmusic_repeat_all
                "one" -> R.string.ytmusic_repeat_one
                else -> R.string.ytmusic_repeat_none
            },
        )
    }

    private var queueRetryAt = 0L

    private fun queueCoverFailed() {
        if (queueRetryAt == 0L) queueRetryAt = SystemClock.elapsedRealtime() + COVER_RETRY_MS
    }

    /** Lignes recréées seulement quand la file change (pas à chaque seconde), puis le titre en cours mis en vue. */
    private fun renderQueue(queue: List<YtQueueItem>) {
        queueEmpty.visibility = if (queue.isEmpty()) VISIBLE else GONE
        val sign = queue.map { "${it.title}|${it.subtitle}|${it.current}" }
        if (queueRetryAt != 0L && SystemClock.elapsedRealtime() >= queueRetryAt) {
            queueRetryAt = 0L
            queueSign = emptyList()
        }
        if (sign == queueSign) return
        queueSign = sign
        queueList.removeAllViews()
        var currentRow: View? = null
        queue.forEachIndexed { index, item ->
            val row = queueRow(item, index)
            queueList.addView(row)
            if (item.current) currentRow = row
        }
        currentRow?.let { row -> queueScroll.post { queueScroll.smoothScrollTo(0, (row.top - dp(56)).coerceAtLeast(0)) } }
    }

    private fun queueRow(item: YtQueueItem, index: Int): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundResource(R.drawable.flat_bg)
            contentDescription = context.getString(if (item.current) R.string.ytmusic_queue_current else R.string.ytmusic_queue_play, item.title)
            setOnClickListener { if (!item.current) send("play-queue", index = index) }
        }
        val thumb = ImageView(context).apply {
            background = context.getDrawable(R.drawable.thumb_bg)
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(thumb, LayoutParams(dp(40), dp(40)))
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(
            TextView(context).apply {
                text = item.title
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(themeColor(context, R.attr.c_text))
                if (item.current) setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        texts.addView(
            TextView(context).apply {
                text = item.subtitle
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(themeColor(context, R.attr.c_text_dim))
            },
        )
        row.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        if (item.current) {
            // En cours : une icône et le gras, pas la seule couleur.
            row.addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_music_note)
                    imageTintList = themeColorList(context, R.attr.c_accent)
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) },
            )
        }
        // Vignette ratée : la file sera reconstruite, au plus une fois toutes les 10 s, pour la redemander.
        item.cover?.let { url -> loadImage(url, dp(40), onFailed = ::queueCoverFailed) { bitmap -> thumb.setImageBitmap(bitmap) } }
        return row
    }

    /** Pochette rechargée seulement quand le titre change ; un échec (ordinateur occupé) est réessayé à la relecture suivante. */
    private fun showCover(url: String?) {
        if (url == coverUrl) return
        coverUrl = url
        cover.setImageDrawable(null)
        url?.let {
            loadImage(it, dp(112), onFailed = { if (coverUrl == url) coverUrl = null }) { bitmap -> if (coverUrl == url) cover.setImageBitmap(bitmap) }
        }
    }

    private fun loadImage(url: String, size: Int, onFailed: () -> Unit = {}, onLoaded: (Bitmap) -> Unit) {
        images.get(url)?.let { return onLoaded(it) }
        scope.launch {
            val bitmap = BackendClient(context).ytmusic.cover(url)?.let { bytes -> withContext(Dispatchers.Default) { decode(bytes, size) } }
            if (bitmap == null) return@launch onFailed()
            images.put(url, bitmap)
            onLoaded(bitmap)
        }
    }

    /** Réduite à la taille affichée : une pochette en 544 px pèse lourd pour la tablette 4.2. */
    private fun decode(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Une action à la fois : un double appui n'envoie pas deux fois (un « suivant » sauterait deux titres). */
    private fun send(op: String, index: Int? = null, seconds: Double? = null) {
        if (sending) return
        sending = true
        scope.launch {
            runCatchingCancellable { BackendClient(context).ytmusic.action(op, index, seconds) }
                .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
            // Le temps que YouTube Music change de titre ou d'état.
            delay(ACTION_SETTLE_MS)
            sending = false
            refresh()
        }
    }

    private fun connectDesktop() {
        emptyAction.isEnabled = false
        scope.launch {
            runCatchingCancellable { BackendClient(context).ytmusic.connectDesktop() }
                .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
            emptyAction.isEnabled = true
            refresh()
        }
    }

    private fun clock(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds / 60 % 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private companion object {
        const val POLL_MS = 1_000L
        const val ACTION_SETTLE_MS = 350L
        const val COVER_RETRY_MS = 10_000L

        /** Pochettes déjà décodées (titre en cours et file), partagées entre les widgets. */
        val images = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }
}
