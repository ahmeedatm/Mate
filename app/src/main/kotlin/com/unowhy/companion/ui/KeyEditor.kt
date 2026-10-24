package com.unowhy.companion.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.unowhy.companion.Computers
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.DeckCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Éditeur d'une touche perso du deck, par-dessus le deck : libellé, symbole, couleur et étapes
 * (une étape = raccourci simple, plusieurs = macro). Types d'étapes : voir backend/src/perso.ts.
 */
class KeyEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val title: TextView
    private val label: EditText
    private val glyph: EditText
    private val colors: LinearLayout
    private val status: TextView
    private val stepList: LinearLayout
    private val types: LinearLayout
    private val form: LinearLayout
    private val clearButton: Button

    private lateinit var scope: CoroutineScope
    private var onChanged: () -> Unit = {}
    private var slot = -1
    private val steps = mutableListOf<JSONObject>()
    private var color: String? = null
    private var catalog: DeckCatalog? = null
    /** Ordinateur sous Windows : modificateurs Ctrl/Alt/Win, pas de raccourcis Apple. */
    private val onWindows = Computers.active(context)?.os == "windows"
    private var type = "app"

    init {
        setBackgroundResource(R.drawable.scrim)
        isClickable = true
        // Reprend le focus d'un champ validé : sinon il partirait sur la barre latérale.
        isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        visibility = GONE
        LayoutInflater.from(context).inflate(R.layout.view_key_editor, this, true)
        title = findViewById(R.id.editor_title)
        label = findViewById(R.id.editor_label)
        glyph = findViewById(R.id.editor_glyph)
        colors = findViewById(R.id.editor_colors)
        status = findViewById(R.id.editor_status)
        stepList = findViewById(R.id.editor_steps)
        types = findViewById(R.id.editor_types)
        form = findViewById(R.id.editor_form)

        findViewById<ImageButton>(R.id.editor_close).setOnClickListener { hide() }
        findViewById<Button>(R.id.editor_save).setOnClickListener { save() }
        clearButton = findViewById(R.id.editor_clear)
        clearButton.setOnClickListener { clear() }
        closeOnDone(label)
        closeOnDone(glyph)

        COLORS.forEach { hex ->
            colors.addView(View(context).apply {
                // Pastille pleine de la couleur, ou vide (couleur par défaut) avec seulement le contour.
                if (hex == null) setBackgroundResource(R.drawable.swatch) else background = swatchDrawable(Color.parseColor(hex))
                tag = hex
                setOnClickListener { color = hex; renderColors() }
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(6) }
            })
        }
        TYPES.filterNot { onWindows && it.first == "shortcut" }.forEach { (key, text) ->
            types.addView(chip(context.getString(text), selected = false) { selectType(key) }.apply { tag = key })
        }
    }

    fun attach(scope: CoroutineScope, onChanged: () -> Unit) {
        this.scope = scope
        this.onChanged = onChanged
    }

    val isShowing get() = visibility == VISIBLE

    /** Ouvre la touche `slot` (0 à 13) : vide, ou avec ce qui est enregistré sur le Mac. */
    fun show(slot: Int) {
        this.slot = slot
        title.text = context.getString(R.string.key_title, slot + 1)
        label.setText("")
        glyph.setText("")
        color = null
        steps.clear()
        status.visibility = GONE
        clearButton.visibility = GONE
        renderColors()
        renderSteps()
        selectType("app")
        visibility = VISIBLE
        scope.launch {
            try {
                val client = BackendClient(context)
                if (catalog == null) {
                    catalog = client.deckCatalog()
                    if (this@KeyEditor.slot == slot) selectType(type)
                }
                val key = client.persoKey(slot) ?: return@launch
                if (this@KeyEditor.slot != slot) return@launch
                clearButton.visibility = VISIBLE
                label.setText(key.optString("label"))
                glyph.setText(key.optString("glyph"))
                color = key.optString("color").ifEmpty { null }
                val saved = key.getJSONArray("steps")
                for (i in 0 until saved.length()) steps += saved.getJSONObject(i)
                renderColors()
                renderSteps()
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    fun hide() {
        // Un champ masqué garderait le clavier ouvert (pas de bouton Retour sur la tablette).
        dismissKeyboard()
        visibility = GONE
        slot = -1
    }

    private fun dismissKeyboard() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
        requestFocus()
    }

    private fun save() {
        if (steps.isEmpty()) return showError(context.getString(R.string.key_no_steps))
        val name = label.text.toString().trim().ifEmpty { summary(steps.first()) }.take(40)
        val key = JSONObject().put("label", name).put("steps", JSONArray(steps))
        glyph.text.toString().trim().takeIf { it.isNotEmpty() }?.let { key.put("glyph", it) }
        color?.let { key.put("color", it) }
        val target = slot
        scope.launch {
            try {
                BackendClient(context).savePersoKey(target, key)
                IconCache.forget("k-$target")
                hide()
                onChanged()
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    private fun clear() {
        val target = slot
        scope.launch {
            try {
                BackendClient(context).clearPersoKey(target)
                IconCache.forget("k-$target")
                hide()
                onChanged()
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    private fun showError(e: Exception) = showError(context.errorText(e))

    private fun showError(message: String) {
        status.text = message
        status.visibility = VISIBLE
    }

    private fun addStep(step: JSONObject) {
        steps += step
        status.visibility = GONE
        renderSteps()
    }

    private fun renderColors() {
        for (i in 0 until colors.childCount) colors.getChildAt(i).let { it.isSelected = it.tag == color }
    }

    private fun renderSteps() {
        stepList.removeAllViews()
        if (steps.isEmpty()) {
            stepList.addView(TextView(context).apply {
                setText(R.string.key_steps_empty)
                setTextColor(themeColor(context, R.attr.c_text_dim))
                textSize = 15f
            })
            return
        }
        steps.forEachIndexed { i, step ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.row_bg)
                setPadding(dp(16), dp(4), dp(4), dp(4))
                minimumHeight = dp(56)
            }
            row.addView(TextView(context).apply {
                text = context.getString(R.string.key_step_line, i + 1, summary(step))
                textSize = 16f
                maxLines = 2
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (i > 0) row.addView(iconButton(R.drawable.ic_chevron_left, R.string.key_step_up, rotation = 90f) {
                steps.add(i - 1, steps.removeAt(i)); renderSteps()
            })
            row.addView(iconButton(R.drawable.ic_delete, R.string.key_step_delete) { steps.removeAt(i); renderSteps() })
            stepList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
    }

    // --- Formulaires d'ajout, un par type d'étape

    private fun selectType(key: String) {
        type = key
        for (i in 0 until types.childCount) types.getChildAt(i).let { it.isSelected = it.tag == key }
        form.removeAllViews()
        when (key) {
            "app" -> appPicker { addStep(JSONObject().put("type", "app").put("name", it)) }
            "keys" -> keysForm()
            "text" -> textForm(R.string.step_text_hint, multiline = true) { addStep(JSONObject().put("type", "text").put("text", it)) }
            "url" -> textForm(R.string.step_url_hint, multiline = false) { addStep(JSONObject().put("type", "url").put("url", it)) }
            "window" -> windowForm()
            "shortcut" -> shortcutList()
            "media" -> chipRow(MEDIA.map { (cmd, text) -> context.getString(text) to { addStep(JSONObject().put("type", "media").put("command", cmd)) } })
            "system" -> chipRow(SYSTEM.map { (cmd, text) -> context.getString(text) to { addStep(JSONObject().put("type", "system").put("command", cmd)) } })
            "wait" -> chipRow(WAITS.map { ms -> seconds(ms) to { addStep(JSONObject().put("type", "wait").put("ms", ms)) } })
        }
    }

    /**
     * Applis du Mac filtrées par un champ de recherche ; `onPick` reçoit le nom choisi.
     * `highlight` : l'appli choisie reste en couleur (choix d'une option, pas ajout immédiat).
     */
    private fun appPicker(highlight: Boolean = false, onPick: (String) -> Unit) {
        val apps = catalog?.apps
        if (apps == null) return note(R.string.loading)
        val filter = input(R.string.step_app_filter, multiline = false)
        val results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var selected: String? = null
        fun render() {
            results.removeAllViews()
            val query = filter.text.toString().trim()
            apps.filter { it.contains(query, ignoreCase = true) }.take(40).forEach { app ->
                results.addView(row(app, app == selected) {
                    onPick(app)
                    if (highlight) { selected = app; render() }
                })
            }
        }
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render()
        })
        form.addView(filter)
        form.addView(results, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        render()
    }

    private fun keysForm() {
        val mods = if (onWindows) linkedMapOf("ctrl" to "Ctrl", "shift" to "⇧ Maj", "alt" to "Alt", "win" to "⊞ Win")
        else linkedMapOf("cmd" to "⌘ Cmd", "shift" to "⇧ Maj", "alt" to "⌥ Option", "ctrl" to "⌃ Ctrl")
        val chosen = mutableSetOf<String>()
        chipRow(mods.map { (mod, text) -> text to { if (!chosen.add(mod)) chosen.remove(mod) } }, toggle = true)
        lateinit var key: EditText
        val add = {
            val k = key.text.toString().trim().lowercase()
            if (k.isNotEmpty()) addStep(JSONObject().put("type", "keys").put("keys", (mods.keys.filter { it in chosen } + k).joinToString("+")))
        }
        key = input(R.string.step_key_hint, multiline = false, onDone = add)
        form.addView(key, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        chipRow(SPECIAL_KEYS.map { (name, text) -> text to { key.setText(name) } })
        addButton(add)
    }

    private fun textForm(hint: Int, multiline: Boolean, onAdd: (String) -> Unit) {
        lateinit var field: EditText
        val add = { field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onAdd); Unit }
        field = input(hint, multiline, onDone = add)
        form.addView(field)
        addButton(add)
    }

    private fun windowForm() {
        var app: String? = null
        var display: String? = null
        var layout = "max"
        note(R.string.step_window_display)
        val displays = listOf<String?>(null) + catalog?.displays.orEmpty()
        chipRow(displays.map { d -> (d ?: context.getString(R.string.step_window_current)) to { display = d } }, single = true, initial = 0)
        note(R.string.step_window_layout)
        chipRow(LAYOUTS.map { (l, text) -> context.getString(text) to { layout = l } }, single = true, initial = LAYOUTS.indexOfFirst { it.first == layout })
        addButton {
            val name = app ?: return@addButton showError(context.getString(R.string.step_window_no_app))
            val step = JSONObject().put("type", "window").put("app", name).put("layout", layout)
            display?.let { step.put("display", it) }
            addStep(step)
        }
        note(R.string.step_window_app)
        appPicker(highlight = true) { app = it }
    }

    private fun shortcutList() {
        val shortcuts = catalog?.shortcuts
        when {
            shortcuts == null -> note(R.string.loading)
            shortcuts.isEmpty() -> note(R.string.step_shortcut_none)
            else -> shortcuts.forEach { name -> form.addView(row(name, false) { addStep(JSONObject().put("type", "shortcut").put("name", name)) }) }
        }
    }

    // --- Petits éléments d'interface

    private fun note(text: Int) {
        form.addView(TitleView(context).apply { setText(text) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
    }

    /** Rangée de pastilles. `toggle` : chacune s'allume/s'éteint ; `single` : une seule allumée. */
    private fun chipRow(items: List<Pair<String, () -> Unit>>, toggle: Boolean = false, single: Boolean = false, initial: Int = -1) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { i, (text, action) ->
            row.addView(chip(text, selected = i == initial) {
                if (toggle) it.isSelected = !it.isSelected
                if (single) for (j in 0 until row.childCount) row.getChildAt(j).isSelected = row.getChildAt(j) == it
                action()
            })
        }
        form.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    }

    private fun chip(text: String, selected: Boolean, onClick: (View) -> Unit) =
        Button(context, null, 0, R.style.Chip).apply {
            this.text = text
            isSelected = selected
            setOnClickListener(onClick)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(8) }
        }

    private fun row(text: String, selected: Boolean, onClick: () -> Unit) =
        TextView(context).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.row_bg)
            setPadding(dp(16), 0, dp(16), 0)
            if (selected) setTextColor(themeColor(context, R.attr.c_accent))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(6) }
        }

    /** `onDone` : touche ✓ du clavier (champ d'une ligne), qui ferme aussi le clavier. */
    private fun input(hint: Int, multiline: Boolean, onDone: (() -> Unit)? = null) =
        (LayoutInflater.from(context).inflate(R.layout.item_input, form, false) as EditText).apply {
            setHint(hint)
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
                setPadding(dp(20), dp(14), dp(20), dp(14))
            } else {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                isSingleLine = true
                closeOnDone(this, onDone)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    /** ✓ du clavier ferme le clavier, puis `onDone`. Sans ACTION_DONE, Entrée passe au bouton suivant et le clavier reste ouvert. */
    private fun closeOnDone(field: EditText, onDone: (() -> Unit)? = null) {
        field.imeOptions = EditorInfo.IME_ACTION_DONE
        field.setOnEditorActionListener { _, action, event ->
            // ✓ du clavier à l'écran : IME_ACTION_DONE ; Entrée d'un clavier physique : IME_NULL, à l'appui puis au relâchement.
            if (action != EditorInfo.IME_ACTION_DONE && action != EditorInfo.IME_NULL) return@setOnEditorActionListener false
            if (event != null && event.action != KeyEvent.ACTION_DOWN) return@setOnEditorActionListener true
            dismissKeyboard()
            onDone?.invoke()
            true
        }
    }

    private fun addButton(onClick: () -> Unit) {
        form.addView(Button(context, null, 0, R.style.ActionButton).apply {
            setText(R.string.key_add)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_add, 0, 0, 0)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { topMargin = dp(14) })
    }

    private fun iconButton(icon: Int, description: Int, rotation: Float = 0f, onClick: () -> Unit) =
        ImageButton(context, null, 0, R.style.IconButton_Flat).apply {
            setImageResource(icon)
            // `tint` du style n'est lu que par les vues AppCompat gonflées depuis le XML.
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(themeColor(context, R.attr.c_text_dim)))
            this.rotation = rotation
            contentDescription = context.getString(description)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

    private fun swatchDrawable(color: Int) = android.graphics.drawable.StateListDrawable().apply {
        val ring = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(3), themeColor(context, R.attr.c_accent))
        }
        val plain = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
        }
        addState(intArrayOf(android.R.attr.state_selected), ring)
        addState(intArrayOf(), plain)
    }

    private fun seconds(ms: Int) = context.getString(R.string.step_wait_value, if (ms % 1000 == 0) "${ms / 1000}" else "%.1f".format(ms / 1000.0).replace('.', ','))

    /** Description courte d'une étape, en français. */
    private fun summary(step: JSONObject): String = when (step.optString("type")) {
        "app" -> context.getString(R.string.sum_app, step.optString("name"))
        "keys" -> step.optString("keys").split("+").let { parts ->
            // Windows : "Ctrl+Maj+M" ; Mac : "⌘⇧M".
            if (onWindows) parts.joinToString("+") { WINDOWS_NAMES[it] ?: SYMBOLS[it] ?: it.uppercase() }
            else parts.joinToString("") { SYMBOLS[it] ?: it.uppercase() }
        } +
            (step.optString("app").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
        "text" -> context.getString(R.string.sum_text, step.optString("text").replace('\n', ' ').let { if (it.length > 30) it.take(30) + "…" else it })
        "url" -> step.optString("url")
        "shortcut" -> context.getString(R.string.sum_shortcut, step.optString("name"))
        "media" -> MEDIA.firstOrNull { it.first == step.optString("command") }?.let { context.getString(it.second) } ?: step.optString("command")
        "system" -> SYSTEM.firstOrNull { it.first == step.optString("command") }?.let { context.getString(it.second) } ?: step.optString("command")
        "wait" -> context.getString(R.string.sum_wait, seconds(step.optInt("ms")))
        "window" -> {
            val layout = LAYOUTS.firstOrNull { it.first == step.optString("layout", "keep") }?.let { context.getString(it.second) }.orEmpty()
            val display = step.optString("display").ifEmpty { context.getString(R.string.step_window_current) }
            context.getString(R.string.sum_window, step.optString("app"), display, layout.lowercase())
        }
        else -> step.toString()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val COLORS = listOf(null, "#C62828", "#EF6C00", "#2E7D32", "#1565C0", "#6A1B9A", "#37474F")
        val TYPES = listOf(
            "app" to R.string.step_app, "keys" to R.string.step_keys, "text" to R.string.step_text,
            "window" to R.string.step_window, "url" to R.string.step_url, "shortcut" to R.string.step_shortcut,
            "media" to R.string.step_media, "system" to R.string.step_system, "wait" to R.string.step_wait,
        )
        val MEDIA = listOf("play-pause" to R.string.media_play_pause, "next" to R.string.media_next, "previous" to R.string.media_previous)
        val SYSTEM = listOf(
            "sleep-display" to R.string.system_sleep, "toggle-dark-mode" to R.string.system_dark,
            "screenshot-clipboard" to R.string.system_screenshot, "lock" to R.string.system_lock,
        )
        val LAYOUTS = listOf("keep" to R.string.layout_keep, "left" to R.string.windows_left, "right" to R.string.windows_right, "max" to R.string.windows_max)
        val WAITS = listOf(500, 1000, 2000, 5000)
        val SPECIAL_KEYS = listOf(
            "return" to "Entrée", "esc" to "Échap", "tab" to "Tab", "space" to "Espace", "delete" to "⌫",
            "left" to "←", "up" to "↑", "down" to "↓", "right" to "→", "f5" to "F5",
        )
        /** Sur Windows, cmd (raccourci écrit pour Mac) est envoyé comme Ctrl. */
        val WINDOWS_NAMES = mapOf("cmd" to "Ctrl", "ctrl" to "Ctrl", "shift" to "Maj", "alt" to "Alt", "win" to "Win")
        val SYMBOLS = mapOf(
            "cmd" to "⌘", "shift" to "⇧", "alt" to "⌥", "ctrl" to "⌃", "return" to "↩", "esc" to "Échap", "tab" to "⇥",
            "space" to "Espace", "delete" to "⌫", "left" to "←", "up" to "↑", "down" to "↓", "right" to "→",
        )
    }
}
