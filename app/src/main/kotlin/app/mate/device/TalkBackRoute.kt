package app.mate.device

/**
 * Ce que fait l'interrupteur TalkBack de Réglages > Accessibilité. Avec WRITE_SECURE_SETTINGS (donné par adb à
 * l'installation), Mate l'allume lui-même. Sans elle : hors kiosque, il ouvre l'accessibilité d'Android ; en kiosque
 * (verrouillé à l'écran, Paramètres inaccessible), il reste bloqué et la carte renvoie à l'installation.
 */
enum class TalkBackRoute {
    MISSING,
    DIRECT,
    SYSTEM_SETTINGS,
    LOCKED,
    ;

    companion object {
        fun of(installed: Boolean, canWrite: Boolean, kiosk: Boolean): TalkBackRoute = when {
            !installed -> MISSING
            canWrite -> DIRECT
            kiosk -> LOCKED
            else -> SYSTEM_SETTINGS
        }
    }
}
