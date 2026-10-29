package com.unowhy.companion.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.MainActivity
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.Plugin
import com.unowhy.companion.net.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Plugins de l'ordinateur actif, par-dessus tout l'écran : à la première utilisation (et quand une appli compatible
 * apparaît sur l'ordinateur), puis depuis les Réglages. À gauche les applis trouvées (Discord, Teams…), à droite les
 * fonctions intégrées (onglets du Deck). Tout est coché au départ : valider sans rien toucher garde l'écran d'avant.
 */
class PluginsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private lateinit var activity: MainActivity
    private val subtitle: TextView
    private val apps: LinearLayout
    private val natives: LinearLayout
    private val save: Button
    private val later: Button
    /** Écran proposé de lui-même (pas ouvert depuis les Réglages) : « Plus tard » le repousse au prochain lancement. */
    private var offered = false
    private val switches = linkedMapOf<String, SwitchCompat>()

    init {
        LayoutInflater.from(context).inflate(R.layout.view_plugins, this, true)
        setBackgroundColor(themeColor(context, R.attr.c_bg))
        // Rien ne doit passer à l'écran du dessous.
        isClickable = true
        subtitle = findViewById(R.id.plugins_subtitle)
        apps = findViewById(R.id.plugins_apps)
        natives = findViewById(R.id.plugins_native)
        save = findViewById(R.id.plugins_save)
        later = findViewById(R.id.plugins_later)
    }

    /** `onLater` : écran fermé sans choisir ; `onSaved` : choix enregistrés sur l'ordinateur. */
    fun attach(activity: MainActivity, onLater: () -> Unit, onSaved: () -> Unit) {
        this.activity = activity
        later.setOnClickListener {
            hide()
            if (offered) onLater()
        }
        save.setOnClickListener { save(onSaved) }
    }

    fun show(computerName: String, plugins: List<Plugin>, offered: Boolean) {
        this.offered = offered
        later.setText(if (offered) R.string.welcome_later else R.string.cancel)
        subtitle.text = context.getString(R.string.plugins_subtitle, computerName)
        // Badge « Nouveau » seulement après la première fois : au premier passage, tout est nouveau.
        val firstTime = plugins.all { it.fresh }
        switches.clear()
        apps.removeAllViews()
        natives.removeAllViews()
        for (plugin in plugins) addRow(if (plugin.native) natives else apps, plugin, badge = plugin.fresh && !firstTime)
        if (apps.childCount == 0) apps.addView(emptyText())
        save.isEnabled = true
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
    }

    private fun addRow(list: LinearLayout, plugin: Plugin, badge: Boolean) {
        val row = LayoutInflater.from(context).inflate(R.layout.item_plugin, list, false)
        row.findViewById<TextView>(R.id.plugin_name).text = plugin.name
        row.findViewById<TextView>(R.id.plugin_description).text = plugin.description
        row.findViewById<View>(R.id.plugin_new).visibility = if (badge) View.VISIBLE else View.GONE
        val switch = row.findViewById<SwitchCompat>(R.id.plugin_switch)
        // Nouveau plugin : coché, comme au premier passage.
        switch.isChecked = plugin.on
        switch.contentDescription = plugin.name
        row.setOnClickListener { switch.toggle() }
        switches[plugin.id] = switch
        bindIcon(row.findViewById(R.id.plugin_icon), plugin)
        (row.layoutParams as? LinearLayout.LayoutParams)?.topMargin = if (list.childCount == 0) 0 else dp(6)
        list.addView(row)
    }

    /** Appli de l'ordinateur : son icône (lue sur l'ordinateur), sinon un pictogramme de la fonction. */
    private fun bindIcon(icon: ImageView, plugin: Plugin) {
        icon.setImageResource(ICONS[plugin.id] ?: R.drawable.ic_widgets)
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(themeColor(context, R.attr.c_text_dim)))
        val app = plugin.app ?: return
        activity.lifecycleScope.launch {
            val bitmap = runCatchingCancellable { IconCache.loadApp(BackendClient(context), app) }.getOrNull() ?: return@launch
            ImageViewCompat.setImageTintList(icon, null)
            icon.setImageBitmap(bitmap)
        }
    }

    private fun emptyText() = TextView(context).apply {
        setText(R.string.plugins_no_apps)
        setTextColor(themeColor(context, R.attr.c_text_dim))
        textSize = 16f
    }

    private fun save(onSaved: () -> Unit) {
        val choices = switches.mapValues { it.value.isChecked }
        save.isEnabled = false
        activity.lifecycleScope.launch {
            try {
                BackendClient(context).plugins.save(choices)
                hide()
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                save.isEnabled = true
                activity.showBanner(context.errorText(e), 6)
            }
        }
    }

    private companion object {
        val ICONS = mapOf(
            "discord" to R.drawable.ic_chat,
            "teams" to R.drawable.ic_videocam,
            "claude" to R.drawable.ic_agents,
            "open" to R.drawable.ic_tabs,
            "mixer" to R.drawable.ic_volume,
            "pad" to R.drawable.ic_tablet,
            "media" to R.drawable.ic_play,
        )
    }
}
