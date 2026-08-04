package com.notekeep.local.graph

enum class GraphNodeType { NOTE, TAG, LABEL, GHOST }

data class GraphNode(
    val id: String,
    val label: String,
    val type: GraphNodeType,
    val noteId: Long? = null,
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var degree: Int = 0,
    /** true while the user is dragging this node, or after they've dropped it in place. */
    var fixed: Boolean = false
) {
    val isTag: Boolean get() = type == GraphNodeType.TAG
    /** A ghost node stands in for a [[wiki-link]] that points at a note which doesn't exist yet,
     * exactly like Obsidian shows unresolved links as faint placeholder nodes. */
    val isGhost: Boolean get() = type == GraphNodeType.GHOST

    /**
     * The node's actual drawn radius given the current node-size setting. This is the single
     * source of truth for "how big is this circle" — both the renderer (GraphView) and the
     * physics engine (ForceSimulation's collision force) call this same function, so the
     * invisible personal-space bubble around a node always matches what's drawn on screen.
     * More-connected notes are drawn slightly bigger, the same way Obsidian emphasizes hubs.
     */
    fun radius(nodeSizeSetting: Float): Float = nodeSizeSetting + degree.coerceAtMost(8) * 1.5f

    /**
     * The radius of the node's invisible "personal space" bubble used by the collision force —
     * the drawn circle radius plus [collisionMargin], which the user controls separately from
     * node size via the graph settings sheet (minimum 16dp per their request, adjustable up or
     * down). No other node, tag, or label is ever allowed inside this bubble.
     */
    fun collisionRadius(nodeSizeSetting: Float, collisionMargin: Float): Float =
        radius(nodeSizeSetting) + collisionMargin
}

data class GraphEdge(
    val sourceId: String,
    val targetId: String
)

data class GraphGroup(val query: String, val color: Int)

/**
 * Places nodes on the vertices of a honeycomb (hex) lattice, spiraling outward from the center,
 * so the graph starts out looking like a cluster of connected hexagons/a beehive rather than a
 * random scatter. This is only the *starting* position - the force simulation (spring links +
 * repulsion, both configurable from the graph settings) takes over immediately after and lets
 * nodes drift and settle like objects connected by elastic bands in a fluid.
 */
private fun applyHoneycombLayout(nodeList: List<GraphNode>) {
    if (nodeList.isEmpty()) return

    val cellRadius = 90f
    val directions = listOf(
        1f to 0f, 0.5f to 0.8660254f, -0.5f to 0.8660254f,
        -1f to 0f, -0.5f to -0.8660254f, 0.5f to -0.8660254f
    )

    val points = mutableListOf(0f to 0f)
    var ring = 1
    while (points.size < nodeList.size) {
        var (x, y) = directions[4].let { (dx, dy) -> dx * cellRadius * ring to dy * cellRadius * ring }
        for (side in 0 until 6) {
            val (dx, dy) = directions[side]
            repeat(ring) {
                points.add(x to y)
                x += dx * cellRadius
                y += dy * cellRadius
            }
        }
        ring++
    }

    nodeList.forEachIndexed { index, node ->
        val (px, py) = points[index]
        node.x = 400f + px
        node.y = 400f + py
    }
}

