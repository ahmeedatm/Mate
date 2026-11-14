package app.mate.ui.cyberpunk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import app.mate.R
import app.mate.net.CyberpunkBody
import app.mate.net.CyberwareBlock
import app.mate.net.CyberwareImplant
import app.mate.net.CyberwareSlot
import app.mate.ui.cyberpunk.CyberpunkItems.Zone
import app.mate.ui.cyberpunk.CyberpunkKit.Companion.MATCH
import app.mate.ui.cyberpunk.CyberpunkKit.Companion.WRAP

/**
 * Écran cyberware du charcudoc : le corps de V au centre (image du système choisi par-dessus), les zones et leurs
 * emplacements placés comme dans le jeu ([CyberwareLayout]), la capacité en bas à droite. Toucher un emplacement ouvre le
 * panneau de droite : l'implant posé, puis ceux de l'inventaire qui vont dans cette zone. Sans les images du jeu, le
 * corps manque et les emplacements gardent leurs cadres. Lecture seule : rien ne se pose depuis la tablette.
 */
class CyberwareView(private val kit: CyberpunkKit) : ViewGroup(kit.context) {
    private val context0 = kit.context
    private var block: CyberwareBlock? = null
    private var look: PhoneLook? = null
    private var selected: Pair<String, Int>? = null
    private var fit = CyberwareLayout.fit(1f, 1f)

    private val dots = ImageView(context0).apply { scaleType = ImageView.ScaleType.FIT_XY; alpha = DOTS_ALPHA }
    private val base = ImageView(context0).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val part = ImageView(context0).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val capacity = LinearLayout(context0).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val zoneViews = mutableMapOf<String, LinearLayout>()
    private val panelBody = LinearLayout(context0).apply { orientation = LinearLayout.VERTICAL }
    private val panel = LinearLayout(context0).apply {
        orientation = LinearLayout.VERTICAL
        background = kit.panel(R.color.cp_fond, R.color.cp_cyan, 2)
        setPadding(kit.dp(20), kit.dp(16), kit.dp(20), kit.dp(16))
        isClickable = true
        visibility = GONE
    }

    init {
        addView(dots)
        addView(base)
        addView(part)
        addView(capacity)
        panel.addView(ScrollView(context0).apply { isVerticalScrollBarEnabled = false; addView(panelBody, ViewGroup.LayoutParams(MATCH, WRAP)) }, LinearLayout.LayoutParams(MATCH, MATCH))
        // Toucher le fond ferme le panneau.
        setOnClickListener { select(null) }
    }

    fun bind(block: CyberwareBlock, look: PhoneLook?) {
        val zonesChanged = this.block?.zones?.keys != block.zones.keys
        this.block = block
        this.look = look
        if (selected?.let { (a, i) -> block.zones[a]?.getOrNull(i) } == null) selected = null
        if (zonesChanged) buildZones()
        refresh()
    }

    private fun zoneOf(area: String) = CyberpunkItems.zone(area)

    private fun body() = block?.body?.let { if (it == CyberpunkBody.WOMAN) CyberwareLayout.Body.WOMAN else CyberwareLayout.Body.MAN } ?: CyberwareLayout.Body.MAN

    private fun buildZones() {
        zoneViews.values.forEach { removeView(it) }
        zoneViews.clear()
        removeView(panel)
        block?.zones?.keys?.filter { zoneOf(it) != null }?.forEach { area ->
            val col = LinearLayout(context0).apply { orientation = LinearLayout.VERTICAL }
            zoneViews[area] = col
            addView(col)
        }
        addView(panel)
    }

    private fun refresh() {
        val b = block ?: return
        val l = look
        dots.setImageBitmap(l?.bitmap(DOTS))
        val body = body()
        base.setImageBitmap(l?.bitmap(CyberwareLayout.base(body).asset))
        val zone = selected?.first?.let(::zoneOf)
        val sel = zone?.let { CyberwareLayout.part(body, it) }
        val full = sel != null && sel.width >= FULL_BODY_MIN_W
        part.setImageBitmap(sel?.let { l?.bitmap(it.asset) })
        part.visibility = if (sel != null && !full) VISIBLE else GONE
        // Système plein corps : il remplace le corps ; une partie (bras, yeux…) : par-dessus le corps assombri.
        if (full) base.setImageBitmap(l?.bitmap(sel!!.asset))
        base.alpha = if (sel != null && !full) DIM_ALPHA else 1f
        renderCapacity(b)
        zoneViews.forEach { (area, col) -> renderZone(col, area, b.zones[area].orEmpty()) }
        renderPanel(b)
        requestLayout()
    }

