package app.mate.ui.mixer

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.os.SystemClock
import android.text.InputType
import android.util.AttributeSet
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.ImageViewCompat
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.HttpException
import app.mate.net.MixerChannel
import app.mate.net.MixerScene
import app.mate.net.MixerState
import app.mate.ui.IconCache
import app.mate.ui.LatestSender
import app.mate.ui.dp
import app.mate.ui.errorText
import app.mate.ui.inputField
import app.mate.ui.showForTyping
import app.mate.ui.showImmersive
import app.mate.ui.themeColor
import app.mate.ui.themeFont
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Onglet « Son » du deck : console de mixage de l'ordinateur, sur fond uni. En haut, les scènes (volumes
 * enregistrés, rappelés en un toucher) ; dessous, une tranche par source : sortie, micro, puis chaque appli qui a du
 * son (Windows ; le Mac n'a pas de volume par appli). Relue 4 fois par seconde tant qu'elle est à l'écran, pour les
 * vumètres.
 */
class MixerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val sceneScroll: HorizontalScrollView
    private val sceneRow: LinearLayout
    private val strips: LinearLayout
    private val note: TextView
    private val status: TextView

    private lateinit var scope: CoroutineScope
    private var active = false
    private var poll: Job? = null
    private var stopped = false
    private val views = LinkedHashMap<String, FaderStrip>()
    private val senders = HashMap<String, LatestSender<Int>>()
    /** Tranche touchée récemment : l'état relu ne la fait pas revenir en arrière le temps que l'ordinateur suive. */
    private val heldUntil = HashMap<String, Long>()
    private val iconsAsked = HashSet<String>()
    private var scenes: List<MixerScene> = emptyList()
    private var litScene: String? = null
    private var state: MixerState? = null

    /** Mode édition des scènes ouvert par le crayon. */
    private var editing = false

    /** Console basse (widget d'un profil) : rangée des scènes et tranches resserrées. */
    private var tight = false

    // Plaque unie (c_mx_plate), dessinée derrière tout.
    private val plate = Paint().apply { color = themeColor(context, R.attr.c_mx_plate) }
    private val brush = Paint()
    private val cShadow = themeColor(context, R.attr.c_mx_shadow)
    private val cHighlight = themeColor(context, R.attr.c_mx_highlight)

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(32).toFloat())
        }

        // Rangée des scènes.
        sceneScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        sceneRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(10), dp(24), dp(6))
        }
        sceneScroll.addView(sceneRow)
        addView(sceneScroll, LayoutParams(LayoutParams.MATCH_PARENT, sceneHeight()))

        // Tranches.
        val stripScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
        }
        strips = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(10))
        }
        stripScroll.addView(strips, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(stripScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        note = engraved(R.string.mixer_no_apps).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            visibility = GONE
        }
        status = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(themeColor(context, R.attr.c_mx_engrave))
            textSize = 15f
            setPadding(dp(16), dp(4), dp(16), dp(10))
            visibility = GONE
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        renderScenes()
    }

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    /** Onglet affiché et deck à l'écran : on relit la console ; sinon rien ne tourne. */
    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        poll?.cancel()
        if (!active) return
        stopped = false
        poll = scope.launch {
            loadScenes()
            while (isActive && !stopped) {
                val ok = refresh { mixer() }
                delay(if (ok) POLL_MS else ERROR_POLL_MS)
            }
        }
    }

    /** Appelle l'ordinateur et affiche l'état renvoyé ; faux si l'ordinateur n'a pas répondu. */
    private suspend fun refresh(call: suspend app.mate.net.SystemApi.() -> MixerState): Boolean = try {
        render(BackendClient(context).system.call())
        if (status.tag == ERROR) hideStatus()
        true
    } catch (e: HttpException) {
        if (e.code == 501) {
            // L'ordinateur ne pilote pas le son (ni Mac ni Windows) : rien à relire.
            stopped = true
            showStatus(context.getString(R.string.mixer_unsupported))
        } else showStatus(context.errorText(e), ERROR)
        false
    } catch (e: Exception) {
        showStatus(context.errorText(e), ERROR)
        false
    }

    private fun render(next: MixerState) {
        state = next
        val channels = listOf(next.master, next.mic) + next.apps.orEmpty()
        val ids = channels.map { it.id }
        if (ids != views.keys.toList()) {
            val reuse = HashMap(views)
            views.clear()
            strips.removeAllViews()
            channels.forEach { c -> views[c.id] = reuse[c.id] ?: newStrip(c) }
            views.values.forEach { strips.addView(it, LinearLayout.LayoutParams(dp(MIN_STRIP_DP), ViewGroup.LayoutParams.MATCH_PARENT)) }
            // La note du Mac (« Volume par appli… ») n'est pas une voie : pas de trait avant elle sur une table nue.
            views.values.forEachIndexed { i, strip -> strip.last = i == views.size - 1 }
            if (next.apps == null) strips.addView(note, LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.MATCH_PARENT))
            fitStrips()
        }
        note.visibility = if (next.apps == null) VISIBLE else GONE
        val now = SystemClock.uptimeMillis()
        channels.forEach { c ->
            val strip = views[c.id] ?: return@forEach
            strip.title = titleOf(c)
            if (now >= (heldUntil[c.id] ?: 0L)) {
                strip.volume = c.volume
                strip.muted = c.muted
            }
            strip.setLevel(c.level)
        }
    }

    /**
     * Largeur des tranches : peu de sources, elles se partagent la console (plus lisibles de loin) ; beaucoup, elles
     * gardent une largeur minimale et la console défile. Un écart sépare sortie et micro des applis.
     */
    private fun fitStrips() {
        val room = (strips.parent as View).width - strips.paddingLeft - strips.paddingRight - dp(GROUP_GAP_DP)
        if (room <= 0 || views.isEmpty()) return
        val reserved = if (note.parent != null) dp(320) else 0
        val width = ((room - reserved) / views.size).coerceIn(dp(MIN_STRIP_DP), dp(MAX_STRIP_DP))
        views.values.forEachIndexed { i, strip ->
            val params = strip.layoutParams as LinearLayout.LayoutParams
            val gap = if (i == 1) dp(GROUP_GAP_DP) else 0
            if (params.width != width || params.marginEnd != gap) {
                params.width = width
                params.marginEnd = gap
                strip.layoutParams = params
            }
        }
    }

    private fun titleOf(c: MixerChannel) = when (c.id) {
        "master" -> context.getString(R.string.mixer_master)
        "mic" -> context.getString(R.string.mixer_mic)
        "system" -> context.getString(R.string.mixer_system)
        else -> c.name
    }

    private fun newStrip(c: MixerChannel) = FaderStrip(context).apply {
        master = c.id == "master"
        iconRes = when (c.id) {
            "master" -> R.drawable.ic_volume
            "mic" -> R.drawable.ic_mic
            "system" -> R.drawable.ic_laptop
            else -> 0
        }
        if (iconRes == 0) {
            IconCache.peekApp(c.name)?.let { icon = it }
            if (icon == null && iconsAsked.add(c.name)) {
                scope.launch { runCatching { IconCache.loadApp(BackendClient(context), c.name) }.getOrNull()?.let { icon = it } }
            }
        }
        onVolume = { volume, final ->
            hold(c.id)
            litScene = null
            renderScenes()
            senderFor(c.id).offer(volume)
            if (final) hold(c.id)
        }
        onMute = {
            val mute = !muted
            muted = mute
            hold(c.id)
            scope.launch { refresh { setMixer(c.id, muted = mute) } }
        }
    }

    private fun hold(id: String) {
        heldUntil[id] = SystemClock.uptimeMillis() + HOLD_MS
    }

    private fun senderFor(id: String) = senders.getOrPut(id) {
        LatestSender(scope) { volume ->
            hold(id)
            refresh { setMixer(id, volume = volume) }
        }
    }

    // --- Scènes

    private suspend fun loadScenes() {
        scenes = runCatching { BackendClient(context).system.mixerScenes() }.getOrDefault(scenes)
        renderScenes()
    }

    private fun renderScenes() {
        sceneRow.removeAllViews()
        sceneRow.setPadding(dp(24), dp(if (tight) 6 else 10), dp(24), dp(6))
        sceneRow.addView(engraved(R.string.mixer_scenes).apply {
            setTextColor(themeColor(context, R.attr.c_mx_text))
            setPadding(0, 0, dp(16), dp(3))
        })
        if (editing && scenes.isEmpty()) editing = false
        if (editing) return renderEditing()
        scenes.forEach { scene ->
            sceneRow.addView(ConsoleKey(context).apply {
                text = scene.name
                lit = scene.id == litScene
                setOnClickListener { apply(scene) }
                setOnLongClickListener { confirmDelete(scene); true }
            }, keyParams())
        }
        sceneRow.addView(ConsoleKey(context).apply {
            setText(R.string.mixer_scene_save)
            led = false
            setPadding(dp(18), 0, dp(18), dp(3))
            setOnClickListener { askSceneName() }
        }, keyParams())
        if (scenes.isNotEmpty()) {
            sceneRow.addView(iconButton(R.drawable.ic_edit, R.attr.c_text_dim, context.getString(R.string.mixer_scenes_edit)) {
                editing = true
                renderScenes()
            })
        }
        if (scenes.isEmpty()) sceneRow.addView(engraved(R.string.mixer_scenes_hint).apply { setPadding(dp(6), 0, 0, dp(3)) })
    }

    // --- Mode édition des scènes (crayon) : glisser pour déplacer, ⟳ mettre à jour, ✕ supprimer, « Terminé ».

    private fun renderEditing() {
        scenes.forEach { sceneRow.addView(editChip(it), keyParams()) }
        sceneRow.addView(Button(context, null, 0, R.style.PrimaryButton).apply {
            setText(R.string.mixer_scenes_done)
            setOnClickListener {
                editing = false
                renderScenes()
            }
        }, keyParams())
        sceneRow.addView(engraved(R.string.mixer_scenes_edit_hint).apply { setPadding(dp(6), 0, 0, dp(3)) })
    }

    /** Pilule d'une scène en édition : poignée (on la tient pour glisser), nom, ⟳ et ✕. `tag` = id, lu à la fin du glisser. */
    @SuppressLint("ClickableViewAccessibility")
    private fun editChip(scene: MixerScene): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.chip_bg)
        setPadding(dp(4), 0, dp(2), 0)
        tag = scene.id
        val chip = this
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_drag)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(themeColor(context, R.attr.c_text_faint)))
            contentDescription = context.getString(R.string.mixer_scene_drag, scene.name)
            setPadding(dp(8), dp(12), dp(4), dp(12))
            setOnTouchListener { _, e ->
                if (e.actionMasked != MotionEvent.ACTION_DOWN) return@setOnTouchListener false
                chip.startDragAndDrop(null, View.DragShadowBuilder(chip), chip, 0)
                true
            }
        }, LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(TextView(context).apply {
            text = scene.name
            typeface = themeFont(context, R.attr.c_font_medium)
            textSize = 16f
            setTextColor(themeColor(context, R.attr.c_mx_text))
            setPadding(dp(2), 0, dp(4), 0)
        })
        addView(iconButton(R.drawable.ic_refresh, R.attr.c_accent, context.getString(R.string.mixer_scene_update, scene.name)) { confirmUpdate(scene) })
        addView(iconButton(R.drawable.ic_close, R.attr.c_ko, context.getString(R.string.mixer_scene_remove, scene.name)) { confirmDelete(scene) })
        setOnDragListener(sceneDrag)
    }

    /** Glisser une pilule sur une autre la met à sa place ; à la fin, l'ordre lu sur l'écran part à l'ordinateur. */
    private val sceneDrag = View.OnDragListener { target, event ->
        val dragged = event.localState as? View ?: return@OnDragListener false
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> if (target === dragged) dragged.alpha = DRAGGED_ALPHA
            DragEvent.ACTION_DRAG_ENTERED -> if (target !== dragged) {
                val to = sceneRow.indexOfChild(target)
                sceneRow.removeView(dragged)
                sceneRow.addView(dragged, to)
            }
            DragEvent.ACTION_DRAG_ENDED -> if (target === dragged) {
                dragged.alpha = 1f
                saveOrder()
            }
        }
        true
    }

    private fun saveOrder() {
        val ids = (0 until sceneRow.childCount).mapNotNull { sceneRow.getChildAt(it).tag as? String }
        if (ids == scenes.map { it.id }) return
        scope.launch {
            try {
                scenes = BackendClient(context).system.orderMixerScenes(ids)
                renderScenes()
            } catch (e: Exception) {
                showStatus(context.errorText(e), ERROR)
                loadScenes()
            }
        }
    }

    private fun confirmUpdate(scene: MixerScene) {
        AlertDialog.Builder(context)
            .setMessage(context.getString(R.string.mixer_scene_update_confirm, scene.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.mixer_scene_update_ok) { _, _ ->
                scope.launch {
                    // Même nom : le compagnon remplace la scène à sa place (mixer.ts, saveScene).
                    runCatching { BackendClient(context).system.saveMixerScene(scene.name) }
                        .onFailure { showStatus(context.errorText(it), ERROR) }
                    loadScenes()
                }
            }
            .showImmersive()
    }

    private fun iconButton(icon: Int, tint: Int, label: String, onClick: () -> Unit) = ImageButton(context, null, 0, R.style.IconButton_Flat).apply {
        setImageResource(icon)
        // `tint` du style n'est lu que par les vues AppCompat gonflées depuis le XML.
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(themeColor(context, tint)))
        contentDescription = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    private fun keyParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(if (tight) 40 else 48)).apply { marginEnd = dp(10) }

    private fun sceneHeight() = dp(if (tight) SCENES_TIGHT_DP else SCENES_DP)

    private fun apply(scene: MixerScene) {
        litScene = scene.id
        renderScenes()
        scope.launch {
            try {
                val (mixer, missing) = BackendClient(context).system.applyMixerScene(scene.id)
                heldUntil.clear()
                render(mixer)
                if (missing.isEmpty()) hideStatus()
                else showStatus(context.getString(R.string.mixer_scene_missing, scene.name, missing.joinToString(", ") { it.replaceFirstChar(Char::titlecase) }))
            } catch (e: Exception) {
                litScene = null
                renderScenes()
                showStatus(context.errorText(e), ERROR)
            }
        }
    }

    private fun askSceneName() {
        val form = LayoutInflater.from(context).inflate(R.layout.view_dialog_form, null)
        form.findViewById<TextView>(R.id.dialog_message).setText(R.string.mixer_scene_save_hint)
        val input: EditText = context.inputField(form.findViewById(R.id.dialog_body), R.string.mixer_scene_name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.mixer_scene_save_title)
            .setView(form)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        val save = {
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) {
                dialog.dismiss()
                scope.launch {
                    try {
                        val scene = BackendClient(context).system.saveMixerScene(name.take(30))
                        litScene = scene.id
                        loadScenes()
                    } catch (e: Exception) {
                        showStatus(context.errorText(e), ERROR)
                    }
                }
            }
        }
        input.setOnEditorActionListener { _, _, _ -> save(); true }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save() } }
        dialog.showForTyping(input)
    }

    private fun confirmDelete(scene: MixerScene) {
        AlertDialog.Builder(context)
            .setMessage(context.getString(R.string.mixer_scene_delete, scene.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                scope.launch {
                    runCatching { BackendClient(context).system.deleteMixerScene(scene.id) }
                        .onFailure { showStatus(context.errorText(it), ERROR) }
                    if (litScene == scene.id) litScene = null
                    loadScenes()
                }
            }
            .showImmersive()
    }

    // --- Habillage

    private fun engraved(text: Int) = TextView(context).apply {
        setText(text)
        typeface = themeFont(context, R.attr.c_font_medium)
        textSize = 15f
        setTextColor(themeColor(context, R.attr.c_mx_engrave))
    }

    private fun showStatus(message: String, tag: Any? = null) {
        status.text = message
        status.tag = tag
        status.visibility = VISIBLE
    }

    private fun hideStatus() {
        status.visibility = GONE
        status.tag = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val low = h < dp(FaderStrip.TIGHT_DP)
        if (low != tight) {
            tight = low
            sceneScroll.layoutParams = sceneScroll.layoutParams.apply { height = sceneHeight() }
            strips.setPadding(dp(16), 0, dp(16), if (tight) 0 else dp(10))
            renderScenes()
        }
        post { fitStrips() }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, plate)
        // Rainure sous les scènes.
        brush.alpha = 255
        brush.color = cShadow
        val groove = sceneHeight().toFloat()
        canvas.drawRect(dp(24).toFloat(), groove, w - dp(24), groove + 1, brush)
        brush.color = cHighlight
        canvas.drawRect(dp(24).toFloat(), groove + 1, w - dp(24), groove + 2, brush)
    }

    private companion object {
        const val SCENES_DP = 66
        const val DRAGGED_ALPHA = 0.35f
        const val SCENES_TIGHT_DP = 52
        const val MIN_STRIP_DP = 140
        const val MAX_STRIP_DP = 280
        const val GROUP_GAP_DP = 14
        const val POLL_MS = 250L
        const val ERROR_POLL_MS = 2_000L
        const val HOLD_MS = 1_200L
        const val ERROR = "error"
    }
}
