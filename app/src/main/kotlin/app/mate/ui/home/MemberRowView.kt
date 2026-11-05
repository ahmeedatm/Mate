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
import app.mate.R
import app.mate.net.VoiceMember
import app.mate.ui.dp
import app.mate.ui.themeColor
import app.mate.ui.themeFont

/**
 * Rangée de photos rondes d'un appel Discord : photo, ou initiales sans photo, puis « +N » quand la largeur manque
 * (ordre : `planMembers`). Un anneau de la couleur d'accent entoure qui parle : il apparaît en 120 ms et s'éteint en
 * 300 ms, sans boucle (écran à 55 Hz, GPU modeste : docs/tablette-4.2.md). Dessin seul, aucune vue par personne.
 * [photoDp] : diamètre d'une photo ; [vertical] : les photos en colonne (Colonne de l'îlot Discord).
 */
class MemberRowView(context: Context, photoDp: Int = PHOTO_DP, private val vertical: Boolean = false) : View(context) {
    /** Rangée centrée dans sa largeur (Plein écran de l'îlot Discord) ; sinon calée à gauche. */
    var centered = false
        set(value) {
            field = value
            invalidate()
        }

    /** Photo déjà chargée d'un participant ; null : initiales. */
    var photoOf: (String) -> Bitmap? = { null }

    private var members: List<VoiceMember> = emptyList()
    private var lastSpoke: Map<String, Long> = emptyMap()
    private var plan = MemberRowPlan(emptyList(), 0)

    /** Préparé dans `replan`, pas dans `onDraw` (qui tourne à chaque image pendant l'anneau) : initiales, « +N », shaders. */
    private var labels: Map<String, String> = emptyMap()
    private var moreLabel = ""
    private val shaders = HashMap<String, BitmapShader>()

    /** Anneau par participant : opacité affichée (0 : éteint, 1 : allumé), valeur visée, animation en cours. */
    private val halo = HashMap<String, Float>()
    private val haloTarget = HashMap<String, Float>()
    private val haloAnim = HashMap<String, ValueAnimator>()

