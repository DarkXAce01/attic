package io.github.darkxace01.attic.people

import io.github.darkxace01.attic.data.RelationEntity

/**
 * Who's who in the family, from the relations people have been given. Plain Kotlin, tested on its own.
 */
object Family {
    data class Kin(val parents: List<Long>, val children: List<Long>, val spouses: List<Long>, val siblings: List<Long>)

    /** [person]'s close family. Siblings: said to be, or sharing a parent. */
    fun kin(person: Long, relations: List<RelationEntity>): Kin {
        val parents = relations.filter { it.kind == RelationEntity.PARENT && it.b == person }.map { it.a }.distinct()
        val children = relations.filter { it.kind == RelationEntity.PARENT && it.a == person }.map { it.b }.distinct()
        val spouses = relations.filter { it.kind == RelationEntity.SPOUSE && (it.a == person || it.b == person) }.map { if (it.a == person) it.b else it.a }.distinct()
        val said = relations.filter { it.kind == RelationEntity.SIBLING && (it.a == person || it.b == person) }.map { if (it.a == person) it.b else it.a }
        val shared = relations.filter { it.kind == RelationEntity.PARENT && it.a in parents && it.b != person }.map { it.b }
        return Kin(parents, children, spouses, (said + shared).distinct())
    }

    /** Who in the tree has no photos yet, and who has only one, each oldest generation first. */
    data class Missing(val none: List<Long>, val one: List<Long>) {
        val isEmpty get() = none.isEmpty() && one.isEmpty()
    }

    fun missing(layout: Map<Long, Spot>, photos: Map<Long, Int>): Missing {
        val order = layout.keys.sortedWith(compareBy({ layout.getValue(it).generation }, { layout.getValue(it).x }))
        return Missing(order.filter { (photos[it] ?: 0) == 0 }, order.filter { photos[it] == 1 })
    }

    /** Where a person sits in the tree: their generation (0 is the person it's drawn for, parents -1) and place along it. */
    data class Spot(val generation: Int, val x: Float)

    /**
     * Lays out everyone connected to [root]: each generation a row, parents above their children, couples
     * side by side, children under the middle of their parents as far as the rows allow.
     */
    fun layout(root: Long, relations: List<RelationEntity>): Map<Long, Spot> {
        val gen = LinkedHashMap<Long, Int>()
        gen[root] = 0
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            val g = gen.getValue(p)
            val k = kin(p, relations)
            for ((list, dg) in listOf(k.parents to -1, k.children to 1, k.spouses to 0, k.siblings to 0)) {
                for (o in list) if (o !in gen) { gen[o] = g + dg; queue.addLast(o) }
            }
        }
        val rows = gen.entries.groupBy({ it.value }, { it.key }).toSortedMap().mapValues { it.value.toMutableList() }
        val x = HashMap<Long, Float>()
        fun place() = rows.values.forEach { row -> row.forEachIndexed { i, p -> x[p] = i - (row.size - 1) / 2f } }
        place()
        fun mean(ids: List<Long>) = ids.mapNotNull { x[it] }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        repeat(4) {
            // Down: children under their parents. Up: parents over their children.
            for (g in rows.keys) rows.getValue(g).sortBy { p -> mean(kin(p, relations).parents) ?: x.getValue(p) }
            place()
            for (g in rows.keys.reversed()) rows.getValue(g).sortBy { p -> mean(kin(p, relations).children) ?: x.getValue(p) }
            place()
        }
        // Couples next to each other, the one marrying in on the outside, away from their spouse's siblings.
        for (row in rows.values) {
            for (p in row.toList()) {
                val k = kin(p, relations)
                val spouse = k.spouses.firstOrNull { it in row } ?: continue
                if (kin(spouse, relations).parents.isNotEmpty() && k.parents.isEmpty()) continue // they'll move us
                row.remove(spouse)
                val i = row.indexOf(p)
                val sibs = k.siblings.filter { it in row }.map { row.indexOf(it) }
                val sibsLeft = sibs.isNotEmpty() && sibs.average() < i
                row.add(if (sibsLeft || sibs.isEmpty() && i >= row.size / 2f) i + 1 else i, spouse)
            }
        }
        place()
        return gen.keys.associateWith { Spot(gen.getValue(it), x.getValue(it)) }
    }
}