class GraphData(
    val nodes: MutableList<GraphNode>,
    val edges: MutableList<GraphEdge>
) {
    private val indexById = nodes.associateBy { it.id }.let { HashMap(it) }
    /** Adjacency list keyed by node id, built once so Local Graph BFS doesn't rescan all edges
     * at every depth level. */
    private val neighborsById: Map<String, List<String>> = run {
        val map = HashMap<String, MutableList<String>>()
        for (edge in edges) {
            map.getOrPut(edge.sourceId) { mutableListOf() }.add(edge.targetId)
            map.getOrPut(edge.targetId) { mutableListOf() }.add(edge.sourceId)
        }
        map
    }

    fun nodeById(id: String): GraphNode? = indexById[id]

    /**
     * Local Graph: starting from [rootId], walks outward breadth-first (not depth-first) up to
     * [depth] hops and returns the induced subgraph — matching Obsidian's "Local graph" panel,
     * where depth=1 shows direct neighbors, depth=2 adds neighbors-of-neighbors, and so on.
     */
    fun localGraph(rootId: String, depth: Int): GraphData {
        val root = nodeById(rootId) ?: return GraphData(mutableListOf(), mutableListOf())
        val visited = LinkedHashSet<String>()
        visited.add(rootId)
        var frontier = listOf(rootId)
        repeat(depth.coerceAtLeast(0)) {
            val next = LinkedHashSet<String>()
            for (id in frontier) {
                for (neighbor in neighborsById[id].orEmpty()) {
                    if (visited.add(neighbor)) next.add(neighbor)
                }
            }
            frontier = next.toList()
        }
        val subNodes = visited.mapNotNull { nodeById(it) }.toMutableList()
        val subEdges = edges.filter { it.sourceId in visited && it.targetId in visited }.toMutableList()
        applyHoneycombLayout(subNodes.sortedByDescending { if (it.id == root.id) Int.MAX_VALUE else it.degree })
        return GraphData(subNodes, subEdges)
    }

    companion object {
        /**
         * Builds the graph the way Obsidian does: every note is a node, and edges come from
         * three link sources found by scanning each note — [[wiki-links]] to other notes
         * (resolved by title, case-insensitively), #tags, and assigned labels/categories.
         * A [[wiki-link]] to a title that doesn't exist yet becomes a Ghost Node instead of
         * being silently dropped, so unresolved links stay visible like in Obsidian.
         */
        fun build(
            notes: List<com.notekeep.local.data.Note>,
            hideOrphans: Boolean,
            includeTags: Boolean = true,
            labels: List<com.notekeep.local.data.Label> = emptyList(),
            noteLabelPairs: List<Pair<Long, Long>> = emptyList(),
            showGhosts: Boolean = true
        ): GraphData {
            val nodes = LinkedHashMap<String, GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val degreeCount = HashMap<String, Int>()
            val seenEdgeKeys = HashSet<String>()

            fun addEdge(a: String, b: String) {
                // an undirected pair should only ever contribute one Edge, per the "no duplicate
                // edge" rule — order the key so (a,b) and (b,a) can't both slip through.
                val key = if (a < b) "$a|$b" else "$b|$a"
                if (!seenEdgeKeys.add(key)) return
                edges.add(GraphEdge(a, b))
                degreeCount[a] = (degreeCount[a] ?: 0) + 1
                degreeCount[b] = (degreeCount[b] ?: 0) + 1
            }

            val labelById = labels.associateBy { it.id }
            val labelIdsByNote = noteLabelPairs.groupBy({ it.first }, { it.second })

            // index real notes by their resolvable title so wiki-links can find their target
            val noteIdByTitle = HashMap<String, Long>()
            for (note in notes) {
                val key = note.title.trim().lowercase()
                if (key.isNotEmpty()) noteIdByTitle[key] = note.id
            }

            fun noteNodeId(id: Long) = "note_$id"

            for (note in notes) {
                val tags = note.extractTags()
                val wikiLinks = note.extractWikiLinks()
                val noteLabelIds = labelIdsByNote[note.id].orEmpty()
                if (tags.isEmpty() && wikiLinks.isEmpty() && noteLabelIds.isEmpty() && hideOrphans) continue

                val thisNodeId = noteNodeId(note.id)
                val label = note.title.ifBlank {
                    note.content.take(18).ifBlank { "بدون عنوان" }
                }
                nodes.getOrPut(thisNodeId) {
                    GraphNode(id = thisNodeId, label = label, type = GraphNodeType.NOTE, noteId = note.id)
                }

                if (includeTags) {
                    for (tag in tags) {
                        val tagNodeId = "tag_$tag"
                        nodes.getOrPut(tagNodeId) {
                            GraphNode(id = tagNodeId, label = tag, type = GraphNodeType.TAG)
                        }
                        addEdge(thisNodeId, tagNodeId)
                    }
                }

                for (labelId in noteLabelIds) {
                    val labelEntity = labelById[labelId] ?: continue
                    val labelNodeId = "label_$labelId"
                    nodes.getOrPut(labelNodeId) {
                        GraphNode(id = labelNodeId, label = labelEntity.name, type = GraphNodeType.LABEL)
                    }
                    addEdge(thisNodeId, labelNodeId)
                }

                for (linkTitle in wikiLinks) {
                    val targetNoteId = noteIdByTitle[linkTitle.trim().lowercase()]
                    if (targetNoteId != null) {
                        if (targetNoteId == note.id) continue // ignore self-links
                        val targetNodeId = noteNodeId(targetNoteId)
                        nodes.getOrPut(targetNodeId) {
                            val targetNote = notes.first { it.id == targetNoteId }
                            GraphNode(
                                id = targetNodeId,
                                label = targetNote.title.ifBlank { targetNote.content.take(18).ifBlank { "بدون عنوان" } },
                                type = GraphNodeType.NOTE,
                                noteId = targetNoteId
                            )
                        }
                        addEdge(thisNodeId, targetNodeId)
                    } else if (showGhosts) {
                        val ghostNodeId = "ghost_${linkTitle.trim().lowercase()}"
                        nodes.getOrPut(ghostNodeId) {
                            GraphNode(id = ghostNodeId, label = linkTitle.trim(), type = GraphNodeType.GHOST)
                        }
                        addEdge(thisNodeId, ghostNodeId)
                    }
                }
            }

            val nodeList = nodes.values.toMutableList()
            nodeList.forEach { it.degree = degreeCount[it.id] ?: 0 }

            applyHoneycombLayout(nodeList)

            return GraphData(nodeList, edges)
        }
    }
}
