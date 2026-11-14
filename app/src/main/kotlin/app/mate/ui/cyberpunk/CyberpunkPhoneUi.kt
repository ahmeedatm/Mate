package app.mate.ui.cyberpunk

import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import app.mate.R
import app.mate.net.CyberpunkContact
import app.mate.net.CyberpunkContacts
import app.mate.net.CyberpunkPhoneStatus
import app.mate.net.CyberpunkThread
import app.mate.net.CyberpunkThreadChoice
import app.mate.net.CyberpunkThreadDetail
import app.mate.ui.cyberpunk.CyberpunkKit.Companion.MATCH
import app.mate.ui.cyberpunk.CyberpunkKit.Companion.WRAP

enum class PhoneTab { MESSAGES, CONTACTS }

/** Fil ouvert dans le tiroir : `detail` null tant qu'il se charge ; `sending` : un choix est parti, les boutons restent éteints. */
data class PhoneThread(val summary: CyberpunkThread, val detail: CyberpunkThreadDetail? = null, val error: String? = null, val sending: Boolean = false)

/** Tout ce que l'onglet Téléphone affiche ; une valeur immuable que le survol compare pour savoir s'il faut redessiner. */
data class PhoneState(
    val tab: PhoneTab = PhoneTab.MESSAGES,
    /** Hash des fils ouverts depuis l'ouverture du tiroir : ils restent listés une fois lus. */
    val opened: Set<String> = emptySet(),
    val thread: PhoneThread? = null,
    /** null : pas encore su (ou compagnon plus ancien) ; ni bouton ni message. */
    val lookStatus: CyberpunkPhoneStatus? = null,
    val look: PhoneLook? = null,
)

/** Ce que le tiroir Téléphone demande à la page. */
interface CyberpunkPhoneActions {
    fun selectTab(tab: PhoneTab)

    fun openThread(thread: CyberpunkThread)

    fun closeThread()

    fun reply(choice: CyberpunkThreadChoice)

    fun prepareLook()

    fun call(contact: CyberpunkContact)
}

/**
 * Tiroir Téléphone : onglets Messages / Contacts, fil en bulles avec ses choix. Avec les pièces du jeu (`PhoneLook`)
 * les cadres sont celles du jeu teintées en `cp_*` ; sans elles, les cadres coupés de [CyberpunkKit] et Chakra Petch.
 */
class CyberpunkPhoneUi(private val kit: CyberpunkKit, private val actions: CyberpunkPhoneActions) {
    private val context = kit.context

    fun fill(column: LinearLayout, contacts: CyberpunkContacts, state: PhoneState) {
        val thread = state.thread
        if (thread != null) return fillThread(column, thread, state)
        column.addView(tabs(state), kit.lp(MATCH, WRAP).apply { bottomMargin = kit.dp(10) })
        when (state.tab) {
            PhoneTab.MESSAGES -> fillMessages(column, contacts, state)
            PhoneTab.CONTACTS -> fillContacts(column, contacts, state)
        }
    }

    private fun titleFont(state: PhoneState): Typeface = state.look?.title ?: kit.bold

    private fun bodyFont(state: PhoneState): Typeface = state.look?.body ?: kit.regular

    private fun note(text: Int) = kit.text(context.getString(text), 17f, R.color.cp_gris)

    // --- Onglets

