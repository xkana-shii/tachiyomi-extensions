package eu.kanade.tachiyomi.extension.all.patreon

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
data class PatreonApiRoot(
    val data: JsonElement = JsonArray(emptyList()),
    val included: List<PatreonResource> = emptyList(),
    val links: PatreonLinks? = null,
)

@Serializable
data class PatreonLinks(
    val next: String? = null,
)

@Serializable
data class PatreonPost(
    val id: String,
    val type: String? = null,
    val attributes: PatreonAttributes = PatreonAttributes(),
    val relationships: PatreonRelationships = PatreonRelationships(),
)

@Serializable
data class PatreonResource(
    val id: String,
    val type: String? = null,
    val attributes: PatreonAttributes = PatreonAttributes(),
    val relationships: PatreonRelationships = PatreonRelationships(),
)

@Serializable
data class PatreonResourceRef(
    val id: String,
    val type: String? = null,
)

@Serializable
data class PatreonRelationships(
    @SerialName("active_memberships") val activeMemberships: PatreonRelationship? = null,
    @SerialName("attachments_media") val attachmentsMedia: PatreonRelationship? = null,
    @SerialName("card_campaign") val cardCampaign: PatreonRelationship? = null,
    val attachments: PatreonRelationship? = null,
    val images: PatreonRelationship? = null,
    val campaign: PatreonRelationship? = null,
    val items: PatreonRelationship? = null,
    val topic: PatreonRelationship? = null,
    val filter: PatreonRelationship? = null,
    val user: PatreonRelationship? = null,
)

@Serializable
data class PatreonRelationship(
    val data: JsonElement? = null,
)

@Serializable
data class PatreonAttributes(
    val name: String? = null,
    val title: String? = null,
    val url: String? = null,
    @SerialName("url_for_current_user") val urlForCurrentUser: String? = null,
    val vanity: String? = null,
    val summary: String? = null,
    val content: String? = null,
    val description: String? = null,
    val embed: JsonElement? = null,
    val image: PatreonImage? = null,
    @SerialName("content_json_string") val contentJsonString: String? = null,
    @SerialName("current_user_can_view") val currentUserCanView: Boolean? = null,
    @SerialName("current_user_is_free_member") val currentUserIsFreeMember: Boolean? = null,
    @SerialName("current_user_is_teammate_or_owner") val currentUserIsTeammateOrOwner: Boolean? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("patreon_url") val patreonUrl: String? = null,
    @SerialName("post_file") val postFile: PatreonPostFile? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("image_urls") val imageUrls: PatreonImageUrls? = null,
    @SerialName("avatar_photo_url") val avatarPhotoUrl: String? = null,
    @SerialName("avatar_photo_image_urls") val avatarPhotoImageUrls: PatreonImageUrls? = null,
    @SerialName("avatar_photo_blurred_url") val avatarPhotoBlurredUrl: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("cover_photo_url") val coverPhotoUrl: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("campaign_id") val campaignId: JsonElement? = null,
    @SerialName("creation_name") val creationName: String? = null,
    @SerialName("post_count") val postCount: Int? = null,
    @SerialName("member_count") val memberCount: Int? = null,
    @SerialName("patron_count") val patronCount: Int? = null,
    @SerialName("is_nsfw") val isNsfw: Boolean? = null,
    @SerialName("is_free_member") val isFreeMember: Boolean? = null,
    @SerialName("is_paid_member") val isPaidMember: Boolean? = null,
    @SerialName("is_free_trial") val isFreeTrial: Boolean? = null,
    @SerialName("has_rss") val hasRss: Boolean? = null,
    @SerialName("main_video_embed") val mainVideoEmbed: String? = null,
    @SerialName("main_video_url") val mainVideoUrl: String? = null,
    @SerialName("recommendation_reason") val recommendationReason: String? = null,
)

@Serializable
data class PatreonImage(
    val url: String? = null,
    @SerialName("large_url") val largeUrl: String? = null,
)

@Serializable
data class PatreonPostFile(
    val name: String? = null,
    val url: String? = null,
)

