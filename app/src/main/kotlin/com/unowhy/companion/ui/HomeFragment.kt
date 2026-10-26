package com.unowhy.companion.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.Computers
import com.unowhy.companion.MainActivity
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.CalendarEvent
import com.unowhy.companion.net.HomeFeatures
import com.unowhy.companion.ui.home.COLS
import com.unowhy.companion.ui.home.HomeHost
import com.unowhy.companion.ui.home.HomeLayout
import com.unowhy.companion.ui.home.HomeWidget
import com.unowhy.companion.ui.home.MAX_PAGES
import com.unowhy.companion.ui.home.Placed
import com.unowhy.companion.ui.home.ROWS
import com.unowhy.companion.ui.home.Template
import com.unowhy.companion.ui.home.WidgetGrid
import com.unowhy.companion.ui.home.WidgetType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Accueil à widgets, personnalisable comme un écran de téléphone : appui long = mode Modifier (ajouter, retirer,
 * changer la taille, glisser, modèles, pages). La disposition est propre à la tablette ([HomeLayout]) ; l'agenda est
 * chargé ici une fois pour tous les widgets qui l'affichent, toutes les 5 min, le reste chaque minute.
 */
class HomeFragment : Fragment(), HomeHost, WidgetGrid.Listener {

    private lateinit var grid: WidgetGrid
    private lateinit var scroll: ScrollView
    private lateinit var editBar: View
    private lateinit var dots: LinearLayout
    private lateinit var pageButton: View

    private lateinit var layout: HomeLayout
    /** Disposition au moment d'entrer dans le mode Modifier : « Annuler » y revient. */
    private var backup: HomeLayout? = null
    private val widgets = LinkedHashMap<Placed, HomeWidget>()
    private var page = 0
    /** Aucun ordinateur appairé : les widgets qui en ont besoin proposent de le connecter. */
    private var noComputer = false
    private var loop: Job? = null
    /** Entre onStart et onStop (l'état du cycle de vie n'y est pas encore STARTED). */
    private var started = false
    /** « Relier Outlook / Google » touché : l'agenda relié sur l'ordinateur apparaît dans la minute. */
    private var waitingLink = false

    override var events: List<CalendarEvent> = emptyList()
    override var features = HomeFeatures()
    override var calendarNote = ""
    override var chosenId: String? = null
    override val scope: CoroutineScope get() = viewLifecycleOwner.lifecycleScope
    override val main get() = activity as? MainActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        grid = view.findViewById(R.id.home_grid)
        scroll = view.findViewById(R.id.home_scroll)
        editBar = view.findViewById(R.id.home_edit_bar)
        dots = view.findViewById(R.id.home_dots)
        pageButton = view.findViewById(R.id.home_edit_page)
        grid.listener = this

        view.findViewById<View>(R.id.home_edit_add).setOnClickListener { onAddAt(-1, -1) }
        view.findViewById<View>(R.id.home_edit_templates).setOnClickListener { chooseTemplate() }
        pageButton.setOnClickListener { addPage() }
        view.findViewById<View>(R.id.home_edit_cancel).setOnClickListener { endEdit(keep = false) }
        view.findViewById<View>(R.id.home_edit_done).setOnClickListener { endEdit(keep = true) }

        // Écran recréé quand l'ordinateur change (MainActivity.onComputerChanged) : lu une fois ici.
        noComputer = Computers.all(requireContext()).isEmpty()
        layout = HomeLayout.load(requireContext())
        build()

        // Le panneau document peut réduire la largeur de moitié : 2 colonnes, la page défile.
        scroll.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val narrow = r - l < dp(880)
            if (narrow && grid.editing) endEdit(keep = true)
            grid.post {
                grid.narrow = narrow
                grid.viewportHeight = b - t
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        activate()
        if (!isHidden) {
            startLoop()
            animateIn()
        }
    }

