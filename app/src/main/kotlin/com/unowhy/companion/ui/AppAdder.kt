package com.unowhy.companion.ui

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Ajout d'applis du Mac à la page Apps du deck, par-dessus le deck. Le panneau reste ouvert pour en
 * ajouter plusieurs ; « Profil de base » revient à la liste de config/deck.json.
 */
class AppAdder @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val filter: EditText
    private val status: TextView
    private val list: LinearLayout

    private lateinit var scope: CoroutineScope
    private var onChanged: () -> Unit = {}
    private var apps: List<String>? = null
    /** Applis déjà sur la page : nom → identifiant de tuile. */
    private var present: Map<String, String> = emptyMap()

    init {
        setBackgroundResource(R.drawable.scrim)
        isClickable = true
        // Reprend le focus du champ de recherche : sinon il partirait sur la barre latérale.
        isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        visibility = GONE
        LayoutInflater.from(context).inflate(R.layout.view_app_adder, this, true)
        filter = findViewById(R.id.adder_filter)
        status = findViewById(R.id.adder_status)
        list = findViewById(R.id.adder_list)

        findViewById<ImageButton>(R.id.adder_close).setOnClickListener { hide() }
        findViewById<Button>(R.id.adder_reset).setOnClickListener { confirmReset() }
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render()
        })
        filter.setOnEditorActionListener { _, action, event ->
            // ✓ du clavier à l'écran : IME_ACTION_DONE ; Entrée d'un clavier physique : IME_NULL.
            if (action != EditorInfo.IME_ACTION_DONE && action != EditorInfo.IME_NULL) return@setOnEditorActionListener false
            if (event == null || event.action == KeyEvent.ACTION_DOWN) dismissKeyboard()
            true
        }
    }

    fun attach(scope: CoroutineScope, onChanged: () -> Unit) {
        this.scope = scope
        this.onChanged = onChanged
    }

    val isShowing get() = visibility == VISIBLE

    /** `present` : applis déjà sur la page (nom → identifiant de tuile), marquées dans la liste. */
    fun show(present: Map<String, String>) {
        this.present = present
        filter.setText("")
        status.visibility = GONE
        visibility = VISIBLE
        render()
        if (apps == null) scope.launch {
            try {
                apps = BackendClient(context).deckCatalog().apps
                render()
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    fun hide() {
        // Un champ masqué garderait le clavier ouvert (pas de bouton Retour sur la tablette).
        dismissKeyboard()
        visibility = GONE
    }

    private fun dismissKeyboard() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
        requestFocus()
    }

    private fun render() {
        list.removeAllViews()
        val all = apps ?: return list.addView(TextView(context).apply { setText(R.string.loading); textSize = 16f })
        val query = filter.text.toString().trim()
        all.filter { it.contains(query, ignoreCase = true) }.take(60).forEach { name -> list.addView(row(name)) }
    }

    private fun row(name: String) = TextView(context).apply {
        val added = name in present
        text = if (added) context.getString(R.string.apps_added, name) else name
        textSize = 16f
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.row_bg)
        setPadding(dp(16), 0, dp(16), 0)
        if (added) setTextColor(ContextCompat.getColor(context, R.color.accent))
        setOnClickListener {
            dismissKeyboard()
            if (added) remove(name) else add(name)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(6) }
    }

    private fun add(name: String) {
        scope.launch {
            try {
                present = present + (name to BackendClient(context).addDeckApp(name))
                changed(context.getString(R.string.apps_added_status, name))
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    private fun remove(name: String) {
        val id = present[name] ?: return
        scope.launch {
            try {
                BackendClient(context).removeDeckApp(id)
                IconCache.forget(id)
                present = present - name
                changed(context.getString(R.string.apps_removed_status, name))
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    private fun changed(message: String) {
        status.text = message
        status.visibility = VISIBLE
        render()
        onChanged()
    }

    private fun confirmReset() {
        AlertDialog.Builder(context)
            .setMessage(R.string.apps_reset_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                scope.launch {
                    try {
                        BackendClient(context).resetDeckApps()
                        hide()
                        onChanged()
                    } catch (e: Exception) {
                        showError(e)
                    }
                }
            }
            .showImmersive()
    }

    private fun showError(e: Exception) {
        status.text = context.errorText(e)
        status.visibility = VISIBLE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
