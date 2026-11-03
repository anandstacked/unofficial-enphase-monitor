package com.anand.envoymonitor.data

/** Split before reducing: downsampling must not invent gaps or join real outages. */
fun chartSegments(samples: List<Sample>, gapSeconds: Long, reduce: Boolean = true): List<List<Sample>> {
    if (samples.isEmpty()) return emptyList()
    val segments = mutableListOf<MutableList<Sample>>()
    samples.forEach { sample ->
        val previous = segments.lastOrNull()?.lastOrNull()
        if (previous == null || sample.measuredAt - previous.measuredAt > gapSeconds) segments += mutableListOf<Sample>()
        segments.last() += sample
    }
    val bucketSize = if (reduce) (samples.size / 250).coerceAtLeast(1) else 1
    return segments.map { segment ->
        if (bucketSize == 1) segment else segment.chunked(bucketSize).flatMap { chunk ->
            listOf(chunk.first(), chunk.minBy { it.value }, chunk.maxBy { it.value }, chunk.last())
                .distinctBy { it.measuredAt }.sortedBy { it.measuredAt }
        }
    }
}