    override fun onStop() {
        started = false
        widgets.values.forEach { it.setActive(false) }
        loop?.cancel()
        super.onStop()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        if (hidden) {
            if (grid.editing) endEdit(keep = true)
            loop?.cancel()
        } else {
            startLoop()
            animateIn()
        }
        activate()
    }

    /** Les widgets de la page arrivent l'un après l'autre, de haut en bas puis de gauche à droite. */
    private fun animateIn() {
        widgets.keys.filter { it.page == page }.sortedWith(compareBy({ it.row }, { it.col }))
            .forEachIndexed { i, placed -> widgets[placed]?.let { Look.enter(it.view, i) } }
    }

    // --- Widgets

    /** (Re)crée tous les widgets d'après la disposition. */
    private fun build() {
        widgets.values.forEach { it.setActive(false) }
        widgets.clear()
        grid.removeAllViews()
        layout.widgets.forEach { addWidget(it) }
        page = page.coerceIn(0, layout.pages - 1)
        grid.page = page
        renderDots()
        activate()
    }

    private fun addWidget(placed: Placed): HomeWidget {
        val widget = HomeWidget.create(requireContext(), this, placed, noComputer)
        widget.view.tag = placed
        widget.applySize()
        widget.onCalendar()
        widget.onMinute()
        grid.addView(widget.view)
        widgets[placed] = widget
        return widget
    }

