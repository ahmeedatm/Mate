package app.mate.installers

/**
 * Plafond d'échecs par adresse sur le port des installeurs (testé dans app/src/test) : au-delà de `max` refus dans les `windowMs`
 * dernières millisecondes, l'adresse est bloquée, même avec le bon code. Le code n'a que 5 caractères : sans plafond,
 * quelqu'un sur le WiFi pourrait tous les essayer.
 */
class FailureLimiter(private val max: Int = 20, private val windowMs: Long = 60_000) {

    private val failures = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun blocked(address: String, now: Long = System.currentTimeMillis()): Boolean {
        prune(now)
        return (failures[address]?.size ?: 0) >= max
    }

    @Synchronized
    fun fail(address: String, now: Long = System.currentTimeMillis()) {
        prune(now)
        failures.getOrPut(address) { ArrayDeque() }.addLast(now)
    }

    /** Adresses suivies en ce moment. */
    @Synchronized
    fun tracked(): Int = failures.size

    private fun prune(now: Long) {
        val it = failures.values.iterator()
        while (it.hasNext()) {
            val times = it.next()
            while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
            if (times.isEmpty()) it.remove()
        }
    }
}
