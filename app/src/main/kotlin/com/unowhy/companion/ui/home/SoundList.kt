package com.unowhy.companion.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.MixerChannel
import com.unowhy.companion.net.MixerState
import com.unowhy.companion.ui.LatestSender
import com.unowhy.companion.ui.dp
import com.unowhy.companion.ui.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Grand widget Son : une ligne par source du mélangeur (sortie, micro, puis chaque appli qui a du son sur Windows),
 * muet + curseur. Relu chaque seconde tant qu'il est à l'écran ; une ligne touchée ne revient pas en arrière le temps
 * que l'ordinateur suive.
 */
class SoundList(context: Context, private val scope: CoroutineScope) : LinearLayout(context) {
    private val rows = LinkedHashMap<String, View>()
    private val heldUntil = HashMap<String, Long>()
    private val dragging = HashSet<String>()
    private val senders = HashMap<String, LatestSender<Int>>()
    private var poll: Job? = null

    init {
        orientation = VERTICAL
    }

    fun setActive(active: Boolean) {
        poll?.cancel()
        poll = null
        if (!active) return
        poll = scope.launch {
            while (isActive) {
                if (isShown) runCatching { BackendClient(context).system.mixer() }.getOrNull()?.let { render(it) }
                delay(POLL_MS)
            }
        }
    }

    private fun render(state: MixerState) {
        val channels = listOf(state.master, state.mic) + state.apps.orEmpty()
        if (channels.map { it.id } != rows.keys.toList()) {
            val reuse = HashMap(rows)
            rows.clear()
            removeAllViews()
            for (c in channels) {
                val row = reuse[c.id] ?: newRow(c)
                rows[c.id] = row
                addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { if (childCount > 0) topMargin = context.dp(4) })
            }
        }
        val now = SystemClock.elapsedRealtime()
        for (c in channels) {
            if (c.id in dragging || (heldUntil[c.id] ?: 0) > now) continue
            show(rows.getValue(c.id), c)
        }
    }

    private fun newRow(c: MixerChannel): View {
        val row = LayoutInflater.from(context).inflate(R.layout.item_sound_row, this, false)
        row.findViewById<TextView>(R.id.sound_name).text = when (c.id) {
            "master" -> context.getString(R.string.mixer_master)
            MIC -> context.getString(R.string.mixer_mic)
            "system" -> context.getString(R.string.mixer_system)
            else -> c.name
        }
        val value = row.findViewById<TextView>(R.id.sound_value)
        val sender = senders.getOrPut(c.id) { LatestSender(scope) { level -> render(BackendClient(context).system.setMixer(c.id, volume = level)) } }
        row.findViewById<SeekBar>(R.id.sound_volume).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                value.text = context.getString(R.string.percent, progress)
                sender.offer(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {
                dragging += c.id
            }
            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging -= c.id
                hold(c.id)
                sender.offer(bar.progress)
            }
        })
        row.findViewById<ImageButton>(R.id.sound_mute).setOnClickListener { button ->
            val muted = button.tag != true
            hold(c.id)
            style(button as ImageButton, c.id, muted)
            scope.launch { runCatching { BackendClient(context).system.setMixer(c.id, muted = muted) }.getOrNull()?.let { render(it) } }
        }
        return row
    }

    private fun hold(id: String) {
        heldUntil[id] = SystemClock.elapsedRealtime() + HOLD_MS
    }

    private fun show(row: View, c: MixerChannel) {
        val bar = row.findViewById<SeekBar>(R.id.sound_volume)
        bar.isEnabled = c.volume != null
        bar.progress = c.volume ?: 0
        row.findViewById<TextView>(R.id.sound_value).text = when {
            c.muted -> context.getString(R.string.muted)
            c.volume == null -> ""
            else -> context.getString(R.string.percent, c.volume)
        }
        style(row.findViewById(R.id.sound_mute), c.id, c.muted)
    }

    /** Coupé = icône rouge, comme la barre du son de l'ordinateur. */
    private fun style(button: ImageButton, id: String, muted: Boolean) {
        button.tag = muted
        val mic = id == MIC
        button.setImageResource(
            when {
                mic && muted -> R.drawable.ic_mic_off
                mic -> R.drawable.ic_mic
                muted -> R.drawable.ic_volume_off
                else -> R.drawable.ic_volume
            },
        )
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(themeColor(context, if (muted) R.attr.c_ko else R.attr.c_text)))
    }

    private companion object {
        const val POLL_MS = 1_000L
        const val HOLD_MS = 1_200L
        const val MIC = "mic"
    }
}
