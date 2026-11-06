package app.mate.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import app.mate.R
import app.mate.ui.showForTyping
import app.mate.ui.themeColor

/**
 * Une page web choisie (tableau de bord, graphique), rechargée seule. Affichage seulement : un toucher ouvre les
 * réglages (adresse, minutes entre deux rechargements). Le WebView n'existe que page à l'écran : un seul à la fois
 * en mémoire (tas de 128 Mo de la tablette).
 */
class WebWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val frame = FrameLayout(context)
    private val empty = label(EMPTY_SP, R.attr.c_text_faint).apply {
        gravity = Gravity.CENTER
        setText(R.string.widget_web_empty)
    }
    private var web: WebView? = null
    private var active = false
    private var lastLoadMs = 0L

    override val view = LinearLayout(context).asCard(8).apply {
        orientation = LinearLayout.VERTICAL
        addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override val hasSettings get() = true

    /** Aperçu du catalogue : une page dessinée, jamais de WebView ni de réseau. */
    private var sample = false

    /** En Pastille, Ligne et Colonne, la phrase « choisis une page » rapetisse pour tenir. */
    override fun applySize() {
        empty.textSize = if (OpenWindowsSummary.compact(family)) SMALL_EMPTY_SP else EMPTY_SP
    }

    override fun showPreview(sample: WidgetSample) {
        if (sample !is WebSample) return
        this.sample = true
        empty.visibility = View.GONE
        frame.addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(WebSample.drawable(context))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private val url get() = placed.config.optString("url").takeIf { it.isNotBlank() }

    init {
        // Couche au-dessus du WebView : il ne prend pas les gestes de l'Accueil (balayage, appui long).
        frame.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(View(context).apply {
            contentDescription = context.getString(R.string.widget_web_settings)
            setOnClickListener { showSettings() }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun setActive(active: Boolean) {
        if (sample) return
        this.active = active
        if (active) show() else release()
    }

    /** Chaque minute : recharge quand le délai choisi est écoulé. */
    override suspend fun load() {
        if (web == null) return
        val now = SystemClock.elapsedRealtime()
        if (WebTile.dueForReload(lastLoadMs, now, WebTile.refreshMinutes(placed.config.optInt("every", -1).takeIf { it > 0 }))) {
            lastLoadMs = now
            web?.reload()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun show() {
        val address = url
        empty.visibility = if (address == null) View.VISIBLE else View.GONE
        if (address == null || web != null) return
        val created = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            // Pages web seulement : un lien intent:// ou market:// sortirait du kiosque.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest) =
                    request.url.scheme?.lowercase() !in setOf("http", "https")
            }
        }
        frame.addView(created, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = created
        lastLoadMs = SystemClock.elapsedRealtime()
        created.loadUrl(address)
    }

    private fun release() {
        web?.let {
            frame.removeView(it)
            it.destroy()
        }
        web = null
    }

    override fun showSettings() {
        val urlInput = field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, R.string.widget_web_hint).apply {
            setText(url.orEmpty())
        }
        val everyInput = field(InputType.TYPE_CLASS_NUMBER, R.string.widget_web_every_hint).apply {
            setText(WebTile.refreshMinutes(placed.config.optInt("every", -1).takeIf { it > 0 }).toString())
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(urlInput)
            addView(everyInput)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.widget_web)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ -> save(urlInput.text.toString(), everyInput.text.toString()) }
            .create()
            .showForTyping(urlInput)
    }

    private fun field(type: Int, hint: Int) = (LayoutInflater.from(context).inflate(R.layout.item_input, null) as EditText).apply {
        inputType = type
        setHint(hint)
        setSelection(text.length)
    }

    private fun save(address: String, every: String) {
        val clean = if (address.isBlank()) "" else WebTile.normalize(address)
        if (clean == null) {
            Toast.makeText(context, R.string.widget_web_invalid, Toast.LENGTH_LONG).show()
            return
        }
        placed.config.put("url", clean)
        placed.config.put("every", WebTile.refreshMinutes(every.toIntOrNull()))
        host.save()
        release()
        if (active) show()
    }

    private companion object {
        const val EMPTY_SP = 18f
        const val SMALL_EMPTY_SP = 14f
    }
}

/** Exemple de la Page web pour le catalogue : un tableau de bord dessiné aux couleurs du thème (bandeau, trois cartes), sans réseau. */
object WebSample : WidgetSample {
    private const val WIDTH = 320
    private const val HEIGHT = 200

    fun drawable(context: Context): BitmapDrawable {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun rect(attr: Int, left: Float, top: Float, right: Float, bottom: Float, radius: Float = 6f) {
            paint.color = themeColor(context, attr)
            canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, paint)
        }
        canvas.drawColor(themeColor(context, R.attr.c_bg))
        rect(R.attr.c_accent, 0f, 0f, 320f, 28f, 0f)
        rect(R.attr.c_text_faint, 16f, 44f, 104f, 120f)
        rect(R.attr.c_text_faint, 116f, 44f, 204f, 120f)
        rect(R.attr.c_text_faint, 216f, 44f, 304f, 120f)
        rect(R.attr.c_text_dim, 16f, 136f, 240f, 146f, 3f)
        rect(R.attr.c_text_dim, 16f, 156f, 200f, 166f, 3f)
        rect(R.attr.c_text_dim, 16f, 176f, 260f, 186f, 3f)
        return BitmapDrawable(context.resources, bitmap)
    }
}
