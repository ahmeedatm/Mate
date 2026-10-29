package com.unowhy.companion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.ClipHistoryItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * « Récents » de l'écran Presse-papiers (view_clipboard.xml) : les textes copiés sur l'ordinateur depuis une heure,
 * gardés en mémoire par le compagnon (GET /system/clipboard/history). Lus avec « Sur l'ordi » (affichage, Actualiser),
 * jamais en boucle. Toucher une ligne : la recopier sur l'ordi ou sur la tablette, ou la retirer. Les codes arrivent
 * marqués `secret` : masqués, l'œil les montre le temps que l'écran reste ouvert.
 */
class ClipboardHistoryList(
    private val fragment: Fragment,
    root: View,
    /** Erreur de lecture ou d'envoi en texte (503, 501…), comme pour « Sur l'ordi ». */
    private val errorText: (Exception) -> String,
) {
    private val context = root.context
    private val list: LinearLayout = root.findViewById(R.id.clip_history_list)
    private val state: TextView = root.findViewById(R.id.clip_history_state)
    private val clear: View = root.findViewById(R.id.clip_history_clear)

    private var items: List<ClipHistoryItem> = emptyList()
    private val revealed = mutableSetOf<String>()
    private var loadJob: Job? = null

    private val scope get() = fragment.viewLifecycleOwner.lifecycleScope
    private val api get() = BackendClient(context).system

    init {
        clear.setOnClickListener { confirmClear() }
    }

    /** Relit l'historique une fois. */
    fun load() {
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                show(api.clipboardHistory())
            } catch (e: Exception) {
                show(emptyList(), errorText(e))
            }
        }
    }

    /** Écran quitté : lecture oubliée, codes de nouveau masqués. */
    fun stop() {
        loadJob?.cancel()
        revealed.clear()
    }

    private fun show(next: List<ClipHistoryItem>, error: String? = null) {
        items = next
        revealed.retainAll(next.map { it.id }.toSet())
        list.removeAllViews()
        next.forEach { list.addView(row(it)) }
        val message = error ?: if (next.isEmpty()) context.getString(R.string.clip_history_empty) else null
        state.text = message
        state.visibility = if (message == null) View.GONE else View.VISIBLE
        clear.visibility = if (next.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun row(item: ClipHistoryItem): View {
        val time = TextView(context).apply {
            text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.at))
            setTextColor(themeColor(context, R.attr.c_text_faint))
            textSize = 14f
        }
        val text = TextView(context).apply {
            text = historyLine(item.text, item.secret, item.id in revealed)
            setTextColor(themeColor(context, R.attr.c_text))
            textSize = 16f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(context.dp(12), 0, context.dp(8), 0)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(52)
            setBackgroundResource(R.drawable.flat_bg)
            isClickable = true
            setOnClickListener { choose(item) }
            addView(time)
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (item.secret) addView(revealButton(item))
        }
    }

    private fun revealButton(item: ClipHistoryItem) = ImageButton(context, null, 0, R.style.IconButton_Flat).apply {
        val shown = item.id in revealed
        setImageResource(if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
        // `tint` du style n'est lu que par les vues AppCompat gonflées depuis le XML.
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(themeColor(context, R.attr.c_text_dim)))
        contentDescription = context.getString(if (shown) R.string.clip_history_hide else R.string.clip_history_show)
        setOnClickListener {
            if (!revealed.remove(item.id)) revealed.add(item.id)
            show(items)
        }
        layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48))
    }

    /** Recopier sur l'ordi, sur la tablette, ou retirer de l'historique. */
    private fun choose(item: ClipHistoryItem) {
        val title = historyLine(item.text, item.secret, item.id in revealed, max = 60)
        val actions = arrayOf(
            context.getString(R.string.clip_send),
            context.getString(R.string.clip_copy_here),
            context.getString(R.string.clip_history_remove),
        )
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> recopy(item)
                    1 -> copyToTablet(item)
                    else -> remove(item)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun recopy(item: ClipHistoryItem) = act {
        api.recopyFromHistory(item.id)
        context.getString(R.string.clip_copied)
    }

    private fun remove(item: ClipHistoryItem) = act {
        api.removeFromHistory(item.id)
        null
    }

    private fun confirmClear() {
        AlertDialog.Builder(context)
            .setMessage(R.string.clip_history_clear_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clip_history_clear) { _, _ -> act { api.clearHistory(); null } }
            .show()
    }

    /** Envoi au compagnon, puis l'historique est relu ; `block` rend le message à afficher (ou rien). */
    private fun act(block: suspend () -> String?) {
        scope.launch {
            val message = try {
                block()
            } catch (e: Exception) {
                errorText(e)
            }
            message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            load()
        }
    }

    private fun copyToTablet(item: ClipHistoryItem) {
        val message = try {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.clip_choose), item.text))
            context.getString(R.string.clip_copied_here)
        } catch (e: RuntimeException) {
            fragment.errorText(e)
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
