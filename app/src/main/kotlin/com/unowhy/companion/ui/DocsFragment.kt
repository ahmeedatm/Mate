package com.unowhy.companion.ui

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
 * Depuis l'ordi : d'abord un choix expliqué (fichiers du dossier partagé, ou page du navigateur de l'ordi),
 * puis la liste des fichiers ; un fichier s'ouvre dans le panneau latéral.
 */
class DocsFragment : Fragment() {

    private lateinit var list: ListView
    private lateinit var path: TextView
    private lateinit var count: TextView
    private lateinit var status: TextView
    private lateinit var up: ImageButton
    private lateinit var refresh: ImageButton
    private lateinit var back: Button
    private lateinit var choice: View
    private lateinit var files: View
    private lateinit var adapter: DocAdapter

    private var dir = ""
    /** Liste des fichiers affichée (sinon, l'écran de choix). */
    private var browsing = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_docs, container, false)

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
        adapter = DocAdapter()
        list.adapter = adapter

        list.setOnItemClickListener { _, _, position, _ ->
            val entry = adapter.getItem(position) ?: return@setOnItemClickListener
            if (entry.kind == "dir") load(entry.path) else (activity as? MainActivity)?.openDocument(entry)
        }
        up.setOnClickListener { load(dir.substringBeforeLast('/', "")) }
        refresh.setOnClickListener { load(dir) }
        back.setOnClickListener { browse(false) }
        view.findViewById<View>(R.id.docs_choose_files).setOnClickListener { browse(true) }
        view.findViewById<View>(R.id.docs_choose_web).setOnClickListener { (requireActivity() as MainActivity).openComputerPage() }

        browse(false)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        if (!hidden && browsing) load(dir)
    }

    private fun browse(on: Boolean) {
        browsing = on
        choice.visibility = if (on) View.GONE else View.VISIBLE
        files.visibility = if (on) View.VISIBLE else View.GONE
        refresh.visibility = files.visibility
        back.visibility = files.visibility
        if (on) {
            load("")
        } else {
            path.setText(R.string.docs_title)
            count.setText(R.string.docs_subtitle)
            up.visibility = View.GONE
        }
    }

    private fun load(target: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val listing = BackendClient(requireContext()).docs(target)
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
                if (browsing) showStatus(requireContext().errorText(e))
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
