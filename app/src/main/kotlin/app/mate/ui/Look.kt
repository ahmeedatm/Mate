package app.mate.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import app.mate.AnimFamily
import app.mate.Prefs
import app.mate.R
import kotlin.math.abs

/**
 * Ce qui donne de la vie à l'interface : une teinte par fonction (pastilles rondes des titres, tuiles d'applis)
 * et des animations courtes. La tablette est lente : animations de propriétés seulement (alpha, translation, échelle),
 * sur un calque matériel, et les boucles s'arrêtent dès que l'écran n'est plus affiché.
 */
object Look {
    private val HUES = intArrayOf(
        R.attr.c_hue_orange, R.attr.c_hue_green, R.attr.c_hue_cyan, R.attr.c_hue_violet,
        R.attr.c_hue_red, R.attr.c_hue_pink, R.attr.c_hue_yellow, R.attr.c_hue_blue,
    )

    /** Couleurs connues des applis courantes ; les autres noms tirent une teinte stable. */
    private val BRANDS = listOf(
        "teams" to R.attr.c_hue_violet, "discord" to R.attr.c_hue_violet, "code" to R.attr.c_hue_blue,
        "outlook" to R.attr.c_hue_blue, "word" to R.attr.c_hue_blue,
        "whatsapp" to R.attr.c_hue_green, "spotify" to R.attr.c_hue_green, "excel" to R.attr.c_hue_green, "dofus" to R.attr.c_hue_green,
        "youtube" to R.attr.c_hue_red, "netflix" to R.attr.c_hue_red, "valorant" to R.attr.c_hue_red, "micro" to R.attr.c_hue_red,
        "chrome" to R.attr.c_hue_yellow, "firefox" to R.attr.c_hue_orange, "powerpoint" to R.attr.c_hue_orange,
        "edge" to R.attr.c_hue_cyan, "slack" to R.attr.c_hue_pink, "deezer" to R.attr.c_hue_pink,
    )

    /** Teinte d'un nom (tuiles d'applis et de touches sans icône) : couleur de la marque, sinon stable d'après le nom. */
    fun nameHue(name: String): Int {
        val lower = name.lowercase()
        return BRANDS.firstOrNull { lower.contains(it.first) }?.second ?: HUES[Math.floorMod(lower.hashCode(), HUES.size)]
    }