@Serializable
data class PatreonImageUrls(
    val original: String? = null,
    val default: String? = null,
    val large: String? = null,
    val thumbnail: String? = null,
    @SerialName("default_large") val defaultLarge: String? = null,
)

fun PatreonApiRoot.dataPosts(json: Json): List<PatreonPost> = when (data) {
    is JsonArray -> data.mapNotNull { element ->
        runCatching {
            json.decodeFromJsonElement<PatreonPost>(element)
        }.getOrNull()
    }
    is JsonObject -> listOfNotNull(
        runCatching {
            json.decodeFromJsonElement<PatreonPost>(data)
        }.getOrNull(),
    )
    else -> emptyList()
}

fun PatreonApiRoot.dataResource(json: Json): PatreonResource = when (data) {
    is JsonObject -> json.decodeFromJsonElement(data)
    else -> throw Exception("Unexpected Patreon response")
}

fun PatreonApiRoot.currentUserMembershipResults(json: Json, baseUrl: String): List<SManga> {
    val includedById = included.associateBy { it.id }
    val currentUser = data.asResourceList(json).firstOrNull() ?: return emptyList()

    return relationshipRefs(currentUser.relationships.activeMemberships, json).mapNotNull { membershipRef ->
        val membership = includedById[membershipRef.id] ?: return@mapNotNull null
        val campaignRef = relationshipRef(membership.relationships.campaign, json) ?: return@mapNotNull null
        val campaign = includedById[campaignRef.id] ?: return@mapNotNull null
        val campaignId = campaign.attributes.campaignId.asString()
            ?: campaign.id.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null

        campaign.toMembershipSManga(campaignId, baseUrl)
    }.distinctBy { it.url }
}

fun PatreonApiRoot.exploreCampaignResults(json: Json, baseUrl: String): List<SManga> {
    val includedById = included.associateBy { it.id }
    val result = mutableListOf<SManga>()

    data.asResourceList(json).forEach { section ->
        relationshipRefs(section.relationships.items, json).forEach { ref ->
            val item = includedById[ref.id] ?: return@forEach
            val campaign = item.resolveExploreCampaign(includedById, json) ?: return@forEach
            val campaignId = campaign.attributes.campaignId.asString()
                ?: campaign.id.takeIf { it.isNotBlank() }
                ?: return@forEach

            result.add(campaign.toExploreSManga(campaignId, baseUrl))
        }
    }

    if (result.isEmpty()) {
        included.forEach { resource ->
            val campaign = resource.resolveExploreCampaign(includedById, json) ?: return@forEach
            val campaignId = campaign.attributes.campaignId.asString()
                ?: campaign.id.takeIf { it.isNotBlank() }
                ?: return@forEach

            result.add(campaign.toExploreSManga(campaignId, baseUrl))
        }
    }

    return result.distinctBy { it.url }
}

fun PatreonApiRoot.searchFeedCampaignResults(json: Json, baseUrl: String): List<SManga> {
    val includedById = included.associateBy { it.id }

    return data.asResourceList(json).mapNotNull { resource ->
        val campaign = resource.resolveCampaignFromSearchFeed(includedById, json) ?: return@mapNotNull null
        val campaignId = campaign.attributes.campaignId.asString()
            ?: campaign.id.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null

        campaign.toSearchSManga(campaignId, baseUrl)
    }.distinctBy { it.url }
}

