package io.github.darkxace01.attic.vision

import kotlin.math.sqrt

/**
 * Who's who, from face fingerprints (128 numbers each, from [Faces]). Plain Kotlin, so it's tested on its
 * own. Two faces are the same person when their fingerprints point the same way: cosine similarity at or
 * over [SAME]. Faces from the same photo are never the same person.
 */
object FaceGroups {
    /** At or over this, two faces are one person. SFace's own mark is 0.363; a little stricter, so
     *  different people are rarely joined (asking twice is better than a wrong name). */
    const val SAME = 0.40f

    /** A face to sort: its id, the photo it's in, and its fingerprint. */
    class Face(val id: Long, val printId: Long, val vec: FloatArray)

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0f || nb == 0f) 0f else dot / sqrt(na * nb)
    }

    /**
     * Groups faces that belong together, biggest group first. Each face joins the group it's most like on
     * average (if that's over [threshold] and the group has nobody from its photo yet), or starts a new one.
     * Two passes, so the order faces come in matters less.
     */
    fun groups(faces: List<Face>, threshold: Float = SAME): List<List<Face>> {
        if (faces.isEmpty()) return emptyList()
        val dim = faces[0].vec.size
        val units = faces.map { unit(it.vec) }

        // First pass, greedy: each face joins the group it's most like so far, or starts one.
        val sums = ArrayList<FloatArray>()
        val photos = ArrayList<MutableMap<Long, Int>>()
        val first = IntArray(faces.size)
        for ((i, f) in faces.withIndex()) {
            var best = -1; var bestSim = threshold
            for (g in sums.indices) {
                if ((photos[g][f.printId] ?: 0) > 0) continue
                val s = cosine(units[i], sums[g])
                if (s >= bestSim) { best = g; bestSim = s }
            }
            if (best < 0) { sums += FloatArray(dim); photos += HashMap(); best = sums.lastIndex }
            for (k in 0 until dim) sums[best][k] += units[i][k]
            photos[best].merge(f.printId, 1, Int::plus)
            first[i] = best
        }

        // Second pass: now that the groups are whole, each face moves to the group it's most like
        // (comparing with its own group minus itself), so early faces aren't stuck where they landed.
        val final = IntArray(faces.size)
        var fresh = sums.size
        for ((i, f) in faces.withIndex()) {
            var best = -1; var bestSim = threshold
            for (g in sums.indices) {
                val own = g == first[i]
                val others = (photos[g][f.printId] ?: 0) - if (own) 1 else 0
                if (others > 0) continue
                val sum = if (own) FloatArray(dim) { sums[g][it] - units[i][it] } else sums[g]
                if (own && sum.all { kotlin.math.abs(it) < 1e-6f }) continue
                val s = cosine(units[i], sum)
                if (s >= bestSim) { best = g; bestSim = s }
            }
            final[i] = if (best >= 0) best else fresh++
        }
        return faces.indices.groupBy { final[it] }.values.map { ix -> ix.map { faces[it] } }.sortedByDescending { it.size }
    }

    /**
     * Who [face] most likely is, among [known] (person id to that person's faces), and how alike: the mean
     * of its best three matches with that person, so one lookalike photo isn't enough. Null if nobody is
     * at [threshold].
     */
    fun suggest(face: FloatArray, known: Map<Long, List<FloatArray>>, threshold: Float = SAME): Pair<Long, Float>? =
        known.mapNotNull { (person, vecs) ->
            if (vecs.isEmpty()) return@mapNotNull null
            val sims = vecs.map { cosine(face, it) }.sortedDescending().take(3)
            person to sims.average().toFloat()
        }.filter { it.second >= threshold }.maxByOrNull { it.second }

    private fun unit(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        n = sqrt(n).coerceAtLeast(1e-9f)
        return FloatArray(v.size) { v[it] / n }
    }

    fun toBytes(v: FloatArray): ByteArray {
        val b = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putFloat(it) }
        return b.array()
    }

    fun fromBytes(b: ByteArray): FloatArray {
        val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { buf.getFloat() }
    }
}
