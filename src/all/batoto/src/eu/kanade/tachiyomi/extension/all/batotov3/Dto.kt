package eu.kanade.tachiyomi.extension.all.batotov3

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import java.util.Locale

@Serializable
data class ApiSearchResponse(
    val data: SearchData,
) {
    @Serializable
    data class SearchData(
        @SerialName("get_content_searchComic") val search: SearchComic,
    ) {
        @Serializable
        data class SearchComic(
            val paging: PageInfo,
            val items: List<SearchItems>,
        ) {
            @Serializable
            data class PageInfo(
                @SerialName("pages") val total: Int,
                val page: Int,
            )

            @Serializable
            data class SearchItems(
                val data: SeriesDto,
            )
        }
    }
}

@Serializable
data class ApiDetailsResponse(
    val data: ComicNode,
) {
    @Serializable
    data class ComicNode(
        @SerialName("get_content_comicNode") val comicNode: MangaData,
    ) {
        @Serializable
        data class MangaData(
            val data: SeriesDto,
        )
    }
}

@Serializable
data class SeriesDto(
    val id: String,
    val name: String,
    val slug: String,
    val summary: SummaryDto?,
    val altNames: List<String>? = emptyList(),
    val authors: List<String>? = emptyList(),
    val artists: List<String>? = emptyList(),
    val genres: List<String>? = emptyList(),
    val originalStatus: String?,
    val uploadStatus: String?,
    @SerialName("urlCoverOri") val coverOriginal: String?,
    @SerialName("urlCover600") val coverMedium: String?,
    @SerialName("urlCover300") val coverLow: String?,
) {
    fun toSManga(cover: CoverQuality = CoverQuality.Original): SManga = SManga.create().apply {
        title = name.trim()
        url = "/series/$id/$slug"
        author = authors?.joinToString { it.trim() }
        artist = artists?.joinToString { it.trim() }
        description = summary?.text?.trim()
        if (!altNames.isNullOrEmpty()) {
            description += altNames.joinToString(
                prefix = "\n\nAlternative Names: \n* ",
                separator = "\n* ",
            ) { it.trim() }
        }
        genre = genres?.joinToString { genre ->
            genre
                .replace("_", " ")
                .replaceFirstChar {
                    if (it.isLowerCase()) {
                        it.titlecase(Locale.ROOT)
                    } else {
                        it.toString()
                    }
                }
        }
        thumbnail_url = when (cover) {
            CoverQuality.Medium -> coverMedium
            CoverQuality.Low -> coverLow
            else -> coverOriginal
        }
        status = parseStatus(originalStatus, uploadStatus)
    }

    private fun parseStatus(originalStatus: String?, uploadStatus: String?): Int {
        return when (originalStatus) {
            null -> SManga.UNKNOWN
            "ongoing" -> SManga.ONGOING
            "cancelled" -> SManga.CANCELLED
            "hiatus" -> SManga.ON_HIATUS
            "completed" -> when (uploadStatus) {
                "ongoing" -> SManga.PUBLISHING_FINISHED
                else -> SManga.COMPLETED
            }
            else -> SManga.UNKNOWN
        }
    }

    @Serializable
    data class SummaryDto(
        val text: String?,
    )
}

enum class CoverQuality {
    Original,
    Medium,
    Low,
}

@Serializable
data class ApiChapterListResponse(
    val data: ChapterList,
) {
    @Serializable
    data class ChapterList(
        @SerialName("get_content_chapterList") val chapters: List<ChapterData>,
    ) {
        @Serializable
        data class ChapterData(
            val data: ChapterDto,
        ) {
            @Serializable
            data class ChapterDto(
                val id: String,
                val title: String? = null,
                val chaNum: Float,
                val urlPath: String,
                val dateCreate: JsonPrimitive? = null,
                val dateModify: JsonPrimitive? = null,
                @SerialName("userNode") val user: ScanlatorNode? = null,
                @SerialName("groupNodes") val groups: List<ScanlatorNode>? = emptyList(),
            ) {
                fun toSChapter() = SChapter.create().apply {
                    url = urlPath
                    name = "Chapter ${chaNum.parseChapterNumber()}"
                    if (!title.isNullOrEmpty()) {
                        name += ": $title"
                    }
                    chapter_number = chaNum
                    if (!groups.isNullOrEmpty()) {
                        scanlator = groups
                            .filterNot { it.data.name.isNullOrEmpty() }
                            .joinToString { it.data.name?.trim().toString() }
                    } else if (user != null && user.data.name != null) {
                        scanlator = "Uploaded by ${user.data.name.trim()}"
                    }
                    date_upload = dateModify?.parseDate() ?: dateCreate?.parseDate() ?: 0L
                }

                private fun Float.parseChapterNumber(): String {
                    return this.toString().replace(BatoToV3.chapterNumRegex, "")
                }

                private fun JsonPrimitive.parseDate(): Long? {
                    // api sometimes return string and sometimes long 🗿
                    return runCatching {
                        if (this.isString) {
                            BatoToV3.DATE_FORMATTER.parse(this.toString())!!.time
                        } else {
                            return this.long
                        }
                    }.getOrNull()
                }

                @Serializable
                data class ScanlatorNode(
                    val data: NameDto,
                ) {
                    @Serializable
                    data class NameDto(
                        val name: String? = null,
                    )
                }
            }
        }
    }
}

@Serializable
data class ApiPageListResponse(
    val data: ChapterNode,
) {
    @Serializable
    data class ChapterNode(
        @SerialName("get_content_chapterNode") val pageList: PageList,
    ) {
        @Serializable
        data class PageList(
            val data: PageData,
        ) {
            @Serializable
            data class PageData(
                val imageFiles: List<String>? = emptyList(),
            )
        }
    }
}