fun PatreonApiRoot.searchResults(json: Json, baseUrl: String): List<SManga> {
    val includedById = included.associateBy { it.id }

    val elements = when (data) {
        is JsonArray -> data
        is JsonObject -> JsonArray(listOf(data))
        else -> JsonArray(emptyList())
    }

    return elements.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val type = obj.string("type").orEmpty()
        val id = obj.string("id").orEmpty()
        val attrs = obj.obj("attributes") ?: JsonObject(emptyMap())

        val campaignRefId = obj.obj("relationships")
            ?.obj("campaign")
            ?.get("data")
            ?.let { relationshipData ->
                when (relationshipData) {
                    is JsonObject -> relationshipData.string("id")
                    else -> null
                }
            }

        val campaignFromIncluded = campaignRefId?.let { includedById[it] }

        val campaignId = when {
            type.contains("campaign", ignoreCase = true) && id.isNotBlank() -> id
            campaignRefId != null -> campaignRefId
            campaignFromIncluded != null -> campaignFromIncluded.id
            else -> null
        }

        val campaignAttrs = campaignFromIncluded?.attributes

        val title = campaignAttrs?.name
            ?: attrs.string("name")
            ?: attrs.string("title")
            ?: attrs.string("full_name")
            ?: attrs.string("vanity")
            ?: return@mapNotNull null

        val patreonUrl = campaignAttrs?.url
            ?: attrs.string("url")
            ?: attrs.string("patreon_url")
            ?: attrs.string("vanity")?.let { "$baseUrl/$it" }

        val username = campaignAttrs?.pageUsername()
            ?: attrs.string("vanity")
            ?: patreonUrl.usernameFromPatreonUrl()
            ?: title

        val thumbnail = campaignAttrs?.avatarPhotoUrl
            ?: campaignAttrs?.avatarPhotoImageUrls.best()
            ?: campaignAttrs?.avatarUrl
            ?: campaignAttrs?.coverPhotoUrl
            ?: attrs.string("avatar_photo_url")
            ?: attrs.obj("avatar_photo_image_urls")?.imageUrlsBest()
            ?: attrs.string("avatar_url")
            ?: attrs.string("image_url")
            ?: attrs.string("thumbnail_url")
            ?: attrs.string("cover_photo_url")
            ?: attrs.obj("image")?.string("url")
            ?: attrs.obj("avatar")?.string("url")

        val description = attrs.string("summary").htmlToMarkdown().orEmpty()

        SManga.create().apply {
            this.url = campaignId?.let { "/campaign/$it" } ?: patreonUrl.toSourcePath(baseUrl)
            this.title = title
            author = username
            artist = username
            thumbnail_url = thumbnail
            this.description = description
            initialized = true
        }
    }.distinctBy { it.url }
}

fun PatreonResource.toSManga(campaignId: String, fallbackName: String): SManga = SManga.create().apply {
    val username = attributes.pageUsername()

    this.url = "/campaign/$campaignId"
    title = attributes.name ?: fallbackName.ifBlank { "Patreon campaign $campaignId" }
    author = username
    artist = username
    thumbnail_url = attributes.avatarPhotoUrl
        ?: attributes.avatarPhotoImageUrls.best()
        ?: attributes.avatarUrl
        ?: attributes.coverPhotoUrl
        ?: attributes.coverUrl
    description = attributes.summary.htmlToMarkdown().orEmpty()
    initialized = true
}

fun PatreonPost.toSChapter(campaignId: String): SChapter = SChapter.create().apply {
    url = "/campaign/$campaignId/post/$id"
    name = attributes.title?.takeIf { it.isNotBlank() } ?: "Post $id"
    date_upload = attributes.publishedAt.parsePatreonDate()
    chapter_number = -2f
}

fun PatreonPost.imageUrls(root: PatreonApiRoot, json: Json): List<String> {
    if (attributes.currentUserCanView == false) return emptyList()

    val includedById = root.included.associateBy { it.id }
    val urls = mutableListOf<String>()

    val refs = mutableListOf<PatreonResourceRef>().apply {
        addAll(relationshipRefs(relationships.attachmentsMedia, json))
        addAll(relationshipRefs(relationships.attachments, json))
        addAll(relationshipRefs(relationships.images, json))
    }.distinctBy { it.id }

    refs.forEach { ref ->
        val media = includedById[ref.id] ?: return@forEach
        media.attributes.bestImageUrl()?.let { urls.add(it) }
    }

    attributes.postFile?.let { postFile ->
        val url = postFile.url

        if (!url.isNullOrBlank() && (postFile.name.isImageFileName() || url.isImageUrl())) {
            urls.add(url)
        }
    }

    attributes.image?.let { image ->
        listOf(image.largeUrl, image.url).forEach { url ->
            if (!url.isNullOrBlank() && url.isImageUrl()) {
                urls.add(url)
            }
        }
    }

    attributes.content?.extractImageUrlsFromHtml()?.let { urls.addAll(it) }
    attributes.contentJsonString?.extractImageUrlsFromText()?.let { urls.addAll(it) }

    return urls.distinct()
}

