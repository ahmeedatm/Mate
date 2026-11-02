package app.mate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
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
import app.mate.net.YtLyricLine
import app.mate.net.YtLyrics
import app.mate.net.YtMusicState
import app.mate.net.YtQueueItem
import app.mate.net.currentLyricIndex
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
    private val emptyOpen: Button
    private val tabQueue: Button
    private val tabLyrics: Button
    private val lyricsScroll: ScrollView
    private val lyricsList: LinearLayout
    private val lyricsNote: TextView

    private lateinit var scope: CoroutineScope
    private var poll: Job? = null
    private var ticker: Job? = null

    // Paroles : chargées par titre ; la ligne chantée suit la position, avancée entre deux relectures.
    private var showLyrics = false
    private var lyrics: YtLyrics? = null
    private var lyricsKey: String? = null
    private var lyricsFailedKey: String? = null
    private var lyricsFailedAt = 0L
    private var lyricRows: List<TextView> = emptyList()
    private var currentLine = -1
    private var lastPosition = 0.0
    private var lastPositionAt = 0L
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
        emptyOpen = findViewById(R.id.yt_empty_open)
        tabQueue = findViewById(R.id.yt_tab_queue)
        tabLyrics = findViewById(R.id.yt_tab_lyrics)
        lyricsScroll = findViewById(R.id.yt_lyrics_scroll)
        lyricsList = findViewById(R.id.yt_lyrics)
        lyricsNote = findViewById(R.id.yt_lyrics_note)
        tabQueue.setOnClickListener { setTab(lyrics = false) }
        tabLyrics.setOnClickListener { setTab(lyrics = true) }
        findViewById<View>(R.id.yt_open).setOnClickListener { openOnComputer() }
        setTab(lyrics = false)

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
        emptyOpen.setOnClickListener { openOnComputer() }
    }

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    /** Écran affiché ou non : on ne relit que s'il est visible. */
    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        poll?.cancel()
        ticker?.cancel()
        if (!active) return
        poll = scope.launch {
            while (isActive) {
                refresh()
                delay(POLL_MS)
            }
        }
        // Ligne chantée : 4 fois par seconde, seulement quand les paroles synchronisées sont affichées.
        ticker = scope.launch {
            while (isActive) {
                if (showLyrics && lyrics?.synced == true) followLyrics()
                delay(LYRICS_TICK_MS)
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
        lastPosition = track.position
        lastPositionAt = SystemClock.elapsedRealtime()
        if (!showQueue) return
        if (showLyrics) renderLyrics("${track.title}|${track.artist}") else renderQueue(s.queue)
    }

    // --- File d'attente ou paroles

    private fun setTab(lyrics: Boolean) {
        showLyrics = lyrics
        tabQueue.isSelected = !lyrics
        tabLyrics.isSelected = lyrics
        queueScroll.visibility = if (lyrics) GONE else VISIBLE
        lyricsScroll.visibility = if (lyrics) VISIBLE else GONE
        lyricsNote.visibility = if (lyrics) VISIBLE else GONE
        if (lyrics) queueEmpty.visibility = GONE
        // La file est reconstruite au retour (elle a pu changer pendant les paroles), la ligne chantée remise en vue.
        queueSign = emptyList()
        lyricRows.forEach { styleLine(it, current = false) }
        currentLine = -1
        render()
    }

    /** Paroles chargées une fois par titre ; un échec (LRCLIB occupé) est réessayé au bout de 15 s. */
    private fun renderLyrics(key: String) {
        if (key == lyricsKey) return
        if (key == lyricsFailedKey && SystemClock.elapsedRealtime() - lyricsFailedAt < LYRICS_RETRY_MS) return
        lyricsKey = key
        lyrics = null
        lyricRows = emptyList()
        currentLine = -1
        lyricsList.removeAllViews()
        lyricsNote.setText(R.string.ytmusic_lyrics_loading)
        scope.launch {
            val result = runCatchingCancellable { BackendClient(context).ytmusic.lyrics() }
            if (lyricsKey != key) return@launch
            result.onSuccess(::showLyrics).onFailure {
                lyricsKey = null
                lyricsFailedKey = key
                lyricsFailedAt = SystemClock.elapsedRealtime()
                lyricsNote.text = context.errorText(it)
            }
        }
    }

    private fun showLyrics(found: YtLyrics) {
        lyrics = found
        lyricsNote.setText(
            when {
                !found.found -> R.string.ytmusic_lyrics_none
                found.instrumental -> R.string.ytmusic_lyrics_instrumental
                found.synced -> R.string.ytmusic_lyrics_credit
                else -> R.string.ytmusic_lyrics_credit_unsynced
            },
        )
        lyricRows = found.lines.map { line -> lyricRow(line) }
        lyricRows.forEach { lyricsList.addView(it) }
        lyricsScroll.scrollTo(0, 0)
        if (found.synced) followLyrics()
    }

    private fun lyricRow(line: YtLyricLine) = TextView(context).apply {
        text = line.text
        textSize = 17f
        setTextColor(themeColor(context, R.attr.c_text_dim))
        // Ligne vide entre deux couplets : un blanc, pas une cible.
        if (line.text.isEmpty()) {
            height = dp(12)
            return@apply
        }
        setPadding(dp(8), dp(6), dp(8), dp(6))
        val at = line.t
        if (at != null) {
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.flat_bg)
            contentDescription = context.getString(R.string.ytmusic_lyrics_seek, line.text)
            setOnClickListener { send("seek", seconds = at) }
        }
    }

    /** Allume la ligne chantée (gras et couleur pleine, les autres atténuées) et la garde au tiers de la hauteur. */
    private fun followLyrics() {
        val lines = lyrics?.lines ?: return
        val playing = state?.playing == true
        val elapsed = if (playing) (SystemClock.elapsedRealtime() - lastPositionAt) / 1000.0 else 0.0
        val index = currentLyricIndex(lines, lastPosition + elapsed)
        if (index == currentLine) return
        lyricRows.getOrNull(currentLine)?.let { styleLine(it, current = false) }
        currentLine = index
        val row = lyricRows.getOrNull(index) ?: return
        styleLine(row, current = true)
        lyricsScroll.smoothScrollTo(0, (row.top - lyricsScroll.height / 3).coerceAtLeast(0))
    }

    private fun styleLine(row: TextView, current: Boolean) {
        row.setTextColor(themeColor(context, if (current) R.attr.c_text else R.attr.c_text_dim))
        row.setTypeface(Typeface.create(row.typeface, if (current) Typeface.BOLD else Typeface.NORMAL))
    }

    /** Un seul envoi à la fois : YTMDesktop met quelques secondes à démarrer, un second appui le relancerait. */
    private fun openOnComputer() {
        if (!emptyOpen.isEnabled) return
        emptyOpen.isEnabled = false
        scope.launch {
            runCatchingCancellable { BackendClient(context).ytmusic.open() }
                .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
            delay(OPEN_SETTLE_MS)
            emptyOpen.isEnabled = true
        }
    }

    /** Rien à piloter : pourquoi, et quoi faire selon l'état de la connexion à YTMDesktop. */
    private fun renderEmpty(s: YtMusicState) {
        val d = s.desktop
        when {
            s.open -> showEmpty(R.string.ytmusic_idle, null, open = true)
            // Le code s'affiche aussi dans YTMDesktop : rien d'autre à proposer pendant qu'on l'accepte.
            d.status == "connecting" -> showEmpty(context.getString(R.string.ytmusic_connecting, d.code.orEmpty()), null, open = false)
            d.status == "error" -> showEmpty(d.error ?: context.getString(R.string.ytmusic_empty_hint), R.string.ytmusic_retry, open = true)
            d.status == "connected" -> showEmpty(R.string.ytmusic_desktop_closed, null, open = true)
            else -> showEmpty(R.string.ytmusic_empty_hint, R.string.ytmusic_connect, open = true)
        }
    }

    private fun showEmpty(hint: Int, action: Int?, open: Boolean = false) = showEmpty(context.getString(hint), action, open)

    /** [open] : bouton « Ouvrir YouTube Music » (YTMDesktop lancé sur l'ordinateur, sinon le site). */
    private fun showEmpty(hint: String, action: Int?, open: Boolean = false) {
        player.visibility = GONE
        queueColumn.visibility = GONE
        empty.visibility = VISIBLE
        emptyTitle.setText(R.string.ytmusic_empty)
        emptyHint.text = hint
        emptyOpen.visibility = if (open) VISIBLE else GONE
        (emptyAction.layoutParams as MarginLayoutParams).marginStart = if (open) dp(12) else 0
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
        const val LYRICS_TICK_MS = 250L
        const val OPEN_SETTLE_MS = 5_000L
        const val LYRICS_RETRY_MS = 15_000L

        /** Pochettes déjà décodées (titre en cours et file), partagées entre les widgets. */
        val images = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }
}
