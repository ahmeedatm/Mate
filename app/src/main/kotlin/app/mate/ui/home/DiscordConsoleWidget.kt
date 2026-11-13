package app.mate.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import app.mate.AnimFamily
import app.mate.R
import app.mate.net.DeckTile
import app.mate.net.VoiceMember
import app.mate.ui.IconCache
import app.mate.ui.Look
import app.mate.ui.themeColor
import app.mate.ui.themeFont
import app.mate.ui.themeRes

/**
 * Le widget Discord des thèmes Instrument (`c_lcd_overlay`) : une console. En haut une fenêtre LCD (salon, état du
 * micro, un vumètre par participant avec sa photo et un halo quand il parle, « Montrer les photos »), en bas les touches de la page Discord du Deck en
 * touches rondes du dock, avec leur DEL (allumée : micro coupé, sourdine). Mêmes données et mêmes gestes que
 * [DiscordWidget] (relectures, photos, qui parle, aperçu) ; pas de réglette « Son de Discord ». Une mise en page par
 * famille de formes ([planConsole]) : Pastille, la touche de l'état (et le LCD si la hauteur le permet) ; Ligne, le LCD
 * à gauche et les touches à droite ; Colonne, vumètres en colonne ; Bloc, Grand et Plein écran, vumètres en rangée.
 */
class DiscordConsoleWidget(context: Context, host: HomeHost, placed: Placed) : CallWidget(context, host, placed, isDiscordPage) {
    override val discordOutputs get() = true
    private val lcdContext = ContextThemeWrapper(context, themeRes(context, R.attr.c_lcd_overlay))

