package com.unowhy.companion.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Conteneur des modes : un balayage vertical d'un doigt passe à l'entrée voisine de la barre (vers le haut : suivante,
 * vers le bas : précédente), comme sur l'Accueil (WidgetGrid). Laissé à l'écran si ce qui est sous le doigt défile encore
 * dans ce sens (listes) ; deux doigts (zoom de la caméra) jamais repris. `enabled` faux : l'Accueil, qui a son propre
 * balayage, et Réglages, hors barre.
 */
class RailSwipeLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var swipeEnabled = false
    var onSwipe: ((Int) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tracking = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled || ev.pointerCount > 1) {
            tracking = false
            return false
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                tracking = true
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dy) > slop * 2 && abs(dy) > abs(dx) * 2) {
                    tracking = false
                    // Vers le haut (dy < 0) : le contenu monterait ; s'il le peut encore, le geste est pour lui.
                    if (!scrollsAt(this, downX, downY, if (dy < 0) 1 else -1)) return true
                } else if (abs(dx) > slop * 2) {
                    tracking = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            val dy = ev.y - downY
            if (abs(dy) > height / 8) onSwipe?.invoke(if (dy < 0) 1 else -1)
        }
        return true
    }

    private fun scrollsAt(group: ViewGroup, x: Float, y: Float, direction: Int): Boolean {
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE || x < child.left || x >= child.right || y < child.top || y >= child.bottom) continue
            if (child.canScrollVertically(direction)) return true
            if (child is ViewGroup && scrollsAt(child, x - child.left + child.scrollX, y - child.top + child.scrollY, direction)) return true
        }
        return false
    }
}
