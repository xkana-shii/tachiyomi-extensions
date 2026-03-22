package eu.kanade.tachiyomi.extension.en.kagane

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
class GenreDto(
    val id: String,
    @SerialName("genre_name")
    val genreName: String,
)

@Serializable
class TagDto(
    val id: String,
    @SerialName("tag_name")
    val tagName: String,
)

@Serializable
class SourcesDto(
    val sources: List<SourceDto>,
)

@Serializable
data class SourceDto(
    @SerialName("source_id") val sourceId: String,
    @SerialName("source_type") val sourceType: String, // "Official", "Unofficial", "Mixed"
    val title: String,
)

@Serializable
class SearchDto(
    val content: List<Book> = emptyList(),
    val last: Boolean = true,
    @SerialName("total_elements")
    val totalElements: Int = 0,
    @SerialName("total_pages")
    val totalPages: Int = 0,
) {
    fun hasNextPage() = !last

    @Serializable
    class Book(
        @SerialName("series_id")
        val id: String,
        val title: String,
        @SerialName("source_id")
        val sourceId: String? = null,
        @SerialName("current_books")
        val booksCount: Int,
        @SerialName("start_year")
        val startYear: Int? = null,
        @SerialName("cover_image_id")
        val coverImage: String? = null,
        @SerialName("alternate_titles")
        val alternateTitles: List<String> = emptyList(),
    ) {

        fun toSManga(domain: String, showSource: Boolean, sources: Map<String, String>, removeExtras: Boolean = false): SManga = SManga.create().apply {
            val cleanTitle = this@Book.title.trim().let { if (removeExtras) it.removeTitleExtras() else it }
            title = if (showSource) "$cleanTitle [${sources[this@Book.sourceId]}]" else cleanTitle
            url = id
            thumbnail_url = coverImage?.let { "$domain/api/v2/image/$it" }
        }
    }
}

@Serializable
class AlternateSeries(
    @SerialName("current_books")
    val booksCount: Int,
    @SerialName("start_year")
    val startYear: Int? = null,
)

@Serializable
class DetailsDto(
    val title: String,
    val description: String?,
    @SerialName("upload_status")
    val publicationStatus: String,
    val format: String?,
    @SerialName("source_id")
    val sourceId: String?,
    @SerialName("series_staff")
    val seriesStaff: List<SeriesStaff> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val tags: List<Tag> = emptyList(),
    @SerialName("series_alternate_titles")
    val seriesAlternateTitles: List<AlternateTitle> = emptyList(),
    @SerialName("series_books")
    val seriesBooks: List<ChapterDto.Book> = emptyList(),
    @SerialName("edition_info")
    val editionInfo: String? = null,
) {
    @Serializable
    class SeriesStaff(
        val name: String,
        val role: String,
    )

    @Serializable
    class Genre(
        @SerialName("genre_name")
        val genreName: String,
    )

    @Serializable
    class Tag(
        @SerialName("tag_name")
        val tagName: String,
    )

    @Serializable
    class AlternateTitle(
        val title: String,
        val label: String?,
    )

    fun toSManga(sourceName: String? = null, baseUrl: String = "", showEdition: Boolean = false, showSource: Boolean = false, removeExtras: Boolean = false): SManga = SManga.create().apply {
        val base = this@DetailsDto.title.trim().let { if (removeExtras) it.removeTitleExtras() else it }
        val withEdition = if (showEdition && !this@DetailsDto.editionInfo.isNullOrBlank()) "$base (${this@DetailsDto.editionInfo})" else base
        title = if (showSource && sourceName != null) "$withEdition [$sourceName]" else withEdition
        val desc = StringBuilder()

        // Add main description
        this@DetailsDto.description?.takeIf { it.isNotBlank() }?.let {
            desc.append(it.trim())
            desc.append("\n")
        }

        // Add alternate titles at the end
        if (seriesAlternateTitles.isNotEmpty()) {
            if (desc.isNotEmpty()) desc.append("\n")
            desc.append("Associated Name(s):\n")
            seriesAlternateTitles.forEach {
                desc.append("• ${it.title}\n")
            }
        }

        // Extract authors and artists from staff (roles like "Author", "Artist", "Story", "Art")
        val authors = seriesStaff.filter {
            it.role.contains("Author", ignoreCase = true) || it.role.contains("Story", ignoreCase = true)
        }.map { it.name }.distinct()
        val artists = seriesStaff.filter {
            it.role.contains("Artist", ignoreCase = true) || it.role.contains("Art", ignoreCase = true)
        }
            .map { it.name }
            .distinct()
            .joinToString(", ")

        artist = artists
        author = authors.joinToString()
        description = desc.toString().trim()
        genre = buildList {
            sourceName?.takeIf { it.isNotBlank() }?.let { add(it) }
            this@DetailsDto.format?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(genres.map { it.genreName })
        }.joinToString()
        status = this@DetailsDto.publicationStatus.toStatus()
    }

    private fun String.toStatus(): Int = when (this.uppercase()) {
        "ONGOING" -> SManga.ONGOING
        "COMPLETED" -> SManga.COMPLETED
        "HIATUS" -> SManga.ON_HIATUS
        "ABANDONED" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }
}

