package app.mate.net

/**
 * D'où viennent les lumières, prises et scènes du widget Maison : par l'ordinateur ([HaApi]), par Home Assistant en
 * direct ([HaDirectApi]) ou par une WLED en direct ([WledApi]). Le widget ne voit que des [HaEntity].
 */
interface LightSource {
    suspend fun entities(): List<HaEntity>

    /** Bascule une lumière ou une prise, lance une scène. */
    suspend fun toggle(id: String)

    /** Règle la luminosité (%) et/ou la couleur (0xRRGGBB) d'une lumière ; l'allume si elle est éteinte. */
    suspend fun setLight(id: String, brightness: Int?, rgb: Int?)
}
