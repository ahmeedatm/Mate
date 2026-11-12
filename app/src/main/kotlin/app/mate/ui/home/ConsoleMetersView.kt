package app.mate.ui.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import app.mate.AnimFamily
import app.mate.R
import app.mate.net.VoiceMember
import app.mate.ui.Look
import app.mate.ui.dp
import app.mate.ui.themeColor
import app.mate.ui.themeFont
import kotlin.math.ceil

/**
 * Vumètres de la console Discord (thèmes Instrument), dans la fenêtre LCD : un par participant, sa photo (ou ses
 * initiales), un halo autour d'elle et une colonne de segments qui monte quand il parle (orange, le haut en jaune). Ils
 * montent en 120 ms et retombent en 300 ms, sans boucle (écran à 55 Hz : docs/tablette-4.2.md). Dessin seul, aucune vue
 * par personne ; taille et place des photos : `planFaces` ; ordre d'un salon plein et « +N » : `planMembers`.
 * [vertical] (Colonne) : une ligne par participant, segments à l'horizontale. Couleurs lues dans le contexte LCD du widget.
 */
class ConsoleMetersView(context: Context, private val vertical: Boolean) : View(context) {
    /** Photo déjà chargée d'un participant ; null : initiales. */
    var photoOf: (String) -> Bitmap? = { null }

    /** Appelé quand des photos apparaissent ou disparaissent ([showsFaces]) : la grande ligne du LCD les remplace. */
    var onFacesChanged: () -> Unit = {}

    /** Au moins une photo dessinée (place suffisante, participants lus). */
    var showsFaces = false
        private set

    private var members: List<VoiceMember> = emptyList()
    private var lastSpoke: Map<String, Long> = emptyMap()
    private var plan = MemberRowPlan(emptyList(), 0)
    private var faces = FacePlan(0, 0f, 0f)
    private var labels: Map<String, String> = emptyMap()
    private var names: Map<String, String> = emptyMap()
    private val shaders = HashMap<String, BitmapShader>()

    /** Côté des photos mises en cache : une autre taille de vue les refait (sinon coupées ou étirées). */
    private var shaderSide = 0

    /** « +N » et noms coupés à la largeur de leur colonne, préparés dans `replan` (pas d'allocation dans `onDraw`). */
    private var moreLabel = ""

    /** Niveau affiché par participant (0 : muet, 1 : parle), valeur visée, animation en cours. */
    private val level = HashMap<String, Float>()
    private val levelTarget = HashMap<String, Float>()
    private val levelAnim = HashMap<String, ValueAnimator>()

