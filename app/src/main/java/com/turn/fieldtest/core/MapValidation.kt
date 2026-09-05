package com.turn.fieldtest.core

/** Reject a self-crossing map before it can become a particle-filter constraint. */
fun validateSimpleWalkablePolygon(polygon: MetricPolygon) {
    val edges = polygon.edges
    edges.forEachIndexed { i, edge ->
        require(edge.start != edge.end) { "Walkable polygon contains a zero-length edge" }
        for (j in i + 1 until edges.size) {
            if (j == i + 1 || (i == 0 && j == edges.lastIndex)) continue
            require(MetricGeometry.intersection(edge, edges[j]) == SegmentIntersection.NONE) {
                "Walkable polygon crosses or touches itself at edges $i and $j"
            }
        }
    }
}
