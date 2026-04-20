package eu.kanade.tachiyomi.extension.all.lezhin

import android.util.Log
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.json.JSONArray
import org.jsoup.Jsoup

/**
 * All tag / filter helpers for Lezhin collected in one file.
 *
 * Usage:
 *  - Call parseDivisionsFromHtml(html) with the HTML of the Lezhin tags page (or any page containing the embedded tag JSON).
 *  - Convert the returned divisions into a FilterList using divisionsToFilterList(divisions) or directly use parseHtmlToFilterList(html).
 *  - When building the search URL, call selectedTagIds(filters) to get the list of selected tagId Longs and append them as repeated ext_id parameters:
 *      val extIdQuery = buildExtIdQuery(selectedTagIds(filters))
 *
 * Notes:
 * - The embedded script on Lezhin contains tagId values (preferred). If parseDivisionsFromHtml cannot find the embedded JSON,
 *   it falls back to scraping visible buttons and produces tags with tagId = negative synthetic ids (these cannot be used with ext_id).
 * - Text search is intentionally ignored for this integration; use tag checkboxes.
 */

private const val LOG_TAG = "LezhinFilters"

/** Lightweight model for a Lezhin tag */
data class LezhinTag(
    val tagId: Long,
    val tag: String,
    val name: String,
    val divisionId: Int,
    val divisionName: String,
)

/** Division (e.g., Theme, Worldbuilding) containing tags */
data class LezhinDivision(
    val divisionId: Int,
    val divisionName: String,
    val tags: List<LezhinTag>,
)

