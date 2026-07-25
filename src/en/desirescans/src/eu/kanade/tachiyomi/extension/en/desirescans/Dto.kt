package eu.kanade.tachiyomi.extension.en.desirescans

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.time.Instant

@Serializable
internal class BrowsePageDto(
    val initialSeries: List<BrowseSeriesDto>,
    val initialHasMore: Boolean,
)

@Serializable
internal class BrowseSeriesDto(
    private val slug: String,
    private val title: String,
    private val coverImage: String? = null,
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        url = slug
        title = this@BrowseSeriesDto.title
        thumbnail_url = coverImage?.toAbsoluteUrl(baseUrl)
    }
}

@Serializable
internal class SeriesPageDto(
    val series: SeriesDto,
    val chapters: List<ChapterDto>,
)

@Serializable
internal class SeriesDto(
    private val title: String,
    private val slug: String,
    private val altTitle: String? = null,
    private val origin: String? = null,
    private val originalTitle: String? = null,
    private val aliases: List<String> = emptyList(),
    private val description: String? = null,
    private val coverImage: String? = null,
    private val status: String,
    private val type: String,
    private val genres: List<OptionDto> = emptyList(),
    private val tags: List<OptionDto> = emptyList(),
) {
    fun toSManga(
        baseUrl: String,
        authorName: String?,
    ) = SManga.create().apply {
        url = slug
        title = this@SeriesDto.title
        thumbnail_url = coverImage?.toAbsoluteUrl(baseUrl)

        authorName
            ?.takeIf { it.isNotBlank() }
            ?.let { author = it }

        genre = buildList {
            add(type.toDisplayName())
            addAll(genres.map { it.name })
            addAll(tags.map { it.name })
        }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .joinToString()

        description = buildDescription()
        status = this@SeriesDto.status.toMangaStatus()
    }

    private fun buildDescription(): String? {
        val alternativeTitles = buildList {
            altTitle?.let(::add)
            originalTitle?.let(::add)
            addAll(aliases)
        }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.equals(title, ignoreCase = true) }
            .distinctBy { it.lowercase() }

        return buildString {
            description
                ?.trimEnd()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::append)

            if (alternativeTitles.isNotEmpty()) {
                if (isNotEmpty()) {
                    append("\n\n")
                }

                append("----\n")
                append("#### **Alternative Titles**\n")

                alternativeTitles.forEachIndexed { index, alternativeTitle ->
                    append("- ")
                    append(alternativeTitle)

                    if (index != alternativeTitles.lastIndex) {
                        append('\n')
                    }
                }
            }
        }.ifBlank { null }
    }
}

@Serializable
internal class ChapterDto(
    private val number: Double,
    private val title: String,
    private val isLocked: Boolean,
    private val publishedAt: String? = null,
    private val hasAccess: Boolean = false,
) {
    fun toSChapter(seriesSlug: String): SChapter {
        val chapterNumber = number
            .toString()
            .removeSuffix(".0")

        val unavailable = isLocked && !hasAccess

        return SChapter.create().apply {
            url = "$seriesSlug/$chapterNumber"
            name = if (unavailable) {
                "🔒 $title"
            } else {
                title
            }

            chapter_number = number.toFloat()
            date_upload = publishedAt
                ?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
                ?: 0L
        }
    }
}

@Serializable
internal class FilterDataDto(
    val genres: List<OptionDto>,
    val tags: List<OptionDto>,
)

@Serializable
internal class OptionDto(
    val name: String,
    val slug: String,
)

@Serializable
internal class BookDto(
    @SerialName("@type")
    val type: String? = null,
    val author: AuthorDto? = null,
)

@Serializable
internal class AuthorDto(
    val name: String? = null,
)

private fun String.toAbsoluteUrl(baseUrl: String): String = baseUrl
    .toHttpUrl()
    .resolve(this)
    ?.toString()
    ?: this

private fun String.toDisplayName(): String = lowercase()
    .split('_')
    .joinToString(" ") { word ->
        word.replaceFirstChar { it.titlecase() }
    }

private fun String.toMangaStatus(): Int = when (uppercase()) {
    "ONGOING" -> SManga.ONGOING
    "COMPLETED" -> SManga.COMPLETED
    "HIATUS" -> SManga.ON_HIATUS
    "DROPPED",
    "DISCONTINUED",
    "CANCELLED",
    "CANCELED",
    -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}