@Serializable
class ChapterDto(
    @SerialName("series_books")
    val seriesBooks: List<Book>,
) {
    @Serializable
    class Book(
        @SerialName("book_id")
        val id: String,
        @SerialName("series_id")
        val seriesId: String? = null,
        val title: String,
        @SerialName("created_at")
        val createdAt: String?,
        @SerialName("page_count")
        val pagesCount: Int,
        @SerialName("sort_no")
        val number: Float,
        @SerialName("chapter_no")
        val chapterNo: String?,
        @SerialName("volume_no")
        val volumeNo: String?,
        val groups: List<Group> = emptyList(),
    ) {
        fun toSChapter(actualSeriesId: String, useSourceChapterNumber: Boolean = false, chapterTitleMode: String = "smart_vol_chapter"): SChapter = SChapter.create().apply {
            url = "/series/$actualSeriesId/reader/$id"
            name = buildChapterName(chapterTitleMode)
            date_upload = dateFormat.tryParse(createdAt)
            if (useSourceChapterNumber) {
                chapter_number = number
            }
            scanlator = groups.joinToString(", ") { it.title }
        }

        private fun buildChapterName(mode: String = "smart_vol_chapter"): String {
            val trimmedTitle = title.trim()
            return when (mode) {
                "always" -> {
                    when {
                        chapterNo.isNullOrBlank() -> trimmedTitle
                        trimmedTitle.isEmpty() -> "Ch.$chapterNo"
                        else -> "Ch.$chapterNo $trimmedTitle"
                    }
                }

                "vol_chapter" -> {
                    val volPart = if (!volumeNo.isNullOrBlank()) "Vol.$volumeNo " else ""
                    val chPart = if (!chapterNo.isNullOrBlank()) "Ch.$chapterNo" else ""
                    val numPart = "$volPart$chPart".trim()
                    when {
                        numPart.isEmpty() -> trimmedTitle
                        trimmedTitle.isEmpty() -> numPart
                        else -> "$numPart $trimmedTitle"
                    }
                }

                "smart_vol_chapter" -> {
                    val volPart = if (!volumeNo.isNullOrBlank()) "Vol.$volumeNo " else ""
                    val chPart = if (!chapterNo.isNullOrBlank()) "Ch.$chapterNo" else ""
                    val numPart = "$volPart$chPart".trim()

                    when {
                        numPart.isEmpty() -> trimmedTitle
                        trimmedTitle.isEmpty() -> numPart
                        SMART_KEYWORDS.any { trimmedTitle.contains(it, ignoreCase = true) } -> trimmedTitle
                        SMART_SEASON_REGEX.matches(trimmedTitle) -> trimmedTitle
                        SMART_ANY_CHAPTER_REGEX.matches(trimmedTitle) -> trimmedTitle
                        else -> {
                            val leadingMatch = SMART_LEADING_NUMBER_REGEX.find(trimmedTitle)
                            if (leadingMatch != null) {
                                val leadingNum = leadingMatch.groupValues[1]
                                val sameNumber = leadingNum.toIntOrNull()?.let { it == chapterNo?.toIntOrNull() }
                                    ?: (leadingNum == chapterNo)
                                if (sameNumber) return trimmedTitle
                            }
                            "$numPart $trimmedTitle"
                        }
                    }
                }

                else -> {
                    if (chapterNo.isNullOrBlank()) return trimmedTitle
                    if (trimmedTitle.isEmpty()) return "Ch.$chapterNo"

                    if (SMART_KEYWORDS.any { trimmedTitle.contains(it, ignoreCase = true) }) return trimmedTitle

                    if (SMART_SEASON_REGEX.matches(trimmedTitle)) return trimmedTitle
                    if (SMART_ANY_CHAPTER_REGEX.matches(trimmedTitle)) return trimmedTitle

                    val leadingMatch = SMART_LEADING_NUMBER_REGEX.find(trimmedTitle)
                    if (leadingMatch != null) {
                        val leadingNum = leadingMatch.groupValues[1]
                        val sameNumber = leadingNum.toIntOrNull()?.let { it == chapterNo.toIntOrNull() }
                            ?: (leadingNum == chapterNo)
                        if (sameNumber) return trimmedTitle
                    }

                    "Ch.$chapterNo $trimmedTitle"
                }
            }
        }
    }

    @Serializable
    class Group(
        val title: String,
    )

    companion object {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)

        val SMART_KEYWORDS = listOf(
            "hiatus", "special episode", "season", "special", "finale",
            "bonus", "romantasy au", "historical au", "side story",
            "creator's note", "scheduled break",
        )

        val SMART_SEASON_REGEX = Regex(
            "^\\(S\\d+\\)\\s*(Chapter|Episode|Ch|Ep).*",
            RegexOption.IGNORE_CASE,
        )

        val SMART_ANY_CHAPTER_REGEX = Regex(
            "^(Chapter|Ch\\.|Ch|Episode|Ep\\.|Ep)\\s*\\d+.*",
            RegexOption.IGNORE_CASE,
        )

        val SMART_LEADING_NUMBER_REGEX = Regex("^(\\d+)(?:[.:\\-\\s].*|$)")
    }
}

@Serializable
class ChallengeDto(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("cache_url")
    val cacheUrl: String,
    val pages: List<PageDto>,
)

@Serializable
class PageDto(
    @SerialName("page_number")
    val pageNumber: Int,
    @SerialName("page_uuid")
    val pageUuid: String,
)

@Serializable
class IntegrityDto(
    val token: String,
    val exp: Long,
)

private val TITLE_EXTRAS_KEYWORDS = listOf(
    "mature",
    "full ver",
    "uncensored",
    "special episode",
    "special",
    "bonus",
    "steamy",
    "uncut",
).joinToString("|") { Regex.escape(it) }

private val TITLE_EXTRAS_REGEX = Regex(
    """\s*(?:\[[^\]]*(?:$TITLE_EXTRAS_KEYWORDS)[^\]]{0,25}\]|\([^\)]*(?:$TITLE_EXTRAS_KEYWORDS)[^\)]{0,25}\))""",
    RegexOption.IGNORE_CASE,
)

private val WHITESPACE_REGEX = Regex("\\s+")

internal fun String.removeTitleExtras(): String = TITLE_EXTRAS_REGEX.replace(this, "")
    .replace(WHITESPACE_REGEX, " ")
    .trim { it.isWhitespace() || it == '-' || it == ':' }
