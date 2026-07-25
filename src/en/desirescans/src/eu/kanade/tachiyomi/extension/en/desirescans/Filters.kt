package eu.kanade.tachiyomi.extension.en.desirescans

import eu.kanade.tachiyomi.source.model.Filter

internal class SortFilter :
    Filter.Sort(
        "Sort",
        SORT_OPTIONS.map { it.first }.toTypedArray(),
        Filter.Sort.Selection(0, false),
    ) {

    val value: String
        get() = SORT_OPTIONS[state?.index ?: 0].second

    companion object {
        private val SORT_OPTIONS = arrayOf(
            "Recently Updated" to "",
            "Most Bookmarked" to "popular",
            "Most Viewed" to "views",
            "Longest" to "chapters",
            "Trending" to "trending",
            "Top Rated" to "rating",
            "Newest" to "newest",
        )
    }
}

internal class TypeFilter :
    Filter.Group<TypeCheckBox>(
        "Types",
        TYPE_OPTIONS.map { (name, value) ->
            TypeCheckBox(name, value)
        },
    ) {

    val selectedValues: List<String>
        get() = state
            .filter { it.state }
            .map { it.value }

    companion object {
        private val TYPE_OPTIONS = arrayOf(
            "Manhwa" to "Manhwa",
            "Manhua" to "Manhua",
            "Manga" to "Manga",
            "Webtoon" to "Webtoon",
        )
    }
}

internal class TypeCheckBox(
    name: String,
    val value: String,
) : Filter.CheckBox(name, true)

internal class StatusFilter :
    Filter.Select<String>(
        "Status",
        STATUS_OPTIONS.map { it.first }.toTypedArray(),
    ) {

    val value: String
        get() = STATUS_OPTIONS[state].second

    companion object {
        private val STATUS_OPTIONS = arrayOf(
            "All" to "",
            "Ongoing" to "ONGOING",
            "Completed" to "COMPLETED",
            "Hiatus" to "HIATUS",
            "Dropped" to "DROPPED",
            "Discontinued" to "DISCONTINUED",
            "Upcoming" to "UPCOMING",
        )
    }
}

internal class OriginFilter :
    Filter.Select<String>(
        "Origin",
        ORIGIN_OPTIONS.map { it.first }.toTypedArray(),
    ) {

    val value: String
        get() = ORIGIN_OPTIONS[state].second

    companion object {
        private val ORIGIN_OPTIONS = arrayOf(
            "All Origins" to "",
            "Korean" to "KOREAN",
            "Japanese" to "JAPANESE",
            "Chinese" to "CHINESE",
            "Other" to "OTHER",
        )
    }
}

internal class OnSaleFilter : Filter.CheckBox("On Sale")

internal class HasImagesFilter : Filter.CheckBox("Has Images")

internal class MinimumChaptersFilter : Filter.Text("Minimum chapters")

internal class MaximumChaptersFilter : Filter.Text("Maximum chapters")

internal class GenreFilter(
    options: List<OptionDto>,
) : Filter.Group<UriPartTriState>(
    "Genres",
    options.map { option ->
        UriPartTriState(
            name = option.name,
            value = option.slug,
        )
    },
) {

    val includedValues: List<String>
        get() = state
            .filter {
                it.state == Filter.TriState.STATE_INCLUDE
            }
            .map {
                it.value
            }

    val excludedValues: List<String>
        get() = state
            .filter {
                it.state == Filter.TriState.STATE_EXCLUDE
            }
            .map {
                it.value
            }
}

internal class TagFilter(
    options: List<OptionDto>,
) : Filter.Group<UriPartTriState>(
    "Tags",
    options.map { option ->
        UriPartTriState(
            name = option.name,
            value = option.slug,
        )
    },
) {

    val includedValues: List<String>
        get() = state
            .filter {
                it.state == Filter.TriState.STATE_INCLUDE
            }
            .map {
                it.value
            }

    val excludedValues: List<String>
        get() = state
            .filter {
                it.state == Filter.TriState.STATE_EXCLUDE
            }
            .map {
                it.value
            }
}

internal class UriPartTriState(
    name: String,
    val value: String,
) : Filter.TriState(name)