    private fun renderCapacity(b: CyberwareBlock) {
        capacity.removeAllViews()
        val c = b.capacity ?: return
        val used = c.used ?: return
        val max = c.max ?: return
        capacity.addView(kit.label(context0.getString(R.string.cp_cw_capacity), R.color.cp_cyan))
        capacity.addView(
            CyberpunkBar(context0, kit.color(R.color.cp_cyan), kit.color(R.color.cp_barre_vide)).apply { percent = if (max > 0) (used * 100 / max).toInt() else 0 },
            LinearLayout.LayoutParams(kit.dp(CAPACITY_BAR_DP), kit.dp(6)).apply { topMargin = kit.dp(6); bottomMargin = kit.dp(4) },
        )
        capacity.addView(kit.text(context0.getString(R.string.cp_cw_capacity_value, used.toInt(), max.toInt()), 15f, R.color.cp_texte, kit.mono))
    }

    private fun zoneName(z: Zone) = context0.getString(
        when (z) {
            Zone.OS -> R.string.cp_zone_os
            Zone.FRONTAL -> R.string.cp_zone_frontal
            Zone.EYES -> R.string.cp_zone_eyes
            Zone.ARMS -> R.string.cp_zone_arms
            Zone.HANDS -> R.string.cp_zone_hands
            Zone.SKELETON -> R.string.cp_zone_skeleton
            Zone.NERVOUS -> R.string.cp_zone_nervous
            Zone.CARDIO -> R.string.cp_zone_cardio
            Zone.IMMUNE -> R.string.cp_zone_immune
            Zone.SKIN -> R.string.cp_zone_skin
            Zone.LEGS -> R.string.cp_zone_legs
        },
    )

