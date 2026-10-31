package app.mate.ui.mixer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.DrawableCompat
import app.mate.R
import app.mate.ui.Look
import app.mate.ui.dp
import app.mate.ui.themeBool
import app.mate.ui.themeColor
import app.mate.ui.themeFont
import kotlin.math.abs
import kotlin.math.max

/**
 * Tranche de console de l'onglet Son : vis, icône, nom sur un afficheur, graduations, curseur à saisir, vumètre à
 * LED, volume en gros et touche Muet éclairée. Objets de console, mais aux tons, à la police (Figtree) et aux formes
 * de Console (docs/ui-ux/da). Dessinée à la main (une seule vue par tranche, rien à gonfler : la tablette est lente) ;
 * la plaque est celle de [MixerView], derrière.
 *
 * Sous [TIGHT_DP] de hauteur (console d'un profil de l'Accueil), la tranche est resserrée : ni vis ni icône, le nom
 * seul sur l'afficheur, le volume et Muet sur une même rangée, un capuchon plus court : le curseur garde sa course.
 *
 * Table nue (`c_mx_bare`, Hush) : style « fil et pastille » (maquette B, d'après Elgato Wave Link) : icône et nom en
 * haut, rail fin et pastille ronde, vumètre fin et continu, volume en bas ; ni vis, ni afficheur, ni graduations, ni
 * touche Muet : toucher l'icône coupe ou rend le son (pictogramme barré en rouge).
 *
 * Le curseur se saisit sans sauter : glisser n'importe où dans sa colonne le déplace d'autant (pas de saut de
 * volume en touchant la piste). Un glisser de côté laisse défiler la console.
 */
class FaderStrip(context: Context) : View(context) {

    /** Volume choisi en glissant ; `final` au lâcher. */
    var onVolume: ((volume: Int, final: Boolean) -> Unit)? = null
    var onMute: (() -> Unit)? = null

    var title = ""
        set(value) {
            if (field == value) return
            field = value
            describe()
            invalidate()
        }

    /** Dernière tranche de la rangée (posé par MixerView) : sur une table nue, pas de filet après elle. */
    var last = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Table nue (`c_mx_bare`, Hush) : style « fil et pastille », pas de filet après la dernière tranche. */
    private val bare = themeBool(context, R.attr.c_mx_bare)
    private val cAccent = themeColor(context, R.attr.c_accent)
    private val cThumb = themeColor(context, R.attr.c_mx_cap_top)
    private var thumbR = 0f

