package app.mate.ui

import android.app.Activity
import android.net.Uri
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import app.mate.R
import java.net.URI

/**
 * Page web en plein écran par-dessus les modes (ordre `page` de l'ordinateur) : les maquettes HTML de
 * scripts/maquettes.mjs, à toucher sur la tablette. Le verrou (une boîte) reste devant. WebView créé à l'ouverture et
 * détruit à la fermeture (mémoire de la tablette), comme celui de DocumentPanel. Jamais d'en-tête d'appairage ici :
 * la page passe souvent en HTTP clair sur le WiFi.
 */
class PageOverlay(private val activity: Activity) {
    private var root: View? = null
    private var web: WebView? = null

    val isOpen get() = root != null

    fun open(url: String) {
        require(isPageUrl(url)) { "Adresse http ou https attendue" }
        (web ?: create()).loadUrl(url)
    }

    /** Écran éteint ou appli en arrière-plan : la maquette arrête ses minuteurs et animations. */
    fun pause() = web?.onPause()

    fun resume() = web?.onResume()

    fun close() {
        web?.let {
            it.stopLoading()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        root?.let { (it.parent as? ViewGroup)?.removeView(it) }
        root = null
        web = null
    }

    private fun create(): WebView {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val view = LayoutInflater.from(activity).inflate(R.layout.view_page_overlay, content, false)
        val page = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Maquette à largeur fixe (1280) : mise à l'échelle de l'écran, sans zoom à la main.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(false)
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                // Pages web seulement : un lien intent://, market://… sortirait du kiosque.
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest) = !isWebScheme(request.url.scheme)

                @Suppress("OVERRIDE_DEPRECATION")
                override fun shouldOverrideUrlLoading(v: WebView, url: String) = !isWebScheme(Uri.parse(url).scheme)

                // Script des maquettes arrêté, mauvaise adresse : une phrase en français plutôt que la page d'erreur du WebView.
                override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    val text = TextUtils.htmlEncode(activity.getString(R.string.page_unreachable, request.url.toString()))
                    v.loadDataWithBaseURL(null, "<p style='font:20px sans-serif;margin:48px'>$text</p>", "text/html", "utf-8", null)
                }
            }
        }
        view.findViewById<FrameLayout>(R.id.page_slot).addView(page, FrameLayout.LayoutParams(-1, -1))
        view.findViewById<View>(R.id.page_close).setOnClickListener { close() }
        content.addView(view)
        root = view
        web = page
        return page
    }

    companion object {
        /** http ou https avec un hôte : jamais un fichier de la tablette, du javascript: ni un intent. */
        fun isPageUrl(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return isWebScheme(uri.scheme) && !uri.host.isNullOrEmpty()
        }

        /** Lien suivi dans la page : un lien intent://, market://… sortirait du kiosque. */
        fun isWebScheme(scheme: String?) = scheme?.lowercase() in DocumentPanel.WEB_SCHEMES
    }
}
