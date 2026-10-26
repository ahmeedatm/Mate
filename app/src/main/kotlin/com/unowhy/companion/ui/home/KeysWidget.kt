package com.unowhy.companion.ui.home

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.DeckTile
import com.unowhy.companion.ui.IconCache
import com.unowhy.companion.ui.Look
import com.unowhy.companion.ui.TitleView
import com.unowhy.companion.ui.errorText
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.unowhy.companion.ui.showImmersive

/**
 * Touches du deck choisies (1, 2 ou 4 selon la taille) : lancer Discord, une macro, couper le micro… sans passer par
 * le Deck. Les touches sont gardées avec leur nom et leur emoji : elles s'affichent même ordinateur éteint.
 */
class KeysWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    override val hasSettings get() = true

    private val capacity get() = when (placed.size) { WidgetSize.S -> 1; WidgetSize.M -> 2; else -> 4 }

    private fun chosen(): List<JSONObject> {
        val keys = placed.config.optJSONArray("keys") ?: return emptyList()
        return (0 until keys.length()).map { keys.getJSONObject(it) }
    }

    override fun applySize() = render()

    private fun render() {
        view.removeAllViews()
        val keys = chosen().take(capacity)
        if (keys.isEmpty()) {
            view.addView(LinearLayout(context).apply {
                setBackgroundResource(R.drawable.tile_empty_bg)
                gravity = Gravity.CENTER
                orientation = LinearLayout.VERTICAL
                addView(label(30f, R.attr.c_text_faint).apply { text = "+"; gravity = Gravity.CENTER })
                addView(label(16f, R.attr.c_text_dim).apply { setText(R.string.widget_keys_choose); gravity = Gravity.CENTER })
                setOnClickListener { showSettings() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            return
        }
        // Grand : 2 × 2 ; sinon une rangée.
        val perRow = if (capacity == 4) 2 else capacity
        val gap = dp(12)
        keys.chunked(perRow).forEachIndexed { r, rowKeys ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, key) in rowKeys.withIndex()) {
                row.addView(tile(key), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = gap })
            }
            // Rangée incomplète : la place vide garde la largeur des touches.
            repeat(perRow - rowKeys.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = gap }) }
            view.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { if (r > 0) topMargin = gap })
        }
    }

    private fun tile(key: JSONObject): View {
        val view = LayoutInflater.from(context).inflate(R.layout.item_tile, null)
        val id = key.getString("id")
        val icon = view.findViewById<ImageView>(R.id.tile_icon)
        val initial = view.findViewById<TextView>(R.id.tile_initial)
        view.findViewById<TextView>(R.id.tile_label).text = key.optString("label")
        key.optString("color").takeIf { it.isNotBlank() }?.let { runCatching { Color.parseColor(it) }.getOrNull() }?.let { view.background.mutate().setTint(it) }
        val glyph = key.optString("glyph").takeIf { it.isNotBlank() }
        Look.tintInitial(initial, key.optString("label"))
        if (glyph != null) {
            initial.text = glyph
        } else {
            initial.text = key.optString("label").take(1).uppercase()
            IconCache.peek(id)?.let { icon.setImageBitmap(it); initial.visibility = View.GONE } ?: host.scope.launch {
                IconCache.load(BackendClient(context), id)?.let { icon.setImageBitmap(it); initial.visibility = View.GONE }
            }
        }
        view.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            host.scope.launch {
                try {
                    BackendClient(context).deck.runTile(id)
                } catch (e: Exception) {
                    Toast.makeText(context, context.errorText(e), Toast.LENGTH_LONG).show()
                }
            }
        }
        return view
    }

    /** Liste des touches de toutes les pages du deck de l'ordinateur actif, à cocher (au plus 4). */
    override fun showSettings() {
        host.scope.launch {
            val pages = try {
                BackendClient(context).deck.pages()
            } catch (e: Exception) {
                Toast.makeText(context, context.errorText(e), Toast.LENGTH_LONG).show()
                return@launch
            }
            val selected = chosen().map { it.getString("id") }.toMutableList()
            val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(4), dp(24), dp(8)) }
            val boxes = mutableListOf<Pair<CheckBox, DeckTile>>()
            val hint = label(15f, R.attr.c_text_dim).apply { setPadding(0, 0, 0, dp(8)) }
            box.addView(hint)
            fun update() {
                hint.text = context.getString(R.string.widget_keys_hint, selected.size, MAX_KEYS)
                boxes.forEach { (check, tile) -> check.isEnabled = check.isChecked || selected.size < MAX_KEYS || tile.id in selected }
            }
            for (page in pages) {
                val tiles = page.tiles.filter { it.type != "empty" }
                if (tiles.isEmpty()) continue
                box.addView(TitleView(context).apply { text = page.title; setPadding(0, dp(12), 0, dp(4)) })
                for (tile in tiles) {
                    val check = CheckBox(context).apply {
                        text = listOfNotNull(tile.glyph, tile.label).joinToString("  ")
                        textSize = 17f
                        minHeight = dp(44)
                        isChecked = tile.id in selected
                        setOnCheckedChangeListener { _, on ->
                            if (on) { if (tile.id !in selected) selected += tile.id } else selected -= tile.id
                            update()
                        }
                    }
                    boxes += check to tile
                    box.addView(check)
                }
            }
            update()
            AlertDialog.Builder(context)
                .setTitle(R.string.widget_keys)
                .setView(ScrollView(context).apply { addView(box) })
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save) { _, _ ->
                    val all = pages.flatMap { it.tiles }
                    placed.config.put("keys", JSONArray().apply {
                        selected.mapNotNull { id -> all.firstOrNull { it.id == id } }.forEach { tile ->
                            put(JSONObject().put("id", tile.id).put("label", tile.label).put("glyph", tile.glyph ?: "").put("color", tile.color ?: ""))
                        }
                    })
                    host.save()
                    render()
                }
                .showImmersive()
        }
    }

    private companion object {
        const val MAX_KEYS = 4
    }
}
