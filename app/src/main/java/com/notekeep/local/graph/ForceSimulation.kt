package com.notekeep.local.graph

import kotlin.math.sqrt

/**
 * Force-directed layout engine, following the same forces Obsidian (built on d3-force) uses:
 * Repulsion (nodes push each other apart at long range), Link/spring force (edges pull their two
 * nodes toward a target distance), Center force (a gentle pull toward the canvas center so the
 * graph doesn't drift off screen), Collision (a hard "personal space" bubble around every node
 * so no two circles are ever allowed to overlap, regardless of what repulsion/links are doing),
 * and Damping (velocity decays every frame so the layout settles instead of oscillating forever).
 *
 * Repulsion and collision are TWO SEPARATE forces, on purpose, matching d3-force's own design
 * (forceManyBody + forceCollide as distinct forces, not one). Repulsion alone decays with
 * distance (F = k/d²) and goes nearly to zero once nodes are a bit apart, so it can never by
 * itself guarantee nodes stop overlapping — that guarantee only comes from a dedicated collision
 * force that looks at each node's actual drawn radius and forcibly separates any pair closer than
 * radius(a) + radius(b). This is exactly the "invisible circle around every node" behavior from
 * Obsidian's repel-strength setting.
 *
 * Repulsion uses a Barnes-Hut quadtree (see QuadTree.kt) so it stays close to O(n log n) instead
 * of O(n²), which matters once a note collection has hundreds or thousands of linked notes.
 *
 * The simulation stops itself once total kinetic energy drops under a threshold, matching the
 * "run until it settles, then stop drawing frames" behavior described in the spec.
 */
class ForceSimulation(private val data: GraphData) {

    var centerStrength: Float = 0.3f
    var repelStrength: Float = 1200f
    var linkStrength: Float = 0.4f
    var linkDistance: Float = 140f
    /** The node-size setting from Display settings, needed here so collision radii match what's
     * actually drawn — see GraphNode.radius(). */
    var nodeSizeSetting: Float = 14f
    /** Extra "personal space" margin added on top of each node's drawn radius for the collision
     * bubble — the invisible circle the user asked for. Defaults to 16dp per their request, and
     * is adjustable up or down from the graph settings sheet's "Repel force" section. */
    var collisionMargin: Float = 16f

    /** Barnes-Hut approximation threshold: smaller = more accurate but slower, larger = faster
     * but coarser. 0.8 is a standard, Obsidian-like balance. */
    var theta: Float = 0.8f
    /** Collision passes per tick. d3-force's forceCollide runs several relaxation iterations per
     * tick (it defaults to 1, but denser graphs need more to fully resolve chains of overlaps in
     * one frame instead of visibly untangling over several seconds). Raised from 3 to 6 so a
     * heavily-overlapped starting layout (many nodes reloaded near their old positions, or a
     * dense local graph) fully untangles within a handful of frames instead of leaking residual
     * overlap into the next tick, which is what let nodes look permanently stuck together. */
    private val collisionIterations = 6

    var alpha: Float = 1f
    private val alphaDecay = 0.985f
    private val alphaMin = 0.01f
    private val velocityDamping = 0.82f
    /** Kinetic-energy threshold under which the simulation is considered "settled" and stops
     * ticking, per spec section 6 ("Total Kinetic Energy < threshold, then stop"). Lowered from
     * the first version, which was stopping the sim while nodes were still visibly overlapping —
     * collision resolution needs a few more ticks of headroom to finish separating everything. */
    private val kineticEnergyThreshold = 0.4f

    var centerX: Float = 0f
    var centerY: Float = 0f

    private var settled = false

    fun reheat() {
        alpha = 1f
        settled = false
    }

    val isActive: Boolean
        get() = !settled && alpha > alphaMin

