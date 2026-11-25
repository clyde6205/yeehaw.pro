package co.yehaw.browser.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import co.yehaw.browser.R

data class Row(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val badge: String? = null,
    val actionLabel: String? = null,
)

/** One reusable list dialog for tabs, offline pages, downloads and accounts. */
class RowListDialog(private val activity: Activity) {

    private var dialog: AlertDialog? = null
    private var adapter: RowAdapter? = null
    private var emptyView: TextView? = null
    private var emptyText: String = ""

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show(
        title: String,
        rows: List<Row>,
        emptyText: String,
        onClick: (Row) -> Unit,
        onAction: ((Row) -> Unit)? = null,
        positive: Pair<String, () -> Unit>? = null,
        neutral: Pair<String, () -> Unit>? = null,
    ) {
        dismiss()
        this.emptyText = emptyText
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_list, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = title
        emptyView = view.findViewById(R.id.dialogEmpty)
        val list = view.findViewById<RecyclerView>(R.id.dialogList)
        list.layoutManager = LinearLayoutManager(activity)
        adapter = RowAdapter(rows, { row -> onClick(row) }, onAction?.let { a -> { row: Row -> a(row) } })
        list.adapter = adapter
        updateEmpty(rows)

        val builder = AlertDialog.Builder(activity).setView(view)
            .setNegativeButton(R.string.close, null)
        positive?.let { (label, action) -> builder.setPositiveButton(label) { _, _ -> action() } }
        neutral?.let { (label, action) -> builder.setNeutralButton(label) { _, _ -> action() } }
        dialog = builder.create().also { it.show() }
    }

    fun update(rows: List<Row>) {
        adapter?.submit(rows)
        updateEmpty(rows)
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun updateEmpty(rows: List<Row>) {
        emptyView?.apply {
            text = emptyText
            visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private class RowAdapter(
        private var rows: List<Row>,
        private val onClick: (Row) -> Unit,
        private val onAction: ((Row) -> Unit)?,
    ) : RecyclerView.Adapter<RowAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.rowTitle)
            val subtitle: TextView = v.findViewById(R.id.rowSubtitle)
            val badge: TextView = v.findViewById(R.id.rowBadge)
            val action: TextView = v.findViewById(R.id.rowAction)
        }

        fun submit(newRows: List<Row>) {
            rows = newRows
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val row = rows[position]
            h.title.text = row.title
            h.subtitle.text = row.subtitle.orEmpty()
            h.subtitle.visibility = if (row.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
            h.badge.text = row.badge.orEmpty()
            h.badge.visibility = if (row.badge.isNullOrBlank()) View.GONE else View.VISIBLE
            if (row.actionLabel != null && onAction != null) {
                h.action.visibility = View.VISIBLE
                h.action.text = row.actionLabel
                h.action.setOnClickListener { onAction.invoke(row) }
            } else {
                h.action.visibility = View.GONE
            }
            h.itemView.setOnClickListener { onClick(row) }
        }
    }
}
