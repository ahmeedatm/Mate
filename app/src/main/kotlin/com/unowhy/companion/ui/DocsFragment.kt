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

/** Documents : parcourt le dossier partagé du Mac ; un fichier s'ouvre dans le panneau latéral. */
class DocsFragment : Fragment() {

    private lateinit var list: ListView
    private lateinit var path: TextView
    private lateinit var count: TextView
    private lateinit var status: TextView
    private lateinit var up: ImageButton
    private lateinit var adapter: DocAdapter

    private var dir = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_docs, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        list = view.findViewById(R.id.docs_list)
        path = view.findViewById(R.id.docs_path)
        count = view.findViewById(R.id.docs_count)
        status = view.findViewById(R.id.docs_status)
        up = view.findViewById(R.id.docs_up)
        adapter = DocAdapter()
        list.adapter = adapter

        list.setOnItemClickListener { _, _, position, _ ->
            val entry = adapter.getItem(position) ?: return@setOnItemClickListener
            if (entry.kind == "dir") load(entry.path) else (activity as? MainActivity)?.openDocument(entry)
        }
        up.setOnClickListener { load(dir.substringBeforeLast('/', "")) }
        view.findViewById<ImageButton>(R.id.docs_refresh).setOnClickListener { load(dir) }
        view.findViewById<Button>(R.id.docs_web).setOnClickListener { (requireActivity() as MainActivity).openComputerPage() }

        load("")
    }

    override fun onHiddenChanged(hidden: Boolean) {
        if (!hidden) load(dir)
    }

    private fun load(target: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val listing = BackendClient(requireContext()).docs(target)
                dir = listing.dir
                path.text = if (dir.isEmpty()) getString(R.string.docs_root) else dir
                up.isEnabled = dir.isNotEmpty()
                up.alpha = if (dir.isEmpty()) 0.4f else 1f
                count.text = getString(
                    R.string.docs_count_free,
                    resources.getQuantityString(R.plurals.items_count, listing.entries.size, listing.entries.size),
                    tabletFreeSpace(),
                )
                adapter.clear()
                adapter.addAll(listing.entries)
                showStatus(if (listing.entries.isEmpty()) getString(R.string.empty_folder) else null)
            } catch (e: Exception) {
                showStatus(requireContext().errorText(e))
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
                "dir" -> R.drawable.ic_folder to R.color.accent
                "pdf" -> R.drawable.ic_doc to R.color.ko
                "image" -> R.drawable.ic_image to R.color.ok
                "text" -> R.drawable.ic_doc to R.color.text_dim
                else -> R.drawable.ic_file to R.color.text_faint
            }
            val tint = ContextCompat.getColor(context, color)
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
