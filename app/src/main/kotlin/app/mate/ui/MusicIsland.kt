package app.mate.ui

import android.annotation.SuppressLint
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.dynamicanimation.animation.SpringAnimation
import app.mate.AnimFamily
import app.mate.R
import app.mate.net.MediaSession

/**
 * Lecteur ouvert d'un toucher sur la pastille ([MusicChip]), à la manière de la Dynamic Island : la pastille s'étire
 * sur place en un îlot (découpe animée de ses bords à ceux de l'îlot, puis le contenu apparaît), posé par-dessus l'écran.
 * Titre et artiste en entier, barre d'avancement (toucher ou glisser pour avancer), grands boutons. Se referme après
 * [MiniPlayerLogic.AUTO_CLOSE_MS] sans toucher, ou au premier toucher ailleurs (qui ne va pas à l'écran dessous).
 * La découpe et le fondu suivent un seul ressort : seuls le contour et l'opacité changent, rien n'est remis en page.
 */
class MusicIsland(private val chip: View, private val control: (action: String, positionMs: Long?) -> Unit) {

    private val context = chip.context
    private val overlay = Overlay()
    private val panel: View
    private val content: View
    private val thumb: ImageView
    private val title: TextView
    private val artist: TextView
    private val timeline: View
    private val progress: SeekBar
    private val position: TextView
    private val duration: TextView
    private val previous: ImageButton
    private val play: ImageButton
    private val next: ImageButton
    private val autoClose = Runnable { close() }

    /** Bords de la pastille dans le repère de l'îlot, au moment de l'ouverture : point de départ de la découpe. */
    private val from = IntArray(4)
    private val radius = context.resources.getDimension(R.dimen.music_island_radius)

    /** Avancement de l'ouverture : 0 = pastille, 1 = îlot ouvert. */
    private var t = 0f
    private var spring: SpringAnimation? = null
    private var seeking = false
    private var session: MediaSession? = null