    private fun tabs(state: PhoneState): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(tab(state, PhoneTab.MESSAGES, R.string.cp_threads, R.string.cp_phone_tab_messages, "icon_message.png"), kit.lp(0, WRAP).apply { weight = 1f })
        addView(tab(state, PhoneTab.CONTACTS, R.string.cp_phone_contacts, R.string.cp_phone_tab_contacts, "phone_icon.png"), kit.lp(0, WRAP).apply { weight = 1f })
    }

    /** Onglet de 52 dp de haut : le choisi a un trait dessiné sous le texte, pas seulement sa couleur. */
    private fun tab(state: PhoneState, tab: PhoneTab, labelRes: Int, descriptionRes: Int, iconName: String): View {
        val selected = state.tab == tab
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = kit.dp(52)
            isSelected = selected
            contentDescription = context.getString(descriptionRes)
            isClickable = true
            isFocusable = true
            with(kit) { pressable() }
            val head = LinearLayout(context).apply { gravity = Gravity.CENTER }
            state.look?.bitmap(iconName)?.let { bmp ->
                head.addView(
                    ImageView(context).apply {
                        setImageBitmap(bmp)
                        setColorFilter(kit.color(if (selected) R.color.cp_rouge else R.color.cp_gris))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    kit.lp(kit.dp(22), kit.dp(22)).apply { marginEnd = kit.dp(8) },
                )
            }
            head.addView(kit.text(context.getString(labelRes), 17f, if (selected) R.color.cp_rouge else R.color.cp_gris, titleFont(state), spacing = 0.08f, upper = true))
            addView(head, kit.lp(WRAP, WRAP))
            addView(View(context).apply { setBackgroundColor(kit.color(if (selected) R.color.cp_rouge else R.color.cp_trait)) }, kit.lp(MATCH, kit.dp(if (selected) 3 else 1)).apply { topMargin = kit.dp(8) })
            setOnClickListener { actions.selectTab(tab) }
        }
    }

    // --- Messages

    private fun fillMessages(column: LinearLayout, contacts: CyberpunkContacts, state: PhoneState) {
        val threads = PhoneInbox.messages(contacts.threads, state.opened)
        if (threads.isEmpty()) column.addView(note(R.string.cp_no_threads))
        threads.forEach { t ->
            column.addView(threadRow(t, state), kit.lp(MATCH, WRAP).apply { bottomMargin = kit.dp(6) })
        }
        prepareControl(state)?.let { column.addView(it, kit.lp(MATCH, WRAP).apply { topMargin = kit.dp(18) }) }
    }

    /** « Préparer le look du jeu » (absent ou en erreur) ou « préparation… » ; rien quand c'est prêt ou inconnu. */
    private fun prepareControl(state: PhoneState): View? = when (state.lookStatus) {
        CyberpunkPhoneStatus.ABSENT, CyberpunkPhoneStatus.ERROR -> LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (state.lookStatus == CyberpunkPhoneStatus.ERROR) addView(kit.text(context.getString(R.string.cp_phone_look_failed), 16f, R.color.cp_gris), kit.lp(MATCH, WRAP).apply { bottomMargin = kit.dp(8) })
            addView(
                kit.button(context.getString(R.string.cp_phone_prepare), context.getString(R.string.cp_phone_prepare_description), R.color.cp_cyan, R.color.cp_sur_cyan).apply { setOnClickListener { actions.prepareLook() } },
                kit.lp(MATCH, WRAP),
            )
        }
        CyberpunkPhoneStatus.LOADING -> kit.text(context.getString(R.string.cp_phone_preparing), 16f, R.color.cp_cyan).apply { contentDescription = text }
        else -> null
    }

    /** Une ligne par fil : nom, repère carré si non lu, mention « à répondre » en toutes lettres, début du dernier message. */
    private fun threadRow(t: CyberpunkThread, state: PhoneState): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = kit.dp(58)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(kit.dp(12), kit.dp(10), kit.dp(12), kit.dp(10))
        background = frame(state, "frame_message.png", R.color.cp_trait) ?: kit.panel(R.color.cp_panneau, cutDp = 8, both = false)
        val head = kit.row().apply { minimumHeight = 0 }
        head.addView(kit.text(t.name, 17f, R.color.cp_rouge, titleFont(state)).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, kit.lp(0, WRAP).apply { weight = 1f })
        if (t.canReply) head.addView(kit.label(context.getString(R.string.cp_phone_to_answer), R.color.cp_jaune), kit.lp(WRAP, WRAP).apply { marginStart = kit.dp(8) })
        if (t.unread > 0) head.addView(View(context).apply { background = kit.panel(R.color.cp_cyan, cutDp = 0, both = false) }, kit.lp(kit.dp(10), kit.dp(10)).apply { marginStart = kit.dp(8) })
        addView(head, kit.lp(MATCH, WRAP))
        t.preview?.let { addView(kit.text(it, 16f, R.color.cp_texte, bodyFont(state)).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, kit.lp(MATCH, WRAP).apply { topMargin = kit.dp(2) }) }
        contentDescription = listOfNotNull(
            t.name,
            if (t.canReply) context.getString(R.string.cp_phone_to_answer) else null,
            if (t.unread > 0) context.getString(R.string.cp_unread_thread) else null,
            t.preview,
        ).joinToString(", ")
        if (t.hash.isNotEmpty()) {
            isClickable = true
            isFocusable = true
            with(kit) { pressable() }
            setOnClickListener { actions.openThread(t) }
        }
    }

    // --- Fil

    private fun fillThread(column: LinearLayout, thread: PhoneThread, state: PhoneState) {
        column.addView(
            kit.button(context.getString(R.string.cp_back), context.getString(R.string.cp_back_threads), R.color.cp_panneau, R.color.cp_rouge).apply { setOnClickListener { actions.closeThread() } },
            kit.lp(WRAP, kit.dp(52)),
        )
        column.addView(kit.text(thread.summary.name, 26f, R.color.cp_rouge, titleFont(state)), kit.lp(MATCH, WRAP).apply { topMargin = kit.dp(14); bottomMargin = kit.dp(10) })
        val detail = thread.detail
        when {
            thread.error != null -> column.addView(kit.text(thread.error, 17f, R.color.cp_texte, bodyFont(state)))
            detail == null -> column.addView(note(R.string.cp_loading))
            else -> {
                detail.messages.forEach { column.addView(bubble(it.text, it.player, thread.summary.name, state), bubbleParams(it.player)) }
                if (detail.choices.isNotEmpty()) choices(column, detail, thread.sending, state)
            }
        }
    }

    private fun bubbleParams(player: Boolean) = kit.lp(WRAP, WRAP).apply {
        gravity = if (player) Gravity.END else Gravity.START
        if (player) marginStart = kit.dp(BUBBLE_GAP_DP) else marginEnd = kit.dp(BUBBLE_GAP_DP)
        bottomMargin = kit.dp(8)
    }

    /** Le contact à gauche (rouge), V à droite (cyan) : la place dit qui parle, la description aussi. */
    private fun bubble(text: String, player: Boolean, contact: String, state: PhoneState): View = kit.text(text, 17f, R.color.cp_texte, bodyFont(state)).apply {
        setPadding(kit.dp(14), kit.dp(10), kit.dp(14), kit.dp(10))
        val accent = if (player) R.color.cp_cyan else R.color.cp_rouge
        background = layers(state, if (player) "msgBuble_reply" else "msgBuble", if (player) R.color.cp_sur_cyan else R.color.cp_panneau, accent)
            ?: kit.panel(R.color.cp_panneau, accent, 3, cutDp = 8, both = false)
        contentDescription = context.getString(R.string.cp_phone_said, if (player) context.getString(R.string.cp_v) else contact, text)
    }

    private fun choices(column: LinearLayout, detail: CyberpunkThreadDetail, sending: Boolean, state: PhoneState) {
        column.addView(kit.label(context.getString(if (sending) R.string.cp_phone_sending else R.string.cp_phone_choose)), kit.lp(WRAP, WRAP).apply { topMargin = kit.dp(10); bottomMargin = kit.dp(6) })
        detail.choices.forEach { c ->
            val button = kit.text(c.text, 17f, R.color.cp_cyan, bodyFont(state)).apply {
                minHeight = kit.dp(52)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(kit.dp(14), kit.dp(10), kit.dp(14), kit.dp(10))
                background = kit.panel(R.color.cp_panneau, R.color.cp_cyan, 3, cutDp = 8, both = false)
                contentDescription = context.getString(R.string.cp_phone_reply_with, c.text)
                isEnabled = !sending
                alpha = if (sending) DISABLED_ALPHA else 1f
                isClickable = true
                isFocusable = true
                with(kit) { pressable() }
                setOnClickListener { if (isEnabled) actions.reply(c) }
            }
            column.addView(button, kit.lp(MATCH, WRAP).apply { bottomMargin = kit.dp(8) })
        }
    }

    // --- Contacts

    private fun fillContacts(column: LinearLayout, contacts: CyberpunkContacts, state: PhoneState) {
        column.addView(kit.label(context.getString(R.string.cp_call_section)), kit.lp(WRAP, WRAP).apply { bottomMargin = kit.dp(6) })
        contacts.contacts.filter { it.callable }.forEach { c ->
            val line = kit.row()
            line.addView(kit.text(c.name, 17f, font = bodyFont(state)), kit.lp(0, WRAP).apply { weight = 1f })
            line.addView(
                kit.button(context.getString(R.string.cp_call_button), context.getString(R.string.cp_call_contact, c.name), R.color.cp_cyan, R.color.cp_sur_cyan).apply { setOnClickListener { actions.call(c) } },
                kit.lp(WRAP, kit.dp(48)),
            )
            column.addView(line, kit.lp(MATCH, WRAP))
            column.addView(kit.divider(), kit.lp(MATCH, 1))
        }
    }

    // --- Cadres du jeu

    private fun frame(state: PhoneState, name: String, tintRes: Int): Drawable? =
        state.look?.bitmap(name)?.let { NineSliceDrawable(it, minOf(it.width, it.height) / 3, kit.color(tintRes)) }

    /** Fond puis contour du jeu (`<base>_bg.png`, `<base>_fg.png`), teintés ; null si une des deux pièces manque. */
    private fun layers(state: PhoneState, base: String, fillRes: Int, outlineRes: Int): Drawable? {
        val bg = frame(state, base + "_bg.png", fillRes) ?: return null
        val fg = frame(state, base + "_fg.png", outlineRes) ?: return null
        return LayerDrawable(arrayOf(bg, fg))
    }

    private companion object {
        const val BUBBLE_GAP_DP = 40
        const val DISABLED_ALPHA = 0.4f
    }
}
