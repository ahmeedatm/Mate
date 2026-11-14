package app.mate.ui.cyberpunk

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.mate.R
import app.mate.net.CyberpunkApi
import app.mate.net.CyberpunkMap3d
import app.mate.net.CyberpunkMapStatus
import app.mate.net.CyberpunkPoi
import app.mate.net.CyberpunkPos
import app.mate.net.CyberpunkServerPin
import app.mate.net.CyberpunkTracked
import app.mate.net.PoiCategory
import app.mate.net.runCatchingCancellable
import app.mate.ui.cyberpunk.CyberpunkKit.Companion.MATCH
import app.mate.ui.cyberpunk.CyberpunkKit.Companion.WRAP
import app.mate.ui.errorText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Carte de Night City de la page Cyberpunk : la carte 3D du jeu, moteur op77_map3d d'Open2077 (MIT) embarqué dans
 * `assets/carte3d`, affiché dans un WebView qui ne charge rien d'Internet. Ses données (dérivées du jeu) viennent de
 * l'ordinateur une fois (`GET /cyberpunk/map3d/<fichier>`) et restent sur la tablette. Mate lui donne la position du
 * joueur, les repères, les points d'intérêt et la destination ([Map3dBridge]) ; un double toucher sur la carte pose une
 * destination, que Mate transforme en repère suivi par le GPS du jeu. Design : docs/specs/2026-11-15-cyberpunk-carte-3d-design.md.
 */
