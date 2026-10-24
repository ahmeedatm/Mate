package com.unowhy.companion.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.DocEntry
import com.unowhy.companion.net.WebPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Panneau latéral qui affiche un document du Mac (PDF, image, texte) à côté du mode courant.
 * PDF rendu page par page avec PdfRenderer (inclus dans Android) : une seule page en mémoire.
 * Affiche aussi la page web ouverte dans le navigateur de l'ordinateur (tuto à côté d'un jeu), dans un WebView
 * créé à la demande et détruit à la fermeture (mémoire de la tablette).
 */
class DocumentPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val title: TextView
    private val image: ImageView
    private val imageScroll: ScrollView
    private val text: TextView
    private val textScroll: ScrollView
    private val status: TextView
    private val pager: View
    private val pageLabel: TextView
    private val content: FrameLayout
    private val webRefresh: ImageButton
    private val expandButton: ImageButton

    /** Panneau en grand (le mode courant masqué) ; MainActivity masque ou remontre le mode. */
    var expanded = false
        private set
    var onExpandedChanged: (Boolean) -> Unit = {}
    private var web: WebView? = null

    private lateinit var scope: CoroutineScope
    private var loadJob: Job? = null
    private var entry: DocEntry? = null
    private var renderer: PdfRenderer? = null
    private var pdfFd: ParcelFileDescriptor? = null
    private var pageIndex = 0
    private var bitmap: Bitmap? = null

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.panel_bg)
        LayoutInflater.from(context).inflate(R.layout.view_document_panel, this, true)
        title = findViewById(R.id.doc_title)
        image = findViewById(R.id.doc_image)
        imageScroll = findViewById(R.id.doc_image_scroll)
        text = findViewById(R.id.doc_text)
        textScroll = findViewById(R.id.doc_text_scroll)
        status = findViewById(R.id.doc_status)
        pager = findViewById(R.id.doc_pager)
        pageLabel = findViewById(R.id.doc_page)
        content = findViewById(R.id.doc_content)
        webRefresh = findViewById(R.id.doc_web_refresh)
        webRefresh.setOnClickListener { openComputerPage(expanded, expandButton.visibility == VISIBLE) }
        expandButton = findViewById(R.id.doc_expand)
        expandButton.setOnClickListener { setExpanded(!expanded) }

        findViewById<View>(R.id.doc_close).setOnClickListener { close() }
        findViewById<View>(R.id.doc_prev).setOnClickListener { showPage(pageIndex - 1) }
        findViewById<View>(R.id.doc_next).setOnClickListener { showPage(pageIndex + 1) }
        findViewById<Button>(R.id.doc_open_mac).setOnClickListener {
            val current = entry
            // Page web : celle où l'on est arrivé sur la tablette, rouverte dans le navigateur de l'ordinateur.
            val url = web?.url?.takeIf { current == null }
            if (current == null && url == null) return@setOnClickListener
            scope.launch {
                val message = runCatching {
                    if (url != null) BackendClient(context).openUrlOnComputer(url) else BackendClient(context).openOnMac(current!!.path)
                }
                    .fold({ context.getString(R.string.opened_on_mac) }, { context.errorText(it) })
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Chemin (relatif au dossier partagé) du document affiché. */
    val currentPath: String?
        get() = entry?.path

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    /**
     * En grand (le mode courant masqué) ou à côté : choisi par MainActivity selon le mode. `splittable` : bouton pour
     * passer de l'un à l'autre ; absent dans Partage, où la liste des fichiers à côté ne sert à rien.
     */
    fun setExpanded(value: Boolean, splittable: Boolean = true) {
        expanded = value
        expandButton.visibility = if (splittable) VISIBLE else GONE
        expandButton.setImageResource(if (value) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        expandButton.contentDescription = context.getString(if (value) R.string.doc_shrink else R.string.doc_expand)
        onExpandedChanged(value)
    }

    fun open(doc: DocEntry, expand: Boolean, splittable: Boolean) {
        release()
        entry = doc
        visibility = VISIBLE
        setExpanded(expand, splittable)
        title.text = doc.name
        showStatus(context.getString(R.string.loading))

        loadJob = scope.launch {
            try {
                val dir = File(context.cacheDir, "docs").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() } // un seul document en cache à la fois
                val file = File(dir, "doc-" + doc.name.replace(Regex("[^\\w.-]"), "_"))
                BackendClient(context).downloadDoc(doc.path, file)
                when (doc.kind) {
                    "pdf" -> openPdf(file)
                    "image" -> showImage(withContext(Dispatchers.IO) { decodeSampled(file, maxWidth()) })
                    "text" -> showText(withContext(Dispatchers.IO) { file.readText().take(200_000) })
                    else -> showStatus(context.getString(R.string.unsupported_doc))
                }
            } catch (e: Exception) {
                showStatus(context.errorText(e))
            }
        }
    }

    /** Récupère la page ouverte dans le navigateur de l'ordinateur et l'affiche. */
    fun openComputerPage(expand: Boolean, splittable: Boolean) {
        release()
        entry = null
        visibility = VISIBLE
        setExpanded(expand, splittable)
        webRefresh.visibility = VISIBLE
        title.setText(R.string.web_from_computer)
        showStatus(context.getString(R.string.loading))
        loadJob = scope.launch {
            try {
                showWeb(BackendClient(context).browserPage())
            } catch (e: Exception) {
                showStatus(context.errorText(e))
            }
        }
    }

    private fun showWeb(page: WebPage) {
        title.text = page.title
        val view = web ?: WebView(context).also { view ->
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.useWideViewPort = true
            view.settings.loadWithOverviewMode = true
            view.settings.builtInZoomControls = true
            view.settings.displayZoomControls = false
            // Liens « nouvel onglet » ouverts dans la même vue.
            view.settings.setSupportMultipleWindows(false)
            view.webViewClient = object : WebViewClient() {
                // Pages web seulement : un lien intent://, market://… sortirait du kiosque (ou échouerait).
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest) =
                    request.url.scheme !in WEB_SCHEMES

                @Suppress("OVERRIDE_DEPRECATION")
                override fun shouldOverrideUrlLoading(v: WebView, url: String) = Uri.parse(url).scheme !in WEB_SCHEMES

                override fun onPageFinished(v: WebView, url: String) {
                    v.title?.takeIf { it.isNotBlank() && web === v }?.let { title.text = it }
                }
            }
            content.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            web = view
        }
        status.visibility = GONE
        imageScroll.visibility = GONE
        textScroll.visibility = GONE
        view.visibility = VISIBLE
        view.loadUrl(page.url)
    }

    fun close() {
        release()
        entry = null
        visibility = GONE
        setExpanded(false)
    }

    private fun release() {
        web?.let {
            content.removeView(it)
            it.destroy()
        }
        web = null
        webRefresh.visibility = GONE
        loadJob?.cancel()
        image.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
        renderer?.close()
        renderer = null
        pdfFd?.close()
        pdfFd = null
        pager.visibility = GONE
    }

    private fun openPdf(file: File) {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pdfFd = fd
        renderer = PdfRenderer(fd)
        pager.visibility = VISIBLE
        showPage(0)
    }

    private fun showPage(index: Int) {
        val pdf = renderer ?: return
        if (index !in 0 until pdf.pageCount) return
        pageIndex = index
        pdf.openPage(index).use { page ->
            val width = maxWidth()
            val height = (width.toFloat() * page.height / page.width).toInt()
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE) // les PDF ont un fond transparent
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            showImage(bmp)
        }
        pageLabel.text = context.getString(R.string.page_x_of_y, index + 1, pdf.pageCount)
        imageScroll.scrollTo(0, 0)
    }

    private fun showImage(bmp: Bitmap?) {
        if (bmp == null) return showStatus(context.getString(R.string.unsupported_doc))
        val previous = bitmap
        bitmap = bmp
        image.setImageBitmap(bmp)
        previous?.recycle()
        status.visibility = GONE
        textScroll.visibility = GONE
        imageScroll.visibility = VISIBLE
    }

    private fun showText(content: String) {
        text.text = content
        status.visibility = GONE
        imageScroll.visibility = GONE
        textScroll.visibility = VISIBLE
        textScroll.scrollTo(0, 0)
    }

    private fun showStatus(message: String) {
        web?.visibility = GONE
        status.text = message
        status.visibility = VISIBLE
        imageScroll.visibility = GONE
        textScroll.visibility = GONE
    }

    /** Largeur de rendu : celle du panneau, bornée pour ménager la mémoire de la tablette. */
    private fun maxWidth(): Int {
        val w = if (width > 0) width else resources.displayMetrics.widthPixels / 2
        return w.coerceIn(480, 1400)
    }

    private fun decodeSampled(file: File, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        val WEB_SCHEMES = setOf("http", "https")
    }
}