    private val density = resources.displayMetrics.density
    private val segment = dp(SEGMENT_DP).toFloat()
    private val segmentGap = dp(SEGMENT_GAP_DP).toFloat()
    private val haloGap = dp(HALO_GAP_DP).toFloat()

    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(themeColor(context, R.attr.c_text), OFF_ALPHA) }
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(context, R.attr.c_accent) }
    private val peak = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(context, R.attr.c_warn) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
        color = themeColor(context, R.attr.c_text_dim)
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(HALO_RING_DP).toFloat()
        color = themeColor(context, R.attr.c_accent)
    }
    private val initialsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(context, R.attr.c_text)
        textAlign = Paint.Align.CENTER
        typeface = themeFont(context, R.attr.c_font_display)
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(NAME_DP).toFloat()
        typeface = themeFont(context, R.attr.c_font_display)
    }
    private val shortNameDp = namePaint.measureText(SHORT_NAME) / resources.displayMetrics.density
    private val dim = themeColor(context, R.attr.c_text_dim)
    private val bright = themeColor(context, R.attr.c_text)
    private val photo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaderMatrix = Matrix()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun show(members: List<VoiceMember>, lastSpoke: Map<String, Long>) {
        this.members = members
        this.lastSpoke = lastSpoke
        replan()
    }

    fun photosChanged() {
        shaders.clear()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = replan()

    /** Diamètre d'une photo, en px. */
    private fun avatar(): Float = faces.avatarDp * density

    /** Place d'un participant le long de la rangée (photo, halo, nom, écart), en px. */
    private fun slot(): Float = faces.slotDp * density

    /** Anneau du halo, d'un côté de la photo. */
    private fun haloRim(): Float = haloGap + halo.strokeWidth

    private fun replan() {
        val length = (if (vertical) height else width) / density
        val cross = (if (vertical) width else height) / density
        // Un nom court (SHORT_NAME : 7 fois la lettre la plus large) tient toujours dans sa place ; la Colonne n'a pas de nom.
        val nameDp = if (vertical) 0f else shortNameDp
        faces = planFaces(members.size, length, cross * if (vertical) COLUMN_AVATAR_SHARE else AVATAR_SHARE, nameDp)
        plan = planMembers(members, lastSpoke, faces.fits, faceFirst = vertical)
        if (plan.shown.isNotEmpty() != showsFaces) {
            showsFaces = plan.shown.isNotEmpty()
            onFacesChanged()
        }
        labels = plan.shown.associate { it.id to initials(it.name) }
        names = plan.shown.associate { it.id to ellipsize(it.name.uppercase(), slot() - dp(FaceMetrics.GAP_DP.toInt())) }
        moreLabel = if (plan.more > 0) "+${plan.more}" else ""
        initialsPaint.textSize = avatar() * INITIALS_SHARE
        val side = avatar().toInt()
        if (side != shaderSide) {
            shaderSide = side
            shaders.clear()
        }
        val speaking = members.filter { it.speaking }.map { it.id }.toSet()
        plan.shown.forEach { animateLevel(it.id, if (it.id in speaking) 1f else 0f) }
        val shownIds = plan.shown.map { it.id }.toSet()
        (levelAnim.keys - shownIds).forEach { levelAnim.remove(it)?.cancel() }
        level.keys.retainAll(shownIds)
        levelTarget.keys.retainAll(shownIds)
        shaders.keys.retainAll(shownIds)
        // Noms seulement : « qui parle » change deux fois par seconde, TalkBack relirait la rangée en boucle.
        val description = members.takeIf { it.isNotEmpty() }?.let { m -> context.getString(R.string.deck_voice_with, m.joinToString(", ") { it.name }) }
        if (description != contentDescription) contentDescription = description
        invalidate()
    }

    private fun animateLevel(id: String, target: Float) {
        if ((levelTarget[id] ?: 0f) == target) return
        levelTarget[id] = target
        val from = level[id] ?: 0f
        levelAnim.remove(id)?.cancel()
        if (!Look.on(context, AnimFamily.DETAILS) || Look.reduced(context)) {
            level[id] = target
            invalidate()
            return
        }
        levelAnim[id] = ValueAnimator.ofFloat(from, target).apply {
            duration = if (target > from) RISE_MS else FALL_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                level[id] = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (plan.shown.isEmpty()) return
        val size = avatar()
        val slot = slot()
        var along = 0f
        for (member in plan.shown) {
            val value = level[member.id] ?: 0f
            if (vertical) drawRow(canvas, member, along, slot, size, value) else drawColumn(canvas, member, along + slot / 2, size, value)
            along += slot
        }
        if (moreLabel.isNotEmpty()) {
            namePaint.color = dim
            val text = moreLabel
            if (vertical) {
                canvas.drawText(text, haloRim() + size / 2, along + slot / 2 - (namePaint.descent() + namePaint.ascent()) / 2, namePaint)
            } else {
                canvas.drawText(text, along + slot / 2, height - namePaint.descent() - size / 2, namePaint)
            }
        }
    }

    /** Rangée : segments en colonne, photo dessous, nom tout en bas, au milieu de sa place. */
    private fun drawColumn(canvas: Canvas, member: VoiceMember, cx: Float, size: Float, value: Float) {
        val nameLine = namePaint.fontSpacing
        val photoTop = height - nameLine - size - haloRim() - dp(NAME_GAP_DP)
        drawFace(canvas, member, cx, photoTop + size / 2, size, value)
        namePaint.color = bright
        namePaint.alpha = if (value > 0.5f) 255 else NAME_REST
        canvas.drawText(names[member.id].orEmpty(), cx, height - namePaint.descent(), namePaint)
        val barsBottom = photoTop - haloRim() - dp(BAR_GAP_DP)
        val count = ((barsBottom + segmentGap) / (segment + segmentGap)).toInt().coerceIn(0, MAX_SEGMENTS)
        val half = dp(BAR_WIDTH_DP) / 2f
        val on = ceil(value * count).toInt()
        for (i in 0 until count) {
            val bottom = barsBottom - i * (segment + segmentGap)
            canvas.drawRect(cx - half, bottom - segment, cx + half, bottom, paintFor(i, on, count))
        }
    }

    /** Colonne : photo à gauche, segments à l'horizontale, au milieu de sa place. */
    private fun drawRow(canvas: Canvas, member: VoiceMember, top: Float, slot: Float, size: Float, value: Float) {
        val cy = top + slot / 2
        drawFace(canvas, member, haloRim() + size / 2, cy, size, value)
        val start = size + 2 * haloRim() + dp(BAR_GAP_DP)
        val count = ((width - start + segmentGap) / (segment + segmentGap)).toInt().coerceIn(0, MAX_SEGMENTS)
        val half = dp(ROW_BAR_DP) / 2f
        val on = ceil(value * count).toInt()
        for (i in 0 until count) {
            val left = start + i * (segment + segmentGap)
            canvas.drawRect(left, cy - half, left + segment, cy + half, paintFor(i, on, count))
        }
    }

    private fun paintFor(index: Int, on: Int, count: Int): Paint = when {
        index >= on -> off
        index >= count - count / PEAK_SHARE -> peak
        else -> lit
    }

    private fun drawFace(canvas: Canvas, member: VoiceMember, cx: Float, cy: Float, size: Float, value: Float) {
        val radius = size / 2
        // Qui parle : un halo autour de sa photo, qui s'allume et s'éteint avec son vumètre.
        if (value > 0f) {
            halo.alpha = (value * 255).toInt()
            canvas.drawCircle(cx, cy, radius + haloGap + halo.strokeWidth / 2, halo)
        }
        // Qui ne parle pas reste en retrait : la photo s'éclaire avec le niveau.
        val alpha = (FACE_REST + (255 - FACE_REST) * value).toInt()
        val shader = if (member.avatar) shaderOf(member.id, size) else null
        if (shader != null) {
            shaderMatrix.setTranslate(cx - radius, cy - radius)
            shader.setLocalMatrix(shaderMatrix)
            photo.shader = shader
            photo.alpha = alpha
            canvas.drawCircle(cx, cy, radius, photo)
            return
        }
        ring.alpha = alpha
        canvas.drawCircle(cx, cy, radius - ring.strokeWidth / 2, ring)
        initialsPaint.alpha = alpha
        canvas.drawText(labels[member.id].orEmpty(), cx, cy - (initialsPaint.descent() + initialsPaint.ascent()) / 2, initialsPaint)
    }

    private fun shaderOf(id: String, size: Float): BitmapShader? {
        shaders[id]?.let { return it }
        val bitmap = photoOf(id) ?: return null
        val side = size.toInt().coerceAtLeast(1)
        val scaled = if (bitmap.width == side && bitmap.height == side) bitmap else Bitmap.createScaledBitmap(bitmap, side, side, true)
        return BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { shaders[id] = it }
    }

    /** Nom coupé à la largeur de sa colonne (pas d'allocation de mesure dans la boucle : `breakText`). */
    private fun ellipsize(text: String, max: Float): String {
        val n = namePaint.breakText(text, true, max, null)
        return if (n >= text.length) text else text.take((n - 1).coerceAtLeast(1)) + "…"
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Animation coupée en route (changement de taille) : le niveau prend sa valeur visée.
        levelAnim.values.forEach { it.cancel() }
        levelAnim.clear()
        level.putAll(levelTarget)
    }

    private fun dp(value: Int) = context.dp(value)

    private companion object {
        const val SEGMENT_DP = 4
        const val SEGMENT_GAP_DP = 2
        const val BAR_WIDTH_DP = 26
        const val ROW_BAR_DP = 14
        const val BAR_GAP_DP = 6
        const val NAME_GAP_DP = 4
        const val NAME_DP = 12
        const val NAME_REST = 190
        const val HALO_GAP_DP = 2
        const val HALO_RING_DP = 3

        /** Photo au plus à cette part de la hauteur (rangée, sous les segments) ou de la largeur (Colonne). */
        const val AVATAR_SHARE = 0.4f
        const val COLUMN_AVATAR_SHARE = 0.2f

        /** Un nom de 7 lettres (« BASTIEN ») tient toujours en entier sous sa photo. */
        const val SHORT_NAME = "MMMMMMM"
        const val INITIALS_SHARE = 0.4f
        const val MAX_SEGMENTS = 32

        /** Le quart du haut de la colonne s'allume en jaune. */
        const val PEAK_SHARE = 4
        const val OFF_ALPHA = 0x1F
        const val FACE_REST = 140
        const val RISE_MS = 120L
        const val FALL_MS = 300L
    }
}