@SuppressLint("ViewConstructor")
class Map3dView(
    private val kit: CyberpunkKit,
    private val api: CyberpunkApi,
    parentScope: CoroutineScope,
    /** Un repère a changé côté ordinateur : relire la liste. */
    private val onPinsChanged: () -> Unit,
    /** La préparation des données est lancée : relire leur état. */
    private val onMapChanged: () -> Unit,
    private val say: (String) -> Unit,
) : FrameLayout(kit.context) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val dataDir = File(context.noBackupFilesDir, DATA_DIR)

    /** Faux quand la page n'est plus en partie : une destination posée sur la carte n'est pas envoyée au jeu. */
    var interactive = true

    private var web: WebView? = null
    private var pageReady = false
    private var loadedFiles: Map<String, String>? = null
    private var syncJob: Job? = null

    private var pos: CyberpunkPos? = null
    private var tracked: CyberpunkTracked? = null
    private var questId: String? = null
    private var inVehicle = false
    private var pins = emptyList<CyberpunkServerPin>()
    private var pois = emptyList<CyberpunkPoi>()
    private var posJob: Job? = null

    /** Dernier envoi de chaque sorte : rien n'est renvoyé à la page tant que ça ne change pas. */
    private val sent = mutableMapOf<String, String>()

    private val statusText = kit.text("", 20f, R.color.cp_texte, kit.medium).apply { gravity = Gravity.CENTER }
    private val prepareButton = kit.button(context.getString(R.string.cp_map_prepare), context.getString(R.string.cp_map_prepare_description))
        .apply { setOnClickListener { prepare() } }
    private val statusPanel = LinearLayout(kit.context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = kit.panel()
        setPadding(kit.dp(PANEL_H_DP), kit.dp(PANEL_V_DP), kit.dp(PANEL_H_DP), kit.dp(PANEL_V_DP))
        addView(statusText, kit.lp(WRAP, WRAP))
        addView(prepareButton, kit.lp(WRAP, kit.dp(BUTTON_DP)).apply { topMargin = kit.dp(GAP_DP) })
    }

    init {
        contentDescription = context.getString(R.string.cp_map3d_description)
        addView(CyberpunkGridView(context, kit.color(R.color.cp_grille), kit.dp(GRID_DP)), LayoutParams(MATCH, MATCH))
        addView(statusPanel, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = kit.dp(STATUS_BOTTOM_DP) })
        statusPanel.visibility = GONE
    }

    // --- Données de la carte

    fun setMap(map: CyberpunkMap3d) {
        if (map.status != CyberpunkMapStatus.READY || map.files.isEmpty()) {
            showStatus(
                when (map.status) {
                    CyberpunkMapStatus.LOADING -> context.getString(R.string.cp_map3d_preparing)
                    CyberpunkMapStatus.ERROR -> map.error ?: context.getString(R.string.cp_map_failed)
                    else -> context.getString(R.string.cp_map_absent)
                },
                retry = map.status != CyberpunkMapStatus.LOADING,
            )
            return
        }
        if (map.files == loadedFiles || syncJob?.isActive == true) return
        syncJob = scope.launch { copyFiles(map.files) }
    }

    /** La lecture de l'état a échoué : phrase déjà rédigée (errorText), ou ordinateur trop ancien. */
    fun setMapError(text: String) = showStatus(text, retry = true)

    /** Copie sur la tablette les fichiers absents ou changés (empreinte vérifiée), puis ouvre la page. */
    private suspend fun copyFiles(files: Map<String, String>) {
        val missing = withContext(Dispatchers.IO) { Map3dBridge.missing(files, files.keys.associateWith { localSha(it) }) }
        if (missing.isNotEmpty()) showStatus(context.getString(R.string.cp_map3d_copying), retry = false)
        for (name in missing) {
            val body = runCatchingCancellable { api.map3dFile(name) }.getOrElse { return setMapError(context.errorText(it)) }
            val ok = body != null && withContext(Dispatchers.IO) { save(name, body, files.getValue(name)) }
            if (!ok) return setMapError(context.getString(R.string.cp_map_failed))
        }
        loadedFiles = files
        statusPanel.visibility = GONE
        openPage()
    }

    private fun localSha(name: String): String? = runCatching { File(dataDir, "$name$SHA_SUFFIX").readText() }.getOrNull()

    /** Fichier temporaire puis renommage : une coupure ne laisse jamais une donnée tronquée. Empreinte vérifiée avant. */
    private fun save(name: String, body: ByteArray, sha: String): Boolean {
        if (sha256(body) != sha) return false
        dataDir.mkdirs()
        val tmp = File(dataDir, "$name.part")
        tmp.writeBytes(body)
        if (!tmp.renameTo(File(dataDir, name))) return false
        File(dataDir, "$name$SHA_SUFFIX").writeText(sha)
        return true
    }

    private fun showStatus(text: String, retry: Boolean) {
        statusText.text = text
        prepareButton.visibility = if (retry) VISIBLE else GONE
        statusPanel.visibility = VISIBLE
    }

    private fun prepare() {
        prepareButton.visibility = GONE
        statusText.text = context.getString(R.string.cp_map3d_preparing)
        scope.launch {
            runCatchingCancellable { api.prepareMap3d() }
                .onSuccess { onMapChanged() }
                .onFailure { setMapError(context.errorText(it)) }
        }
    }

    // --- Page

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun openPage() {
        val view = web ?: WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            settings.setSupportMultipleWindows(false)
            setBackgroundColor(kit.color(R.color.cp_fond))
            addJavascriptInterface(Bridge(), BRIDGE)
            webViewClient = Client()
            this@Map3dView.addView(this, 1, LayoutParams(MATCH, MATCH))
        }.also { web = it }
        pageReady = false
        sent.clear()
        view.loadUrl("$ORIGIN/index.html")
    }

    /** Sert la page (assets de l'APK) et ses données (dossier de l'appli) ; tout le reste est refusé, Internet compris. */
    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            val url = request.url
            val path = url.path.orEmpty()
            if ("${url.scheme}://${url.host}" != ORIGIN) return refused()
            val name = path.substringAfterLast('/')
            return when {
                path == "/index.html" || path == "/mate.js" || (path.startsWith("/assets/") && name.matches(ASSET_NAME)) ->
                    runCatching { ok(mime(name), context.assets.open("$ASSETS$path")) }.getOrElse { refused() }
                path.startsWith("/data/") && name in Map3dBridge.FILES ->
                    File(dataDir, name).takeIf { it.isFile }?.let { ok(mime(name), FileInputStream(it)) } ?: refused()
                else -> refused()
            }
        }
    }

    private fun ok(mime: String, body: java.io.InputStream) = WebResourceResponse(mime, if (mime.startsWith("text/") || mime.endsWith("javascript")) "utf-8" else null, body)

    private fun refused() = WebResourceResponse("text/plain", "utf-8", HTTP_NOT_FOUND, "Not Found", emptyMap(), "".byteInputStream())

    private fun mime(name: String) = when (name.substringAfterLast('.')) {
        "html" -> "text/html"
        "js" -> "text/javascript"
        "css" -> "text/css"
        "woff2" -> "font/woff2"
        "json" -> "application/json"
        "glb" -> "model/gltf-binary"
        else -> "application/octet-stream"
    }

    /** Appelé par le moteur sur un fil à lui : tout repasse sur le fil de l'interface. */
    private inner class Bridge {
        @JavascriptInterface
        fun emit(name: String, json: String) {
            post { onPageEvent(name, json) }
        }
    }

    private fun onPageEvent(name: String, json: String) {
        when (name) {
            "map3d:hello", "map3d:ready" -> {
                pageReady = true
                sent.clear()
                pushAll()
            }
            "map3d:waypoint" -> if (interactive) runCatching { JSONObject(json) }.getOrNull()?.let { act(Map3dBridge.action(it, pins)) }
        }
    }

    private fun act(action: Map3dBridge.Action) {
        when (action) {
            is Map3dBridge.Action.NewPin -> launchAction {
                val pin = api.addPin(action.x, action.y, action.z, track = true)
                context.getString(if (pin.tracked) R.string.cp_pin_added_followed else R.string.cp_pin_added)
            }
            is Map3dBridge.Action.Track -> launchAction {
                api.trackPin(action.pinId)
                context.getString(R.string.cp_pin_followed)
            }
            is Map3dBridge.Action.Clear -> launchAction {
                questId?.let { api.action("track", kind = "quest", id = it) }
                action.pinId?.let { api.deletePin(it) }
                context.getString(R.string.cp_pin_back_to_quest)
            }
            Map3dBridge.Action.None -> Unit
        }
    }

    private fun launchAction(call: suspend () -> String) {
        scope.launch {
            runCatchingCancellable { call() }
                .onSuccess {
                    say(it)
                    onPinsChanged()
                }
                .onFailure { say(context.errorText(it)) }
        }
    }

    private fun fire(name: String, data: Any) {
        val json = data.toString()
        if (sent[name] == json) return
        sent[name] = json
        web?.evaluateJavascript("window.MateMap3d&&MateMap3d.fire(${JSONObject.quote(name)},${JSONObject.quote(json)})", null)
    }

    private fun pushAll() {
        fire("map3d:role", JSONObject().put("role", "full"))
        fire("map3d:config", config())
        fire("map3d:open", JSONObject())
        pushPlayer()
        pushBlips()
    }

    private fun config(): JSONObject {
        val colors = PoiCategory.entries.associate<PoiCategory, PoiCategory?, String> { it to hex(it.color) } + mapOf(null to hex(R.color.cp_cyan))
        val labels = PoiCategory.entries.associate<PoiCategory, PoiCategory?, String> { it to context.getString(it.label) } + mapOf(null to context.getString(R.string.cp_map3d_pins))
        val locale = context.resources.getStringArray(R.array.cp_map3d_locale).associate { it.substringBefore('|') to it.substringAfter('|') }
        return Map3dBridge.config(colors, labels, locale)
    }

    private fun hex(colorRes: Int) = String.format("#%06x", kit.color(colorRes) and RGB_MASK)

    private fun objective(): Pair<Double, Double>? = tracked?.takeIf { it.x != null && it.y != null }?.let { Pair(it.x!!, it.y!!) }

    private fun pushBlips() {
        if (!pageReady) return
        val colors = PoiCategory.entries.associate<PoiCategory, PoiCategory?, String> { it to hex(it.color) } + mapOf(null to hex(R.color.cp_cyan))
        val label = tracked?.desc ?: tracked?.quest
        fire("map3d:blips", JSONObject().put("set", Map3dBridge.blips(pins, pois, objective(), label, colors, context.getString(R.string.cp_map3d_pin))))
        val wp = Map3dBridge.waypoint(pins, objective())
        fire("map3d:waypoint", wp ?: JSONObject().put("clear", true))
    }

    private fun pushPlayer(x: Double, y: Double, z: Double?, yaw: Double, speed: Double?) {
        if (pageReady) fire("map3d:player", Map3dBridge.player(x, y, z, yaw, inVehicle, speed))
    }

    private fun pushPlayer() {
        pos?.let { pushPlayer(it.x, it.y, it.z, it.yaw, null) }
    }

    // --- État de la partie

    /** Appelé à chaque état frais de la partie (~1 par seconde). */
    fun update(pos: CyberpunkPos?, tracked: CyberpunkTracked?, questId: String?, inVehicle: Boolean = false) {
        this.pos = pos
        this.questId = questId
        val trackedChanged = tracked != this.tracked
        this.tracked = tracked
        this.inVehicle = inVehicle
        // En véhicule, la position rapide (~4 par seconde) prend le relais.
        if (!inVehicle || posJob?.isActive != true) pushPlayer()
        if (trackedChanged) pushBlips()
        updatePosPolling()
    }

    fun setPins(pins: List<CyberpunkServerPin>) {
        this.pins = pins
        pushBlips()
    }

    fun setPois(points: List<CyberpunkPoi>) {
        pois = points
        pushBlips()
    }

    /** La partie n'est plus en cours : plus de sondage rapide. */
    fun leaveDrive() {
        inVehicle = false
        updatePosPolling()
    }

    private fun updatePosPolling() {
        val wanted = inVehicle && pageReady && isShown
        if (!wanted) {
            posJob?.cancel()
            posJob = null
            return
        }
        if (posJob?.isActive == true) return
        posJob = scope.launch {
            while (true) {
                runCatchingCancellable { api.livePos() }.getOrNull()?.let { pushPlayer(it.x, it.y, pos?.z, it.yaw, it.speed) }
                delay(POS_POLL_MS)
            }
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updatePosPolling()
    }

    /** La page se ferme : requêtes annulées, WebView détruit. */
    fun release() {
        scope.cancel()
        web?.let {
            it.stopLoading()
            removeView(it)
            it.destroy()
        }
        web = null
    }

    private companion object {
        const val ORIGIN = "https://carte.mate.invalid"
        const val ASSETS = "carte3d"
        const val BRIDGE = "MateNative"
        const val DATA_DIR = "cyberpunk-map3d"
        const val SHA_SUFFIX = ".sha256"
        const val HTTP_NOT_FOUND = 404
        const val RGB_MASK = 0xFFFFFF
        const val POS_POLL_MS = 250L
        val ASSET_NAME = Regex("^[a-zA-Z0-9._-]+\\.(js|css|woff2)$")
        const val GRID_DP = 64
        const val STATUS_BOTTOM_DP = 110
        const val PANEL_H_DP = 32
        const val PANEL_V_DP = 24
        const val BUTTON_DP = 52
        const val GAP_DP = 18

        fun sha256(body: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    }
}