fun List<String>.toPages(): List<Page> = mapIndexed { index, url -> Page(index, imageUrl = url) }

private fun PatreonResource.resolveExploreCampaign(
    includedById: Map<String, PatreonResource>,
    json: Json,
): PatreonResource? {
    if (
        type?.contains("explore-campaign", ignoreCase = true) == true ||
        type?.contains("campaign", ignoreCase = true) == true
    ) {
        if (attributes.name != null || attributes.campaignId != null) {
            return this
        }
    }

    relationshipRef(relationships.campaign, json)?.id?.let { id ->
        includedById[id]?.let { return it }
    }

    return null
}

private fun PatreonResource.resolveCampaignFromSearchFeed(
    includedById: Map<String, PatreonResource>,
    json: Json,
): PatreonResource? {
    if (type?.contains("campaign", ignoreCase = true) == true && attributes.name != null) {
        return this
    }

    relationshipRef(relationships.campaign, json)?.id?.let { id ->
        includedById[id]?.let { return it }
    }

    relationshipRef(relationships.cardCampaign, json)?.id?.let { cardId ->
        val cardCampaign = includedById[cardId] ?: return@let

        relationshipRef(cardCampaign.relationships.campaign, json)?.id?.let { campaignId ->
            includedById[campaignId]?.let { return it }
        }

        if (cardCampaign.type?.contains("campaign", ignoreCase = true) == true) {
            return cardCampaign
        }
    }

    attributes.campaignId.asString()?.let {
        return this
    }

    return null
}

private fun PatreonResource.toMembershipSManga(campaignId: String, baseUrl: String): SManga = toCampaignSearchSManga(campaignId, baseUrl)

private fun PatreonResource.toExploreSManga(campaignId: String, baseUrl: String): SManga = toCampaignSearchSManga(campaignId, baseUrl)

private fun PatreonResource.toSearchSManga(campaignId: String, baseUrl: String): SManga = toCampaignSearchSManga(campaignId, baseUrl)

private fun PatreonResource.toCampaignSearchSManga(
    campaignId: String,
    baseUrl: String,
): SManga = SManga.create().apply {
    val username = attributes.pageUsername()

    this.url = "/campaign/$campaignId"
    title = attributes.name ?: "Patreon campaign $campaignId"
    author = username
    artist = username
    thumbnail_url = attributes.avatarPhotoUrl
        ?: attributes.avatarPhotoImageUrls.best()
        ?: attributes.avatarUrl
        ?: attributes.coverPhotoUrl
        ?: attributes.coverUrl
    description = attributes.summary.htmlToMarkdown().orEmpty()
    initialized = true
}

private fun JsonElement.asResourceList(json: Json): List<PatreonResource> = when (this) {
    is JsonArray -> mapNotNull { element ->
        runCatching {
            json.decodeFromJsonElement<PatreonResource>(element)
        }.getOrNull()
    }
    is JsonObject -> listOfNotNull(
        runCatching {
            json.decodeFromJsonElement<PatreonResource>(this)
        }.getOrNull(),
    )
    else -> emptyList()
}

private fun relationshipRefs(relationship: PatreonRelationship?, json: Json): List<PatreonResourceRef> {
    val data = relationship?.data ?: return emptyList()

    return when (data) {
        is JsonArray -> data.mapNotNull { element ->
            runCatching {
                json.decodeFromJsonElement<PatreonResourceRef>(element)
            }.getOrNull()
        }
        is JsonObject -> listOfNotNull(
            runCatching {
                json.decodeFromJsonElement<PatreonResourceRef>(data)
            }.getOrNull(),
        )
        else -> emptyList()
    }
}

