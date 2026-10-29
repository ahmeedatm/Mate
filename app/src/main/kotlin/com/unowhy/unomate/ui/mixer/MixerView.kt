package com.unowhy.unomate.ui.mixer

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.unowhy.unomate.R
import com.unowhy.unomate.net.BackendClient
import com.unowhy.unomate.net.HttpException
import com.unowhy.unomate.net.MixerChannel
import com.unowhy.unomate.net.MixerScene
import com.unowhy.unomate.net.MixerState
import com.unowhy.unomate.ui.IconCache
import com.unowhy.unomate.ui.LatestSender
import com.unowhy.unomate.ui.dp
import com.unowhy.unomate.ui.errorText
import com.unowhy.unomate.ui.inputField
import com.unowhy.unomate.ui.showForTyping
import com.unowhy.unomate.ui.showImmersive
import com.unowhy.unomate.ui.themeColor
import com.unowhy.unomate.ui.themeFont
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Onglet « Son » du deck : console de mixage de l'ordinateur, en métal brossé. En haut, les scènes (volumes
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

    /** Console basse (widget d'un profil) : rangée des scènes et tranches resserrées. */
    private var tight = false

    // Plaque de métal brossé, dessinée derrière tout.
    private val plate = Paint()
    private val brush = Paint()
    private val brushLines = Random(7).let { r -> FloatArray(400) { r.nextFloat() } }
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
    private suspend fun refresh(call: suspend com.unowhy.unomate.net.SystemApi.() -> MixerState): Boolean = try {
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
        if (scenes.isEmpty()) sceneRow.addView(engraved(R.string.mixer_scenes_hint).apply { setPadding(dp(6), 0, 0, dp(3)) })
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
        plate.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), themeColor(context, R.attr.c_mx_plate_top), themeColor(context, R.attr.c_mx_plate_bottom), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, plate)
        // Brossage discret : fines lignes horizontales à peine plus claires ou plus sombres.
        var i = 0
        var y = 0f
        while (y < h) {
            val v = brushLines[i++ % brushLines.size]
            brush.color = if (v > 0.5f) cHighlight else cShadow
            brush.alpha = ((if (v > 0.5f) v - 0.5f else 0.5f - v) * 40).toInt()
            canvas.drawRect(0f, y, w, y + 1, brush)
            y += 2f
        }
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