    private fun renderZone(col: LinearLayout, area: String, slots: List<CyberwareSlot>) {
        val z = zoneOf(area) ?: return
        col.removeAllViews()
        val chosen = selected?.first == area
        // À gauche du corps, nom et cases se collent au corps, comme dans le jeu.
        col.gravity = if (fit.zone(z, body())?.side == CyberwareLayout.Side.LEFT) Gravity.END else Gravity.START
        col.addView(kit.label(zoneName(z), if (chosen) R.color.cp_cyan else R.color.cp_etiquette), LinearLayout.LayoutParams(WRAP, WRAP))
        val row = LinearLayout(context0).apply { orientation = LinearLayout.HORIZONTAL }
        val icon = look?.bitmap(zoneIcon(z))
        slots.forEachIndexed { i, s ->
            val own = s.item?.iconAsset?.let { look?.bitmap(it) }
            val v = SlotView(s, own ?: icon, own != null, chosen && selected?.second == i).apply {
                contentDescription = context0.getString(
                    R.string.cp_cw_slot_desc,
                    zoneName(z),
                    i + 1,
                    when {
                        s.locked -> context0.getString(R.string.cp_cw_slot_locked)
                        s.item != null -> s.item.name
                        else -> context0.getString(R.string.cp_empty_slot)
                    },
                )
                setOnClickListener { select(if (chosen && selected?.second == i) null else area to i) }
            }
            row.addView(v, LinearLayout.LayoutParams(slotPx(), slotPx()).apply { if (i > 0) marginStart = kit.dp(SLOT_GAP_DP) })
        }
        col.addView(row, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(4) })
    }

    private fun select(next: Pair<String, Int>?) {
        if (selected == next) return
        selected = next
        refresh()
    }

    private fun renderPanel(b: CyberwareBlock) {
        val (area, i) = selected ?: run { panel.visibility = GONE; return }
        val z = zoneOf(area) ?: return
        val slot = b.zones[area]?.getOrNull(i) ?: return
        panel.visibility = VISIBLE
        panelBody.removeAllViews()
        val head = kit.row().apply { minimumHeight = 0 }
        head.addView(kit.text(zoneName(z), 22f, R.color.cp_cyan, kit.bold, spacing = 0.06f, upper = true), kit.lp(0, WRAP).apply { weight = 1f })
        head.addView(
            ImageView(context0).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(kit.color(R.color.cp_gris))
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = context0.getString(R.string.cp_cw_close)
                setOnClickListener { select(null) }
            },
            kit.lp(kit.dp(48), kit.dp(48)),
        )
        panelBody.addView(head)
        slot.item?.let { panelBody.addView(implantRow(it), kit.lp(MATCH, WRAP)) }
            ?: panelBody.addView(kit.text(context0.getString(R.string.cp_empty_slot), 17f, R.color.cp_gris), kit.lp(MATCH, WRAP).apply { topMargin = kit.dp(6) })
        if (slot.locked) {
            panelBody.addView(kit.text(context0.getString(R.string.cp_cw_locked), 15f, R.color.cp_jaune), kit.lp(MATCH, WRAP).apply { topMargin = kit.dp(12) })
            return
        }
        panelBody.addView(kit.label(context0.getString(R.string.cp_cw_owned)), kit.lp(MATCH, WRAP).apply { topMargin = kit.dp(20); bottomMargin = kit.dp(4) })
        val owned = b.owned.filter { it.area == area }
        if (owned.isEmpty()) panelBody.addView(kit.text(context0.getString(R.string.cp_cw_none_owned), 15f, R.color.cp_gris))
        owned.forEach { panelBody.addView(implantRow(it), kit.lp(MATCH, WRAP)) }
    }

    /** Une ligne d'implant : barre de rareté, icône, nom, coût en capacité. */
    private fun implantRow(it: CyberwareImplant): View {
        val line = kit.row()
        line.addView(kit.rarityBar(kit.rarityColor(CyberpunkItems.rarity(it.quality))), kit.lp(kit.dp(4), kit.dp(30)).apply { marginEnd = kit.dp(10) })
        it.iconAsset?.let { a -> look?.bitmap(a) }?.let { bmp ->
            line.addView(ImageView(context0).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER }, kit.lp(kit.dp(ROW_ICON_DP), kit.dp(ROW_ICON_DP)).apply { marginEnd = kit.dp(10) })
        }
        val texts = LinearLayout(context0).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(kit.text(it.name, 16f, R.color.cp_texte, kit.medium))
        it.cost?.let { c -> texts.addView(kit.text(context0.getString(R.string.cp_cw_cost, c), 13f, R.color.cp_gris, kit.mono), kit.lp(WRAP, WRAP).apply { topMargin = kit.dp(2) }) }
        line.addView(texts, kit.lp(0, WRAP).apply { weight = 1f })
        return LinearLayout(context0).apply {
            orientation = LinearLayout.VERTICAL
            addView(line, kit.lp(MATCH, WRAP))
            addView(kit.divider(), kit.lp(MATCH, 1))
        }
    }

    private fun zoneIcon(z: Zone) = when (z) {
        Zone.OS, Zone.FRONTAL -> "slot_brain.png"
        Zone.EYES -> "slot_eyes.png"
        Zone.ARMS -> "slot_arms.png"
        Zone.HANDS -> "slot_hands.png"
        Zone.SKELETON -> "slot_muscoskeletal.png"
        Zone.NERVOUS -> "slot_nervous.png"
        Zone.CARDIO -> "slot_cardiovascular.png"
        Zone.IMMUNE -> "slot_immune.png"
        Zone.SKIN -> "slot_skin.png"
        Zone.LEGS -> "slot_legs.png"
    }

    private fun slotPx() = maxOf(fit.slotPx.toInt(), kit.dp(MIN_SLOT_DP))

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        setMeasuredDimension(w, h)
        val newFit = CyberwareLayout.fit(w.toFloat(), h.toFloat())
        if (newFit.slotPx != fit.slotPx) {
            fit = newFit
            zoneViews.forEach { (area, col) -> block?.let { renderZone(col, area, it.zones[area].orEmpty()) } }
        }
        fit = newFit
        fun exact(v: View, box: CyberwareLayout.Box) =
            v.measure(MeasureSpec.makeMeasureSpec(box.width().toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(box.height().toInt(), MeasureSpec.EXACTLY))
        val body = body()
        exact(base, fit.partRect(CyberwareLayout.base(body)))
        selected?.first?.let(::zoneOf)?.let { exact(part, fit.partRect(CyberwareLayout.part(body, it))) }
        dots.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        val wrap = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        capacity.measure(wrap, wrap)
        zoneViews.values.forEach { it.measure(wrap, wrap) }
        panel.measure(MeasureSpec.makeMeasureSpec(kit.dp(PANEL_DP), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        fun place(v: View, x: Int, y: Int) = v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
        dots.layout(0, 0, w, h)
        val body = body()
        fit.partRect(CyberwareLayout.base(body)).let { place(base, it.left.toInt(), it.top.toInt()) }
        selected?.first?.let(::zoneOf)?.let { z -> fit.partRect(CyberwareLayout.part(body, z)).let { place(part, it.left.toInt(), it.top.toInt()) } }
        // En bas à droite, sous les jambes : seul coin libre, loin du bouton Fermer.
        place(capacity, w - capacity.measuredWidth, h - capacity.measuredHeight)
        zoneViews.forEach { (area, col) ->
            val spot = fit.zone(zoneOf(area), body) ?: return@forEach
            val left = if (spot.side == CyberwareLayout.Side.LEFT) spot.x.toInt() - col.measuredWidth else spot.x.toInt()
            val x = left.coerceIn(0, maxOf(0, w - col.measuredWidth))
            val y = spot.y.toInt().coerceIn(0, maxOf(0, h - col.measuredHeight))
            place(col, x, y)
        }
        place(panel, w - panel.measuredWidth, 0)
    }

    /** Un emplacement : cadre, fond de la couleur de rareté si un implant est posé, icône de la zone teintée. */
    /** `colored` : icône de l'implant (en couleur, dessinée telle quelle) ; sinon le masque de la zone, teinté. */
    private inner class SlotView(private val slot: CyberwareSlot, private val icon: Bitmap?, private val colored: Boolean, private val chosen: Boolean) : View(context0) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()

        init {
            isClickable = true
            isFocusable = true
        }

        override fun onDraw(canvas: Canvas) {
            val stroke = kit.dp(2).toFloat()
            box.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
            val item = slot.item
            val tint = when {
                slot.locked -> kit.color(R.color.cp_segment_vide)
                item != null -> kit.rarityColor(CyberpunkItems.rarity(item.quality))
                else -> kit.color(R.color.cp_gris)
            }
            paint.style = Paint.Style.FILL
            paint.color = if (item != null) (tint and 0x00ffffff) or FILL_ALPHA else kit.color(R.color.cp_tiroir)
            canvas.drawRect(box, paint)
            icon?.let {
                val pad = width * ICON_PAD
                val alpha = if (item != null) 255 else EMPTY_ICON_ALPHA
                paint.colorFilter = if (colored) null else PorterDuffColorFilter(if (item != null) kit.color(R.color.cp_texte) else tint, PorterDuff.Mode.SRC_IN)
                paint.alpha = if (slot.locked) LOCKED_ICON_ALPHA else alpha
                // Proportions gardées : les icônes du jeu ne sont pas carrées (80 × 65…).
                val side = minOf(width, height) - 2 * pad
                val k = side / maxOf(it.width, it.height)
                val (w, h) = it.width * k to it.height * k
                canvas.drawBitmap(it, null, RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), paint)
                paint.colorFilter = null
                paint.alpha = 255
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = if (chosen) kit.color(R.color.cp_cyan) else tint
            if (item == null && !chosen) paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(stroke * 2, stroke * 2), 0f)
            canvas.drawRect(box, paint)
            paint.pathEffect = null
            if (slot.locked) canvas.drawLine(box.left, box.bottom, box.right, box.top, paint)
        }
    }

    companion object {
        const val DOTS = "dots_bg.png"

        /** Images du jeu à charger pour ce corps : le corps par système, les icônes des zones, le fond pointillé. */
        fun pieces(body: CyberpunkBody): List<String> {
            val b = if (body == CyberpunkBody.WOMAN) CyberwareLayout.Body.WOMAN else CyberwareLayout.Body.MAN
            val icons = listOf("brain", "eyes", "immune", "nervous", "cardiovascular", "hands", "arms", "legs", "muscoskeletal", "skin").map { "slot_$it.png" }
            return CyberwareLayout.assets(b) + icons + DOTS
        }

        /** Le corps se décode presque entier : il occupe toute la hauteur du tiroir. */
        const val BODY_MIN_SIDE_PX = 360

        private const val PANEL_DP = 360
        private const val ROW_ICON_DP = 40
        private const val MIN_SLOT_DP = 48
        private const val SLOT_GAP_DP = 8
        private const val CAPACITY_BAR_DP = 220
        private const val FULL_BODY_MIN_W = 300
        private const val DIM_ALPHA = 0.35f
        private const val DOTS_ALPHA = 0.25f
        private const val ICON_PAD = 0.2f
        private const val EMPTY_ICON_ALPHA = 110
        private const val LOCKED_ICON_ALPHA = 50
        private const val FILL_ALPHA = 0x40000000
    }
}