private fun relationshipRef(relationship: PatreonRelationship?, json: Json): PatreonResourceRef? {
    val data = relationship?.data ?: return null

    return when (data) {
        is JsonObject -> runCatching {
            json.decodeFromJsonElement<PatreonResourceRef>(data)
        }.getOrNull()
        else -> null
    }
}

private fun PatreonAttributes.bestImageUrl(): String? {
    val candidates = listOfNotNull(
        downloadUrl,
        imageUrls?.original,
        imageUrls?.default,
        imageUrls?.large,
        imageUrls?.defaultLarge,
        imageUrls?.thumbnail,
    )

    return candidates.firstOrNull { url ->
        fileName.isImageFileName() || url.isImageUrl()
    }
}

private fun PatreonAttributes.pageUsername(): String = vanity?.takeIf { it.isNotBlank() }
    ?: url.usernameFromPatreonUrl()
    ?: urlForCurrentUser.usernameFromPatreonUrl()
    ?: name?.takeIf { it.isNotBlank() }
    ?: "Patreon"

private fun String?.usernameFromPatreonUrl(): String? {
    if (isNullOrBlank()) return null

    return substringBefore("?")
        .substringBefore("#")
        .trimEnd('/')
        .substringAfterLast('/')
        .takeIf { it.isNotBlank() && it != "www.patreon.com" && !it.contains("patreon.com") }
}

private fun String?.htmlToMarkdown(): String? {
    if (isNullOrBlank()) return null

    val document = Jsoup.parseBodyFragment(this)
    val markdown = document.body().childNodes()
        .joinToString("") { node -> node.toMarkdown() }

    return markdown
        .replace("\u00A0", " ")
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n[ \\t]+"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
        .takeIf { it.isNotBlank() }
}

private fun Node.toMarkdown(): String = when (this) {
    is TextNode -> text().replace("\u00A0", " ")
    is Element -> toMarkdownElement()
    else -> childNodes().joinToString("") { node -> node.toMarkdown() }
}

private fun Element.toMarkdownElement(): String {
    val tag = tagName().lowercase(Locale.ROOT)

    return when (tag) {
        "br" -> "\n"

        "p" -> {
            val content = childrenToMarkdown().trim()
            if (content.isBlank()) "" else "$content\n\n"
        }

        "strong", "b" -> wrapMarkdown("**", childrenToMarkdown())
        "em", "i" -> wrapMarkdown("*", childrenToMarkdown())
        "s", "strike", "del" -> wrapMarkdown("~~", childrenToMarkdown())

        "a" -> {
            val text = childrenToMarkdown().trim().ifBlank { text().trim() }
            val href = attr("href").ifBlank { attr("abs:href") }.trim()

            when {
                text.isBlank() && href.isBlank() -> ""
                href.isBlank() -> text
                text.isBlank() || text == href -> href
                else -> "[$text]($href)"
            }
        }

        "img" -> {
            val src = attr("src").ifBlank { attr("abs:src") }.trim()
            val alt = attr("alt").ifBlank { attr("title") }.ifBlank { "image" }.trim()

            if (src.isBlank()) "" else "![$alt]($src)"
        }

        "ul" -> {
            val items = childNodes()
                .joinToString("") { node ->
                    if (node is Element && node.tagName().equals("li", ignoreCase = true)) {
                        "- ${node.childrenToMarkdown().trim()}\n"
                    } else {
                        node.toMarkdown()
                    }
                }
                .trimEnd()

            if (items.isBlank()) "" else "$items\n\n"
        }

        "ol" -> {
            var index = 1
            val items = childNodes()
                .joinToString("") { node ->
                    if (node is Element && node.tagName().equals("li", ignoreCase = true)) {
                        "${index++}. ${node.childrenToMarkdown().trim()}\n"
                    } else {
                        node.toMarkdown()
                    }
                }
                .trimEnd()

            if (items.isBlank()) "" else "$items\n\n"
        }

        "li" -> {
            val content = childrenToMarkdown().trim()
            if (content.isBlank()) "" else "- $content\n"
        }

        "blockquote" -> {
            val content = childrenToMarkdown().trim()
            if (content.isBlank()) {
                ""
            } else {
                content.lines().joinToString("\n") { line -> "> $line" } + "\n\n"
            }
        }

        "h1" -> markdownHeading(1)
        "h2" -> markdownHeading(2)
        "h3" -> markdownHeading(3)
        "h4" -> markdownHeading(4)
        "h5" -> markdownHeading(5)
        "h6" -> markdownHeading(6)

        else -> childrenToMarkdown()
    }
}