object LezhinTagsParser {
    /**
     * Parse Lezhin tag divisions from the provided HTML.
     *
     * Strategy:
     *  1) Look through <script> tags for one that references "/v2/tag/list" (the script with embedded JSON).
     *  2) Extract the "general": [ ... ] (or the first array near /v2/tag/list) JSON array using bracket matching.
     *  3) Parse the array into LezhinDivision/LezhinTag objects.
     *
     * Fallback:
     *  - If embedded JSON cannot be found, try to scrape visible buttons (.panelBody__items__Bzuhu button, etc.)
     *    and return a single Division with synthetic negative tagId values (these cannot be used with ext_id API params).
     */
    fun parseDivisionsFromHtml(html: String): List<LezhinDivision> {
        try {
            val doc = Jsoup.parse(html)
            val scripts = doc.select("script")
            for (s in scripts) {
                val data = s.data()
                if (data.contains("/v2/tag/list")) {
                    // Prefer the 'general' array if present
                    val generalIndex = data.indexOf("\"general\":")
                    val arrayStartIdx = if (generalIndex >= 0) {
                        data.indexOf('[', generalIndex).takeIf { it >= 0 }
                    } else {
                        data.indexOf('[', data.indexOf("/v2/tag/list")).takeIf { it >= 0 }
                    } ?: continue

                    val arrayEndIdx = findMatchingBracket(data, arrayStartIdx)
                    if (arrayEndIdx < 0) continue

                    val arrayJson = data.substring(arrayStartIdx, arrayEndIdx + 1)
                    val arr = JSONArray(arrayJson)
                    val divisions = mutableListOf<LezhinDivision>()
                    for (i in 0 until arr.length()) {
                        val divObj = arr.getJSONObject(i)
                        val divisionId = divObj.optInt("divisionId", -1)
                        val divisionName = divObj.optString("divisionName", "Tags")
                        val tagsArr = divObj.optJSONArray("tags") ?: JSONArray()
                        val tagList = mutableListOf<LezhinTag>()
                        for (j in 0 until tagsArr.length()) {
                            val t = tagsArr.getJSONObject(j)
                            val tagId = t.optLong("tagId", -1L)
                            val tag = t.optString("tag", "")
                            val name = t.optString("name", tag)
                            tagList.add(LezhinTag(tagId, tag, name, divisionId, divisionName))
                        }
                        if (tagList.isNotEmpty()) divisions.add(LezhinDivision(divisionId, divisionName, tagList))
                    }
                    if (divisions.isNotEmpty()) {
                        Log.d(LOG_TAG, "parseDivisionsFromHtml: parsed ${divisions.sumOf { it.tags.size }} tags across ${divisions.size} divisions")
                        return divisions
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "parseDivisionsFromHtml: error parsing embedded JSON", e)
        }

        // Fallback: scrape visible buttons
        try {
            val doc = Jsoup.parse(html)
            val elems = doc.select(".panelBody__items__Bzuhu button, .panelBody__items__Bzuhu a, .panelBody__item__TBUYn")
            val names = elems.mapNotNull { it.text()?.trim()?.takeIf { t -> t.isNotEmpty() } }.distinct()
            if (names.isNotEmpty()) {
                val tags = names.mapIndexed { idx, nm ->
                    // synthetic negative tagIds to indicate no real id available
                    LezhinTag(tagId = -1L - idx, tag = nm, name = nm, divisionId = -1, divisionName = "Tags")
                }
                Log.d(LOG_TAG, "parseDivisionsFromHtml: fallback scraped ${tags.size} tags (no ids)")
                return listOf(LezhinDivision(-1, "Tags", tags))
            }
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "parseDivisionsFromHtml: fallback scraping failed", e)
        }

        Log.w(LOG_TAG, "parseDivisionsFromHtml: no tags found")
        return emptyList()
    }

    private fun findMatchingBracket(s: String, startIdx: Int): Int {
        var depth = 0
        for (i in startIdx until s.length) {
            when (s[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }
}

/* -----------------------
   Filter conversions + helpers
   ----------------------- */

/** A checkbox filter that carries the Lezhin tagId alongside the visible name. */
class LezhinTagFilter(val tagId: Long, name: String) : Filter.CheckBox(name)

/** A Filter.Group representing one division (e.g., "Theme", "Worldbuilding"). */
class LezhinTagFilterGroup(displayName: String, tags: List<LezhinTag>) : Filter.Group<LezhinTagFilter>(displayName, tags.map { LezhinTagFilter(it.tagId, it.name) })

/**
 * Convert parsed divisions into a FilterList suitable for getFilterList()
 * (includes a header to indicate text search is ignored).
 */
fun divisionsToFilterList(divisions: List<LezhinDivision>): FilterList {
    val groups = divisions.map { div ->
        LezhinTagFilterGroup(div.divisionName, div.tags)
    }
    return FilterList(
        Filter.Header("Text search is ignored for Lezhin; use tag checkboxes"),
        *groups.toTypedArray(),
    )
}

/**
 * Convenience: parse HTML and return a FilterList in one step.
 * Use this if you fetch the tags page HTML and want the FilterList immediately.
 */
fun parseHtmlToFilterList(html: String): FilterList {
    val divisions = LezhinTagsParser.parseDivisionsFromHtml(html)
    return divisionsToFilterList(divisions)
}

/**
 * Extract selected tagIds from a FilterList produced by divisionsToFilterList().
 * Only returns those tagIds >= 0 (real IDs). Synthetic negative IDs from fallback scraping are not included.
 */
fun selectedTagIds(filters: FilterList): List<Long> {
    val ids = mutableListOf<Long>()
    filters.forEach { f ->
        if (f is Filter.Group<*>) {
            @Suppress("UNCHECKED_CAST")
            val group = f as Filter.Group<LezhinTagFilter>
            group.state.forEach { tf ->
                if (tf.state && tf.tagId >= 0L) ids.add(tf.tagId)
            }
        }
    }
    return ids
}

/**
 * Build a query fragment with repeated ext_id params for the selected tag ids.
 * Example output: "&ext_id=2643&ext_id=2652"
 */
fun buildExtIdQuery(selectedIds: List<Long>): String {
    if (selectedIds.isEmpty()) return ""
    return selectedIds.joinToString(separator = "") { "&ext_id=$it" }
}
