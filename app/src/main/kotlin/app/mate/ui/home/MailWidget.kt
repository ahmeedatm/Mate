package app.mate.ui.home

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.ArrayRes
import androidx.core.view.isVisible
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.Mail
import app.mate.net.MailAccount
import app.mate.net.runCatchingCancellable
import app.mate.ui.errorText
import app.mate.ui.themeFont
import kotlinx.coroutines.launch

/** Exemple des Mails pour le catalogue : des noms de boîtes et leur nombre de non lus, dans le même ordre. */
class MailSample(@ArrayRes val labels: Int, val unread: List<Int>) : WidgetSample

/**
 * Nombre de mails non lus (boîte de réception de tous les comptes Microsoft et Google connectés sur l'ordinateur) ;
 * un toucher ouvre la boîte sur l'ordinateur. Les jetons restent sur l'ordinateur : la tablette ne reçoit que des nombres.
 * Relu chaque minute. La forme posée ([MailShape]) dit la taille du nombre et s'il est détaillé boîte par boîte.
 */
class MailWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val disc = hueDisc(context, R.drawable.ic_mail, R.attr.c_hue_blue)
    private val title = cardTitle(R.string.widget_mail)
    private val head = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(disc, hueDiscParams(context, side = false))
        addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
    }
    private val count = label(36f).apply {
        typeface = themeFont(context, R.attr.c_font_light)
        maxLines = 1
        includeFontPadding = false
    }
    private val caption = label(14f, R.attr.c_text_dim).apply { maxLines = 1 }
    private val number = LinearLayout(context).apply {
        gravity = Gravity.BOTTOM
        addView(count)
        addView(caption, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
    }
    private val status = label(14f, R.attr.c_text_dim).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val boxes = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(number)
        addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(boxes, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    override val view = LinearLayout(context).asCard(16).apply {
        addView(head)
        addView(content)
        setOnClickListener { open() }
        isClickable = false // rien à ouvrir tant qu'aucun compte n'a répondu
    }

    private var mail: Mail? = null
    private var error: String? = null
    private var opening = false
    private var shape = MailShape.of(placed.size)

    /** Aperçu du catalogue : ses boîtes restent affichées, sans réseau ni ouverture. */
    private var preview = false

    override fun setActive(active: Boolean) {
        if (active) host.scope.launch { load() }
    }

    override suspend fun load() {
        if (preview) return
        runCatchingCancellable { BackendClient(context).mail.unread() }
            .onSuccess { mail = it; error = null; render() }
            .onFailure { error = context.errorText(it); render() }
    }

    override fun applySize() {
        shape = MailShape.of(placed.size)
        view.orientation = if (shape.side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        view.gravity = if (shape.side) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
        title.isVisible = shape.title
        disc.layoutParams = hueDiscParams(context, shape.side)
        content.layoutParams = if (shape.side) {
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dp(16) }
        } else {
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) }
        }
        count.textSize = shape.numberSp
        caption.textSize = shape.captionSp
        // Ligne : le détail colle au nombre, la rangée est basse.
        (boxes.layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (shape.side) 2 else 8)
        render()
    }

    override fun showPreview(sample: WidgetSample) {
        val example = sample as? MailSample ?: return
        preview = true
        val labels = context.resources.getStringArray(example.labels)
        mail = Mail(labels.mapIndexed { i, label -> MailAccount(SAMPLE_PROVIDER, label, example.unread.getOrNull(i), null) })
        error = null
        render()
    }

    /** Ce qui est dessiné : une relecture qui ne change rien ne refait pas les vues. */
    private var drawn: List<Any?>? = null

    private fun render() {
        val current = mail
        val note = when (mailNote(current, error != null)) {
            MailNote.ERROR -> error
            MailNote.NO_ACCOUNT -> context.getString(R.string.widget_mail_empty)
            MailNote.ACCOUNT_ERROR -> current?.accounts?.firstNotNullOfOrNull { it.error }
            null -> null
        }
        val list = current?.let(::mailBoxes).orEmpty()
        val next = listOf(note, shape, placed.size, list, current?.target, preview)
        if (next == drawn) return
        drawn = next
        val shown = current?.takeIf { it.hasCount }?.total
        val none = context.getString(R.string.widget_mail_none)
        count.text = shown?.toString() ?: none
        caption.text = context.resources.getQuantityString(R.plurals.widget_mail_unread, shown ?: 0)
        caption.isVisible = shown != null
        status.text = note
        status.isVisible = note != null
        renderBoxes(list)
        val target = current?.target?.takeIf { !preview }
        view.isClickable = target != null
        // Lu à voix haute : le nombre, puis le geste (le tiret seul ne dit rien), puis le détail affiché par boîte.
        val said = if (target != null && shown != null) context.getString(R.string.widget_mail_a11y, shown) else note
        val detail = if (boxes.isVisible) list.joinToString(", ") { boxText(it) } else null
        view.contentDescription = listOfNotNull(said, detail).joinToString(". ").ifEmpty { null }
    }

    /** « Gmail 9 », ou « Outlook indisponible » pour une boîte qui n'a pas répondu (la cause suit en colonne). */
    private fun boxText(box: MailBox) = box.count?.let { "${box.label} $it" } ?: context.getString(R.string.widget_mail_box_down, box.label)

    /** Une ligne par boîte (son nom, son nombre, ou sa panne et sa cause) ; en Ligne, toutes sur une ligne. Rien pour une seule boîte. */
    private fun renderBoxes(list: List<MailBox>) {
        boxes.removeAllViews()
        boxes.isVisible = shape.accounts && list.size > 1
        if (!boxes.isVisible) return
        if (shape.side) {
            boxes.addView(label(shape.boxSp, R.attr.c_text_dim).apply {
                text = list.joinToString(" · ", transform = ::boxText)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            return
        }
        list.forEach { box ->
            boxes.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(BOX_ROW_DP)
                addView(label(shape.boxSp, R.attr.c_text_dim).apply {
                    text = box.label
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(label(shape.boxSp, R.attr.c_text).apply {
                    text = box.count ?: context.getString(R.string.widget_mail_box_down_short)
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // La cause d'une panne, sur sa propre ligne sous le nom : lisible, pas seulement une couleur.
            box.error?.takeIf { box.count == null }?.let { cause ->
                boxes.addView(label(shape.boxSp - 1f, R.attr.c_text_dim).apply {
                    text = cause
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(4) })
            }
        }
    }

    private fun open() {
        if (preview) return
        val target = mail?.target ?: return
        if (opening) return
        opening = true
        host.scope.launch {
            runCatchingCancellable { BackendClient(context).mail.open(target.provider) }
                .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
            opening = false
        }
    }

    private companion object {
        /** Hauteur d'une ligne de boîte. */
        const val BOX_ROW_DP = 32

        /** Fournisseur des boîtes de l'aperçu : rien ne s'ouvre, il ne sert qu'à remplir le modèle. */
        const val SAMPLE_PROVIDER = "google"
    }
}