private fun Element.childrenToMarkdown(): String = childNodes().joinToString("") { node -> node.toMarkdown() }

private fun Element.markdownHeading(level: Int): String {
    val content = childrenToMarkdown().trim()
    if (content.isBlank()) return ""

    return "${"#".repeat(level)} $content\n\n"
}

private fun wrapMarkdown(marker: String, value: String): String {
    val content = value.trim()
    if (content.isBlank()) return ""

    return "$marker$content$marker"
}

private fun String?.parsePatreonDate(): Long {
    if (isNullOrBlank()) return 0L

    DATE_FORMATS.forEach { format ->
        runCatching {
            return format.parse(this)?.time ?: 0L
        }
    }

    return 0L
}

private fun String?.isImageFileName(): Boolean {
    if (isNullOrBlank()) return false

    val clean = substringBefore('?')
        .substringBefore('#')
        .lowercase(Locale.ROOT)

    return IMAGE_EXTENSIONS.any { clean.endsWith(it) }
}

private fun String.isImageUrl(): Boolean = substringBefore('?').substringBefore('#').isImageFileName()

private fun String.extractImageUrlsFromHtml(): List<String> = Jsoup.parse(this)
    .select("img[src], source[srcset]")
    .flatMap { element ->
        val src = element.attr("abs:src").ifBlank { element.attr("src") }
        val srcset = element.attr("srcset")
            .split(',')
            .map { it.trim().substringBefore(' ') }

        listOf(src) + srcset
    }
    .filter { it.startsWith("http") && it.isImageUrl() }
    .distinct()

private fun String.extractImageUrlsFromText(): List<String> = IMAGE_URL_REGEX
    .findAll(this)
    .map { it.value.replace("\\/", "/") }
    .filter { it.isImageUrl() }
    .distinct()
    .toList()

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonElement?.asString(): String? {
    val primitive = this?.jsonPrimitive ?: return null
    return primitive.contentOrNull ?: primitive.intOrNull?.toString()
}

private fun String?.toSourcePath(baseUrl: String): String {
    val clean = this
        ?.substringBefore("?")
        ?.substringBefore("#")
        ?.trim()
        .orEmpty()

    return when {
        clean.isBlank() -> "/"
        clean.startsWith(baseUrl) -> clean.removePrefix(baseUrl).ifBlank { "/" }
        clean.startsWith("/") -> clean
        clean.startsWith("http://") || clean.startsWith("https://") -> clean
        else -> "/$clean"
    }
}

private fun PatreonImageUrls?.best(): String? = this?.original ?: this?.default ?: this?.large ?: this?.defaultLarge ?: this?.thumbnail

private fun JsonObject.imageUrlsBest(): String? = string("original") ?: string("default") ?: string("large") ?: string("default_large") ?: string("thumbnail")

private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif")

private val IMAGE_URL_REGEX =
    Regex(
        """https?:\\?/\\?/[^\"'<>\s]+\.(?:jpg|jpeg|png|gif|webp|avif)(?:\?[^\"'<>\s]*)?""",
        RegexOption.IGNORE_CASE,
    )

private val DATE_FORMATS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    "yyyy-MM-dd'T'HH:mm:ssXXX",
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    "yyyy-MM-dd'T'HH:mm:ss'Z'",
).map { pattern ->
    SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}
