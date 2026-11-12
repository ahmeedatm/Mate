package app.mate.ui.home

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.HaEntity
import app.mate.net.HaLight
import app.mate.net.runCatchingCancellable
import app.mate.ui.Look
import app.mate.ui.errorText
import app.mate.ui.showImmersive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Entité montrée par la Pastille : celle choisie ([id]) si elle existe encore, sinon la première lumière ou prise. */
internal fun haPick(entities: List<HaEntity>, id: String?): HaEntity? =
    entities.firstOrNull { it.id == id } ?: entities.firstOrNull { !it.isScene } ?: entities.firstOrNull()

/** Icône d'une entité, lue dans son type : scène, prise (`switch.`), télé ou Apple TV (`media_player.`) ou lumière. */
internal fun haIcon(e: HaEntity) = when {
    e.isScene -> R.drawable.ic_palette
    e.id.startsWith("switch.") -> R.drawable.ic_bolt
    e.id.startsWith("media_player.") -> R.drawable.ic_tv
    else -> R.drawable.ic_lightbulb
}

/** Exemple de Home Assistant pour le catalogue : des entités au nom traduit ([name]), sans l'ordinateur. */
class HaSample(val entities: List<Pair<HaEntity, Int>>) : WidgetSample {
    companion object {
        /** Deux lumières (une allumée, à 80 %), une prise allumée, une chambre éteinte et une scène. */
        val DEFAULT = HaSample(
            listOf(
                HaEntity("light.salon", "", isScene = false, on = true, available = true, light = HaLight(color = false, brightness = 80, rgb = null)) to R.string.ha_sample_salon,
                HaEntity("light.bureau", "", isScene = false, on = false, available = true) to R.string.ha_sample_bureau,
                HaEntity("switch.cafe", "", isScene = false, on = true, available = true) to R.string.ha_sample_cafe,
                HaEntity("light.chambre", "", isScene = false, on = false, available = true) to R.string.ha_sample_chambre,
                HaEntity("scene.film", "", isScene = true, on = false, available = true) to R.string.ha_sample_film,
            ),
        )
    }
}

/**
 * Lumières, prises, télés et scènes de Home Assistant (liste blanche réglée sur l'ordinateur, qui garde le jeton). Une ligne par
 * entité : icône, nom et état ; un toucher la bascule (une scène se lance). Allumée : icône et état prennent l'accent, l'icône
 * d'une lumière de couleur prend la sienne, et l'état dit sa luminosité. Un appui long sur une lumière réglable ouvre son
 * panneau ([HaLightPanel] : luminosité, couleur).
 * L'état se relit à chaque minute et après chaque toucher. Une mise en page par famille de formes :
 * - Pastille : une seule entité (choisie dans les réglages du widget), la case entière s'allume ;
 * - Ligne : les entités côte à côte, le reste au défilement ;
 * - Colonne : les entités l'une sous l'autre ;
 * - Bloc : une grille d'autant de colonnes que de cases de large ;
 * - Grand, Plein écran : la grille des lumières et prises, puis les scènes à part.
 */
class HaWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val title = cardTitle(R.string.widget_ha)
    private val status = label(14f, R.attr.c_text_dim)
    override val view = LinearLayout(context).asCard(16).apply { orientation = LinearLayout.VERTICAL }

    /** Où les entités sont dessinées, refait quand la forme change. */
    private var body: ViewGroup = LinearLayout(context)

    private var entities: List<HaEntity> = emptyList()

    /** Entités dont l'ordre est parti : seule leur case est bloquée, les autres restent touchables. */
    private var pending: Set<String> = emptySet()

    /** Dernière liste dessinée : rien n'est refait tant qu'elle ne change pas (toutes les minutes, rien n'a bougé). */
    private var drawn: Pair<List<HaEntity>, Set<String>>? = null

    /** Numéro de la dernière lecture demandée : une lecture plus ancienne que le dernier toucher est jetée. */
    private var reads = 0
    private var preview = false

    /** Ligne posée sur une rangée haute (232 dp) : des tuiles hautes à grande icône au lieu de lignes perdues dans le vide. */
    private var tallRow = false

    init {
        view.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    lightPanel?.dismiss()
                    lightPanel = null
                }
            },
        )
        view.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val tall = family == ShapeFamily.ROW && bottom - top >= dp(TALL_ROW_DP)
            if (tall != tallRow) {
                tallRow = tall
                drawn = null
                view.post { render() }
            }
        }
    }

    override fun applySize() {
        for (v in listOf(title, status)) (v.parent as? ViewGroup)?.removeView(v)
        view.removeAllViews()
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        // Erreur ou liste vide : deux lignes au plus, la place reste aux entités.
        status.maxLines = if (family == ShapeFamily.PILL || family == ShapeFamily.ROW) 2 else 4
        status.ellipsize = TextUtils.TruncateAt.END
        if (family == ShapeFamily.PILL) {
            view.setPadding(dp(6), dp(6), dp(6), dp(6))
            body = LinearLayout(context)
            view.addView(status, LinearLayout.LayoutParams(match, wrap))
            view.addView(body, LinearLayout.LayoutParams(match, 0, 1f))
        } else {
            // Ligne : sans le titre, pour tenir dans la rangée basse de l'Accueil (92 dp) ; les icônes disent ce que c'est.
            val row = family == ShapeFamily.ROW
            view.setPadding(dp(16), dp(if (row) 8 else 12), dp(16), dp(if (row) 8 else 12))
            if (!row) view.addView(title, LinearLayout.LayoutParams(match, wrap))
            view.addView(status, LinearLayout.LayoutParams(match, wrap))
            body = when (family) {
                ShapeFamily.ROW -> LinearLayout(context)
                else -> LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            }
            val scroll = if (family == ShapeFamily.ROW) {
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    // Fondu au bord : l'entité coupée annonce qu'il y en a d'autres, au lieu d'un bord franc qui fait bug.
                    isHorizontalFadingEdgeEnabled = true
                    setFadingEdgeLength(dp(FADE_DP))
                    addView(body)
                }
            } else {
                ScrollView(context).apply {
                    isVerticalScrollBarEnabled = false
                    isVerticalFadingEdgeEnabled = true
                    setFadingEdgeLength(dp(FADE_DP))
                    addView(body, ViewGroup.LayoutParams(match, wrap))
                }
            }
            view.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f).apply { if (!row) topMargin = dp(8) })
        }
        drawn = null
        render()
    }

    override fun showPreview(sample: WidgetSample) {
        val s = sample as? HaSample ?: return
        preview = true
        entities = s.entities.map { (e, name) -> e.copy(name = context.getString(name)) }
        status.visibility = View.GONE
        drawn = null
        render()
    }

    override fun onMinute() {
        host.scope.launch { load() }
    }

    override suspend fun load() {
        if (preview) return
        val mine = ++reads
        val result = runCatchingCancellable { BackendClient(context).ha.entities() }
        if (mine != reads) return
        result
            .onSuccess { entities = it; status.visibility = View.GONE }
            .onFailure { status.text = context.errorText(it); status.visibility = View.VISIBLE }
        render()
    }

    override fun setActive(active: Boolean) {
        if (active) onMinute()
    }

    /** Réglage de la Pastille : l'entité à montrer. */
    override val hasSettings get() = family == ShapeFamily.PILL

    override fun showSettings() {
        if (entities.isEmpty()) return toast(context.getString(R.string.ha_empty))
        val current = haPick(entities, placed.config.optString("entity").ifEmpty { null })
        AlertDialog.Builder(context)
            .setTitle(R.string.ha_pick_title)
            .setSingleChoiceItems(entities.map { it.name }.toTypedArray(), entities.indexOf(current)) { dialog, which ->
                placed.config.put("entity", entities[which].id)
                host.save()
                drawn = null
                render()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .showImmersive()
    }

    private fun render() {
        if (drawn == entities to pending) return
        drawn = entities to pending
        body.removeAllViews()
        if (entities.isEmpty() && status.visibility != View.VISIBLE) {
            status.setText(R.string.ha_empty)
            status.visibility = View.VISIBLE
        }
        when (family) {
            ShapeFamily.PILL -> haPick(entities, placed.config.optString("entity").ifEmpty { null })?.let {
                body.addView(pill(it), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            ShapeFamily.ROW -> for (e in entities) body.addView(if (tallRow) tile(e) else row(e), LinearLayout.LayoutParams(dp(ROW_ITEM_DP), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(8) })
            ShapeFamily.COLUMN -> for (e in entities) body.addView(row(e), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            ShapeFamily.BLOCK -> body.addView(grid(entities))
            ShapeFamily.LARGE, ShapeFamily.FULL -> {
                val (scenes, things) = entities.partition { it.isScene }
                if (things.isNotEmpty()) body.addView(grid(things))
                if (scenes.isNotEmpty()) {
                    body.addView(
                        label(13f, R.attr.c_text_dim).apply { setText(R.string.ha_scenes) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(if (things.isEmpty()) 0 else 12); bottomMargin = dp(6) },
                    )
                    body.addView(grid(scenes))
                }
            }
        }
    }

    /** Autant de colonnes que de cases de large. */
    private fun grid(list: List<HaEntity>) = GridLayout(context).apply {
        columnCount = placed.size.w
        for (e in list) {
            val params = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            addView(row(e), params)
        }
    }

    /** Une entité : icône, nom et état, sur un fond en relief ; allumée, l'icône et l'état prennent l'accent. */
    private fun row(e: HaEntity): View {
        val on = e.on && !e.isScene
        return EntityBox(context, ownsLongPress = adjustable(e)).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(color(R.attr.c_surface_raised))
                cornerRadius = dp(14).toFloat()
            }
            minimumHeight = dp(56)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            addView(icon(e, 24, if (on) R.attr.c_accent else R.attr.c_text_dim, lampTint = true))
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(e.name, 15f, R.attr.c_text))
                addView(text(stateText(e), 13f, if (on) R.attr.c_accent else R.attr.c_text_dim))
            }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            touchable(e)
            Look.pressSpring(this)
        }
    }

    /** Tuile haute (Ligne sur une rangée haute) : grande icône en haut, nom et état en bas, comme la Pastille. */
    private fun tile(e: HaEntity): View {
        val on = e.on && !e.isScene
        return EntityBox(context, ownsLongPress = adjustable(e)).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(color(R.attr.c_surface_raised))
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(icon(e, 36, if (on) R.attr.c_accent else R.attr.c_text_dim, lampTint = true))
            addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            addView(text(e.name, 18f, R.attr.c_text))
            addView(text(stateText(e), 14f, if (on) R.attr.c_accent else R.attr.c_text_dim))
            touchable(e)
            Look.pressSpring(this)
        }
    }

    /**
     * Pastille : la case entière est l'entité ; allumée, elle prend l'accent, et l'état est toujours écrit (jamais la couleur
     * seule). Dans la rangée basse de l'Accueil (92 dp), l'état passe sur la ligne du nom : « Salon · Allumé ».
     */
    private fun pill(e: HaEntity): View {
        val on = e.on && !e.isScene
        val ink = if (on) R.attr.c_on_accent else R.attr.c_text
        val name = text(e.name, 16f, ink).apply { setShadowLayer(0f, 0f, 0f, 0) }
        val state = text(stateText(e), 13f, if (on) R.attr.c_on_accent else R.attr.c_text_dim).apply { setShadowLayer(0f, 0f, 0f, 0) }
        return EntityBox(context, ownsLongPress = adjustable(e)).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(color(if (on) R.attr.c_accent else R.attr.c_surface_raised))
                cornerRadius = dp(20).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(icon(e, 28, ink))
            addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            addView(name)
            addView(state)
            touchable(e)
            Look.pressSpring(this)
            addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                val low = bottom - top < dp(PILL_STATE_DP)
                if (low == (state.visibility == View.GONE)) return@addOnLayoutChangeListener
                post {
                    state.visibility = if (low) View.GONE else View.VISIBLE
                    name.text = if (low) context.getString(R.string.ha_entity_inline, e.name, stateText(e)) else e.name
                }
            }
        }
    }

    private fun View.touchable(e: HaEntity) {
        isEnabled = e.id !in pending
        alpha = if (!e.available) UNAVAILABLE_ALPHA else if (e.id in pending) PENDING_ALPHA else 1f
        contentDescription = context.getString(R.string.ha_entity_state, e.name, stateText(e))
        setOnClickListener { toggle(e) }
        if (adjustable(e)) {
            setOnLongClickListener {
                adjust(e)
                true
            }
            ViewCompat.replaceAccessibilityAction(this, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK, context.getString(R.string.ha_light_adjust), null)
        }
    }

    /** [lampTint] : une lumière allumée prend sa propre couleur, si elle reste visible sur la case. */
    private fun icon(e: HaEntity, size: Int, colorId: Int, lampTint: Boolean = false) = ImageView(context).apply {
        setImageResource(haIcon(e))
        val base = color(colorId)
        imageTintList = ColorStateList.valueOf(if (lampTint) haTint(e, base, color(R.attr.c_surface_raised)) else base)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun text(value: String, size: Float, colorId: Int) = label(size, colorId).apply {
        text = value
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun stateText(e: HaEntity) = haBrightness(e)?.let { context.getString(R.string.ha_on_level, it) } ?: context.getString(
        when {
            !e.available -> R.string.ha_unavailable
            e.isScene -> R.string.ha_scene
            e.on -> R.string.ha_on
            else -> R.string.ha_off
        },
    )

    private fun toggle(entity: HaEntity) {
        if (preview) return
        if (!entity.available) return toast(context.getString(R.string.ha_unavailable_hint, entity.name))
        if (entity.id in pending) return
        pending = pending + entity.id
        reads++
        render()
        host.scope.launch {
            try {
                runCatchingCancellable { BackendClient(context).ha.toggle(entity.id) }
                    .onFailure { toast(context.errorText(it)) }
                load()
            } finally {
                pending = pending - entity.id
                render()
            }
        }
    }

    /** Lumière qui se règle : son appui long ouvre le panneau, pas le mode Modifier de l'Accueil. */
    private fun adjustable(e: HaEntity) = e.light != null && !preview

    /** Panneau de lumière ouvert : fermé quand le widget quitte l'écran. */
    private var lightPanel: Dialog? = null

    /** Réglages envoyés un par un, dans l'ordre des gestes : une valeur ancienne ne passe pas après la récente. */
    private val lightOrders = Mutex()

    /** Panneau de la lumière : chaque réglage part à l'ordinateur, puis l'état est relu. */
    private fun adjust(entity: HaEntity) {
        if (!entity.available) return toast(context.getString(R.string.ha_unavailable_hint, entity.name))
        lightPanel?.dismiss()
        lightPanel = HaLightPanel(
            context,
            entity,
            onSet = { brightness, rgb ->
                reads++
                host.scope.launch {
                    lightOrders.withLock {
                        runCatchingCancellable { BackendClient(context).ha.setLight(entity.id, brightness, rgb) }
                            .onFailure { toast(context.errorText(it)) }
                    }
                    load()
                }
            },
            onToggle = { toggle(entity) },
        ).show()
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    private companion object {
        const val ROW_ITEM_DP = 220
        const val FADE_DP = 24

        /** Hauteur de Ligne à partir de laquelle les entités passent en tuiles hautes. */
        const val TALL_ROW_DP = 150

        /** Hauteur sous laquelle la Pastille ne montre plus l'état (rangée basse de l'Accueil). */
        const val PILL_STATE_DP = 104
        const val UNAVAILABLE_ALPHA = 0.6f
        const val PENDING_ALPHA = 0.7f
    }
}

/** Case d'une entité ; celle d'une lumière réglable garde l'appui long ([GestureOwner]) pour ouvrir son panneau. */
private class EntityBox(context: Context, override val ownsLongPress: Boolean) : LinearLayout(context), GestureOwner {
    override val ownsSwipe = false
}
