package com.unowhy.unomate.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.core.widget.ImageViewCompat
import com.unowhy.unomate.R
import com.unowhy.unomate.agents.VoicePrompt

/**
 * Micro d'une session Claude (seule action de sa carte) : rond doux au repos ; en écoute, rond rouge avec un carré pour
 * finir et un halo qui grandit avec la voix (le seul mouvement : il montre que le micro entend) ; pendant la
 * transcription et l'envoi, grisé dans un anneau qui tourne. Cible de 48 dp au centre d'un cadre de [SIZE_DP] dp qui
 * laisse la place au halo (un parent coupe ce qui dépasse).
 */
class TalkButton(context: Context, phase: VoicePrompt.Phase?, onClick: () -> Unit) : FrameLayout(context) {

    private val halo = View(context).apply {
        setBackgroundResource(R.drawable.talk_halo)
        alpha = 0f
    }
    private val level: (Float) -> Unit = { v ->
        // Animateur de propriété : instantané si les animations sont coupées, rien ne reste à mi-chemin.
        halo.animate().scaleX(1f + v * HALO_GROWTH).scaleY(1f + v * HALO_GROWTH).alpha(if (v > 0f) 1f else 0.4f)
            .setDuration(LEVEL_MS).setInterpolator(DecelerateInterpolator()).start()
    }
    private val listening = phase == VoicePrompt.Phase.LISTENING

    init {
        clipChildren = false
        clipToPadding = false
        val size = dp(48)
        addView(halo, LayoutParams(size, size, Gravity.CENTER))
        addView(
            ImageButton(context, null, 0, R.style.IconButton).apply {
                when (phase) {
                    VoicePrompt.Phase.LISTENING -> {
                        setImageResource(R.drawable.ic_tile_stop)
                        setBackgroundResource(R.drawable.talk_listening_bg)
                        ImageViewCompat.setImageTintList(this, themeColorList(context, R.attr.c_on_ko))
                        contentDescription = context.getString(R.string.agent_talk_stop)
                    }
                    VoicePrompt.Phase.SENDING -> {
                        setImageResource(R.drawable.ic_mic)
                        isEnabled = false
                        ImageViewCompat.setImageTintList(this, themeColorList(context, R.attr.c_text_faint))
                        contentDescription = context.getString(R.string.agent_talk_sending)
                    }
                    null -> {
                        setImageResource(R.drawable.ic_mic)
                        setBackgroundResource(R.drawable.talk_idle_bg)
                        ImageViewCompat.setImageTintList(this, themeColorList(context, R.attr.c_accent))
                        contentDescription = context.getString(R.string.agent_talk)
                    }
                }
                setOnClickListener { onClick() }
            },
            LayoutParams(size, size, Gravity.CENTER),
        )
        if (phase == VoicePrompt.Phase.SENDING) {
            addView(ProgressBar(context).apply { isIndeterminate = true }, LayoutParams(size, size, Gravity.CENTER))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (listening) {
            halo.alpha = 0.4f
            VoicePrompt.levels += level
        }
    }

    override fun onDetachedFromWindow() {
        VoicePrompt.levels -= level
        halo.animate().cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        /** Cadre du bouton : le halo à fond le remplit (48 dp × 4/3). */
        const val SIZE_DP = 64
        const val HALO_GROWTH = 1f / 3
        private const val LEVEL_MS = 120L
    }
}
