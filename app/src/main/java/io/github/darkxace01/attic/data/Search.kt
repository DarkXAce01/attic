package io.github.darkxace01.attic.data

/**
 * Finding photos by what's known about them: who's in them, where and when they were taken, their
 * caption, their album, whether they have a story or a back. Everything is read from what people typed
 * into Attic, so it's only as good as the details, and it never guesses at what's in a picture.
 *
 * Smart albums are searches Attic offers by itself: everyone photographed often, each decade, each
 * place, the photos with stories.
 */
object Search {
    /** What's known about one print, all in one place. */
    data class Known(
        val print: PrintEntity,
        val date: PhotoDate?,
        val album: String,
        val people: List<String>,
        val hasStory: Boolean,
    ) {
        /** Every word that can find it, lowercased. */
        internal val words: String = buildString {
            for (p in people) append(p).append(' ')
            print.place?.let { append(it).append(' ') }
            print.caption?.let { append(it).append(' ') }
            append(album).append(' ')
            date?.let { append(it.label).append(' ') }
            if (hasStory) append("story stories told ")
            if (print.backFile != null) append("back backs writing ")
            if (print.negative) append("negative negatives film ")
        }.lowercase()
    }

    /** A smart album: a title, the search that fills it, and what it holds now. */
    data class Smart(val title: String, val query: String, val prints: List<PrintEntity>)

    /** The fewest photos a person, decade or place needs before it's worth its own smart album. */
    const val MIN_SMART = 3

    /**
     * The prints that match [query], oldest first. Every word has to match something: a date ("1987",
     * "80s", "summer 87") the photo's date overlaps, or the start of a word in a name, place, caption,
     * album, or the words "story", "back", "film" and "undated". The whole query is first tried as one
     * date, so "14 jul 1998" isn't three searches.
     */
    fun find(query: String, all: List<Known>): List<PrintEntity> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        PhotoDate.parse(q)?.takeIf { looksLikeDate(q) }?.let { d -> return sorted(all.filter { overlaps(it.date, d) }) }
        val terms = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return sorted(all.filter { k -> terms.all { matches(k, it) } })
    }

    private fun matches(k: Known, term: String): Boolean {
        if (term == "undated" || term == "nodate") return k.date == null
        if (looksLikeDate(term)) PhotoDate.parse(term)?.let { return overlaps(k.date, it) }
        // The start of a word: "ann" finds Anna and Annie, not Joanne.
        return Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(term)).containsMatchIn(k.words)
    }

    private fun looksLikeDate(s: String) = s.any(Char::isDigit)

    private fun overlaps(a: PhotoDate?, b: PhotoDate): Boolean = a != null && !a.to.isBefore(b.from) && !a.from.isAfter(b.to) &&
        // A photo known only to "the 80s" isn't a match for 1987: it might be, but showing it would be a guess.
        (a.to.toEpochDay() - a.from.toEpochDay()) <= (b.to.toEpochDay() - b.from.toEpochDay()) + 31

    private fun sorted(list: List<Known>) = list.sortedWith(compareBy({ it.date == null }, { it.date?.from }, { it.print.albumId }, { it.print.page }, { it.print.position })).map { it.print }

    /** The smart albums for this library, the biggest kinds first: people, then decades, places and stories. */
    fun smart(all: List<Known>): List<Smart> {
        val out = mutableListOf<Smart>()
        val byPerson = all.flatMap { k -> k.people.map { it to k } }.groupBy({ it.first }, { it.second })
        byPerson.entries.filter { it.value.size >= MIN_SMART }.sortedByDescending { it.value.size }
            .forEach { (name, ks) -> out += Smart(name, name, sorted(ks)) }
        val byDecade = all.filter { it.date != null && it.date.to.year - it.date.from.year < 10 }
            .groupBy { it.date!!.representative.year / 10 * 10 }
        byDecade.entries.filter { it.value.size >= MIN_SMART }.sortedBy { it.key }
            .forEach { (decade, ks) -> out += Smart("The ${decade}s", "${decade}s", sorted(ks)) }
        val byPlace = all.filter { !it.print.place.isNullOrBlank() }.groupBy { it.print.place!!.trim() }
        byPlace.entries.filter { it.value.size >= MIN_SMART }.sortedByDescending { it.value.size }
            .forEach { (place, ks) -> out += Smart(place, place, sorted(ks)) }
        all.filter { it.hasStory }.takeIf { it.isNotEmpty() }?.let { out += Smart("With a story", "story", sorted(it)) }
        all.filter { it.print.backFile != null }.takeIf { it.size >= MIN_SMART }?.let { out += Smart("Writing on the back", "back", sorted(it)) }
        return out
    }
}
