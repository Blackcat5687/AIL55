package com.notekeep.local.graph

import kotlin.math.sqrt

/**
 * Barnes-Hut quadtree: groups distant clusters of nodes into a single "center of mass" so
 * repulsion can be approximated in O(n log n) instead of checking every pair in O(n²).
 * This is what lets the graph stay smooth with thousands of nodes, per the spec's performance
 * requirement (section 18).
 *
 * Each leaf holds at most one node; when a second node would land in the same leaf, the leaf
 * splits into 4 quadrants (hence "quad" tree) and both nodes are pushed down into them.
 */
class QuadTree(private val x0: Float, private val y0: Float, private val x1: Float, private val y1: Float) {
    private var node: GraphNode? = null
    private var massX = 0f
    private var massY = 0f
    private var mass = 0f
    private var children: Array<QuadTree>? = null

    fun insert(n: GraphNode) {
        mass += 1f
        massX += n.x
        massY += n.y

        val existing = node
        if (existing == null && children == null) {
            node = n
            return
        }

        if (children == null) {
            children = subdivide()
            existing?.let { placeInChild(it) }
            node = null
        }
        placeInChild(n)
    }

    private fun subdivide(): Array<QuadTree> {
        val midX = (x0 + x1) / 2f
        val midY = (y0 + y1) / 2f
        return arrayOf(
            QuadTree(x0, y0, midX, midY),
            QuadTree(midX, y0, x1, midY),
            QuadTree(x0, midY, midX, y1),
            QuadTree(midX, midY, x1, y1)
        )
    }

    private fun placeInChild(n: GraphNode) {
        val midX = (x0 + x1) / 2f
        val midY = (y0 + y1) / 2f
        val index = (if (n.x >= midX) 1 else 0) + (if (n.y >= midY) 2 else 0)
        children!![index].insert(n)
    }

    /**
     * Accumulates the repulsion force on [target] into [outFx]/[outFy]. Nodes/clusters farther
     * than [theta] * (region width) relative to [target] are treated as one point at their
     * center of mass rather than descended into individually — the core Barnes-Hut approximation.
     */
    fun accumulateForce(target: GraphNode, repelStrength: Float, theta: Float, outFx: FloatArray, outFy: FloatArray, idx: Int) {
        if (mass <= 0f) return
        val leafNode = node
        if (leafNode != null) {
            if (leafNode === target) return
            applyPointForce(target.x, target.y, leafNode.x, leafNode.y, 1f, repelStrength, outFx, outFy, idx)
            return
        }
        val kids = children
        if (kids == null) return

        val cx = massX / mass
        val cy = massY / mass
        val width = x1 - x0
        val dx = target.x - cx
        val dy = target.y - cy
        val dist = sqrt(dx * dx + dy * dy)

        if (width / (dist + 0.0001f) < theta) {
            // far enough away: treat this whole quadrant as one mass at its center of gravity
            applyPointForce(target.x, target.y, cx, cy, mass, repelStrength, outFx, outFy, idx)
        } else {
            for (child in kids) {
                child.accumulateForce(target, repelStrength, theta, outFx, outFy, idx)
            }
        }
    }

    private fun applyPointForce(
        tx: Float, ty: Float, px: Float, py: Float, pmass: Float,
        repelStrength: Float, outFx: FloatArray, outFy: FloatArray, idx: Int
    ) {
        var dx = tx - px
        var dy = ty - py
        var distSq = dx * dx + dy * dy
        if (distSq < 1f) {
            dx = (Math.random().toFloat() - 0.5f)
            dy = (Math.random().toFloat() - 0.5f)
            distSq = 1f
        }
        val dist = sqrt(distSq)
        val force = repelStrength * pmass / distSq
        outFx[idx] += (dx / dist) * force
        outFy[idx] += (dy / dist) * force
    }

    companion object {
        /** Builds a tree covering all nodes with a small margin, then inserts every node. */
        fun build(nodes: List<GraphNode>): QuadTree? {
            if (nodes.isEmpty()) return null
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (n in nodes) {
                if (n.x < minX) minX = n.x
                if (n.y < minY) minY = n.y
                if (n.x > maxX) maxX = n.x
                if (n.y > maxY) maxY = n.y
            }
            val margin = 50f
            val tree = QuadTree(minX - margin, minY - margin, maxX + margin, maxY + margin)
            for (n in nodes) tree.insert(n)
            return tree
        }
    }
}