    fun tick() {
        if (!isActive) return
        val nodes = data.nodes
        val n = nodes.size
        if (n == 0) return

        val fx = FloatArray(n)
        val fy = FloatArray(n)

        // Repulsion, approximated via Barnes-Hut instead of checking every O(n²) pair. This is
        // the long-range force that spreads clusters apart; it is intentionally NOT responsible
        // for guaranteeing zero overlap (that's collision's job below).
        val tree = QuadTree.build(nodes)
        if (tree != null) {
            for (i in 0 until n) {
                tree.accumulateForce(nodes[i], repelStrength, theta, fx, fy, i)
            }
        }

        // Spring links pulling connected nodes toward the target distance. Uses an id->index map
        // built once per tick (O(n)) instead of List.indexOf per edge (which was O(n) per edge,
        // i.e. O(n * edges) overall).
        val indexById = HashMap<String, Int>(n * 2)
        for (i in 0 until n) indexById[nodes[i].id] = i

        for (edge in data.edges) {
            val ia = indexById[edge.sourceId] ?: continue
            val ib = indexById[edge.targetId] ?: continue
            val a = nodes[ia]
            val b = nodes[ib]

            var dx = b.x - a.x
            var dy = b.y - a.y
            var dist = sqrt(dx * dx + dy * dy)
            if (dist < 0.01f) dist = 0.01f
            val diff = (dist - linkDistance) * linkStrength
            val ux = dx / dist
            val uy = dy / dist
            fx[ia] += ux * diff
            fy[ia] += uy * diff
            fx[ib] -= ux * diff
            fy[ib] -= uy * diff
        }

        // Center force: gentle pull toward the canvas center so the graph doesn't drift away.
        for (i in 0 until n) {
            val node = nodes[i]
            fx[i] += (centerX - node.x) * centerStrength * 0.02f
            fy[i] += (centerY - node.y) * centerStrength * 0.02f
        }

        var kineticEnergy = 0f
        for (i in 0 until n) {
            val node = nodes[i]
            if (node.fixed) {
                node.vx = 0f
                node.vy = 0f
                continue
            }
            node.vx = (node.vx + fx[i] * alpha) * velocityDamping
            node.vy = (node.vy + fy[i] * alpha) * velocityDamping
            node.x += node.vx * 0.02f
            node.y += node.vy * 0.02f
            kineticEnergy += node.vx * node.vx + node.vy * node.vy
        }

        // Collision: the hard "no two circles may overlap" constraint. Run after positions move,
        // using a fresh quadtree each iteration (positions just changed), so a chain of touching
        // nodes gets fully untangled within this tick rather than leaking into the next one.
        repeat(collisionIterations) {
            resolveCollisions(nodes)
        }

        alpha *= alphaDecay
        // Stop entirely once the layout has essentially stopped moving, rather than only relying
        // on alpha decay — this is what makes dragging a single node settle quickly instead of
        // the whole graph continuing to jiggle for the full decay curve.
        if (kineticEnergy / n < kineticEnergyThreshold) {
            settled = true
        }
    }

    /**
     * For every pair of nodes closer together than radius(a) + radius(b) + padding, pushes them
     * directly apart along the line between their centers, split evenly between the two (unless
     * one is fixed/being dragged, in which case the other absorbs the full correction). This is
     * a direct position correction, not a velocity-based force, matching d3-force's own
     * "iterative relaxation" approach to forceCollide — it's what actually guarantees the
     * invisible personal-space bubble around every node, tag, and label.
     */
    private fun resolveCollisions(nodes: List<GraphNode>) {
        val n = nodes.size
        // Grid-bucket the nodes by position so we only test pairs that are plausibly close,
        // instead of every O(n²) pair — collision only matters at short range anyway. The cell
        // size is sized off the LARGEST node's full collision radius present (drawn circle +
        // personal-space margin), so a high-degree hub node, or a large collisionMargin setting,
        // can never be missed by the 3x3 neighbor search below.
        val maxRadius = nodes.maxOf { it.collisionRadius(nodeSizeSetting, collisionMargin) }
        val cellSize = (maxRadius * 2f).coerceAtLeast(24f)
        val buckets = HashMap<Long, MutableList<Int>>()
        fun bucketKey(gx: Int, gy: Int): Long = (gx.toLong() shl 32) xor (gy.toLong() and 0xFFFFFFFFL)
        for (i in 0 until n) {
            val gx = (nodes[i].x / cellSize).toInt()
            val gy = (nodes[i].y / cellSize).toInt()
            buckets.getOrPut(bucketKey(gx, gy)) { mutableListOf() }.add(i)
        }

        for (i in 0 until n) {
            val a = nodes[i]
            val ra = a.collisionRadius(nodeSizeSetting, collisionMargin)
            val gx = (a.x / cellSize).toInt()
            val gy = (a.y / cellSize).toInt()
            for (ox in -1..1) {
                for (oy in -1..1) {
                    val bucket = buckets[bucketKey(gx + ox, gy + oy)] ?: continue
                    for (j in bucket) {
                        if (j <= i) continue // each unordered pair handled once
                        val b = nodes[j]
                        val rb = b.collisionRadius(nodeSizeSetting, collisionMargin)
                        var dx = b.x - a.x
                        var dy = b.y - a.y
                        var dist = sqrt(dx * dx + dy * dy)
                        val minDist = ra + rb
                        if (dist >= minDist) continue
                        if (dist < 0.001f) {
                            // exactly coincident: nudge apart in a random direction to break the tie
                            dx = (Math.random().toFloat() - 0.5f) * 0.01f
                            dy = (Math.random().toFloat() - 0.5f) * 0.01f
                            dist = 0.001f
                        }
                        val overlap = minDist - dist
                        val ux = dx / dist
                        val uy = dy / dist
                        val aFixed = a.fixed
                        val bFixed = b.fixed
                        when {
                            aFixed && bFixed -> { /* both pinned, nothing to do */ }
                            aFixed -> {
                                b.x += ux * overlap
                                b.y += uy * overlap
                            }
                            bFixed -> {
                                a.x -= ux * overlap
                                a.y -= uy * overlap
                            }
                            else -> {
                                val half = overlap / 2f
                                a.x -= ux * half
                                a.y -= uy * half
                                b.x += ux * half
                                b.y += uy * half
                            }
                        }
                    }
                }
            }
        }
    }
}
