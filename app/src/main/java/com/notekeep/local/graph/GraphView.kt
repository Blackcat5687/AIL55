package com.notekeep.local.graph

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** The full Global Graph, as built from every note. Kept separate from [data] (which is what
     * actually gets drawn) so switching in and out of Local Graph mode never needs a full DB reload. */
    private var globalData: GraphData = GraphData(mutableListOf(), mutableListOf())

    /** Node-size setting, declared before [data] so its default is already valid the moment
     * data's own initializer runs (Kotlin initializes properties top-to-bottom in declaration
     * order, so this ordering is what makes that safe rather than accidental). */
    var nodeSizeSetting: Float = 14f
        set(value) {
            field = value
            simulation.nodeSizeSetting = value
        }

    /** Extra "personal space" the user wants around every node, tag, and label, on top of its
     * drawn radius — the invisible circle nothing else may enter. Defaults to 16dp (their
     * requested minimum) and is adjustable from the graph settings sheet. */
    var collisionMargin: Float = 16f
        set(value) {
            field = value
            simulation.collisionMargin = value
        }

    var data: GraphData = GraphData(mutableListOf(), mutableListOf())
        private set(value) {
            field = value
            simulation = ForceSimulation(value).also {
                it.nodeSizeSetting = nodeSizeSetting
                it.collisionMargin = collisionMargin
            }
            requestSimTick()
        }

    /** Sets the Global Graph. Carries over position/velocity/fixed for any node id that already
     * existed in the previous globalData, instead of starting that node back at its honeycomb
     * spot with zero velocity — otherwise every routine reload (e.g. returning to this screen
     * after editing a note) would visibly snap the whole layout back to its unsettled starting
     * shape, undoing whatever the simulation had already resolved. Brand-new nodes still get
     * their honeycomb starting position from GraphData.build. Also resets any active Local Graph
     * focus, since the note set changed. */
    fun setGlobalData(value: GraphData) {
        val previousById = globalData.nodes.associateBy { it.id }
        for (node in value.nodes) {
            val previous = previousById[node.id] ?: continue
            node.x = previous.x
            node.y = previous.y
            node.vx = previous.vx
            node.vy = previous.vy
            node.fixed = previous.fixed
        }
        globalData = value
        focusedNodeId = null
        data = value
    }

    private var simulation = ForceSimulation(data).also {
        it.nodeSizeSetting = nodeSizeSetting
        it.collisionMargin = collisionMargin
    }

    /** When non-null, the view shows the Local Graph (BFS neighborhood) of this node instead of
     * the full Global Graph — matching Obsidian's "focus on a note" behavior (spec section 4). */
    private var focusedNodeId: String? = null
    var localGraphDepth: Int = 2

    /** Switches to showing the Local Graph around [nodeId]: itself plus everything reachable
     * within [localGraphDepth] hops, found via breadth-first search (not depth-first). */
    fun focusOnNode(nodeId: String) {
        focusedNodeId = nodeId
        data = globalData.localGraph(nodeId, localGraphDepth)
        onGraphModeChanged?.invoke()
    }

    /** Leaves Local Graph mode and returns to showing every note. */
    fun showGlobalGraph() {
        focusedNodeId = null
        data = globalData
        onGraphModeChanged?.invoke()
    }

    val isShowingLocalGraph: Boolean get() = focusedNodeId != null

    var showArrows: Boolean = false
    var linkThicknessSetting: Float = 2f
    var fadeThreshold: Float = 0.8f
    var searchQuery: String = ""
        set(value) { field = value; invalidate() }
    var groups: List<GraphGroup> = emptyList()
        set(value) { field = value; invalidate() }

    var onNoteTapped: ((Long) -> Unit)? = null
    /** Fired whenever Local/Global graph mode changes, so the host Activity can update its
     * "back to global graph" button and toolbar subtitle. */
    var onGraphModeChanged: (() -> Unit)? = null

    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f

    private var draggingNode: GraphNode? = null
    private var dragStartWorldX = 0f
    private var dragStartWorldY = 0f
    private var dragStartNodeX = 0f
    private var dragStartNodeY = 0f
    private var didDrag = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.15f, 6f)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            translateX += dx
            translateY += dy
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val (worldX, worldY) = screenToWorld(e.x, e.y)
            val node = findNodeAt(worldX, worldY) ?: return
            if (!node.isGhost) focusOnNode(node.id)
        }
    })

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5A5A5A")
        strokeWidth = 3f
    }
    private val tagNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4CAF50") }
    private val noteNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E6E1E5") }
    private val labelNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E8A33D") }
    /** Ghost nodes (unresolved [[wiki-links]]) render faint/hollow, like Obsidian's unresolved-link nodes. */
    private val ghostNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B6B6B")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6E1E5")
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    /** Drawn behind each label as a soft dark halo so overlapping labels in a dense cluster stay
     * legible instead of turning into unreadable crossed strokes (a known limitation Obsidian
     * itself has in very dense local graphs — this at least keeps the nearest label readable). */
    private val labelHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DD121212")
        textSize = 28f
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            simulation.centerX = width / 2f
            simulation.centerY = height / 2f
            simulation.tick()
            invalidate()
            if (simulation.isActive) postOnAnimation(this)
        }
    }

    fun requestSimTick() {
        removeCallbacks(tickRunnable)
        post(tickRunnable)
    }

    fun applyForceSettings(center: Float, repel: Float, linkStrength: Float, linkDistance: Float) {
        simulation.centerStrength = center
        simulation.repelStrength = repel
        simulation.linkStrength = linkStrength
        simulation.linkDistance = linkDistance
    }

    fun restart() {
        simulation.reheat()
        requestSimTick()
    }

    private fun screenToWorld(screenX: Float, screenY: Float): Pair<Float, Float> {
        val worldX = (screenX + translateX - width / 2f) / scaleFactor + width / 2f
        val worldY = (screenY + translateY - height / 2f) / scaleFactor + height / 2f
        return worldX to worldY
    }

    private fun findNodeAt(worldX: Float, worldY: Float): GraphNode? {
        var closest: GraphNode? = null
        var closestDist = Float.MAX_VALUE
        for (node in data.nodes) {
            val dx = node.x - worldX
            val dy = node.y - worldY
            val d = sqrt(dx * dx + dy * dy)
            if (d < closestDist) {
                closestDist = d
                closest = node
            }
        }
        val radius = nodeRadius(closest) + 24f
        return if (closest != null && closestDist <= radius) closest else null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (wx, wy) = screenToWorld(event.x, event.y)
                val hit = findNodeAt(wx, wy)
                if (hit != null) {
                    draggingNode = hit
                    didDrag = false
                    hit.fixed = true
                    dragStartWorldX = wx
                    dragStartWorldY = wy
                    dragStartNodeX = hit.x
                    dragStartNodeY = hit.y
                    // wake the simulation back up so linked nodes are pulled along through their
                    // spring connections while this one is being dragged, like it's all floating in a fluid
                    simulation.reheat()
                    requestSimTick()
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // a second finger arrived; hand off to pinch/pan instead of node-dragging
                draggingNode?.fixed = false
                draggingNode = null
            }
            MotionEvent.ACTION_MOVE -> {
                val node = draggingNode
                if (node != null) {
                    val (wx, wy) = screenToWorld(event.x, event.y)
                    node.x = dragStartNodeX + (wx - dragStartWorldX)
                    node.y = dragStartNodeY + (wy - dragStartWorldY)
                    didDrag = true
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val node = draggingNode
                if (node != null) {
                    node.fixed = false
                    draggingNode = null
                    if (!didDrag) {
                        // it was a tap, not a drag
                        handleTapOnNode(node)
                    }
                    invalidate()
                    return true
                }
            }
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun handleTap(screenX: Float, screenY: Float) {
        val (worldX, worldY) = screenToWorld(screenX, screenY)
        val node = findNodeAt(worldX, worldY) ?: return
        handleTapOnNode(node)
    }

    private fun handleTapOnNode(node: GraphNode) {
        if (node.type == GraphNodeType.NOTE) {
            node.noteId?.let { onNoteTapped?.invoke(it) }
        }
    }

    private fun nodeRadius(node: GraphNode?): Float {
        if (node == null) return nodeSizeSetting
        return node.radius(nodeSizeSetting)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(-translateX, -translateY)
        canvas.scale(scaleFactor, scaleFactor, width / 2f, height / 2f)

        edgePaint.strokeWidth = linkThicknessSetting
        for (edge in data.edges) {
            val a = data.nodeById(edge.sourceId) ?: continue
            val b = data.nodeById(edge.targetId) ?: continue
            canvas.drawLine(a.x, a.y, b.x, b.y, edgePaint)
            if (showArrows) drawArrowHead(canvas, a.x, a.y, b.x, b.y)
        }

        val showLabels = scaleFactor >= fadeThreshold
        val hasQuery = searchQuery.isNotBlank()
        for (node in data.nodes) {
            val matchesSearch = !hasQuery || node.label.contains(searchQuery, ignoreCase = true)
            val groupColor = groups.firstOrNull { node.label.contains(it.query, ignoreCase = true) }?.color
            val basePaint = when (node.type) {
                GraphNodeType.TAG -> tagNodePaint
                GraphNodeType.LABEL -> labelNodePaint
                GraphNodeType.NOTE -> noteNodePaint
                GraphNodeType.GHOST -> ghostNodePaint
            }
            val paint = if (groupColor != null && node.type != GraphNodeType.GHOST) {
                Paint(basePaint).apply { color = groupColor }
            } else basePaint
            val originalAlpha = paint.alpha
            if (!matchesSearch) paint.alpha = 70
            val radius = nodeRadius(node)
            canvas.drawCircle(node.x, node.y, radius, paint)
            paint.alpha = originalAlpha
            if (showLabels) {
                val wasAlpha = labelPaint.alpha
                val wasHaloAlpha = labelHaloPaint.alpha
                if (!matchesSearch) {
                    labelPaint.alpha = 90
                    labelHaloPaint.alpha = 60
                } else if (node.isGhost) {
                    labelPaint.alpha = 140
                    labelHaloPaint.alpha = 90
                }
                val labelY = node.y - radius - 10f
                canvas.drawText(node.label, node.x, labelY, labelHaloPaint)
                canvas.drawText(node.label, node.x, labelY, labelPaint)
                labelPaint.alpha = wasAlpha
                labelHaloPaint.alpha = wasHaloAlpha
            }
        }
        canvas.restore()
    }

    private fun drawArrowHead(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val midX = (x1 + x2) / 2f
        val midY = (y1 + y2) / 2f
        val arrowLen = 14f
        val a1 = angle + Math.PI - 0.4
        val a2 = angle + Math.PI + 0.4
        canvas.drawLine(midX, midY, (midX + arrowLen * cos(a1)).toFloat(), (midY + arrowLen * sin(a1)).toFloat(), edgePaint)
        canvas.drawLine(midX, midY, (midX + arrowLen * cos(a2)).toFloat(), (midY + arrowLen * sin(a2)).toFloat(), edgePaint)
    }
}