    /** Icône teintée dans une pastille ronde de sa couleur (filtre de couleur : le teintage de TextView ne l'écrase pas). */
    fun badge(context: Context, icon: Int, color: Int, sizeDp: Int = 30, iconDp: Int = 18): Drawable {
        val density = context.resources.displayMetrics.density
        val size = (sizeDp * density).toInt()
        val inset = ((sizeDp - iconDp) * density / 2).toInt()
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(color, 0x38))
        }
        val glyph = ContextCompat.getDrawable(context, icon)!!.mutate().apply {
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        return LayerDrawable(arrayOf(circle, glyph)).apply {
            setLayerInset(1, inset, inset, inset, inset)
            setBounds(0, 0, size, size)
        }
    }

    /** Initiale d'une tuile : lettre de la teinte du nom sur un rond de la même teinte. */
    fun tintInitial(view: TextView, name: String) {
        val color = themeColor(view.context, nameHue(name))
        view.setTextColor(color)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(color, 0x30))
        }
    }

    /**
     * Texte lisible sur un fond imposé (tuile colorée de deck.json) : le texte du thème ou le fond du thème,
     * celui qui contraste le plus. Ex. Verrouiller (ardoise) en thème clair : texte clair.
     */
    fun readableOn(context: Context, fill: Int): Int {
        val text = themeColor(context, R.attr.c_text)
        val bg = themeColor(context, R.attr.c_bg)
        val opaque = ColorUtils.setAlphaComponent(fill, 0xFF)
        return if (ColorUtils.calculateContrast(text, opaque) >= ColorUtils.calculateContrast(bg, opaque)) text else bg
    }

    /** Initiale neutre (emplacement vide « + », emoji choisi) : sans rond. */
    fun plainInitial(view: TextView, colorAttr: Int) {
        view.setTextColor(themeColor(view.context, colorAttr))
        view.background = null
    }

    /** Arrivée à l'écran : montée et fondu, décalés d'un élément à l'autre (pas en kiosque, voir [enterSpring]). */
    fun enter(view: View, index: Int) = enterSpring(view, index * 45L, 18)

    /** Petit rebond (sélection, ajout), sur un ressort peu amorti : joue aussi en kiosque. Réduire les animations : rien. */
    fun pop(view: View) {
        view.animate().cancel()
        if (!on(view.context, AnimFamily.DETAILS)) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.scaleX = 0.7f
        view.scaleY = 0.7f
        navSpring(view, DynamicAnimation.SCALE_X, BOUNCE, 1f)
        navSpring(view, DynamicAnimation.SCALE_Y, BOUNCE, 1f)
    }

    /**
     * Pastille dont l'état change (ordinateur en ligne ↔ injoignable) : elle gonfle puis revient, une fois. Réduire les
     * animations : rien.
     */
    fun blip(view: View) {
        if (!on(view.context, AnimFamily.ALERTS)) return
        view.scaleX = BLIP_SCALE
        view.scaleY = BLIP_SCALE
        navSpring(view, DynamicAnimation.SCALE_X, BOUNCE, 1f)
        navSpring(view, DynamicAnimation.SCALE_Y, BOUNCE, 1f)
    }

    /**
     * Contenu chargé pour la première fois dans `view` (agenda, tâches, MRs, Jira) : fondu au lieu d'un saut du vide au
     * plein. Les relectures suivantes (toutes les minutes…) ne rejouent rien.
     */
    fun revealOnce(view: View) {
        if (view.getTag(R.id.revealed) == true) return
        view.setTag(R.id.revealed, true)
        if (!on(view.context, AnimFamily.DETAILS)) return
        view.alpha = 0f
        navSpring(view, DynamicAnimation.ALPHA, EFFECTS_SLOW, 1f)
    }

    /**
     * Dernières secondes d'un compte à rebours (Pomodoro) : le chiffre grossit un peu à chaque seconde puis revient.
     * À appeler à chaque rafraîchissement : une seule pulsation par seconde.
     */
    fun countdown(view: View, remainingMs: Long, running: Boolean) {
        val second = ((remainingMs + 999) / 1000).toInt()
        if (!running || second > COUNTDOWN_SECONDS || second <= 0 || view.getTag(R.id.countdown_second) == second) return
        view.setTag(R.id.countdown_second, second)
        if (!on(view.context, AnimFamily.DETAILS)) return
        view.scaleX = COUNTDOWN_SCALE
        view.scaleY = COUNTDOWN_SCALE
        navSpring(view, DynamicAnimation.SCALE_X, SPATIAL_DEFAULT, 1f)
        navSpring(view, DynamicAnimation.SCALE_Y, SPATIAL_DEFAULT, 1f)
    }

    /**
     * Bandeau qui arrive (`show`) d'en haut en fondu, ou repart vers le haut puis disparaît ; `onGone` une fois masqué.
     * Réduire les animations : affiché ou masqué tout de suite.
     */
    /**
     * Écran de démarrage (cold start) : le logo apparaît en fondu et grandit depuis 82 %, puis l'écran s'efface pour
     * révéler l'appli. Un filet de sécurité le masque dans tous les cas (jamais coincé dessus). Réduire les animations :
     * court, sans mouvement. Ressorts : jouent aussi en kiosque.
     */
    fun splash(cover: View, logo: View) {
        if (still(cover.context)) return
        logo.alpha = 0f
        logo.scaleX = SPLASH_LOGO_FROM
        logo.scaleY = SPLASH_LOGO_FROM
        navSpring(logo, DynamicAnimation.ALPHA, EFFECTS_DEFAULT, 1f)
        navSpring(logo, DynamicAnimation.SCALE_X, SPATIAL_DEFAULT, 1f)
        navSpring(logo, DynamicAnimation.SCALE_Y, SPATIAL_DEFAULT, 1f)
    }

    /** Masque l'écran de démarrage (une fois l'appli dessinée) : fondu puis retiré, instantané si animations réduites. */
    fun hideSplash(cover: View) {
        if (cover.visibility != View.VISIBLE) return
        if (still(cover.context)) {
            cover.visibility = View.GONE
            return
        }
        navSpring(cover, DynamicAnimation.ALPHA, EFFECTS_DEFAULT, 0f) { cover.visibility = View.GONE }
    }

    fun slideBanner(view: View, show: Boolean, onGone: () -> Unit = {}) {
        val away = -view.dp(BANNER_AWAY_DP).toFloat()
        if (!on(view.context, AnimFamily.ALERTS)) {
            view.translationY = 0f
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            if (!show) onGone()
            return
        }
        // Il pivote depuis son bord haut : à l'arrivée il « tombe » et rebondit doucement (ressort peu amorti), avec un
        // léger grandissement et un fondu ; au départ il remonte et s'efface en se réduisant un peu.
        view.pivotY = 0f
        view.pivotX = (if (view.width > 0) view.width else view.dp(BANNER_AWAY_DP) * 8) / 2f
        if (show && view.alpha == 1f && view.translationY == 0f) {
            view.translationY = away
            view.alpha = 0f
            view.scaleX = BANNER_POP
            view.scaleY = BANNER_POP
        }
        navSpring(view, DynamicAnimation.ALPHA, EFFECTS_DEFAULT, if (show) 1f else 0f)
        navSpring(view, DynamicAnimation.SCALE_X, if (show) BANNER_DROP else SPATIAL_FAST, if (show) 1f else BANNER_POP)
        navSpring(view, DynamicAnimation.SCALE_Y, if (show) BANNER_DROP else SPATIAL_FAST, if (show) 1f else BANNER_POP)
        navSpring(view, DynamicAnimation.TRANSLATION_Y, if (show) BANNER_DROP else SPATIAL_DEFAULT, if (show) 0f else away) { if (!show) onGone() }
    }

    /** Flottement lent, en boucle : à arrêter avec [stop]. Réduire les animations : immobile (pas lancé). */
    fun float(view: View): ValueAnimator {
        val amplitude = 4 * view.resources.displayMetrics.density
        return ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, -amplitude, amplitude).apply {
            duration = 2400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            if (!still(view.context)) start()
        }
    }

    /** Pulsation douce, en boucle (chrono en marche). Le parent ne rogne plus l'élément agrandi. Réduire les animations : pas lancée. */
    fun pulse(view: View): ValueAnimator {
        var parent = view.parent as? android.view.ViewGroup
        repeat(2) {
            parent?.clipChildren = false
            parent?.clipToPadding = false
            parent = parent?.parent as? android.view.ViewGroup
        }
        return ValueAnimator.ofFloat(1f, 1.07f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { view.scaleX = it.animatedValue as Float; view.scaleY = view.scaleX }
            if (!still(view.context)) start()
        }
    }

    /** Ressort M3 (schéma standard, docs/ui-ux/da) : raideur et amortissement. */
    class Spring(val stiffness: Float, val damping: Float)

    val SPATIAL_FAST = Spring(1400f, 0.9f)
    val SPATIAL_DEFAULT = Spring(700f, 0.9f)
    val SPATIAL_SLOW = Spring(300f, 0.9f)
    val EFFECTS_FAST = Spring(3800f, 1f)
    val EFFECTS_DEFAULT = Spring(1600f, 1f)
    val EFFECTS_SLOW = Spring(800f, 1f)

    /**
     * Réduire les animations (Réglages > Accessibilité) ou échelle d'animation d'Android à zéro (kiosque, réglage
     * développeur, tests instrumentés) : états finaux posés sans animer. Les ressorts ne suivent pas cette échelle d'eux-mêmes.
     */
    fun reduced(context: Context): Boolean = Motion.transitions(
        Prefs.reduceMotion(context),
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
    )

    /**
     * Réduire les animations, demandé dans Réglages > Accessibilité : plus rien ne bouge en continu, et même les ressorts
     * joués malgré l'échelle à zéro se posent. Pas l'échelle d'Android : le kiosque la met à zéro pour la vitesse (voir Motion).
     */
    fun still(context: Context): Boolean = Motion.loops(Prefs.reduceMotion(context))

    /** Animations de `family` jouées : ni Réduire les animations, ni la famille coupée dans Réglages > Animations. */
    fun on(context: Context, family: AnimFamily): Boolean = !still(context) && Prefs.animation(context, family)

    private fun force(token: Spring, to: Float) = SpringForce(to).setStiffness(token.stiffness).setDampingRatio(token.damping)

    private fun spatialFast(view: View, property: DynamicAnimation.ViewProperty) =
        SpringAnimation(view, property).setSpring(force(SPATIAL_FAST, 1f))

    /**
     * Ressort sur une valeur seule : `onUpdate` reçoit la valeur de `from` vers `to`, `onEnd` quand elle est posée
     * (pas si on l'annule). À annuler avec `cancel()` quand la vue part. Échelle d'animation à zéro : `to` tout de suite, null,
     * sauf `always` (joue quand même : voir [slideIn]) ; Réduire les animations : `to` tout de suite, même avec `always`.
     */
    fun spring(
        context: Context, token: Spring, from: Float, to: Float, onUpdate: (Float) -> Unit, onEnd: (() -> Unit)? = null, always: Boolean = false,
    ): SpringAnimation? {
        if (from == to || (!always && reduced(context)) || still(context)) {
            onUpdate(to)
            onEnd?.invoke()
            return null
        }
        return SpringAnimation(FloatValueHolder(from))
            .setSpring(force(token, to))
            .setMinimumVisibleChange(maxOf(abs(to - from) * 0.002f, 0.0005f))
            .addUpdateListener { _, value, _ -> onUpdate(value) }
            .addEndListener { _, canceled, _, _ -> if (!canceled) onEnd?.invoke() }
            .also { it.start() }
    }

    /**
     * Ressort sur une propriété de vue, lancé à `velocity` (px/s : celle du doigt qui lâche). `onEnd` quand elle est posée
     * (pas si on l'annule). Échelle d'animation à zéro : valeur posée tout de suite, null, sauf `always` (navigation :
     * le kiosque met cette échelle à zéro pour les animations du système, les ressorts de la barre jouent quand même).
     */
    fun springTo(
        view: View, property: DynamicAnimation.ViewProperty, token: Spring, to: Float, velocity: Float = 0f, onEnd: (() -> Unit)? = null,
        always: Boolean = false,
    ): SpringAnimation? {
        if (!always && reduced(view.context)) {
            property.setValue(view, to)
            onEnd?.invoke()
            return null
        }
        return SpringAnimation(view, property).setSpring(force(token, to)).setStartVelocity(velocity)
            .addEndListener { _, canceled, _, _ -> if (!canceled) onEnd?.invoke() }
            .also { it.start() }
    }

    /**
     * Ressort de navigation (barre, changement d'écran, repos) : joue même en kiosque (échelle d'animation à zéro, voir
     * [springTo]) et remplace le ressort en cours sur la même propriété, qui repart de sa position et de sa vitesse.
     */
    fun navSpring(view: View, property: DynamicAnimation.ViewProperty, token: Spring, to: Float, onEnd: (() -> Unit)? = null) {
        @Suppress("UNCHECKED_CAST")
        val springs = view.getTag(R.id.nav_springs) as? MutableMap<DynamicAnimation.ViewProperty, SpringAnimation>
            ?: mutableMapOf<DynamicAnimation.ViewProperty, SpringAnimation>().also { view.setTag(R.id.nav_springs, it) }
        val running = springs[property]
        if (running != null && running.isRunning) {
            running.spring.finalPosition = to
            running.addEndListener(endOnce(onEnd))
            return
        }
        springs[property] = springTo(view, property, token, to, onEnd = onEnd, always = true) ?: return
    }

    private fun endOnce(onEnd: (() -> Unit)?): DynamicAnimation.OnAnimationEndListener =
        object : DynamicAnimation.OnAnimationEndListener {
            override fun onAnimationEnd(animation: DynamicAnimation<*>, canceled: Boolean, value: Float, velocity: Float) {
                animation.removeEndListener(this)
                if (!canceled) onEnd?.invoke()
            }
        }

    /**
     * Changement d'écran ou de profil par la barre (axe partagé vertical) : le nouveau arrive de 24 dp dans le sens du
     * déplacement (`fromBelow` : on est descendu dans la barre, il monte) en fondu, sur le fond de l'écran. L'ancien est
     * déjà masqué : pas de fondu croisé, qui mêle deux écrans chargés. Calque matériel le temps du mouvement.
     */
    fun sharedAxis(into: View, fromBelow: Boolean) {
        if (!on(into.context, AnimFamily.NAVIGATION)) {
            into.alpha = 1f
            into.translationY = 0f
            return
        }
        into.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        into.alpha = 0f
        into.translationY = into.dp(NAV_AXIS_DP) * if (fromBelow) 1f else -1f
        navSpring(into, DynamicAnimation.ALPHA, EFFECTS_DEFAULT, 1f)
        navSpring(into, DynamicAnimation.TRANSLATION_Y, SPATIAL_DEFAULT, 0f) { into.setLayerType(View.LAYER_TYPE_NONE, null) }
    }

    /**
     * Arrivée d'une boîte de dialogue (`root` : sa fenêtre) : fondu et léger zoom de 94 à 100 % sur des ressorts, qui jouent
     * aussi en kiosque (l'animation de fenêtre d'Android, coupée là-bas, est retirée partout pour qu'elles se ressemblent).
     * Réduire les animations : rien ne bouge.
     */
    fun popIn(root: View) {
        if (!on(root.context, AnimFamily.DIALOGS)) return
        root.alpha = 0f
        root.scaleX = POP_SCALE
        root.scaleY = POP_SCALE
        navSpring(root, DynamicAnimation.ALPHA, EFFECTS_DEFAULT, 1f)
        navSpring(root, DynamicAnimation.SCALE_X, SPATIAL_FAST, 1f)
        navSpring(root, DynamicAnimation.SCALE_Y, SPATIAL_FAST, 1f)
    }

    /**
     * Cloche qui sonne (notification arrivée) : un balancement amorti autour de son anneau, puis plus rien ; la pastille
     * apparaît d'un petit rebond. Réduire les animations : la pastille s'affiche simplement.
     */
    fun ring(bell: View, dot: View) {
        if (!on(bell.context, AnimFamily.ALERTS)) return
        bell.pivotX = bell.width / 2f
        bell.pivotY = bell.height * 0.25f
        SpringAnimation(bell, DynamicAnimation.ROTATION)
            .setSpring(SpringForce(0f).setStiffness(RING_STIFFNESS).setDampingRatio(RING_DAMPING))
            .setStartVelocity(RING_VELOCITY)
            .start()
        dot.scaleX = 0f
        dot.scaleY = 0f
        SpringAnimation(dot, DynamicAnimation.SCALE_X).setSpring(SpringForce(1f).setStiffness(SpringForce.STIFFNESS_MEDIUM).setDampingRatio(0.45f)).start()
        SpringAnimation(dot, DynamicAnimation.SCALE_Y).setSpring(SpringForce(1f).setStiffness(SpringForce.STIFFNESS_MEDIUM).setDampingRatio(0.45f)).start()
    }

    private const val POP_SCALE = 0.94f

    /** Rebond (pop, pastille d'état) : ressort peu amorti, un dépassement visible puis posé. */
    private val BOUNCE = Spring(600f, 0.45f)
    private const val BLIP_SCALE = 1.9f

    /** Compte à rebours : pulsation sur les 10 dernières secondes. */
    private const val COUNTDOWN_SECONDS = 10
    private const val COUNTDOWN_SCALE = 1.08f

    /** Écran de démarrage : échelle de départ du logo (il grandit en apparaissant). */
    private const val SPLASH_LOGO_FROM = 0.82f

    /** Course du bandeau qui arrive d'en haut. */
    private const val BANNER_AWAY_DP = 72

    /** Arrivée du bandeau : échelle de départ (il grandit un peu) et ressort peu amorti pour un léger rebond à l'atterrissage. */
    private const val BANNER_POP = 0.94f
    private val BANNER_DROP = Spring(520f, 0.68f)

    /** Balancement de la cloche : vitesse de départ (°/s), ressort peu amorti (quelques allers-retours, < 1 s). */
    private const val RING_VELOCITY = 900f
    private const val RING_STIFFNESS = 450f
    private const val RING_DAMPING = 0.18f

    /** Temps du glissement d'un écran par la barre : ce qui recharge l'écran attend jusque-là (calque stable). */
    const val NAV_SETTLE_MS = 350L

    /** Course de l'écran qui arrive par la barre (24 dp, spec Deck V2). */
    private const val NAV_AXIS_DP = 24

    /**
     * Kiosque : les widgets de l'Accueil arrivent seuls (montée sans fondu, [enterSpring]) ; l'écran entier ne glisse
     * pas en plus sur son calque, que leurs mouvements forceraient à redessiner à chaque image.
     */
    fun cascadeOnly(context: Context): Boolean = reduced(context) && !still(context)

    /** Arrivée d'un élément de l'Accueil : il monte de `dy` dp (ressort par défaut) en fondu, après `delayMs`. */
    fun enterSpring(view: View, delayMs: Long, dy: Int) {
        view.animate().cancel()
        if (!on(view.context, AnimFamily.HOME)) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        // Kiosque (échelle d'animation à zéro) : la montée seule, sans fondu ni calque. Une translation ne redessine
        // rien ; le fondu par widget (et son calque) faisait passer l'arrivée de l'Accueil de 40 à 56 % d'images
        // lentes sur la 4.2 (mesure du 29/10/2026). L'écran entier ne glisse pas en même temps ([cascadeOnly]).
        val fade = !reduced(view.context)
        if (fade) view.alpha = 0f
        view.translationY = dy * view.resources.displayMetrics.density
        view.postDelayed({
            SpringAnimation(view, DynamicAnimation.TRANSLATION_Y).setSpring(force(SPATIAL_DEFAULT, 0f)).start()
            if (fade) SpringAnimation(view, DynamicAnimation.ALPHA).setSpring(force(EFFECTS_DEFAULT, 1f)).start()
        }, delayMs)
    }

    /**
     * Changement d'onglet (axe partagé M3, docs/ui-ux/specs-ds.md) : les vues visibles entrent de 30 dp du côté
     * `direction` (1 droite, -1 gauche) en fondu, sur un calque matériel. Rend les ressorts, à annuler avec [settle].
     * Joue même échelle d'animation à zéro : le kiosque la met à zéro pour la vitesse (docs/kiosque.md), et ces ressorts
     * (Android 10 : ils ne lisent pas l'échelle) ne touchent que des propriétés de vue sur calque matériel.
     * Réduire les animations : rien ne glisse.
     */
    fun slideIn(views: List<View>, direction: Int): List<SpringAnimation> {
        if (direction == 0 || views.isEmpty() || still(views.first().context)) return emptyList()
        val shift = direction * SHARED_AXIS_DP * views.first().resources.displayMetrics.density
        return views.filter { it.visibility == View.VISIBLE }.flatMap { view ->
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            view.translationX = shift
            view.alpha = 0f
            listOf(
                SpringAnimation(view, DynamicAnimation.TRANSLATION_X).setSpring(force(SPATIAL_DEFAULT, 0f))
                    .addEndListener { _, _, _, _ -> view.setLayerType(View.LAYER_TYPE_NONE, null) },
                SpringAnimation(view, DynamicAnimation.ALPHA).setSpring(force(EFFECTS_DEFAULT, 1f)),
            )
        }.onEach { it.start() }
    }

    /** Arrête un [slideIn] en cours et remet les vues en place. */
    fun settle(springs: List<SpringAnimation>, views: List<View>) {
        springs.forEach { it.cancel() }
        views.forEach {
            it.translationX = 0f
            it.alpha = 1f
            it.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    private const val SHARED_AXIS_DP = 30

    /** Appui (DA Console douce) : la vue s'enfonce à 0,96 puis revient sur un ressort. Le clic n'est pas consommé. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressSpring(view: View) {
        if (still(view.context)) return
        val x = spatialFast(view, DynamicAnimation.SCALE_X)
        val y = spatialFast(view, DynamicAnimation.SCALE_Y)
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> 0.96f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 1f
                else -> null
            }?.let { x.animateToFinalPosition(it); y.animateToFinalPosition(it) }
            false
        }
    }

    /**
     * Appui piloté de l'extérieur, pour une vue qui ne reçoit pas elle-même le DOWN et l'UP (case d'une liste dont le
     * clic passe par la liste) : `true` l'enfonce à 0,96, `false` la rend. [pressSpring] pour une vue cliquable.
     */
    fun pressTo(view: View, pressed: Boolean) {
        if (still(view.context)) return
        val (x, y) = springsOf(view) ?: (spatialFast(view, DynamicAnimation.SCALE_X) to spatialFast(view, DynamicAnimation.SCALE_Y))
            .also { view.setTag(R.id.press_springs, it) }
        val to = if (pressed) 0.96f else 1f
        x.animateToFinalPosition(to)
        y.animateToFinalPosition(to)
    }

    @Suppress("UNCHECKED_CAST")
    private fun springsOf(view: View) = view.getTag(R.id.press_springs) as? Pair<SpringAnimation, SpringAnimation>

    /** Vue réutilisée pour une autre case : plus d'appui en cours, taille normale. */
    fun pressReset(view: View) {
        springsOf(view)?.let { (x, y) -> x.cancel(); y.cancel() }
        view.scaleX = 1f
        view.scaleY = 1f
    }

    /** Change l'icône : la nouvelle arrive en grandissant (0,5 → 1) sur un ressort, en fondu. Même icône : rien. */
    fun swapIcon(icon: ImageView, res: Int) {
        if (icon.tag == res) return
        val first = icon.tag == null
        icon.tag = res
        icon.setImageResource(res)
        if (first || still(icon.context)) return
        icon.scaleX = 0.5f
        icon.scaleY = 0.5f
        icon.alpha = 0f
        spatialFast(icon, DynamicAnimation.SCALE_X).animateToFinalPosition(1f)
        spatialFast(icon, DynamicAnimation.SCALE_Y).animateToFinalPosition(1f)
        icon.animate().alpha(1f).setDuration(120).start()
    }

    fun stop(animator: ValueAnimator?, view: View) {
        animator?.cancel()
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }
}

/**
 * Titre de carte (style CardTitle) : texte simple, sans icône ni majuscules (un `android:drawableStart` du XML est retiré).
 */
class TitleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0, defStyleRes: Int = R.style.CardTitle,
) : TextView(context, attrs, defStyleAttr, defStyleRes) {
    init {
        // TalkBack : titre de section, atteint par la navigation de titre en titre.
        ViewCompat.setAccessibilityHeading(this, true)
        setCompoundDrawablesRelative(null, null, null, null)
        // Étiquettes du thème (Hush : Share Tech Mono, majuscules, un peu espacées) ; ailleurs, la police des titres telle quelle.
        val caps = themeBool(context, R.attr.c_label_caps)
        isAllCaps = caps
        typeface = themeFont(context, R.attr.c_font_label)
        letterSpacing = maxOf(ReadingAids.letterSpacing(context), if (caps) CAPS_SPACING else 0f)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, themePx(context, R.attr.c_title_size).toFloat())
        setTextColor(themeColor(context, R.attr.c_label))
    }
}

/** Espacement des lettres d'une étiquette en majuscules (em). */
private const val CAPS_SPACING = 0.08f
