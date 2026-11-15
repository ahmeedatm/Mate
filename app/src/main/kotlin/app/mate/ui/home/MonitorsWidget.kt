package app.mate.ui.home

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.Monitor
import app.mate.net.MonitorControl
import app.mate.net.MonitorLevel
import app.mate.net.MonitorMode
import app.mate.net.MonitorPreset
import app.mate.net.runCatchingCancellable
import app.mate.ui.LatestSender
import app.mate.ui.errorText
import kotlinx.coroutines.launch

/** Ce que chaque famille de formes montre de l'écran choisi (fonction pure, `MonitorsLayoutTest`). */
internal enum class MonitorSection { PICKER, BRIGHTNESS, CONTRAST, FREQUENCY, COLOR, PRESET, RESOLUTION }

internal fun monitorSections(family: ShapeFamily): List<MonitorSection> = when (family) {
    ShapeFamily.PILL -> emptyList()
    ShapeFamily.ROW -> listOf(MonitorSection.PICKER, MonitorSection.BRIGHTNESS)
    ShapeFamily.COLUMN -> listOf(MonitorSection.PICKER, MonitorSection.BRIGHTNESS, MonitorSection.CONTRAST)
    ShapeFamily.BLOCK -> listOf(MonitorSection.PICKER, MonitorSection.BRIGHTNESS, MonitorSection.CONTRAST, MonitorSection.FREQUENCY)
    ShapeFamily.LARGE, ShapeFamily.FULL -> MonitorSection.entries
}

/** Pastille : un toucher monte la luminosité d'un quart, et repart du bas après 100 %. */
internal fun nextPillBrightness(percent: Int) = if (percent >= 100) 25 else (percent / 25 + 1) * 25

/** Préréglages de couleur de la norme MCCS (code VCP 0x14) : température en kelvins, sinon null. */
internal fun presetKelvin(value: Int): Int? = mapOf(3 to 4000, 4 to 5000, 5 to 6500, 6 to 7500, 7 to 8200, 8 to 9300, 9 to 10000, 10 to 11500)[value]

class MonitorsSample(val monitors: List<Monitor>) : WidgetSample

/**
 * Les écrans de l'ordinateur (Windows) : luminosité, contraste, netteté, gains rouge / vert / bleu et préréglage de couleur
 * par DDC/CI, fréquence et résolution par le système. Pastille : la luminosité de l'écran choisi, un toucher la monte ;
 * Ligne : luminosité ; Colonne : et contraste ; Bloc : et fréquences ; Grand et Plein écran : tout. Plusieurs écrans : une
 * pastille avec son nom passe au suivant. Lu à l'affichage puis chaque minute ; un curseur envoie pendant qu'on le tire.
 */
class MonitorsWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val title = cardTitle(R.string.widget_monitors)
    private val status = label(14f, R.attr.c_text_dim)
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    override val view = LinearLayout(context).asCard(16).apply {
        orientation = LinearLayout.VERTICAL
        addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
    }

    private var screens: List<Monitor>? = null
    private var selected: String? = null
    private var error: String? = null
    private var previewing = false
    private var active = false

    /** Un curseur tiré : les réponses mettent les données à jour sans redessiner sous le doigt. */
    private var dragging = false

    private val sender by lazy {
        LatestSender<suspend () -> List<Monitor>>(host.scope) { send ->
            runCatchingCancellable { send() }
                .onSuccess { screens = it; error = null }
                .onFailure { error = context.errorText(it) }
            if (!dragging) render()
        }
    }

    private val current get() = screens?.let { list -> list.firstOrNull { it.id == selected } ?: list.firstOrNull { it.primary } ?: list.firstOrNull() }

    override fun showPreview(sample: WidgetSample) {
        screens = (sample as? MonitorsSample)?.monitors ?: return
        previewing = true
        render()
    }

    override fun applySize() {
        title.visibility = if (family == ShapeFamily.PILL || family == ShapeFamily.ROW) View.GONE else View.VISIBLE
        render()
    }

    override fun setActive(active: Boolean) {
        this.active = active
        if (active && !previewing) host.scope.launch { load() }
    }

    override fun onMinute() {
        if (active && !previewing && !dragging) host.scope.launch { load() }
    }

    override suspend fun load() {
        if (previewing) return
        if (screens == null) showStatus(context.getString(R.string.widget_monitors_loading))
        runCatchingCancellable { BackendClient(context).monitors.list() }
            .onSuccess { screens = it; error = null }
            .onFailure { error = context.errorText(it) }
        if (!dragging) render()
    }

    private fun change(send: suspend BackendClient.() -> List<Monitor>) {
        if (previewing) return
        val client = BackendClient(context)
        sender.offer { client.send() }
    }

    private fun showStatus(text: String?) {
        status.text = text
        status.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun render() {
        body.removeAllViews()
        val list = screens
        val screen = current
        showStatus(
            error ?: when {
                list == null -> null
                screen == null -> context.getString(R.string.widget_monitors_none)
                !screen.ddc && family != ShapeFamily.PILL -> context.getString(R.string.widget_monitors_no_ddc)
                else -> null
            },
        )
        if (list == null || screen == null) return
        if (family == ShapeFamily.PILL) return renderPill(screen)
        val sections = monitorSections(family)
        val side = family == ShapeFamily.ROW
        val holder = if (side) LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }.also { body.addView(it, matchWrap()) } else body
        for (section in sections) {
            when (section) {
                MonitorSection.PICKER -> if (list.size > 1) holder.addView(picker(list, screen), if (side) wrap() else matchWrap())
                MonitorSection.BRIGHTNESS -> addLevel(holder, screen, MonitorControl.BRIGHTNESS, R.string.widget_monitors_brightness, side)
                MonitorSection.CONTRAST -> addLevel(holder, screen, MonitorControl.CONTRAST, R.string.widget_monitors_contrast, side)
                MonitorSection.COLOR -> {
                    addLevel(holder, screen, MonitorControl.SHARPNESS, R.string.widget_monitors_sharpness, side)
                    addLevel(holder, screen, MonitorControl.RED, R.string.widget_monitors_red, side)
                    addLevel(holder, screen, MonitorControl.GREEN, R.string.widget_monitors_green, side)
                    addLevel(holder, screen, MonitorControl.BLUE, R.string.widget_monitors_blue, side)
                }
                MonitorSection.PRESET -> screen.preset?.takeIf { it.options.isNotEmpty() }?.let { holder.addView(presets(screen, it), matchWrap()) }
                MonitorSection.FREQUENCY -> screen.frequencies().takeIf { it.size > 1 }?.let { holder.addView(frequencies(screen, it), matchWrap()) }
                MonitorSection.RESOLUTION -> screen.modes.takeIf { it.size > 1 }?.let { holder.addView(resolutions(screen, it), matchWrap()) }
            }
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }

    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(8) }

    private fun screenName(screen: Monitor) = screen.name.ifBlank { context.getString(R.string.widget_monitors_unnamed, (screens?.indexOf(screen) ?: 0) + 1) }

    private fun renderPill(screen: Monitor) {
        val level = screen.levels[MonitorControl.BRIGHTNESS]
        body.gravity = Gravity.CENTER
        body.addView(label(30f).apply {
            gravity = Gravity.CENTER
            text = level?.let { context.getString(R.string.percent, it.percent) } ?: context.getString(R.string.widget_monitors_hz, screen.hz)
        })
        body.addView(label(13f, R.attr.c_text_dim).apply {
            gravity = Gravity.CENTER
            text = screenName(screen)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (level == null) return body.setOnClickListener(null)
        body.contentDescription = context.getString(R.string.widget_monitors_pill, screenName(screen), level.percent)
        body.setOnClickListener {
            val next = nextPillBrightness(level.percent)
            change { monitors.setLevel(screen.id, MonitorControl.BRIGHTNESS, level.toValue(next)) }
        }
    }

    /** Pastille au nom de l'écran choisi : un toucher passe au suivant. */
    private fun picker(list: List<Monitor>, screen: Monitor) = Button(context, null, 0, R.style.Chip).apply {
        text = screenName(screen)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        val next = list[(list.indexOf(screen) + 1) % list.size]
        contentDescription = context.getString(R.string.widget_monitors_next, screenName(next))
        setOnClickListener {
            selected = next.id
            render()
        }
    }

    private fun addLevel(parent: LinearLayout, screen: Monitor, control: MonitorControl, name: Int, side: Boolean) {
        val level = screen.levels[control] ?: return
        val value = label(14f, R.attr.c_text_dim).apply { text = context.getString(R.string.percent, level.percent) }
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            if (!side) addView(label(14f).apply { setText(name); maxLines = 1 }, LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(slider(screen, control, level, value, name), LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(value, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        parent.addView(row, if (side) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) else matchWrap())
    }

    private fun slider(screen: Monitor, control: MonitorControl, level: MonitorLevel, value: TextView, name: Int) = SeekBar(context, null, 0, R.style.Slider).apply {
        max = 100
        progress = level.percent
        contentDescription = context.getString(name)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                value.text = context.getString(R.string.percent, progress)
                change { monitors.setLevel(screen.id, control, level.toValue(progress)) }
            }

            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }

            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                change { monitors.setLevel(screen.id, control, level.toValue(bar.progress)) }
            }
        })
    }

    /** Rangée de pastilles, celle de l'état actuel sélectionnée ; quatre au plus pour tenir sur la largeur. */
    private fun <T> chips(items: List<T>, isCurrent: (T) -> Boolean, text: (T) -> String, onPick: (T) -> Unit) = LinearLayout(context).apply {
        for (item in items.take(4)) {
            addView(Button(context, null, 0, R.style.Chip).apply {
                this.text = text(item)
                maxLines = 1
                isSelected = isCurrent(item)
                setOnClickListener { if (!isCurrent(item)) onPick(item) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        }
    }

    private fun frequencies(screen: Monitor, hz: List<Int>) = chips(hz, { it == screen.hz }, { context.getString(R.string.widget_monitors_hz, it) }) {
        change { monitors.setMode(screen.id, screen.width, screen.height, it) }
    }

    private fun resolutions(screen: Monitor, modes: List<MonitorMode>) = chips(
        modes,
        { it.width == screen.width && it.height == screen.height },
        { context.getString(R.string.widget_monitors_resolution, it.width, it.height) },
    ) { mode ->
        // Même fréquence si la nouvelle résolution la permet, sinon la plus haute.
        val hz = if (screen.hz in mode.hz) screen.hz else mode.hz.first()
        change { monitors.setMode(screen.id, mode.width, mode.height, hz) }
    }

    private fun presets(screen: Monitor, preset: MonitorPreset) = chips(preset.options, { it == preset.value }, ::presetName) {
        change { monitors.setPreset(screen.id, it) }
    }

    private fun presetName(value: Int): String = presetKelvin(value)?.let { context.getString(R.string.widget_monitors_kelvin, it) }
        ?: when (value) {
            1 -> context.getString(R.string.widget_monitors_srgb)
            2 -> context.getString(R.string.widget_monitors_native)
            in 11..13 -> context.getString(R.string.widget_monitors_user, value - 10)
            else -> value.toString()
        }

    companion object {
        val SAMPLE = MonitorsSample(
            listOf(
                Monitor(
                    "\\\\.\\DISPLAY1", "MSI G24C4", true, 1920, 1080, 144,
                    listOf(MonitorMode(1920, 1080, listOf(144, 120, 60)), MonitorMode(1600, 900, listOf(144, 60)), MonitorMode(1280, 720, listOf(60))),
                    true,
                    mapOf(
                        MonitorControl.BRIGHTNESS to MonitorLevel(80, 100), MonitorControl.CONTRAST to MonitorLevel(70, 100),
                        MonitorControl.RED to MonitorLevel(50, 100), MonitorControl.GREEN to MonitorLevel(50, 100), MonitorControl.BLUE to MonitorLevel(50, 100),
                    ),
                    MonitorPreset(5, listOf(5, 6, 8, 11)),
                ),
                Monitor("\\\\.\\DISPLAY2", "Dell U2720Q", false, 2560, 1440, 60, emptyList(), true, mapOf(MonitorControl.BRIGHTNESS to MonitorLevel(60, 100)), null),
            ),
        )
    }
}
