package com.unowhy.companion.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.lifecycle.LifecycleOwner
import com.unowhy.companion.R

/**
 * Le tableau des sessions Claude en plein écran, par-dessus l'Accueil : le même [AgentsView] que l'onglet du Deck.
 * Il ne relit l'ordinateur que tant qu'il est ouvert.
 */
class AgentsBoard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val agents: AgentsView

    init {
        setBackgroundResource(R.drawable.scrim)
        isClickable = true
        visibility = GONE
        LayoutInflater.from(context).inflate(R.layout.view_agents_board, this, true)
        agents = findViewById(R.id.board_agents)
        // Toucher le fond assombri ferme le tableau ; la carte absorbe ses propres touchers.
        setOnClickListener { hide() }
        findViewById<ImageButton>(R.id.board_close).setOnClickListener { hide() }
    }

    val isShowing get() = visibility == VISIBLE

    fun show(owner: LifecycleOwner) {
        agents.attach(owner)
        visibility = VISIBLE
        agents.setActive(true)
    }

    fun hide() {
        if (!isShowing) return
        agents.setActive(false)
        visibility = GONE
    }
}
