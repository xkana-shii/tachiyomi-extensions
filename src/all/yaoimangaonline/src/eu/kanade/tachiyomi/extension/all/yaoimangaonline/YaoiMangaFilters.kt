package eu.kanade.tachiyomi.extension.all.yaoimangaonline

import eu.kanade.tachiyomi.source.model.Filter

class TypeFilter(
    values: Array<Pair<String, String>> = arrayOf("ALL" to ""),
) : Filter.Select<String>("Type", values.map { it.first }.toTypedArray()) {
    val vals = values
    override fun toString() = vals[state].second.trim()
}

class DoujinshiFilter(
    values: Array<Pair<String, String>> = arrayOf("ALL" to ""),
) : Filter.Select<String>("Doujinshi", values.map { it.first }.toTypedArray()) {
    val vals = values
    override fun toString() = vals[state].second.trim()
}

class TagFilter(
    values: Array<Pair<String, String>> = arrayOf("ALL" to ""),
) : Filter.Select<String>("Tag", values.map { it.first }.toTypedArray()) {
    val vals = values
    override fun toString() = vals[state].second.trim()
}
