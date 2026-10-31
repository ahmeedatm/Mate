package app.mate.ui

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
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import app.mate.R
import app.mate.net.BackendClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Toutes les applis de l'ordinateur (carte Applis de l'Accueil), en plein écran : toucher une appli la lance sur
 * l'ordinateur et ferme le panneau ; l'épingle l'ajoute à la carte (page Apps du deck) ou l'en retire, le panneau reste
 * ouvert. Épinglées d'abord ([order]) ; « Profil de base » revient à la liste de config/deck.json.
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
                apps = BackendClient(context).deck.catalog().apps
                render()
            } catch (e: Exception) {
                status.showError(e)
            }
        }
    }

    fun hide() {
        // Un champ masqué garderait le clavier ouvert (pas de bouton Retour sur la tablette).
        dismissKeyboard()
        visibility = GONE
    }

    private fun render() {
        list.removeAllViews()
        val all = apps ?: return list.addView(TextView(context).apply { setText(R.string.loading); textSize = 16f })
        order(all, present.keys, filter.text.toString()).take(MAX_ROWS).forEach { name -> list.addView(row(name)) }
    }

    /** Une appli : son nom (toucher = la lancer), puis l'épingle de la carte, rouge si elle y est. */
    private fun row(name: String) = LinearLayout(context).apply {
        val pinned = name in present
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.row_bg)
        setPadding(dp(16), 0, dp(4), 0)
        setOnClickListener { launch(name) }
        addView(
            TextView(context).apply {
                text = name
                textSize = 16f
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            ImageButton(context, null, 0, R.style.IconButton_Flat).apply {
                setImageResource(R.drawable.ic_push_pin)
                imageTintList = android.content.res.ColorStateList.valueOf(themeColor(context, if (pinned) R.attr.c_accent else R.attr.c_text_faint))
                contentDescription = context.getString(if (pinned) R.string.apps_unpin else R.string.apps_pin, name)
                setOnClickListener {
                    dismissKeyboard()
                    if (pinned) remove(name) else add(name)
                }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(6) }
    }

    private fun launch(name: String) {
        dismissKeyboard()
        scope.launch {
            try {
                BackendClient(context).system.launchApp(name)
                hide()
            } catch (e: Exception) {
                status.showError(e)
            }
        }
    }

    private fun add(name: String) {
        scope.launch {
            try {
                present = present + (name to BackendClient(context).deck.addApp(name))
                changed(context.getString(R.string.apps_added_status, name))
            } catch (e: Exception) {
                status.showError(e)
            }
        }
    }

    private fun remove(name: String) {
        val id = present[name] ?: return
        scope.launch {
            try {
                BackendClient(context).deck.removeApp(id)
                IconCache.forget(id)
                present = present - name
                changed(context.getString(R.string.apps_removed_status, name))
            } catch (e: Exception) {
                status.showError(e)
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
                        BackendClient(context).deck.resetApps()
                        hide()
                        onChanged()
                    } catch (e: Exception) {
                        status.showError(e)
                    }
                }
            }
            .showImmersive()
    }

    companion object {
        private const val MAX_ROWS = 80

        /** Applis épinglées sur la carte d'abord, puis les autres, chacune par ordre alphabétique ; filtrées par la recherche. */
        fun order(apps: List<String>, pinned: Set<String>, query: String): List<String> {
            val q = query.trim()
            return apps.filter { it.contains(q, ignoreCase = true) }.sortedWith(compareBy({ it !in pinned }, { it.lowercase() }))
        }
    }
}