    val isOpen: Boolean get() = overlay.parent != null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_music_island, overlay, true)
        panel = overlay.findViewById(R.id.island_panel)
        content = overlay.findViewById(R.id.island_content)
        thumb = overlay.findViewById(R.id.island_thumb)
        thumb.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, view.dp(12).toFloat())
        }
        thumb.clipToOutline = true
        title = overlay.findViewById(R.id.island_title)
        artist = overlay.findViewById(R.id.island_artist)
        timeline = overlay.findViewById(R.id.island_timeline)
        progress = overlay.findViewById(R.id.island_progress)
        position = overlay.findViewById(R.id.island_position)
        duration = overlay.findViewById(R.id.island_duration)
        previous = overlay.findViewById(R.id.island_previous)
        play = overlay.findViewById(R.id.island_play)
        next = overlay.findViewById(R.id.island_next)
        previous.setOnClickListener { control("previous", null) }
        next.setOnClickListener { control("next", null) }
        play.setOnClickListener {
            // Icône changée tout de suite : la relecture suit un instant après.
            session?.let { play.setImageResource(if (it.playing) R.drawable.ic_play else R.drawable.ic_pause) }
            control("play-pause", null)
        }
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) position.text = MiniPlayerLogic.clock(value * 1000L)
            }
            override fun onStartTrackingTouch(bar: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                control("seek", bar.progress * 1000L)
            }
        })
        panel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val k = t.coerceIn(0f, 1f)
                val r = lerp((from[3] - from[1]) / 2f, radius, k)
                outline.setRoundRect(
                    lerp(from[0], 0, k), lerp(from[1], 0, k), lerp(from[2], view.width, k), lerp(from[3], view.height, k), r,
                )
            }
        }
        panel.clipToOutline = true
    }

    /** Ouvre l'îlot sous la pastille, avec la lecture `shown` et sa pochette. */
    fun open(shown: MediaSession, cover: Drawable?) {
        val root = chip.rootView.findViewById<ViewGroup>(android.R.id.content) ?: return
        render(shown, cover)
        if (!isOpen) {
            place(root)
            root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        chip.visibility = View.INVISIBLE
        animateTo(1f) {}
        armAutoClose()
    }

    /** Referme l'îlot vers la pastille. */
    fun close() {
        if (!isOpen) return
        overlay.removeCallbacks(autoClose)
        seeking = false
        animateTo(0f) { dismiss() }
    }

    /** Retire l'îlot tout de suite, sans animer (mode Modifier, cadre photo, plus rien en lecture, pastille retirée). */
    fun dismiss() {
        spring?.cancel()
        spring = null
        overlay.removeCallbacks(autoClose)
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        t = 0f
        seeking = false
        if (chip.visibility == View.INVISIBLE) chip.visibility = View.VISIBLE
    }

    /** Lecture relue par la pastille : titre, avancement, boutons. Rien ne change pendant qu'on glisse la barre. */
    fun render(shown: MediaSession, cover: Drawable?) {
        session = shown
        thumb.setImageDrawable(cover)
        title.text = shown.title
        val subtitle = MusicChip.subtitle(shown)
        artist.text = subtitle
        artist.visibility = if (subtitle == null) View.GONE else View.VISIBLE
        play.setImageResource(if (shown.playing) R.drawable.ic_pause else R.drawable.ic_play)
        enable(previous, shown.canPrevious)
        enable(play, shown.canPlayPause)
        enable(next, shown.canNext)
        val total = shown.durationMs?.takeIf { it > 0 }
        // Direct (Twitch) : pas de durée, pas de barre.
        timeline.visibility = if (total == null) View.GONE else View.VISIBLE
        if (total == null || seeking) return
        progress.isEnabled = MiniPlayerLogic.seekable(shown)
        progress.max = (total / 1000).toInt()
        progress.progress = ((shown.positionMs ?: 0) / 1000).toInt()
        position.text = MiniPlayerLogic.clock(shown.positionMs ?: 0)
        duration.text = MiniPlayerLogic.clock(total)
    }

    /** Sous la pastille, un peu plus haut qu'elle, centré sur elle ; et les bords de la pastille, d'où part la découpe. */
    private fun place(root: ViewGroup) {
        val chipAt = IntArray(2).also { chip.getLocationInWindow(it) }
        val rootAt = IntArray(2).also { root.getLocationInWindow(it) }
        val width = context.resources.getDimensionPixelSize(R.dimen.music_island_width)
        // La pastille dessinée, sans la marge de sa zone de toucher (music_chip_hit_bg : marges données en padding).
        val inset = Rect().also { chip.background?.getPadding(it) }
        val chipLeft = chipAt[0] - rootAt[0]
        val chipTop = chipAt[1] - rootAt[1] + inset.top
        val chipHeight = chip.height - inset.top - inset.bottom
        val left = MiniPlayerLogic.islandLeft(chipLeft + chip.width / 2, width, root.width, context.dp(MARGIN_DP))
        val top = (chipTop - context.dp(RISE_DP)).coerceAtLeast(0)
        panel.layoutParams = FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = left
            topMargin = top
        }
        from[0] = chipLeft - left
        from[1] = chipTop - top
        from[2] = from[0] + chip.width
        from[3] = from[1] + chipHeight
    }

    /** Un seul ressort mène la découpe et le fondu ; repris d'où il en est si on le relance en route. */
    private fun animateTo(target: Float, onEnd: () -> Unit) {
        spring?.cancel()
        val animated = Look.on(context, AnimFamily.DIALOGS)
        content.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val token = if (target > t) Look.SPATIAL_DEFAULT else Look.SPATIAL_FAST
        val finish = {
            content.setLayerType(View.LAYER_TYPE_NONE, null)
            onEnd()
        }
        if (!animated) {
            step(target)
            finish()
            return
        }
        spring = Look.spring(context, token, t, target, ::step, finish, always = true)
    }

    private fun step(value: Float) {
        t = value
        content.alpha = MiniPlayerLogic.contentAlpha(value)
        panel.invalidateOutline()
    }

    private fun armAutoClose() {
        overlay.removeCallbacks(autoClose)
        overlay.postDelayed(autoClose, MiniPlayerLogic.AUTO_CLOSE_MS)
    }

    private fun enable(button: ImageButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    /**
     * Toute la fenêtre : un toucher hors de l'îlot le referme (et s'arrête là) ; un toucher dedans suspend la fermeture
     * automatique, qui repart au lever du doigt.
     */
    @SuppressLint("ViewConstructor")
    private inner class Overlay : FrameLayout(chip.context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    removeCallbacks(autoClose)
                    if (!inside(ev)) {
                        close()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> armAutoClose()
            }
            return super.dispatchTouchEvent(ev)
        }

        private fun inside(ev: MotionEvent): Boolean =
            ev.x >= panel.left && ev.x < panel.right && ev.y >= panel.top && ev.y < panel.bottom
    }

    private companion object {
        /** Écart minimal entre l'îlot et le bord de l'écran. */
        const val MARGIN_DP = 16

        /** L'îlot monte un peu au-dessus de la pastille : il en sort, il ne pend pas dessous. */
        const val RISE_DP = 6
        const val DISABLED_ALPHA = 0.4f

        fun lerp(a: Int, b: Int, k: Float): Int = (a + (b - a) * k).toInt()
        fun lerp(a: Float, b: Float, k: Float): Float = a + (b - a) * k
    }
}
