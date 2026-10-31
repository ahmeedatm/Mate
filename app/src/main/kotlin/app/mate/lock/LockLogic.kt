package app.mate.lock

import java.security.MessageDigest

/**
 * Deux verrous indépendants (ui/LockScreen) : la tablette verrouillée à la main (code à 4 chiffres) et l'ordinateur
 * actif verrouillé (backend/src/screen-lock.ts). Le déverrouillage de l'un ne lève jamais l'autre.
 */
enum class LockKind { NONE, TABLET, COMPUTER }

object LockLogic {
    const val CODE_LENGTH = 4

    /** Le verrou à la main d'abord : son code se tape sur l'écran, celui de l'ordinateur suit s'il reste. */
    fun kind(tablet: Boolean, computer: Boolean): LockKind = when {
        tablet -> LockKind.TABLET
        computer -> LockKind.COMPUTER
        else -> LockKind.NONE
    }

    fun validCode(code: String): Boolean = code.length == CODE_LENGTH && code.all { it in '0'..'9' }

    /** Code jamais gardé en clair : empreinte salée (sel tiré une fois par tablette). */
    fun hash(code: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$code".toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Codes faux : après [max] essais ratés de suite, plus d'essai pendant [waitMs]. */
class LockAttempts(private val max: Int = 5, private val waitMs: Long = 30_000) {
    private var failures = 0
    private var blockedUntil = 0L

    /** Millisecondes avant le prochain essai permis ; 0 : on peut taper. */
    fun waitLeft(now: Long): Long = (blockedUntil - now).coerceAtLeast(0)

    fun fail(now: Long) {
        failures++
        if (failures >= max) {
            failures = 0
            blockedUntil = now + waitMs
        }
    }

    fun reset() {
        failures = 0
        blockedUntil = 0
    }
}
