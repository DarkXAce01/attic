package io.github.darkxace01.attic.people

import io.github.darkxace01.attic.vision.FaceGroups

/**
 * A guess at when an undated photo was taken, from the people in it: a face looks most like the same
 * person's faces from around the same years. It's a hint and only ever offered, never filled in: faces
 * are made to tell people apart, not ages, so it holds back unless the evidence is clear. The person has
 * to be in enough dated photos across enough years, the closest of them have to agree on a few years,
 * and they have to be clearly closer than the rest. Several people in the photo have to agree too.
 */
object DateHint {
    /** One of a person's faces on a photo dated to within a couple of years. */
    class Seen(val vec: FloatArray, val year: Int)

    /** About [year]: the closest faces were from [from] to [to]. [names]: whose faces said so. */
    data class Hint(val year: Int, val from: Int, val to: Int, val names: List<String>)

    /** Dated faces a person needs before their face can say anything about a year. */
    const val MIN_DATED = 4
    /** Their dated photos have to span at least this many years, or there's nothing to tell apart. */
    const val MIN_SPAN = 6
    /** How many of the closest faces vote. */
    private const val K = 3
    /** The closest faces may be at most this many years apart and still agree. */
    const val MAX_SPREAD = 8
    /** How much more alike the closest faces have to be than the person's typical face. */
    const val MARGIN = 0.06f

    /** [faces]: the named faces on the undated photo; [dated]: each person's faces on dated photos. */
    fun suggest(faces: List<Pair<String, FloatArray>>, dated: Map<String, List<Seen>>): Hint? {
        val votes = faces.mapNotNull { (name, vec) -> vote(vec, dated[name].orEmpty())?.let { name to it } }
        if (votes.isEmpty()) return null
        val from = votes.maxOf { it.second.first }
        val to = votes.minOf { it.second.last }
        // Everyone's closest years have to overlap, give or take a year.
        if (from > to + 1) return null
        val years = votes.map { (it.second.first + it.second.last) / 2 }
        return Hint(years.sum() / years.size, minOf(from, to), maxOf(from, to), votes.map { it.first })
    }

    /** The years one person's face points to, or null when it doesn't clearly point anywhere. */
    private fun vote(vec: FloatArray, seen: List<Seen>): IntRange? {
        if (seen.size < MIN_DATED) return null
        if (seen.maxOf { it.year } - seen.minOf { it.year } < MIN_SPAN) return null
        val scored = seen.map { it to FaceGroups.cosine(vec, it.vec) }.sortedByDescending { it.second }
        val top = scored.take(K)
        val rest = scored.drop(K)
        if (rest.isEmpty()) return null
        val margin = top.map { it.second }.average() - rest.map { it.second }.average()
        if (margin < MARGIN) return null
        val ys = top.map { it.first.year }
        if (ys.max() - ys.min() > MAX_SPREAD) return null
        return ys.min()..ys.max()
    }
}