    private val photoSize = dp(photoDp).toFloat()
    private val ringWidth = dp(RING_DP).toFloat()
    private val ringGap = dp(RING_GAP_DP).toFloat()
    private val cell = photoSize + 2 * (ringGap + ringWidth)
    private val spacing = dp(SPACING_DP).toFloat()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(context, R.attr.c_surface_raised) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        color = themeColor(context, R.attr.c_accent)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(context, R.attr.c_text)
        textAlign = Paint.Align.CENTER
        textSize = photoSize * 0.38f
        typeface = themeFont(context, R.attr.c_font_medium)
    }
    private val dimText = Paint(text).apply { color = themeColor(context, R.attr.c_text_dim) }

    /** « +N » : rond en contour seul, pour ne pas ressembler à une personne. */
    private val moreStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
        color = themeColor(context, R.attr.c_stroke)
    }
    private val photo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaderMatrix = Matrix()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Participants de l'appel et instant où chacun a parlé pour la dernière fois (ordre d'un salon plein). */
    fun show(members: List<VoiceMember>, lastSpoke: Map<String, Long>) {
        this.members = members
        this.lastSpoke = lastSpoke
        replan()
    }

    /** Une photo vient d'arriver. */
    fun photosChanged() {
        shaders.clear()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (vertical) {
            setMeasuredDimension(resolveSize(cell.toInt(), widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        } else {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(cell.toInt(), heightMeasureSpec))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = replan()

    private fun replan() {
        val length = if (vertical) height else width
        val fits = if (length == 0) 0 else ((length + spacing) / (cell + spacing)).toInt()
        // Une seule place : un visage (le dernier à avoir parlé), jamais un « +N » tout seul.
        plan = if (fits == 1) planMembers(members, lastSpoke, 2).let { MemberRowPlan(it.shown.take(1), 0) } else planMembers(members, lastSpoke, fits)
        labels = plan.shown.associate { it.id to initials(it.name) }
        moreLabel = if (plan.more > 0) "+${plan.more}" else ""
        val speaking = members.filter { it.speaking }.map { it.id }.toSet()
        plan.shown.forEach { animateHalo(it.id, if (it.id in speaking) 1f else 0f) }
        val shownIds = plan.shown.map { it.id }.toSet()
        (haloAnim.keys - shownIds).forEach { haloAnim.remove(it)?.cancel() }
        halo.keys.retainAll(shownIds)
        haloTarget.keys.retainAll(shownIds)
        shaders.keys.retainAll(shownIds)
        // Noms seulement : « qui parle » change deux fois par seconde, TalkBack relirait la rangée en boucle.
        val description = members.takeIf { it.isNotEmpty() }?.let { m -> context.getString(R.string.deck_voice_with, m.joinToString(", ") { it.name }) }
        if (description != contentDescription) contentDescription = description
        invalidate()
    }

    private fun animateHalo(id: String, target: Float) {
        if ((haloTarget[id] ?: 0f) == target) return
        haloTarget[id] = target
        val from = halo[id] ?: 0f
        haloAnim.remove(id)?.cancel()
        haloAnim[id] = ValueAnimator.ofFloat(from, target).apply {
            duration = if (target > from) HALO_IN_MS else HALO_OUT_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                halo[id] = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val radius = photoSize / 2
        // Position le long de la rangée (gauche à droite, ou haut en bas en colonne) ; l'autre axe est centré.
        val count = plan.shown.size + if (moreLabel.isEmpty()) 0 else 1
        val content = count * cell + (count - 1).coerceAtLeast(0) * spacing
        var along = when {
            vertical -> paddingTop.toFloat()
            centered -> ((width - content) / 2).coerceAtLeast(paddingLeft.toFloat())
            else -> paddingLeft.toFloat()
        }
        for (member in plan.shown) {
            val cx = if (vertical) width / 2f else along + cell / 2
            val cy = if (vertical) along + cell / 2 else height / 2f
            val alpha = halo[member.id] ?: 0f
            if (alpha > 0f) {
                ring.alpha = (alpha * 255).toInt()
                canvas.drawCircle(cx, cy, radius + ringGap + ringWidth / 2, ring)
            }
            // Fond sous la photo : une photo transparente garde son rond.
            canvas.drawCircle(cx, cy, radius, fill)
            val shader = if (member.avatar) shaderOf(member.id) else null
            if (shader != null) {
                shaderMatrix.setTranslate(cx - radius, cy - radius)
                shader.setLocalMatrix(shaderMatrix)
                photo.shader = shader
                canvas.drawCircle(cx, cy, radius, photo)
            } else {
                drawLabel(canvas, labels[member.id].orEmpty(), cx, cy, text)
            }
            along += cell + spacing
        }
        if (moreLabel.isNotEmpty()) {
            val cx = if (vertical) width / 2f else along + cell / 2
            val cy = if (vertical) along + cell / 2 else height / 2f
            canvas.drawCircle(cx, cy, radius - moreStroke.strokeWidth / 2, moreStroke)
            drawLabel(canvas, moreLabel, cx, cy, dimText)
        }
    }

    /** Shader de la photo, mise à la taille du rond une fois pour toutes. */
    private fun shaderOf(id: String): BitmapShader? {
        shaders[id]?.let { return it }
        val bitmap = photoOf(id) ?: return null
        val side = photoSize.toInt().coerceAtLeast(1)
        val scaled = if (bitmap.width == side && bitmap.height == side) bitmap else Bitmap.createScaledBitmap(bitmap, side, side, true)
        return BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { shaders[id] = it }
    }

    private fun drawLabel(canvas: Canvas, label: String, cx: Float, cy: Float, paint: Paint) {
        canvas.drawText(label, cx, cy - (paint.descent() + paint.ascent()) / 2, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Animation coupée en route (changement de taille) : l'anneau prend sa valeur visée, sinon il resterait à mi-chemin.
        haloAnim.values.forEach { it.cancel() }
        haloAnim.clear()
        halo.putAll(haloTarget)
    }

    private fun dp(value: Int) = context.dp(value)

    private companion object {
        const val PHOTO_DP = 44
        const val RING_DP = 3
        const val RING_GAP_DP = 2
        const val SPACING_DP = 6
        const val HALO_IN_MS = 120L
        const val HALO_OUT_MS = 300L
    }
}