    /** Seuls les widgets de la page à l'écran se mettent à jour en continu (Focus, lecture en cours). */
    private fun activate() {
        val visible = started && !isHidden
        widgets.forEach { (placed, widget) -> widget.setActive(visible && placed.page == page) }
    }

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            var tick = 0
            while (true) {
                if (!noComputer && (tick % 5 == 0 || waitingLink) && showsCalendar()) {
                    fetchCalendar()
                    renderCalendar()
                }
                for (widget in widgets.values.toList()) widget.load()
                widgets.values.forEach { it.onMinute() }
                // Sans widget Tâches, la pastille de la barre latérale n'a plus rien à compter.
                if (widgets.keys.none { it.type == WidgetType.TASKS }) main?.setPendingTasks(0)
                tick++
                delay(60_000)
            }
        }
    }

    /** Widgets recréés (Annuler, modèle) : leurs données tout de suite, sans attendre la minute suivante. */
    private fun refreshAll() {
        scope.launch {
            if (!noComputer && showsCalendar()) {
                fetchCalendar()
                renderCalendar()
            }
            for (widget in widgets.values.toList()) widget.load()
        }
    }

    private fun showsCalendar() = widgets.keys.any { it.type == WidgetType.AGENDA || it.type == WidgetType.NEXT }

    private suspend fun fetchCalendar() {
        val client = BackendClient(requireContext())
        runCatching { features = client.deck.homeFeatures() }
        if (features.linked) waitingLink = false
        try {
            val days = client.calendar.days(AGENDA_DAYS)
            events = days.events
            calendarNote = days.error ?: if (days.source == "mock") getString(R.string.calendar_mock) else ""
        } catch (e: Exception) {
            calendarNote = errorText(e)
        }
    }

    // --- HomeHost

    override fun renderCalendar() = widgets.values.forEach { it.onCalendar() }

    override fun reloadCalendar() {
        scope.launch {
            fetchCalendar()
            renderCalendar()
        }
    }

    /** Nouvel événement (`event` null) ou modification d'un événement de l'agenda personnel. */
    override fun editEvent(event: CalendarEvent?) {
        val ctx = requireContext()
        val localId = event?.localId
        val save = { draft: EventDraft ->
            scope.launch {
                try {
                    BackendClient(ctx).calendar.saveEvent(localId, draft.title, draft.startMs, draft.allDay, draft.remindBefore)
                    main?.showBanner(getString(R.string.event_saved, draft.title), 4)
                    fetchCalendar()
                    renderCalendar()
                } catch (e: Exception) {
                    main?.showBanner(ctx.errorText(e), 6)
                }
            }
            Unit
        }
        val delete = localId?.let {
            {
                scope.launch {
                    try {
                        BackendClient(ctx).calendar.deleteEvent(it)
                        fetchCalendar()
                        renderCalendar()
                    } catch (e: Exception) {
                        main?.showBanner(ctx.errorText(e), 6)
                    }
                }
                Unit
            }
        }
        EventDialog(ctx, save, delete).show(event)
    }

    /** Agenda relié : « Mes agendas » (voir, délier, en relier un autre) ; sinon droit à la page de l'ordinateur. */
    override fun showCalendars() {
        if (features.linked) CalendarsDialog(requireContext(), scope, onChanged = { reloadCalendar() }, onAdd = { openAgendaSetup() }).show()
        else openAgendaSetup()
    }

    /** Ouvre sur l'ordinateur la page du compagnon, à la section Agenda (lien ICS à coller). */
    private fun openAgendaSetup() {
        scope.launch {
            try {
                BackendClient(requireContext()).system.openLocalPage("agenda")
                waitingLink = true
                main?.showBanner(getString(R.string.agenda_setup_opened), 8)
            } catch (e: Exception) {
                main?.showBanner(errorText(e), 6)
            }
        }
    }

    /** Réglage d'un widget changé hors mode Modifier ; en mode Modifier, « Terminé » enregistre. */
    override fun save() {
        if (!grid.editing) HomeLayout.save(requireContext(), layout)
    }

    // --- Pages

    private fun showPage(index: Int) {
        page = index.coerceIn(0, layout.pages - 1)
        grid.page = page
        renderDots()
        activate()
    }

    private fun renderDots() {
        dots.removeAllViews()
        dots.visibility = if (layout.pages > 1 || grid.editing) View.VISIBLE else View.GONE
        for (i in 0 until layout.pages) {
            dots.addView(View(requireContext()).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(themeColor(context, if (i == page) R.attr.c_accent else R.attr.c_text_faint))
                }
                setOnClickListener { showPage(i) }
            }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginStart = dp(8); marginEnd = dp(8) })
        }
        pageButton.visibility = if (layout.pages < MAX_PAGES) View.VISIBLE else View.GONE
    }

    override fun onPageSwipe(delta: Int) {
        val next = page + delta
        if (next in 0 until layout.pages) showPage(next)
    }

    // --- Mode Modifier

    override fun onEnterEdit() {
        backup = layout.copy()
        grid.editing = true
        editBar.visibility = View.VISIBLE
        renderDots()
    }

    private fun endEdit(keep: Boolean) {
        if (!grid.editing) return
        grid.editing = false
        editBar.visibility = View.GONE
        val previous = backup
        backup = null
        if (!keep && previous != null) {
            layout = previous
            build()
            refreshAll()
            return
        }
        layout.compact()
        HomeLayout.save(requireContext(), layout)
        showPage(page)
    }

    private fun addPage() {
        if (layout.pages >= MAX_PAGES) return
        layout.pages++
        showPage(layout.pages - 1)
    }

    override fun onAddAt(col: Int, row: Int) {
        val ctx = requireContext()
        val types = WidgetType.entries
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.home_gallery_title)
            .setView(ScrollView(ctx).apply { addView(list) })
            .setNegativeButton(R.string.cancel, null)
            .create()
        for (type in types) list.addView(galleryRow(type) {
            dialog.dismiss()
            place(type, col, row)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        dialog.showImmersive()
    }

    private fun galleryRow(type: WidgetType, onClick: () -> Unit): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.row_bg)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(ImageView(ctx).apply {
                setImageResource(type.icon)
                setColorFilter(themeColor(ctx, R.attr.c_accent))
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    setText(type.title)
                    textSize = 18f
                    setTextColor(themeColor(ctx, R.attr.c_text))
                })
                addView(TextView(ctx).apply {
                    val sizes = type.sizes.joinToString(", ") { getString(it.label).lowercase() }
                    text = getString(R.string.home_gallery_detail, getString(type.description), sizes)
                    textSize = 14f
                    setTextColor(themeColor(ctx, R.attr.c_text_dim))
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
            setOnClickListener { onClick() }
        }
    }

    /** Pose le widget à la case touchée si possible, sinon à la première place libre de la page, dans la plus grande taille qui tient. */
    private fun place(type: WidgetType, col: Int, row: Int) {
        val spot = type.sizes.firstNotNullOfOrNull { size ->
            if (col >= 0) {
                val c = col.coerceAtMost(COLS - size.w)
                val r = row.coerceAtMost(ROWS - size.h)
                if (layout.fits(page, c, r, size)) return@firstNotNullOfOrNull Triple(size, c, r)
            }
            layout.freeSpot(page, size)?.let { (c, r) -> Triple(size, c, r) }
        }
        if (spot == null) {
            Toast.makeText(requireContext(), R.string.home_no_room, Toast.LENGTH_LONG).show()
            return
        }
        val placed = Placed(type, spot.first, page, spot.second, spot.third)
        layout.widgets += placed
        val widget = addWidget(placed)
        grid.showPage()
        activate()
        scope.launch { widget.load() }
        // Touches et météo n'affichent rien tant qu'on ne les a pas réglées.
        if (type == WidgetType.KEYS || type == WidgetType.WEATHER) widget.showSettings()
    }

    override fun onRemove(placed: Placed) {
        layout.widgets.remove(placed)
        widgets.remove(placed)?.let {
            it.setActive(false)
            grid.removeView(it.view)
        }
        grid.invalidate()
    }

    /**
     * Taille suivante qui tient : d'abord depuis la même case, sinon étendue vers la gauche ou le haut, en gardant
     * toujours la case d'origine (le widget ne saute pas ailleurs).
     */
    override fun onCycleSize(placed: Placed) {
        val sizes = placed.type.sizes
        val start = sizes.indexOf(placed.size)
        for (step in 1 until sizes.size) {
            val size = sizes[(start + step) % sizes.size]
            val anchors = (placed.row downTo placed.row - size.h + 1).flatMap { r -> (placed.col downTo placed.col - size.w + 1).map { c -> c to r } }
            val spot = anchors.firstOrNull { (c, r) -> layout.fits(placed.page, c, r, size, ignore = placed) }
            if (spot != null) {
                placed.size = size
                placed.col = spot.first
                placed.row = spot.second
                widgets[placed]?.applySize()
                grid.requestLayout()
                grid.invalidate()
                return
            }
        }
        Toast.makeText(requireContext(), R.string.home_no_room_size, Toast.LENGTH_LONG).show()
    }

    override fun onSettings(placed: Placed) {
        widgets[placed]?.showSettings()
    }

    override fun hasSettings(placed: Placed) = widgets[placed]?.hasSettings == true

    override fun canMove(placed: Placed, col: Int, row: Int) = layout.fits(placed.page, col, row, placed.size, ignore = placed)

    override fun onMove(placed: Placed, col: Int, row: Int) {
        placed.col = col
        placed.row = row
        grid.requestLayout()
        grid.invalidate()
    }

    private fun chooseTemplate() {
        val ctx = requireContext()
        val templates = Template.entries
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.home_templates_title)
            .setView(list)
            .setNegativeButton(R.string.cancel, null)
            .create()
        for (template in templates) list.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.row_bg)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(TextView(ctx).apply { setText(template.title); textSize = 18f; setTextColor(themeColor(ctx, R.attr.c_text)) })
            addView(TextView(ctx).apply { setText(template.description); textSize = 14f; setTextColor(themeColor(ctx, R.attr.c_text_dim)) })
            setOnClickListener {
                dialog.dismiss()
                // Le modèle remplace l'accueil ; « Annuler » ramène celui d'avant.
                layout = template.build()
                page = 0
                build()
                refreshAll()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        dialog.showImmersive()
    }

    private companion object {
        /** Aujourd'hui et les 7 jours suivants : « mardi soir » se voit dès aujourd'hui. */
        const val AGENDA_DAYS = 8
    }
}
