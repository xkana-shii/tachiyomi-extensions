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

// ************ Chapter List ************ //
@Serializable
data class ApiChapterListVariables(
    val comicId: String,
    val start: Int, // set to -1 to grab all chapters
)

@Serializable
data class ApiChapterListResponse(
    val data: ChapterListData,
) {
    @Serializable
    data class ChapterListData(
        @SerialName("get_content_chapterList") val response: List<ChapterNode>,
    ) {
        @Serializable
        data class ChapterNode(
            val data: ChapterData,
        ) {
            @Serializable
            data class ChapterData(
                val id: String,
                val dname: String? = null,
                val title: String? = null,
                val urlPath: String,
                val dateCreate: Long? = null,
                val dateModify: Long? = null,
                val userNode: UserNode? = null,
                val groupNode: GroupNode? = null,
            ) {
                @Serializable
                data class UserNode(
                    val data: UserData,
                ) {

                    @Serializable
                    data class UserData(
                        val name: String? = null,
                    )
                }

                @Serializable
                data class GroupNode(
                    val data: GroupData,
                ) {

                    @Serializable
                    data class GroupData(
                        val name: String? = null,
                    )
                }

                fun toSChapter(): SChapter = SChapter.create().apply {
                    url = urlPath
                    name = buildString {
                        if (!dname.isNullOrEmpty()) {
                            append(dname)
                        }
                        if (!title.isNullOrEmpty()) {
                            if (isNotEmpty()) append(": ")
                            append(title)
                        }
                    }.ifEmpty { "Unnamed Chapter: $id" }
                    date_upload = dateModify ?: dateCreate ?: 0L
                    scanlator = groupNode?.data?.name ?: userNode?.data?.name ?: "Unknown"
                }
            }
        }
    }
}

// ************ Chapter Pages ************ //
@Serializable
data class ApiChapterNodeVariables(
    val id: String,
)

@Serializable
data class ApiChapterNodeResponse(
    val data: ChapterNodeData,
) {
    @Serializable
    data class ChapterNodeData(
        @SerialName("get_chapterNode") val response: ChapterNode,
    ) {
        @Serializable
        data class ChapterNode(
            val data: ChapterData,
        ) {
            @Serializable
            data class ChapterData(
                val imageFile: ChapterImageFile,
            ) {
                @Serializable
                data class ChapterImageFile(
                    val urlList: List<String>,
                )
            }
        }
    }
}

@Serializable
data class ApiSearchPayload(
    val variables: Variables,
    val query: String,
) {
    @Serializable
    data class Variables(
        val select: Select,
    )

    @Serializable
    data class Select(
        val page: Int,
        val size: Int,
        val where: String,
        val word: String,
        val sort: String,
        val incGenres: List<String>,
        val excGenres: List<String>,
        val incOLangs: List<String>,
        val incTLangs: String,
        val origStatus: String,
        val batoStatus: String,
        val chapCount: String,
    )

    constructor(
        pageNumber: Int,
        size: Int,
        sort: String?,
        query: String = "",
        where: String = "browse",
        incGenres: List<String>? = emptyList(),
        excGenres: List<String>? = emptyList(),
        incOLangs: List<String>? = emptyList(),
        incTLangs: String? = "",
        origStatus: String? = "",
        batoStatus: String? = "",
        chapCount: String? = "",
    ) : this(
        Variables(
            Select(
                page = pageNumber,
                size = size,
                where = where,
                word = query,
                sort = sort ?: "",
                incGenres = incGenres ?: emptyList(),
                excGenres = excGenres ?: emptyList(),
                incOLangs = incOLangs ?: emptyList(),
                incTLangs = incTLangs ?: "",
                origStatus = origStatus ?: "",
                batoStatus = batoStatus ?: "",
                chapCount = chapCount ?: "",
            ),
        ),
        SEARCH_QUERY,
    )
}

@Serializable
data class ApiQueryPayload(
    val variables: Variables,
    val query: String,
) {
    @Serializable
    data class Variables(
        val id: String,
    )

    constructor(
        id: String,
        query: String,
    ) : this(
        Variables(id),
        query,
    )
}
