package eu.kanade.tachiyomi.extension.en.desirescans

import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl

internal interface UriFilter {
    fun addToUrl(builder: HttpUrl.Builder)
}

internal open class DynamicUriSelectFilter(
    name: String,
    private val param: String,
    entries: List<Pair<String, String>>,
    private val firstIsUnspecified: Boolean = true,
    state: Int = 0,
) : Filter.Select<String>(
    name,
    entries.map { it.first }.toTypedArray(),
    state.coerceIn(0, (entries.size - 1).coerceAtLeast(0)),
),
    UriFilter {
    // KNS
    val entries: List<Pair<String, String>> = entries.ifEmpty { listOf("Any" to "") }
    // KNS

    override fun addToUrl(builder: HttpUrl.Builder) {
        // KNS
        val selected = entries[state].second
        if ((state != 0 || !firstIsUnspecified) && selected.isNotBlank()) {
            builder.addQueryParameter(param, selected)
        }
        // KNS
    }
}

internal class DynamicSortFilter(entries: List<Pair<String, String>>) :
    DynamicUriSelectFilter(
        name = "Sort",
        param = "sort",
        entries = entries,
        firstIsUnspecified = false,
    )

internal class DynamicStatusFilter(entries: List<Pair<String, String>>) :
    DynamicUriSelectFilter(
        name = "Status",
        param = "status",
        entries = entries,
    )

internal class DynamicOriginFilter(entries: List<Pair<String, String>>) :
    DynamicUriSelectFilter(
        name = "Origin",
        param = "origin",
        entries = entries,
    )

internal class DynamicTypeFilter(entries: List<Pair<String, String>>) :
    DynamicUriSelectFilter(
        name = "Type",
        param = "type",
        entries = entries,
    )

internal class GenreFilter(name: String, val value: String) : Filter.CheckBox(name, false)
internal class TagFilter(name: String, val value: String) : Filter.CheckBox(name, false)

internal class GenreFilterGroup(entries: List<Pair<String, String>>) :
    Filter.Group<GenreFilter>(
        "Genres",
        entries.map { GenreFilter(it.first, it.second) },
    )

internal class TagFilterGroup(entries: List<Pair<String, String>>) :
    Filter.Group<TagFilter>(
        "Tags",
        entries.map { TagFilter(it.first, it.second) },
    )
