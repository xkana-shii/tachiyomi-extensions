package eu.kanade.tachiyomi.extension.all.yaoimangaonline

import eu.kanade.tachiyomi.source.model.Filter

class CategoryFilter(
    values: Array<Pair<String, String>> = arrayOf("ALL" to "-1"),
) : Filter.Select<String>("Category", values.map { it.first }.toTypedArray()) {
    val vals = values
    override fun toString() = vals[state].second
}

class TagFilter(
    values: Array<Pair<String, String>> = arrayOf("ALL" to ""),
) : Filter.Select<String>("Tag", values.map { it.first }.toTypedArray()) {
    val vals = values
    override fun toString() = vals[state].second
}