    private val dotShape = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val dot = View(lcdContext).apply { background = dotShape }
    private val channel = lcdLabel(CAPTION_SP, R.attr.c_text_dim)
    private val flag = lcdLabel(CAPTION_SP, R.attr.c_ko)
    private val line1 = LinearLayout(lcdContext).apply {
        gravity = Gravity.CENTER_VERTICAL
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        addView(dot, LinearLayout.LayoutParams(dp(DOT_DP), dp(DOT_DP)).apply { marginEnd = dp(8) })
        addView(channel, LinearLayout.LayoutParams(0, wrap, 1f))
        addView(flag, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(8) })
    }
    private val line2 = lcdLabel(LINE_SP, R.attr.c_text)
    private val metersRow = ConsoleMetersView(lcdContext, vertical = false).apply {
        photoOf = IconCache::peekDiscord
        onFacesChanged = ::renderTalker
    }
    private val metersColumn = ConsoleMetersView(lcdContext, vertical = true).apply {
        photoOf = IconCache::peekDiscord
        onFacesChanged = ::renderTalker
    }
    private val photos = lcdLabel(CAPTION_SP, R.attr.c_text).apply {
        minHeight = dp(MIN_TOUCH_DP)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(10), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setStroke(dp(1), themeColor(lcdContext, R.attr.c_text_dim))
        }
        setOnClickListener { authorizePhotos() }
        // TalkBack l'annonce comme un bouton.
        ViewCompat.setAccessibilityDelegate(
            this,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            },
        )
    }
    private val screen = LinearLayout(lcdContext).apply {
        orientation = LinearLayout.VERTICAL
        background = AppCompatResources.getDrawable(lcdContext, themeRes(lcdContext, R.attr.c_home_card_bg))
    }
    private val keyRow = LinearLayout(context).apply { gravity = Gravity.CENTER }

    /** Pastille : la fenêtre LCD réduite à une ligne au-dessus de la touche. */
    private var pill = false
    private var meters: ConsoleMetersView? = null
    private var console = planConsole(ShapeFamily.PILL, emptyList(), 0, 0)

    /** Touches dessinées (identifiants, libellés montrés) : refaites seulement si elles changent, sinon allumées sur place. */
    private var drawnKeys: Any? = null
    private val keyViews = ArrayList<Pair<DeckTile, View>>()

    /** Allumage dessiné de chaque touche (identifiant → allumée) : la DEL ne clignote qu'au passage à allumée. */
    private val litKeys = HashMap<String, Boolean>()

    /** Disposition posée : touches à droite de la fenêtre (Ligne, carte très large) ; null : rien de posé. */
    private var arranged: Boolean? = null

    override fun applySize() {
        listOf(line1, line2, metersRow, metersColumn, photos).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        pill = family == ShapeFamily.PILL
        meters = when (family) {
            ShapeFamily.PILL, ShapeFamily.ROW -> null
            ShapeFamily.COLUMN -> metersColumn
            else -> metersRow
        }
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        screen.addView(line1, LinearLayout.LayoutParams(match, wrap))
        screen.addView(line2, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(if (pill) 0 else 4) })
        meters?.let { screen.addView(it, LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = dp(10) }) }
        if (meters != null) screen.addView(photos, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(8) })
        val pad = dp(if (pill) PILL_PAD_DP else PAD_DP)
        screen.setPadding(dp(12), pad, dp(12), pad)
        line2.textSize = lineSp()
        line2.gravity = if (pill) Gravity.CENTER else Gravity.START
        line1.visibility = if (pill) View.GONE else View.VISIBLE
        arrange(family == ShapeFamily.ROW)
        render()
    }

    /** Fenêtre et touches dans la carte : l'une sur l'autre, ou côte à côte ([side]). */
    private fun arrange(side: Boolean) {
        arranged = side
        listOf(screen, keyRow).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        view.removeAllViews()
        view.gravity = if (pill) Gravity.CENTER else Gravity.NO_GRAVITY
        view.orientation = if (side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        when {
            pill -> {
                keyRow.gravity = Gravity.CENTER
                view.addView(screen, LinearLayout.LayoutParams(match, wrap))
                view.addView(keyRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
            }
            side -> {
                keyRow.gravity = Gravity.CENTER
                screen.gravity = if (meters == null) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
                view.addView(screen, LinearLayout.LayoutParams(0, match, 1f))
                view.addView(keyRow, LinearLayout.LayoutParams(wrap, match).apply { marginStart = dp(10) })
            }
            else -> {
                keyRow.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                screen.gravity = Gravity.NO_GRAVITY
                view.addView(screen, LinearLayout.LayoutParams(match, 0, 1f))
                view.addView(keyRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })
            }
        }
        drawnKeys = null
    }

    override fun onCardSize() = render()

    override fun renderState() = render()

    override fun render() {
        val v = voice
        val inCall = v?.connected == true
        console = planConsole(family, shownTiles(), innerW, innerH)
        if (!pill && console.side != arranged) arrange(console.side)
        screen.visibility = if (console.lcd) View.VISIBLE else View.GONE
        // Hors appel, la fenêtre se tasse en haut au lieu de s'étirer vide.
        (screen.layoutParams as? LinearLayout.LayoutParams)?.takeIf { arranged == false && !pill }?.let { lp ->
            val weight = if (inCall) 1f else 0f
            if (lp.weight != weight) {
                lp.weight = weight
                lp.height = if (inCall) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
                (keyRow.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    this.weight = 1f - weight
                    height = if (inCall) ViewGroup.LayoutParams.WRAP_CONTENT else 0
                }
                screen.layoutParams = lp
            }
        }
        // Côte à côte (Grand 4 × 2) hors appel : la fenêtre se tasse au milieu de la hauteur.
        (screen.layoutParams as? LinearLayout.LayoutParams)?.takeIf { arranged == true && family != ShapeFamily.ROW }?.let { lp ->
            val height = if (inCall) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            if (lp.height != height) {
                lp.height = height
                lp.gravity = Gravity.CENTER_VERTICAL
                screen.layoutParams = lp
            }
        }
        dotShape.setColor(themeColor(lcdContext, if (inCall) R.attr.c_ok else R.attr.c_text_faint))
        channel.text = when {
            inCall -> v?.channel ?: page?.app.orEmpty()
            else -> context.getString(R.string.widget_discord)
        }
        val alert = when {
            !inCall -> null
            v?.deafened == true -> R.string.deck_voice_deafened
            v?.muted == true -> R.string.console_mic_off
            else -> null
        }
        flag.text = alert?.let { context.getString(it) }.orEmpty()
        flag.visibility = if (alert == null) View.GONE else View.VISIBLE
        renderLcdMembers(inCall)
        renderTalker()
        renderKeyRow()
    }

    /** Vumètres et bouton « Montrer les photos » ; qui parle relu toutes les 500 ms pendant l'appel. */
    private fun renderLcdMembers(inCall: Boolean) {
        val v = voice
        if (!inCall) forgetSpeakers()
        val members = v?.members?.takeIf { inCall && it.isNotEmpty() }
        meters?.visibility = if (members != null) View.VISIBLE else View.GONE
        if (members == null) {
            meters?.show(emptyList(), emptyMap())
        } else {
            meters?.show(marked(members), spokeAt())
            if (meters != null) loadPhotos(members)
        }
        val asking = meters != null && inCall && v?.photos != null && v.photos != "on"
        photos.visibility = if (asking) View.VISIBLE else View.GONE
        val waiting = authorizing || v?.photos == "waiting"
        photos.text = context.getString(if (waiting) R.string.console_photos_waiting else R.string.call_photos_show)
        // Rien ne montre qui parle sans la fenêtre (Pastille basse) : pas de relecture à 2 Hz pour rien.
        followSpeaking(inCall && console.lcd && !v?.members.isNullOrEmpty())
    }

    override fun renderMembers(members: List<VoiceMember>) {
        meters?.show(marked(members), spokeAt())
        renderTalker()
    }

    private fun marked(members: List<VoiceMember>) = members.map { it.copy(speaking = it.id in speaking) }

    /**
     * Grande ligne du LCD : hors appel, l'état ; en appel sans photos (Pastille, Ligne, participants non lus, place trop courte), qui
     * parle, sinon avec qui ; en Pastille, le nom seul ou l'état du micro. Les photos la remplacent : le halo dit qui parle.
     */
    private fun renderTalker() {
        val v = voice
        val inCall = v?.connected == true
        val faces = meters?.takeIf { it.visibility == View.VISIBLE }?.showsFaces == true
        line2.visibility = if (inCall && faces) View.GONE else View.VISIBLE
        if (line2.visibility == View.GONE) return
        val talker = v?.members?.firstOrNull { it.id in speaking }?.takeIf { inCall }
        val quiet = !inCall || (talker == null && pill)
        line2.setTextColor(themeColor(lcdContext, if (quiet && talker == null) R.attr.c_text_dim else R.attr.c_text))
        // maxLines, pas isSingleLine : celui-ci remplacerait les capitales (méthode de transformation).
        line2.maxLines = if (page == null) NO_PAGE_LINES else 1
        line2.text = when {
            page == null -> if (read) context.getString(R.string.call_no_page) else ""
            !inCall -> context.getString(if (v?.lobby == true) R.string.call_lobby else R.string.call_idle)
            talker != null -> if (pill) talker.name else context.getString(R.string.call_speaking, talker.name)
            pill -> context.getString(
                when {
                    v?.deafened == true -> R.string.deck_voice_deafened
                    v?.muted == true -> R.string.console_mic_off
                    else -> R.string.console_in_call
                },
            )
            !v?.people.isNullOrEmpty() -> context.getString(R.string.deck_voice_with, v?.people.orEmpty().joinToString(", "))
            else -> context.getString(R.string.console_in_call)
        }
    }

    private fun renderKeyRow() {
        val v = voice
        val shape = listOf(console.keys.map { it.id }, console.labels)
        if (shape != drawnKeys) {
            drawnKeys = shape
            keyRow.removeAllViews()
            keyViews.clear()
            litKeys.clear()
            console.keys.forEach { tile ->
                val key = keyView(tile)
                keyRow.addView(key, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4); marginEnd = dp(4) })
                keyViews += tile to key
            }
        }
        keyViews.forEach { (tile, key) -> light(key, tile, tileLit(tile, v)) }
    }

    /** Une touche ronde du dock (`item_dock_tool`) : DEL au-dessus, capuchon qui s'enfonce, libellé court dessous. */
    @SuppressLint("ClickableViewAccessibility")
    private fun keyView(tile: DeckTile): View {
        val key = LayoutInflater.from(context).inflate(R.layout.item_dock_tool, keyRow, false)
        val icon = key.findViewById<ImageView>(R.id.dock_icon)
        icon.setImageResource(TileIcons.of(tile.symbol, tile.glyph) ?: if (tile.type == "app") R.drawable.ic_open else R.drawable.ic_apps)
        icon.imageTintList = ColorStateList.valueOf(color(if (tile.symbol == "call_end") R.attr.c_ko else R.attr.c_text))
        key.findViewById<TextView>(R.id.dock_label).apply {
            text = consoleLabel(tile)?.let { context.getString(it) } ?: tile.label
            visibility = if (console.labels) View.VISIBLE else View.GONE
        }
        val cap = key.findViewById<View>(R.id.dock_cap)
        key.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> Look.keyCap(cap, pressed = true, rest = restOf(view))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Look.keyCap(cap, pressed = false, rest = restOf(view))
            }
            false
        }
        key.setOnClickListener { run(tile) }
        holdForDiscordOutput(key, tile)
        return key
    }

    /** Touche allumée (micro coupé, sourdine) : DEL orange, capuchon enfoncé ; la DEL clignote une fois en s'allumant. */
    private fun light(key: View, tile: DeckTile, on: Boolean) {
        key.contentDescription = if (on) context.getString(R.string.call_key_on, tile.label) else tile.label
        val before = litKeys[tile.id]
        if (before == on) return
        litKeys[tile.id] = on
        val turnedOn = on && before == false
        key.isSelected = on
        val cap = key.findViewById<View>(R.id.dock_cap)
        if (turnedOn) Look.keyCap(cap, pressed = false, rest = restOf(key)) else cap.translationY = restOf(key)
        key.findViewById<View>(R.id.dock_led).apply {
            isSelected = on
            if (turnedOn && Look.on(context, AnimFamily.DETAILS) && !Look.reduced(context)) {
                alpha = FLASH_ALPHA
                animate().alpha(1f).setDuration(FLASH_MS).start()
            }
        }
    }

    private fun restOf(key: View): Float = if (key.isSelected) dp(PRESSED_DP).toFloat() else 0f

    override fun photosArrived() {
        metersRow.photosChanged()
        metersColumn.photosChanged()
    }

    private fun lineSp(): Float = when (family) {
        ShapeFamily.PILL -> 15f
        ShapeFamily.ROW -> if (placed.size.h > 1 || innerH >= ConsoleMetrics.KEY_WITH_LABEL_DP) 24f else 20f
        ShapeFamily.COLUMN -> 18f
        ShapeFamily.BLOCK -> LINE_SP
        ShapeFamily.LARGE -> 30f
        ShapeFamily.FULL -> 40f
    }

    /** Texte de la fenêtre LCD : Departure Mono en capitales, couleurs du contexte LCD. */
    private fun lcdLabel(size: Float, colorAttr: Int) = TextView(lcdContext).apply {
        textSize = size
        setTextColor(themeColor(lcdContext, colorAttr))
        typeface = themeFont(lcdContext, R.attr.c_font_display)
        letterSpacing = LETTER_SPACING
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        isAllCaps = true
    }

    private companion object {
        const val CAPTION_SP = 12f
        const val LINE_SP = 22f
        const val LETTER_SPACING = 0.04f
        const val DOT_DP = 8
        const val MIN_TOUCH_DP = 48
        const val PAD_DP = 10
        const val PILL_PAD_DP = 6
        const val NO_PAGE_LINES = 3
        const val PRESSED_DP = 4
        const val FLASH_ALPHA = 0.25f
        const val FLASH_MS = 140L
    }
}
