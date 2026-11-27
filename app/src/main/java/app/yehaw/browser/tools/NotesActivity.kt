package app.yehaw.browser.tools

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.yehaw.browser.R
import java.text.DateFormat
import java.util.Date

/**
 * Offline notes. Everything is stored on the phone in [NotesStore]; nothing is sent anywhere. An edit is saved a moment after
 * typing stops and again whenever the screen is left, so closing or killing the app never loses more than a second of typing.
 */
class NotesActivity : ToolActivity() {
    private lateinit var store: NotesStore
    private lateinit var body: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    private var editor: EditText? = null
    private var currentId: Long? = null
    private var dirty = false
    private val autosave = Runnable { persist() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NotesStore(this)
        body = scaffold(R.string.tool_notes)
        val restoreId = savedInstanceState?.getLong(STATE_ID, -1L)?.takeIf { it >= 0 }
        if (restoreId != null && store.get(restoreId) != null) openEditor(restoreId) else showList("")
    }

    private fun showList(query: String) {
        persist(); handler.removeCallbacks(autosave)
        editor = null; currentId = null
        body.removeAllViews()
        val search = field(getString(R.string.notes_search_hint)).apply { setText(query) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun render() {
            list.removeAllViews()
            val notes = store.list(search.text.toString())
            if (notes.isEmpty()) {
                list.addView(text(getString(if (search.text.isNullOrBlank()) R.string.notes_empty else R.string.notes_no_match), 16f, MUTED).apply { setPadding(0, dp(16), 0, 0) })
                return
            }
            val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            for (n in notes) {
                val title = n.title.take(80)
                val b = button(title + "\n" + fmt.format(Date(n.updatedAt)), title) { openEditor(n.id) }
                b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                b.maxLines = 3
                list.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            }
        }
        body.addView(button(getString(R.string.notes_new), primary = true) { openEditor(null) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(gap(8))
        body.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(gap(8))
        body.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { render() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        render()
    }

    private fun openEditor(id: Long?) {
        body.removeAllViews()
        currentId = id
        dirty = false
        val ed = field(getString(R.string.notes_hint), multiline = true).apply {
            setText(if (id != null) store.get(id)?.text.orEmpty() else "")
            gravity = Gravity.TOP or Gravity.START
            minLines = 8
            filters = arrayOf(android.text.InputFilter.LengthFilter(NotesStore.MAX_CHARS))
        }
        editor = ed
        ed.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { dirty = true; handler.removeCallbacks(autosave); handler.postDelayed(autosave, AUTOSAVE_MS) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(label: String, action: () -> Unit) = bar.addView(button(label) { action() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        add(getString(R.string.notes_done)) { showList("") }
        add(getString(R.string.label_share)) { share() }
        add(getString(R.string.notes_copy)) { copyToClipboard(ed.text.toString()) }
        add(getString(R.string.notes_delete)) { confirmDelete() }
        body.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(gap(8))
        body.addView(ed, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(text(getString(R.string.notes_saved_on_phone), 13f, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        if (id == null) ed.requestFocus()
    }

    /** Writes the open note now. Returns false (and keeps the text on screen) if the write failed. */
    private fun persist(): Boolean {
        val ed = editor ?: return true
        if (!dirty) return true
        return try {
            val saved = store.save(currentId, ed.text.toString())
            currentId = saved
            dirty = false
            true
        } catch (e: Exception) {
            toast(R.string.notes_save_failed)
            false
        }
    }

    private fun share() {
        val t = editor?.text?.toString().orEmpty()
        if (t.isBlank()) { toast(R.string.notes_nothing_to_share); return }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), getString(R.string.label_share)))
    }

    private fun confirmDelete() {
        val id = currentId
        if (id == null && editor?.text.isNullOrBlank()) { dirty = false; showList(""); return }
        AlertDialog.Builder(this).setTitle(R.string.notes_delete_title).setMessage(R.string.notes_delete_message)
            .setPositiveButton(R.string.notes_delete) { _, _ ->
                handler.removeCallbacks(autosave)
                dirty = false
                if (id != null) store.delete(id)
                showList("")
            }.setNegativeButton(R.string.btn_cancel, null).show()
    }

    override fun onPause() { super.onPause(); handler.removeCallbacks(autosave); persist() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        persist()
        currentId?.let { outState.putLong(STATE_ID, it) }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() { if (editor != null) showList("") else super.onBackPressed() }

    override fun onDestroy() { handler.removeCallbacks(autosave); persist(); store.close(); super.onDestroy() }

    companion object { private const val STATE_ID = "note_id"; private const val AUTOSAVE_MS = 800L }
}
