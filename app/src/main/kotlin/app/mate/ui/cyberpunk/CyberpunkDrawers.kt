package app.mate.ui.cyberpunk

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.mate.R
import app.mate.net.CyberpunkContacts
import app.mate.net.CyberpunkImplant
import app.mate.net.CyberpunkItem
import app.mate.net.CyberpunkPhoneStatus
import app.mate.net.CyberpunkPlayer
import app.mate.net.CyberpunkQuest
import app.mate.net.CyberpunkState
import app.mate.ui.cyberpunk.CyberpunkKit.Companion.MATCH
import app.mate.ui.cyberpunk.CyberpunkKit.Companion.WRAP

/** Pages du rail, dans l'ordre. */
enum class CyberpunkPage(val labelRes: Int, val iconRes: Int) {
    JOURNAL(R.string.cp_rail_journal, R.drawable.ic_cp_journal),
    PHONE(R.string.cp_rail_phone, R.drawable.ic_cp_phone),
    ITEMS(R.string.cp_rail_items, R.drawable.ic_cp_items),
    CYBER(R.string.cp_rail_cyber, R.drawable.ic_cp_cyber),
    PERSO(R.string.cp_rail_perso, R.drawable.ic_cp_perso),
}

/** Ce que les tiroirs peuvent demander au jeu. */
interface CyberpunkDrawerActions : CyberpunkPhoneActions {
    fun trackQuest(quest: CyberpunkQuest)

    fun use(item: CyberpunkItem)
}

/** Contenu des cinq tiroirs de 420 dp : une colonne de vues par page ; une donnée pas encore lue (null) affiche « Chargement ». */
class CyberpunkDrawers(private val kit: CyberpunkKit, private val actions: CyberpunkDrawerActions) {
    private val context = kit.context
    private val phoneUi = CyberpunkPhoneUi(kit, actions)

    /** Gardé d'un affichage à l'autre : l'emplacement choisi survit à une relecture du bloc. */
    private val cyberware by lazy { CyberwareView(kit) }