    /** Icône de l'appli (bitmap de l'ordinateur) ou d'une tranche fixe (sortie, micro). */
    var icon: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }
    var iconRes = 0
        set(value) {
            if (field == value) return
            field = value
            iconDrawable = if (value == 0) null else ContextCompat.getDrawable(context, value)?.mutate()?.also {
                DrawableCompat.setTint(it, cLcdText)
            }
            invalidate()
        }
    private var iconDrawable: Drawable? = null

    /** Tranche de la sortie générale : index du curseur dans la couleur d'accent. */
    var master = false

    /** null : ne se pilote pas (curseur grisé). Ignoré pendant un glisser. */
    var volume: Int?
        get() = current
        set(value) {
            if (dragging || current == value) return
            current = value
            invalidate()
        }
    private var current: Int? = null
    var muted = false
        set(value) {
            if (field == value) return
            field = value
            describe()
            invalidate()
        }

    /** Le curseur est tenu : l'état relu de l'ordinateur ne doit pas le faire sauter. */
    var dragging = false
        private set

    // Vumètre : barre tenue à son plus haut puis qui retombe doucement (pas de LED de crête détachée : le trou
    // en dessous clignotait à chaque relecture) ; `metered` faux : niveau illisible (Mac).
    private var metered = false
    private var targetLeds = 0
    private var shownLeds = 0f
    private var heldAt = 0L
    private var lastFrame = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /** Hush : sans plaque, le gros volume passe devant les reflets ; même ombre que ses textes (Widget.Hush.Text). Lui seul, pour ne pas flouter tous les textes à chaque image du vumètre. */
    private val cTextShadow = themeColor(context, R.attr.c_text_shadow)
    private val rect = RectF()
    private val medium = themeFont(context, R.attr.c_font_medium)
    private val light = themeFont(context, R.attr.c_font_light)

    // Géométrie, calculée à la taille.
    private val pad = dp(12).toFloat()
    private val capW = dp(46).toFloat()
    private var capH = dp(60).toFloat()
    private var tight = false
    private val ledW = dp(10).toFloat()
    private val ledGap = dp(2).toFloat()
    private var ledH = 0f
    private var cx = 0f
    private val iconBox = RectF()
    private val lcd = RectF()
    private val faderArea = RectF()
    private val muteBox = RectF()
    private var valueY = 0f
    private var valueX = 0f
    private var travelTop = 0f
    private var travelBottom = 0f
    private var meterX = 0f
    private var cap: Bitmap? = null
    private var muteFace: LinearGradient? = null

    // Couleurs du thème (palettes.xml, tirées des tons de Console).
    private val cText = themeColor(context, R.attr.c_mx_text)
    private val cEngrave = themeColor(context, R.attr.c_mx_engrave)
    private val cSlot = themeColor(context, R.attr.c_mx_slot)
    private val cHighlight = themeColor(context, R.attr.c_mx_highlight)
    private val cShadow = themeColor(context, R.attr.c_mx_shadow)
    private val cLcdBg = themeColor(context, R.attr.c_mx_lcd_bg)
    private val cLcdText = themeColor(context, R.attr.c_mx_lcd_text)
    private val cLedOff = themeColor(context, R.attr.c_mx_led_off)
    private val cLeds = intArrayOf(
        themeColor(context, R.attr.c_mx_led_low),
        themeColor(context, R.attr.c_mx_led_mid),
        themeColor(context, R.attr.c_mx_led_high),
    )
    private val cKeyTop = themeColor(context, R.attr.c_mx_key_top)
    private val cKeyBottom = themeColor(context, R.attr.c_mx_key_bottom)
    private val cMuted = themeColor(context, R.attr.c_ko)
    private val cOnMuted = themeColor(context, R.attr.c_on_accent)

    // Toucher.
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startVolume = 0
    private var grabbing = false
    private var pressingMute = false

    init {
        isClickable = true
        text.fontFeatureSettings = "tnum"
    }

    /** Niveau 0-100 relu de l'ordinateur, null s'il ne le donne pas. */
    fun setLevel(level: Int?) {
        metered = level != null
        targetLeds = MixerMath.ledsLit(level ?: 0, LEDS)
        val now = SystemClock.uptimeMillis()
        // Réduire les animations : le vumètre saute au niveau lu, sans retombée animée.
        if (targetLeds >= shownLeds || Look.still(context)) {
            shownLeds = targetLeds.toFloat()
            heldAt = now
        }
        lastFrame = now
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        tight = h < dp(TIGHT_DP)
        if (bare) return layoutBare(w, h)
        capH = dp(if (tight) 28 else 60).toFloat()
        valueX = w / 2f
        if (tight) {
            val side = dp(8).toFloat()
            lcd.set(side, dp(6).toFloat(), w - side, dp(30).toFloat())
            muteBox.set(w / 2f + dp(2), h - dp(42).toFloat(), w - side, h - dp(6).toFloat())
            valueX = (side + w / 2f) / 2
            text.typeface = light
            text.textSize = sp(VALUE_TIGHT_SP)
            valueY = muteBox.centerY() - (text.ascent() + text.descent()) / 2
            faderArea.set(0f, lcd.bottom + dp(4), w.toFloat(), muteBox.top - dp(4))
        } else {
            val iconSize = dp(32).toFloat()
            iconBox.set(w / 2f - iconSize / 2, pad + dp(8), w / 2f + iconSize / 2, pad + dp(8) + iconSize)
            lcd.set(pad, iconBox.bottom + dp(10), w - pad, iconBox.bottom + dp(10) + dp(32))
            muteBox.set(pad + dp(4), h - pad - dp(48), w - pad - dp(4), h - pad)
            valueY = muteBox.top - dp(14)
            faderArea.set(0f, lcd.bottom + dp(18), w.toFloat(), valueY - dp(42))
        }
        // Graduations, curseur et vumètre centrés ensemble dans la tranche.
        val group = dp(34) + capW + dp(10) + ledW
        cx = w / 2f - group / 2 + dp(34) + capW / 2
        meterX = cx + capW / 2 + dp(10)
        travelTop = faderArea.top + capH / 2
        travelBottom = faderArea.bottom - capH / 2
        ledH = (faderArea.height() + ledGap) / LEDS - ledGap
        cap = buildCap()
        muteFace = LinearGradient(0f, muteBox.top, 0f, muteBox.bottom, cKeyTop, cKeyBottom, Shader.TileMode.CLAMP)
    }

    /** Table nue : icône (et sa zone Muet) puis nom en haut, rail et vumètre au milieu, volume en bas. */
    private fun layoutBare(w: Int, h: Int) {
        thumbR = dp(if (tight) 13 else 16).toFloat()
        valueX = w / 2f
        if (tight) {
            val icon = dp(22)
            iconBox.set(dp(14).toFloat(), dp(10).toFloat(), (dp(14) + icon).toFloat(), (dp(10) + icon).toFloat())
            muteBox.set(0f, 0f, iconBox.right + dp(10), iconBox.bottom + dp(10))
            text.textSize = sp(VALUE_TIGHT_SP)
            valueY = h - dp(10).toFloat()
            faderArea.set(0f, muteBox.bottom + dp(6), w.toFloat(), valueY - dp(30))
        } else {
            val icon = dp(28)
            iconBox.set(w / 2f - icon / 2f, pad + dp(14), w / 2f + icon / 2f, pad + dp(14) + icon)
            muteBox.set(w / 2f - dp(32), 0f, w / 2f + dp(32), iconBox.bottom + dp(16))
            lcd.set(0f, iconBox.bottom + dp(12), w.toFloat(), iconBox.bottom + dp(36))
            valueY = h - pad - dp(6)
            faderArea.set(0f, lcd.bottom + dp(16), w.toFloat(), valueY - dp(46))
        }
        cx = w / 2f - dp(8)
        meterX = cx + dp(16)
        travelTop = faderArea.top + thumbR
        travelBottom = faderArea.bottom - thumbR
    }

    /** Capuchon du curseur, dessiné une fois : léger dégradé, stries de prise, trait d'index au milieu. */
    private fun buildCap(): Bitmap {
        val b = createBitmap(capW.toInt(), capH.toInt())
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = dp(8).toFloat()
        p.shader = LinearGradient(0f, 0f, 0f, capH, themeColor(context, R.attr.c_mx_cap_top), themeColor(context, R.attr.c_mx_cap_bottom), Shader.TileMode.CLAMP)
        c.drawRoundRect(0f, 0f, capW, capH, r, r, p)
        p.shader = null
        p.color = cHighlight
        c.drawRect(r, 1f, capW - r, dp(2).toFloat(), p)
        val step = dp(if (tight) 5 else 6).toFloat()
        val edge = dp(if (tight) 4 else 10)
        var y = edge.toFloat()
        while (y < capH - edge + 1) {
            if (abs(y - capH / 2) > dp(if (tight) 3 else 6)) {
                p.color = cShadow
                c.drawRect(dp(8).toFloat(), y, capW - dp(8), y + dp(1), p)
            }
            y += step
        }
        p.color = if (master) themeColor(context, R.attr.c_accent) else themeColor(context, R.attr.c_mx_cap_line)
        c.drawRoundRect(dp(3).toFloat(), capH / 2 - dpf(1.5f), capW - dp(3), capH / 2 + dpf(1.5f), dpf(1.5f), dpf(1.5f), p)
        return b
    }

    private fun capY(v: Int) = travelBottom - (travelBottom - travelTop) * v / 100f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Filet entre deux tranches (ton plus sombre puis plus clair : une rainure) et vis du module. Table nue (Hush) :
        // ni vis ni filet après la dernière tranche, le trait ne sert qu'à séparer deux voies.
        val groove = dp(if (tight) 8 else 16).toFloat()
        if (!bare || !last) {
            paint.color = cShadow
            canvas.drawRect(w - dp(2), groove, w - dp(1), h - groove, paint)
            paint.color = cHighlight
            canvas.drawRect(w - dp(1), groove, w, h - groove, paint)
        }
        if (bare) return drawBare(canvas)
        if (!tight) {
            screw(canvas, pad + dp(2), pad + dp(2))
            screw(canvas, w - pad - dp(4), pad + dp(2))
            drawIcon(canvas)
        }

        // Afficheur du nom.
        val lr = dp(8).toFloat()
        paint.color = cHighlight
        canvas.drawRoundRect(lcd.left, lcd.top + 1, lcd.right, lcd.bottom + 1, lr, lr, paint)
        paint.color = cLcdBg
        canvas.drawRoundRect(lcd, lr, lr, paint)
        text.typeface = medium
        text.color = cLcdText
        text.textSize = sp(16f)
        text.textAlign = Paint.Align.CENTER
        val name = TextUtils.ellipsize(title, text, lcd.width() - dp(12), TextUtils.TruncateAt.END).toString()
        canvas.drawText(name, lcd.centerX(), lcd.centerY() - (text.ascent() + text.descent()) / 2, text)

        // Graduations à gauche de la fente : un trait tous les 12,5 %, chiffrées tous les 25 %.
        text.typeface = medium
        text.color = cEngrave
        text.textSize = sp(12f)
        text.textAlign = Paint.Align.RIGHT
        paint.color = cEngrave
        for (i in 0..8) {
            val y = capY(i * 25 / 2)
            val major = i % 2 == 0
            val len = if (major) dp(10) else dp(5)
            canvas.drawRect(cx - capW / 2 - dp(4) - len, y - 0.75f, cx - capW / 2 - dp(4), y + 0.75f, paint)
            if (major) canvas.drawText((i * 25 / 2).toString(), cx - capW / 2 - dp(18), y - (text.ascent() + text.descent()) / 2, text)
        }

        // Fente du curseur.
        paint.color = cSlot
        val slotW = dp(6).toFloat()
        canvas.drawRoundRect(cx - slotW / 2, faderArea.top, cx + slotW / 2, faderArea.bottom, slotW / 2, slotW / 2, paint)

        drawMeter(canvas)

        // Curseur : ombre portée puis capuchon ; grisé si la tranche ne se pilote pas.
        val v = volume
        val y = capY(v ?: 0)
        paint.color = cShadow
        canvas.drawRoundRect(cx - capW / 2 + dp(1), y - capH / 2 + dp(4), cx + capW / 2 + dp(1), y + capH / 2 + dp(4), dp(8).toFloat(), dp(8).toFloat(), paint)
        paint.alpha = if (v == null) 90 else 255
        cap?.let { canvas.drawBitmap(it, cx - capW / 2, y - capH / 2, paint) }
        paint.alpha = 255

        drawValue(canvas, v)
        drawMute(canvas)
    }

    /** Volume en gros, chiffres fins (comme les valeurs de l'Accueil). */
    private fun drawValue(canvas: Canvas, v: Int?) {
        text.typeface = light
        text.textSize = sp(if (tight) VALUE_TIGHT_SP else 34f)
        text.textAlign = Paint.Align.CENTER
        text.color = if (muted) cMuted else cText
        // En sourdine, le volume est barré : pas seulement rouge (la touche Muet, elle, s'allume en plein).
        text.isStrikeThruText = muted
        if (Color.alpha(cTextShadow) != 0) text.setShadowLayer(SHADOW_RADIUS_PX, 0f, 1f, cTextShadow)
        canvas.drawText(if (v == null) "—" else "$v %", valueX, valueY, text)
        text.clearShadowLayer()
        text.isStrikeThruText = false
    }

    /** Table nue : pictogramme Muet, nom, rail rempli jusqu'à la pastille, vumètre continu, volume. */
    private fun drawBare(canvas: Canvas) {
        drawBareIcon(canvas)
        text.typeface = medium
        text.color = cLcdText
        text.textSize = sp(if (tight) 13f else 15f)
        if (tight) {
            text.textAlign = Paint.Align.LEFT
            val room = width - iconBox.right - dp(20)
            val name = TextUtils.ellipsize(title, text, room, TextUtils.TruncateAt.END).toString()
            canvas.drawText(name, iconBox.right + dp(10), iconBox.centerY() - (text.ascent() + text.descent()) / 2, text)
        } else {
            text.textAlign = Paint.Align.CENTER
            val name = TextUtils.ellipsize(title, text, width - dp(16).toFloat(), TextUtils.TruncateAt.END).toString()
            canvas.drawText(name, lcd.centerX(), lcd.centerY() - (text.ascent() + text.descent()) / 2, text)
        }

        // Rail : fin, rempli d'accent sous la pastille (gris en sourdine).
        val railW = dp(4).toFloat()
        val v = volume
        val y = capY(v ?: 0)
        paint.color = cSlot
        canvas.drawRoundRect(cx - railW / 2, travelTop, cx + railW / 2, travelBottom, railW / 2, railW / 2, paint)
        if (v != null) {
            paint.color = if (muted) cEngrave else cAccent
            canvas.drawRoundRect(cx - railW / 2, y, cx + railW / 2, travelBottom, railW / 2, railW / 2, paint)
        }

        // Vumètre continu : fond discret, zones de couleur coupées au niveau.
        advanceMeter()
        val meterW = dp(4).toFloat()
        paint.color = grayed(cLedOff)
        canvas.drawRoundRect(meterX, travelTop, meterX + meterW, travelBottom, meterW / 2, meterW / 2, paint)
        if (metered) {
            val span = travelBottom - travelTop
            for (s in MixerMath.meterSpans(shownLeds, LEDS)) {
                paint.color = cLeds[s.zone]
                canvas.drawRoundRect(meterX, travelBottom - s.to * span, meterX + meterW, travelBottom - s.from * span, meterW / 2, meterW / 2, paint)
            }
        }

        // Pastille : halo d'accent, ombre douce, disque clair (estompé si la tranche ne se pilote pas).
        paint.color = cAccent
        paint.alpha = HALO_ALPHA
        canvas.drawCircle(cx, y, thumbR + dp(6), paint)
        paint.color = cShadow
        canvas.drawCircle(cx, y + dp(2), thumbR, paint)
        paint.color = cThumb
        if (v == null) paint.alpha = 90
        canvas.drawCircle(cx, y, thumbR, paint)
        paint.alpha = 255

        drawValue(canvas, v)
    }

    /** Pictogramme de la voie = touche Muet : barré et rouge en sourdine, halo d'accent pendant l'appui, sans relief. */
    private fun drawBareIcon(canvas: Canvas) {
        val r = iconBox.width() / 2 + dp(if (tight) 7 else 14)
        if (muted || pressingMute) {
            paint.color = if (pressingMute) cAccent else cMuted
            paint.alpha = if (pressingMute) PRESS_ALPHA else MUTED_ALPHA
            canvas.drawCircle(iconBox.centerX(), iconBox.centerY(), r, paint)
            paint.alpha = 255
        }
        val bitmap = icon
        val drawable = iconDrawable
        when {
            bitmap != null -> {
                paint.alpha = if (muted) 110 else 255
                canvas.drawBitmap(bitmap, null, iconBox, paint)
                paint.alpha = 255
            }
            drawable != null -> {
                DrawableCompat.setTint(drawable, if (muted) cMuted else cText)
                drawable.setBounds(iconBox.left.toInt(), iconBox.top.toInt(), iconBox.right.toInt(), iconBox.bottom.toInt())
                drawable.draw(canvas)
            }
            else -> {
                text.typeface = medium
                text.color = if (muted) cMuted else cText
                text.textSize = sp(if (tight) 15f else 18f)
                text.textAlign = Paint.Align.CENTER
                canvas.drawText(title.take(1).uppercase(), iconBox.centerX(), iconBox.centerY() - (text.ascent() + text.descent()) / 2, text)
            }
        }
        if (muted) {
            // Barre du pictogramme : se lit sans la couleur.
            paint.color = cMuted
            paint.strokeWidth = dpf(2.5f)
            paint.strokeCap = Paint.Cap.ROUND
            val i = dp(2).toFloat()
            canvas.drawLine(iconBox.left - i, iconBox.top - i, iconBox.right + i, iconBox.bottom + i, paint)
        }
    }

    private fun drawIcon(canvas: Canvas) {
        // Pastille de l'afficheur, image de l'appli ou initiale.
        paint.color = cLcdBg
        canvas.drawCircle(iconBox.centerX(), iconBox.centerY(), iconBox.width() / 2 + dp(4), paint)
        val bitmap = icon
        val drawable = iconDrawable
        when {
            bitmap != null -> {
                rect.set(iconBox)
                rect.inset(dp(2).toFloat(), dp(2).toFloat())
                canvas.drawBitmap(bitmap, null, rect, paint)
            }
            drawable != null -> {
                drawable.setBounds(iconBox.left.toInt() + dp(3), iconBox.top.toInt() + dp(3), iconBox.right.toInt() - dp(3), iconBox.bottom.toInt() - dp(3))
                drawable.draw(canvas)
            }
            else -> {
                text.typeface = medium
                text.color = cLcdText
                text.textSize = sp(17f)
                text.textAlign = Paint.Align.CENTER
                canvas.drawText(title.take(1).uppercase(), iconBox.centerX(), iconBox.centerY() - (text.ascent() + text.descent()) / 2, text)
            }
        }
    }

    /** Retombée après la tenue : environ 12 LED par seconde, comme l'aiguille d'un vrai vumètre. */
    private fun advanceMeter() {
        val now = SystemClock.uptimeMillis()
        if (shownLeds > targetLeds) {
            if (now - heldAt >= HOLD_MS) shownLeds = max(targetLeds.toFloat(), shownLeds - (now - lastFrame) * FALL_PER_MS)
            lastFrame = now
            postInvalidateOnAnimation()
        }
    }

    private fun drawMeter(canvas: Canvas) {
        advanceMeter()
        val lit = shownLeds.toInt()
        paint.color = grayed(cSlot)
        canvas.drawRoundRect(meterX - dp(3), faderArea.top - dp(3), meterX + ledW + dp(3), faderArea.bottom + dp(3), dp(5).toFloat(), dp(5).toFloat(), paint)
        for (i in 0 until LEDS) {
            val bottom = faderArea.bottom - i * (ledH + ledGap)
            val on = metered && i < lit
            paint.color = if (on) cLeds[MixerMath.ledZone(i, LEDS)] else grayed(cLedOff)
            canvas.drawRoundRect(meterX, bottom - ledH, meterX + ledW, bottom, dp(2).toFloat(), dp(2).toFloat(), paint)
        }
    }

    /** Sans niveau lu (Mac) : le vumètre est estompé, pour ne pas passer pour un silence. */
    private fun grayed(color: Int) = if (metered) color else ColorUtils.setAlphaComponent(color, Color.alpha(color) * UNMETERED_ALPHA / 255)

    /** TalkBack : le nom de la tranche, et « son coupé » en sourdine. */
    private fun describe() {
        contentDescription = if (muted) context.getString(R.string.mixer_strip_muted, title) else title
    }

    /** Touche Muet (pilule) : relief au repos, enfoncée au toucher, éclairée quand le son est coupé. */
    private fun drawMute(canvas: Canvas) {
        val r = muteBox.height() / 2
        val sink = if (pressingMute) dp(2).toFloat() else 0f
        rect.set(muteBox.left, muteBox.top + sink, muteBox.right, muteBox.bottom + sink)
        paint.color = cShadow
        canvas.drawRoundRect(rect.left, muteBox.top + dp(3), rect.right, muteBox.bottom + dp(3), r, r, paint)
        if (muted) {
            paint.color = cMuted
            paint.alpha = 60
            canvas.drawRoundRect(rect.left - dp(4), rect.top - dp(4), rect.right + dp(4), rect.bottom + dp(4), r + dp(4), r + dp(4), paint)
            paint.alpha = 255
            canvas.drawRoundRect(rect, r, r, paint)
        } else {
            paint.shader = muteFace
            canvas.drawRoundRect(rect, r, r, paint)
            paint.shader = null
            paint.color = cHighlight
            canvas.drawRect(rect.left + r, rect.top + 1, rect.right - r, rect.top + dp(2), paint)
        }
        text.typeface = medium
        text.color = if (muted) cOnMuted else cText
        text.textSize = sp(16f)
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(context.getString(R.string.mixer_mute), rect.centerX(), rect.centerY() - (text.ascent() + text.descent()) / 2, text)
    }

    private fun screw(canvas: Canvas, x: Float, y: Float) {
        val r = dp(3).toFloat()
        paint.color = cShadow
        canvas.drawCircle(x, y + 1, r + 1, paint)
        paint.color = cKeyBottom
        canvas.drawCircle(x, y, r, paint)
        paint.color = cKeyTop
        canvas.drawCircle(x, y - 0.5f, r - 1, paint)
        paint.color = cShadow
        canvas.drawRect(x - r + 1, y - 0.75f, x + r - 1, y + 0.75f, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressingMute = muteBox.contains(event.x, event.y)
                grabbing = !pressingMute && volume != null && event.y >= faderArea.top - dp(8) && event.y <= faderArea.bottom + dp(8)
                startVolume = volume ?: 0
                if (pressingMute) invalidate()
                return pressingMute || grabbing
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (grabbing && !dragging && abs(dy) > slop && abs(dy) > abs(dx)) {
                    dragging = true
                    // Le glisser vertical est au curseur : la console ne défile plus et le deck ne change pas d'onglet.
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    val next = MixerMath.dragVolume(startVolume, dy, travelBottom - travelTop)
                    val previous = volume ?: next
                    if (next != previous) {
                        if (next in DETENTS && previous !in DETENTS) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        current = next
                        invalidate()
                        onVolume?.invoke(next, false)
                    }
                }
                if (pressingMute && !muteBox.contains(event.x, event.y)) {
                    pressingMute = false
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    volume?.let { onVolume?.invoke(it, true) }
                } else if (pressingMute) {
                    pressingMute = false
                    invalidate()
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    performClick()
                    onMute?.invoke()
                }
                grabbing = false
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                grabbing = false
                if (pressingMute) {
                    pressingMute = false
                    invalidate()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun sp(value: Float) = value * resources.displayMetrics.scaledDensity

    private fun dpf(value: Float) = value * resources.displayMetrics.density

    companion object {
        private const val LEDS = 24
        private const val SHADOW_RADIUS_PX = 6f

        /** Table nue : halo de la pastille (18 %), fond de l'icône à l'appui (22 %, comme hush_press) et en sourdine (14 %). */
        private const val HALO_ALPHA = 46
        private const val PRESS_ALPHA = 56
        private const val MUTED_ALPHA = 36

        /** Opacité du vumètre sans niveau lu (35 %). */
        private const val UNMETERED_ALPHA = 0x59
        /** Sous cette hauteur (dp), tranche resserrée : la console d'un profil de l'Accueil. */
        const val TIGHT_DP = 380
        private const val VALUE_TIGHT_SP = 22f
        private const val HOLD_MS = 500L
        private const val FALL_PER_MS = 0.012f
        /** Volumes où le curseur « cranté » vibre en passant. */
        private val DETENTS = setOf(0, 50, 100)
    }
}
