package app.mate.net

/** Aperçu de l'écran de l'ordinateur : un JPEG réduit (640 px au plus), jamais gardé par l'ordinateur (src/routes/screen.ts). */
class ScreenApi(private val http: Http) {

    /** Une image JPEG de l'écran principal. 403 sans l'autorisation du Mac : la phrase de l'ordinateur est montrée telle quelle. */
    suspend fun preview(): ByteArray = http.io { http.bytes("/screen/preview", timeoutMs = 5_000) }
}