    private fun column(titleRes: Int): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(kit.text(context.getString(titleRes), 26f, R.color.cp_rouge, kit.bold, spacing = 0.08f, upper = true))
        addView(View(context), kit.lp(MATCH, kit.dp(14)))
    }

    private fun LinearLayout.note(text: Int) = addView(kit.text(context.getString(text), 17f, R.color.cp_gris))

    fun journal(quests: List<CyberpunkQuest>?): View = column(R.string.cp_rail_journal).apply {
        if (quests == null) return@apply note(R.string.cp_loading)
        if (quests.isEmpty()) return@apply note(R.string.cp_no_quests)
        // La suivie d'abord, mise en avant ; le reste dans l'ordre du jeu.
        quests.sortedByDescending { it.tracked }.forEach { addView(questRow(it), kit.lp(MATCH, WRAP)) }
    }

    private fun questRow(q: CyberpunkQuest): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = kit.dp(58)
        gravity = Gravity.CENTER_VERTICAL
        val left = if (q.tracked) kit.dp(15) else 0
        setPadding(left, kit.dp(10), 0, kit.dp(10))
        if (q.tracked) background = kit.panel(R.color.cp_panneau, R.color.cp_jaune, 3, cutDp = 0)
        contentDescription = context.getString(R.string.cp_follow_quest, q.title)
        isClickable = true
        isFocusable = true
        with(kit) { pressable() }
        q.kind?.let { addView(kit.label(it)) }
        addView(kit.text(q.title, 19f, if (q.tracked) R.color.cp_jaune else R.color.cp_texte, kit.bold))
        q.objective?.let { addView(kit.text(it, 16f, R.color.cp_cyan), kit.lp(WRAP, WRAP).apply { topMargin = kit.dp(2) }) }
        setOnClickListener { actions.trackQuest(q) }
    }

    fun phone(contacts: CyberpunkContacts?, state: PhoneState): View = column(R.string.cp_rail_phone).apply {
        if (contacts == null) return@apply note(R.string.cp_loading)
        phoneUi.fill(this, contacts, state)
    }

    /** Lecture du tiroir échouée : la phrase d'erreur (errorText) sous le titre de la page. */
    fun failed(titleRes: Int, text: String): View = column(titleRes).apply { addView(kit.text(text, 17f, R.color.cp_texte)) }

    fun items(items: List<CyberpunkItem>?): View = column(R.string.cp_rail_items).apply {
        if (items == null) return@apply note(R.string.cp_loading)
        CyberpunkItems.grouped(items).forEach { (category, list) ->
            addView(kit.label(context.getString(categoryRes(category))), kit.lp(WRAP, WRAP).apply { topMargin = kit.dp(14); bottomMargin = kit.dp(2) })
            list.forEach { addView(itemRow(it), kit.lp(MATCH, WRAP)) }
        }
    }

    private fun itemRow(item: CyberpunkItem): View {
        val line = kit.row()
        line.addView(kit.rarityBar(kit.rarityColor(CyberpunkItems.rarity(item.quality))), kit.lp(kit.dp(5), kit.dp(30)).apply { marginEnd = kit.dp(12) })
        line.addView(kit.text(item.name, 17f), kit.lp(0, WRAP).apply { weight = 1f })
        line.addView(kit.text(context.getString(R.string.cp_qty, item.qty), 17f, R.color.cp_gris, kit.mono), kit.lp(WRAP, WRAP).apply { marginStart = kit.dp(8) })
        if (item.usable) {
            val use = kit.button(context.getString(R.string.cp_use), context.getString(R.string.cp_use_item, item.name))
            use.minHeight = kit.dp(48)
            use.setOnClickListener { actions.use(item) }
            line.addView(use, kit.lp(WRAP, kit.dp(48)).apply { marginStart = kit.dp(10) })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(line, kit.lp(MATCH, WRAP))
            addView(kit.divider(), kit.lp(MATCH, 1))
        }
    }

    /** Écran cyberware du charcudoc, de `height` pixels sous le titre ; bouton « Préparer le look du jeu » sans les images. */
    fun cyberware(ui: CyberwareUi, height: Int): View = column(R.string.cp_title_cyber).apply {
        val block = ui.block ?: return@apply
        (cyberware.parent as? ViewGroup)?.removeView(cyberware)
        cyberware.bind(block, ui.look)
        val title = getChildAt(0) as? TextView
        if (ui.look == null && (ui.lookStatus == CyberpunkPhoneStatus.ABSENT || ui.lookStatus == CyberpunkPhoneStatus.ERROR)) {
            removeView(title)
            val head = kit.row().apply { minimumHeight = 0 }
            title?.let { head.addView(it, kit.lp(0, WRAP).apply { weight = 1f }) }
            head.addView(
                kit.button(context.getString(R.string.cp_phone_prepare), context.getString(R.string.cp_phone_prepare), R.color.cp_cyan, R.color.cp_sur_cyan).apply {
                    setOnClickListener { actions.prepareLook() }
                },
                kit.lp(WRAP, kit.dp(48)),
            )
            addView(head, 0, kit.lp(MATCH, WRAP))
        }
        addView(cyberware, kit.lp(MATCH, height))
    }

    fun cyber(state: CyberpunkState?): View = column(R.string.cp_title_cyber).apply {
        if (state == null) return@apply note(R.string.cp_loading)
        val known = state.cyberware.entries.groupBy { CyberpunkItems.zone(it.key) }
        CyberpunkItems.Zone.entries.forEach { zone -> known[zone]?.forEach { addView(zoneRow(zoneName(zone), it.value), kit.lp(MATCH, WRAP)) } }
        // Zone que l'application ne connaît pas : sous son nom brut, plutôt que perdue.
        known[null]?.forEach { addView(zoneRow(it.key, it.value), kit.lp(MATCH, WRAP)) }
    }

    private fun zoneRow(zone: String, slots: List<CyberpunkImplant?>): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, kit.dp(8), 0, kit.dp(8))
            addView(kit.label(zone))
            val filled = slots.filterNotNull()
            if (filled.isEmpty()) addView(kit.text(context.getString(R.string.cp_empty_slot), 17f, R.color.cp_gris))
            filled.forEach { addView(kit.text(it.name, 17f, R.color.cp_texte)) }
        }
        val line = kit.row()
        val best = slots.filterNotNull().map { CyberpunkItems.rarity(it.quality) }.maxOrNull()
        val barColor = best?.let { kit.rarityColor(it) } ?: kit.color(R.color.cp_segment_vide)
        line.addView(kit.rarityBar(barColor), kit.lp(kit.dp(5), kit.dp(30)).apply { marginEnd = kit.dp(12) })
        line.addView(col, kit.lp(0, WRAP).apply { weight = 1f })
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            alpha = if (best == null) 0.5f else 1f
            addView(line, kit.lp(MATCH, WRAP))
            addView(kit.divider(), kit.lp(MATCH, 1))
        }
    }

    fun perso(player: CyberpunkPlayer?): View = column(R.string.cp_rail_perso).apply {
        if (player == null) return@apply note(R.string.cp_loading)
        addView(kit.text(context.getString(R.string.cp_perso_cred, player.cred), 17f, R.color.cp_jaune, kit.mono))
        addView(kit.text(context.getString(R.string.cp_perso_humanity, player.humanity), 17f, R.color.cp_cyan, kit.mono), kit.lp(WRAP, WRAP).apply { topMargin = kit.dp(4) })
        addView(
            kit.text(context.getString(R.string.cp_perso_points, player.attrPoints, player.perkPoints), 15f, R.color.cp_gris, kit.mono),
            kit.lp(WRAP, WRAP).apply { topMargin = kit.dp(4); bottomMargin = kit.dp(10) },
        )
        val a = player.attrs
        listOf(R.string.cp_attr_body to a.body, R.string.cp_attr_ref to a.ref, R.string.cp_attr_tech to a.tech, R.string.cp_attr_int to a.int, R.string.cp_attr_cool to a.cool)
            .forEach { (name, value) ->
                val line = kit.row()
                line.addView(kit.text(context.getString(name), 17f), kit.lp(0, WRAP).apply { weight = 1f })
                line.addView(CyberpunkSegments(context, kit.color(R.color.cp_rouge), kit.color(R.color.cp_segment_vide), value), kit.lp(WRAP, WRAP))
                addView(line, kit.lp(MATCH, WRAP))
                addView(kit.divider(), kit.lp(MATCH, 1))
            }
    }

    private fun categoryRes(c: CyberpunkItems.Category) = when (c) {
        CyberpunkItems.Category.HEAL -> R.string.cp_cat_heal
        CyberpunkItems.Category.AMMO -> R.string.cp_cat_ammo
        CyberpunkItems.Category.WEAPON -> R.string.cp_cat_weapon
        CyberpunkItems.Category.CLOTHES -> R.string.cp_cat_clothes
        CyberpunkItems.Category.MOD -> R.string.cp_cat_mod
        CyberpunkItems.Category.OTHER -> R.string.cp_cat_other
    }

    private fun zoneName(z: CyberpunkItems.Zone): String = context.getString(
        when (z) {
            CyberpunkItems.Zone.OS -> R.string.cp_zone_os
            CyberpunkItems.Zone.FRONTAL -> R.string.cp_zone_frontal
            CyberpunkItems.Zone.EYES -> R.string.cp_zone_eyes
            CyberpunkItems.Zone.ARMS -> R.string.cp_zone_arms
            CyberpunkItems.Zone.HANDS -> R.string.cp_zone_hands
            CyberpunkItems.Zone.SKELETON -> R.string.cp_zone_skeleton
            CyberpunkItems.Zone.NERVOUS -> R.string.cp_zone_nervous
            CyberpunkItems.Zone.CARDIO -> R.string.cp_zone_cardio
            CyberpunkItems.Zone.IMMUNE -> R.string.cp_zone_immune
            CyberpunkItems.Zone.SKIN -> R.string.cp_zone_skin
            CyberpunkItems.Zone.LEGS -> R.string.cp_zone_legs
        },
    )
}
