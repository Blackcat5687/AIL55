package com.notekeep.local.graph

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.notekeep.local.R
import com.notekeep.local.data.AppDatabase
import com.notekeep.local.databinding.ActivityGraphBinding
import com.notekeep.local.databinding.BottomsheetGraphSettingsBinding
import com.notekeep.local.databinding.ItemGraphGroupRowBinding
import com.notekeep.local.ui.NoteEditActivity
import kotlinx.coroutines.launch

class GraphActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGraphBinding
    private var hideOrphans = false
    private var showTags = true
    private var showGhosts = true
    private val groups = mutableListOf<GraphGroup>()

    private val groupPalette = intArrayOf(
        0xFFE57373.toInt(), 0xFF64B5F6.toInt(), 0xFFFFD54F.toInt(),
        0xFF81C784.toInt(), 0xFFBA68C8.toInt(), 0xFF4DB6AC.toInt()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGraphBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.graphView.onNoteTapped = { noteId ->
            val intent = Intent(this, NoteEditActivity::class.java)
            intent.putExtra(NoteEditActivity.EXTRA_NOTE_ID, noteId)
            startActivity(intent)
        }

        binding.fabGraphSettings.setOnClickListener { showSettingsSheet() }
        binding.fabBackToGlobal.setOnClickListener {
            binding.graphView.showGlobalGraph()
        }
        binding.graphView.onGraphModeChanged = { updateLocalGraphChrome() }

        loadGraph()
    }

    /** Shows/hides the "back to global graph" button and toolbar subtitle depending on whether
     * Local Graph mode (long-press a node) is currently active. */
    private fun updateLocalGraphChrome() {
        val isLocal = binding.graphView.isShowingLocalGraph
        binding.fabBackToGlobal.visibility = if (isLocal) android.view.View.VISIBLE else android.view.View.GONE
        supportActionBar?.subtitle = if (isLocal) getString(R.string.graph_local_mode) else null
    }

    /** True right after onCreate's own loadGraph() call, so the very next onResume (which Android
     * always fires immediately after onCreate on a fresh launch) doesn't reload and reset the
     * simulation's honeycomb layout + alpha a split-second after it just started — that double
     * load was cancelling the physics before it ever got to actually settle, which is what made
     * nodes look permanently stuck overlapping in their raw starting positions. */
    private var justCreated = true

    override fun onResume() {
        super.onResume()
        if (justCreated) {
            justCreated = false
        } else {
            // a real return to this screen (e.g. after editing a note) - notes may have changed,
            // so refresh from the DB. loadGraph() itself only touches the Global Graph; it leaves
            // an active Local Graph focus alone below.
            loadGraph()
        }
    }

    private fun loadGraph() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val notes = db.noteDao().getAllOnce()
            val labels = db.labelDao().getAllOnce()
            val crossRefs = db.labelDao().getAllCrossRefsOnce()
            val noteLabelPairs = crossRefs.map { it.noteId to it.labelId }
            val graphData = GraphData.build(notes, hideOrphans, showTags, labels, noteLabelPairs, showGhosts)
            binding.graphView.setGlobalData(graphData)
            binding.graphView.groups = groups
            binding.emptyView.visibility =
                if (graphData.nodes.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun showSettingsSheet() {
        val sheet = BottomSheetDialog(this)
        val sb = BottomsheetGraphSettingsBinding.inflate(layoutInflater)
        sheet.setContentView(sb.root)

        // --- accordion expand/collapse ---
        fun bindAccordion(header: android.view.View, chevron: android.view.View, section: android.view.View) {
            header.setOnClickListener {
                val expand = section.visibility != android.view.View.VISIBLE
                section.visibility = if (expand) android.view.View.VISIBLE else android.view.View.GONE
                chevron.animate().rotation(if (expand) 270f else 90f).setDuration(150).start()
            }
        }
        bindAccordion(sb.headerFilter, sb.chevronFilter, sb.sectionFilter)
        bindAccordion(sb.headerGroups, sb.chevronGroups, sb.sectionGroups)
        bindAccordion(sb.headerDisplay, sb.chevronDisplay, sb.sectionDisplay)
        bindAccordion(sb.headerForces, sb.chevronForces, sb.sectionForces)
        // Display and Forces start expanded, like the previous flat panel
        sb.sectionDisplay.visibility = android.view.View.VISIBLE
        sb.chevronDisplay.rotation = 270f
        sb.sectionForces.visibility = android.view.View.VISIBLE
        sb.chevronForces.rotation = 270f

        sb.buttonClose.setOnClickListener { sheet.dismiss() }

        // --- filter section ---
        sb.editGraphSearch.setText(binding.graphView.searchQuery)
        sb.editGraphSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.graphView.searchQuery = s?.toString().orEmpty()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        sb.switchTags.isChecked = showTags
        sb.switchTags.setOnCheckedChangeListener { _, checked ->
            showTags = checked
            loadGraph()
        }
        sb.switchOrphans.isChecked = hideOrphans
        sb.switchOrphans.setOnCheckedChangeListener { _, checked ->
            hideOrphans = checked
            loadGraph()
        }
        sb.switchGhosts.isChecked = showGhosts
        sb.switchGhosts.setOnCheckedChangeListener { _, checked ->
            showGhosts = checked
            loadGraph()
        }

        // --- groups section ---
        fun renderGroups() {
            sb.groupRows.removeAllViews()
            groups.forEach { group ->
                val row = ItemGraphGroupRowBinding.inflate(layoutInflater, sb.groupRows, false)
                row.groupQueryText.text = group.query
                (row.groupColorDot.background.mutate() as GradientDrawable).setColor(group.color)
                row.buttonDeleteGroup.setOnClickListener {
                    groups.remove(group)
                    binding.graphView.groups = groups.toList()
                    binding.graphView.invalidate()
                    renderGroups()
                }
                sb.groupRows.addView(row.root)
            }
        }
        renderGroups()
        sb.buttonAddGroup.setOnClickListener { showAddGroupDialog { renderGroups() } }

        // --- display + forces sections ---
        sb.switchArrows.isChecked = binding.graphView.showArrows
        sb.switchArrows.setOnCheckedChangeListener { _, checked ->
            binding.graphView.showArrows = checked
            binding.graphView.invalidate()
        }

        fun applyForces() {
            binding.graphView.applyForceSettings(
                sb.sliderCenter.value,
                sb.sliderRepel.value,
                sb.sliderLinkStrength.value,
                sb.sliderLinkDistance.value
            )
        }
        sb.sliderCenter.addOnChangeListener { _, _, _ -> applyForces() }
        sb.sliderRepel.addOnChangeListener { _, _, _ -> applyForces() }
        sb.sliderLinkStrength.addOnChangeListener { _, _, _ -> applyForces() }
        sb.sliderLinkDistance.addOnChangeListener { _, _, _ -> applyForces() }

        sb.sliderNodeSize.addOnChangeListener { _, value, _ ->
            binding.graphView.nodeSizeSetting = value
            binding.graphView.invalidate()
        }
        sb.sliderLinkThickness.addOnChangeListener { _, value, _ ->
            binding.graphView.linkThicknessSetting = value
            binding.graphView.invalidate()
        }
        sb.sliderFade.addOnChangeListener { _, value, _ ->
            binding.graphView.fadeThreshold = value
            binding.graphView.invalidate()
        }
        sb.sliderCollisionMargin.value = binding.graphView.collisionMargin
        sb.sliderCollisionMargin.addOnChangeListener { _, value, _ ->
            binding.graphView.collisionMargin = value
            binding.graphView.restart()
        }
        sb.sliderLocalDepth.value = binding.graphView.localGraphDepth.toFloat()
        sb.sliderLocalDepth.addOnChangeListener { _, value, _ ->
            binding.graphView.localGraphDepth = value.toInt()
        }

        sb.btnRestart.setOnClickListener {
            applyForces()
            binding.graphView.restart()
        }

        sb.buttonResetView.setOnClickListener {
            sb.switchTags.isChecked = true
            sb.switchOrphans.isChecked = false
            sb.switchGhosts.isChecked = true
            sb.switchArrows.isChecked = false
            sb.editGraphSearch.setText("")
            sb.sliderFade.value = 0.8f
            sb.sliderNodeSize.value = 14f
            sb.sliderLinkThickness.value = 2f
            sb.sliderCollisionMargin.value = 16f
            sb.sliderLocalDepth.value = 2f
            sb.sliderCenter.value = 0.3f
            sb.sliderRepel.value = 1200f
            sb.sliderLinkStrength.value = 0.4f
            sb.sliderLinkDistance.value = 140f
            binding.graphView.collisionMargin = 16f
            applyForces()
            binding.graphView.restart()
        }

        applyForces()
        sheet.show()
    }

    /** Lets the user type the group's search text and pick its color from the palette, instead of
     * the color being auto-assigned. [onSaved] is called after a group is actually added, so the
     * caller can refresh its group list rows. */
    private fun showAddGroupDialog(onSaved: () -> Unit) {
        val density = resources.displayMetrics.density
        val container = android.widget.LinearLayout(this)
        container.orientation = android.widget.LinearLayout.VERTICAL
        val pad = (20 * density).toInt()
        container.setPadding(pad, pad, pad, pad)

        val input = android.widget.EditText(this)
        input.hint = getString(R.string.graph_group_query_hint)
        container.addView(input)

        val swatchLabel = android.widget.TextView(this)
        swatchLabel.text = getString(R.string.graph_group_color_hint)
        swatchLabel.alpha = 0.7f
        swatchLabel.textSize = 13f
        val labelParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        labelParams.topMargin = (14 * density).toInt()
        container.addView(swatchLabel, labelParams)

        val swatchRow = android.widget.LinearLayout(this)
        swatchRow.orientation = android.widget.LinearLayout.HORIZONTAL
        val rowParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        rowParams.topMargin = (8 * density).toInt()
        container.addView(swatchRow, rowParams)

        var selectedColor = groupPalette[0]
        val swatchViews = mutableListOf<android.view.View>()

        fun refreshSwatchSelection() {
            swatchViews.forEachIndexed { index, view ->
                val drawable = GradientDrawable()
                drawable.shape = GradientDrawable.OVAL
                drawable.setColor(groupPalette[index])
                if (groupPalette[index] == selectedColor) {
                    drawable.setStroke((2 * density).toInt(), ContextCompat.getColor(this, android.R.color.white))
                }
                view.background = drawable
            }
        }

        val swatchSize = (32 * density).toInt()
        val swatchMargin = (8 * density).toInt()
        groupPalette.forEachIndexed { index, color ->
            val swatch = android.view.View(this)
            val params = android.widget.LinearLayout.LayoutParams(swatchSize, swatchSize)
            params.marginEnd = swatchMargin
            swatch.layoutParams = params
            swatch.setOnClickListener {
                selectedColor = color
                refreshSwatchSelection()
            }
            swatchViews.add(swatch)
            swatchRow.addView(swatch)
        }
        refreshSwatchSelection()

        AlertDialog.Builder(this)
            .setTitle(R.string.graph_group_add)
            .setView(container)
            .setPositiveButton(R.string.labels_create) { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) {
                    groups.add(GraphGroup(query, selectedColor))
                    binding.graphView.groups = groups.toList()
                    binding.graphView.invalidate()
                    onSaved()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
