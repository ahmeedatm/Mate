package com.unowhy.companion.ui

import android.Manifest
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.MainActivity
import com.unowhy.companion.R
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.DocEntry
import kotlinx.coroutines.launch

/**
 * Depuis l'ordi : d'abord un choix expliqué (fichiers du dossier partagé, page du navigateur de l'ordi, ou
 * presse-papiers), puis la liste des fichiers (un fichier s'ouvre dans le panneau latéral) ou l'écran Presse-papiers
 * ([ClipboardView]).
 */
class FromComputerFragment : Fragment() {

    private enum class Screen { CHOICE, FILES, CLIPBOARD }

    private lateinit var list: ListView
    private lateinit var path: TextView
    private lateinit var count: TextView
    private lateinit var status: TextView
    private lateinit var up: ImageButton
    private lateinit var refresh: ImageButton
    private lateinit var back: Button
    private lateinit var choice: View
    private lateinit var files: View
    private lateinit var clipboardRoot: View
    private lateinit var clipboard: ClipboardView
    private lateinit var adapter: DocAdapter

    private var dir = ""
    private var screen = Screen.CHOICE
    /** Liste des fichiers affichée. */
    private val browsing get() = screen == Screen.FILES

    /** Dicter (Presse-papiers) sans le micro autorisé : la dictée reprend si l'utilisateur accepte. */
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (view == null || screen != Screen.CLIPBOARD || isHidden) return@registerForActivityResult
        if (granted) clipboard.dictate() else clipboard.micDenied()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_from_computer, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        list = view.findViewById(R.id.docs_list)
        path = view.findViewById(R.id.docs_path)
        count = view.findViewById(R.id.docs_count)
        status = view.findViewById(R.id.docs_status)
        up = view.findViewById(R.id.docs_up)
        refresh = view.findViewById(R.id.docs_refresh)
        back = view.findViewById(R.id.docs_back)
        choice = view.findViewById(R.id.docs_choice)
        files = view.findViewById(R.id.docs_files)
        clipboardRoot = view.findViewById(R.id.docs_clipboard)
        clipboard = ClipboardView(this, clipboardRoot) { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        adapter = DocAdapter()
        list.adapter = adapter

        list.setOnItemClickListener { _, _, position, _ ->
            val entry = adapter.getItem(position) ?: return@setOnItemClickListener
            if (entry.kind == "dir") load(entry.path) else (activity as? MainActivity)?.openDocument(entry)
        }
        up.setOnClickListener { load(dir.substringBeforeLast('/', "")) }
        refresh.setOnClickListener { load(dir) }
        back.setOnClickListener { show(Screen.CHOICE) }
        view.findViewById<View>(R.id.docs_choose_files).setOnClickListener { show(Screen.FILES) }
        view.findViewById<View>(R.id.docs_choose_web).setOnClickListener { (requireActivity() as MainActivity).openComputerPage() }
        view.findViewById<View>(R.id.docs_choose_clipboard).setOnClickListener { show(Screen.CLIPBOARD) }

        show(Screen.CHOICE)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        if (!hidden && browsing) load(dir)
        // Presse-papiers : on revient au choix quand l'écran est caché (comme la caméra), pour qu'il ne se relise jamais
        // sans un toucher sur la tablette (ouverture de l'écran ou Actualiser), même si le mode revient d'un ordre du PC.
        if (hidden && screen == Screen.CLIPBOARD) show(Screen.CHOICE)
    }

    override fun onStop() {
        // Appli en arrière-plan : le micro ne reste pas ouvert.
        if (view != null) clipboard.stopDictation()
        super.onStop()
    }

    override fun onDestroyView() {
        clipboard.release()
        super.onDestroyView()
    }

    private fun show(next: Screen) {
        if (screen == Screen.CLIPBOARD && next != Screen.CLIPBOARD) clipboard.stop()
        screen = next
        choice.visibility = if (next == Screen.CHOICE) View.VISIBLE else View.GONE
        files.visibility = if (next == Screen.FILES) View.VISIBLE else View.GONE
        clipboardRoot.visibility = if (next == Screen.CLIPBOARD) View.VISIBLE else View.GONE
        refresh.visibility = files.visibility
        back.visibility = if (next == Screen.CHOICE) View.GONE else View.VISIBLE
        when (next) {
            Screen.FILES -> load("")
            Screen.CHOICE -> {
                path.setText(R.string.docs_title)
                count.setText(R.string.docs_subtitle)
                up.visibility = View.GONE
            }
            Screen.CLIPBOARD -> {
                path.setText(R.string.clip_choose)
                count.setText(R.string.clip_subtitle)
                up.visibility = View.GONE
                clipboard.read()
            }
        }
    }

    private fun load(target: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val listing = BackendClient(requireContext()).docs.list(target)
                if (!browsing) return@launch
                dir = listing.dir
                path.text = if (dir.isEmpty()) getString(R.string.docs_root) else dir
                up.visibility = if (dir.isEmpty()) View.GONE else View.VISIBLE
                count.text = getString(
                    R.string.docs_count_free,
                    resources.getQuantityString(R.plurals.items_count, listing.entries.size, listing.entries.size),
                    tabletFreeSpace(),
                )
                adapter.clear()
                adapter.addAll(listing.entries)
                showStatus(if (listing.entries.isEmpty()) getString(R.string.empty_folder) else null)
            } catch (e: Exception) {
                if (browsing) showStatus(errorText(e))
            }
        }
    }

    private fun showStatus(message: String?) {
        status.text = message
        status.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    /** Place libre sur la tablette (documents ouverts, photos en attente) : « 5,2 Go ». */
    private fun tabletFreeSpace(): String =
        Formatter.formatShortFileSize(requireContext(), StatFs(Environment.getDataDirectory().path).availableBytes)

    private inner class DocAdapter : ArrayAdapter<DocEntry>(requireContext(), 0) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_doc, parent, false)
            val entry = getItem(position)!!
            val (icon, color) = when (entry.kind) {
                "dir" -> R.drawable.ic_folder to R.attr.c_accent
                "pdf" -> R.drawable.ic_doc to R.attr.c_ko
                "image" -> R.drawable.ic_image to R.attr.c_ok
                "text" -> R.drawable.ic_doc to R.attr.c_text_dim
                else -> R.drawable.ic_file to R.attr.c_text_faint
            }
            val tint = themeColor(context, color)
            view.findViewById<ImageView>(R.id.doc_icon).apply {
                setImageResource(icon)
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(tint))
                background.mutate().setTint(ColorUtils.setAlphaComponent(tint, 0x2E))
            }
            view.findViewById<View>(R.id.doc_chevron).visibility = if (entry.kind == "dir") View.VISIBLE else View.INVISIBLE
            view.findViewById<TextView>(R.id.doc_name).text = entry.name
            val meta = view.findViewById<TextView>(R.id.doc_meta)
            meta.text = if (entry.kind == "dir") "" else
                Formatter.formatShortFileSize(context, entry.size) + " · " +
                    DateUtils.getRelativeTimeSpanString(entry.mtimeMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            meta.visibility = if (meta.text.isEmpty()) View.GONE else View.VISIBLE
            return view
        }
    }
}
