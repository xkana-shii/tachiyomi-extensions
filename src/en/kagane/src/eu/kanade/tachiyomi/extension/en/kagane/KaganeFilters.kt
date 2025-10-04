package eu.kanade.tachiyomi.extension.en.kagane

import eu.kanade.tachiyomi.source.model.Filter

// ----------- FILTER METADATA FOR SSR SEARCH PAGE -----------
// These lists are extracted from Kagane's SSR metadata (search page).
// Do not remove any comments.

// Helper for tristate label/value mapping
class TriStateFilterOption(
    val value: String,
    name: String,
    state: Int = Filter.TriState.State.IGNORE
) : Filter.TriState(name, state)

// Helper for select filter label/value mapping
class SelectFilterOption(
    val name: String,
    val value: String
)

// Source filter (single-select, label/value mapping)
class SourceFilter : Filter.Select<String>(
    "Source",
    getSourceFilter().map { it.name }.toTypedArray()
) {
    companion object {
        private fun getSourceFilter() = listOf(
            SelectFilterOption("All", ""),
            SelectFilterOption("Comikey", "Comikey"),
            SelectFilterOption("Day Comics", "Day Comics"),
            SelectFilterOption("INKR Comics", "INKR Comics"),
            SelectFilterOption("Lezhin", "Lezhin"),
            SelectFilterOption("Manta", "Manta"),
            SelectFilterOption("Others", "Others"),
            SelectFilterOption("Pocket Comics", "Pocket Comics"),
            SelectFilterOption("Tapas", "Tapas"),
            SelectFilterOption("Toomics", "Toomics"),
            SelectFilterOption("Webtoon", "Webtoon"),
        )
    }
    private val values = getSourceFilter().map { it.value }.toTypedArray()
    val selected: String
        get() = values[state]
}

class SortFilter : Filter.Select<String>(
    "Sort By",
    getSortFilter().map { it.name }.toTypedArray()
) {
    companion object {
        private fun getSortFilter() = listOf(
            SelectFilterOption("Relevance", "relevance"),
            SelectFilterOption("Latest", "latest"),
            SelectFilterOption("By Name", "name"),
            SelectFilterOption("Books count", "books-count"),
            SelectFilterOption("Created at", "created-at"),
        )
    }
    private val values = getSortFilter().map { it.value }.toTypedArray()
    val selected: String
        get() = values[state]
}

// Genre filter (tristate group, label/value mapping)
class GenreFilter : Filter.Group<TriStateFilterOption>(
    "Genre",
    listOf(
        TriStateFilterOption("", "All"),
        TriStateFilterOption("action", "Action"),
        TriStateFilterOption("adult", "Adult"),
        TriStateFilterOption("adventure", "Adventure"),
        TriStateFilterOption("all ages", "All Ages"),
        TriStateFilterOption("animals", "Animals"),
        TriStateFilterOption("anime", "Anime"),
        TriStateFilterOption("anime tie-in", "Anime Tie-in"),
        TriStateFilterOption("anthropomorphic", "Anthropomorphic"),
        TriStateFilterOption("avant garde", "Avant Garde"),
        TriStateFilterOption("aventure", "Aventure"),
        TriStateFilterOption("boys' love", "Boys' Love"),
        TriStateFilterOption("cats", "Cats"),
        TriStateFilterOption("cgdct", "CGDCT"),
        TriStateFilterOption("childcare", "Childcare"),
        TriStateFilterOption("college", "College"),
        TriStateFilterOption("comedy", "Comedy"),
        TriStateFilterOption("completed series", "Completed Series"),
        TriStateFilterOption("crime", "Crime"),
        TriStateFilterOption("crossdressing", "Crossdressing"),
        TriStateFilterOption("delinquents", "Delinquents"),
        TriStateFilterOption("detective", "Detective"),
        TriStateFilterOption("doujinshi", "Doujinshi"),
        TriStateFilterOption("drama", "Drama"),
        TriStateFilterOption("drame", "Drame"),
        TriStateFilterOption("dungeon / labyrinth", "Dungeon / Labyrinth"),
        TriStateFilterOption("dystopian", "Dystopian"),
        TriStateFilterOption("ecchi", "Ecchi"),
        TriStateFilterOption("erotica", "Erotica"),
        TriStateFilterOption("erotique", "Erotique"),
        TriStateFilterOption("fant. romance", "Fant. Romance"),
        TriStateFilterOption("fantastique", "Fantastique"),
        TriStateFilterOption("fantasy", "Fantasy"),
        TriStateFilterOption("gag humor", "Gag Humor"),
        TriStateFilterOption("gender bender", "Gender Bender"),
        TriStateFilterOption("girls' love", "Girls' Love"),
        TriStateFilterOption("gore", "Gore"),
        TriStateFilterOption("gourmet", "Gourmet"),
        TriStateFilterOption("graphic novel", "Graphic Novel"),
        TriStateFilterOption("harem", "Harem"),
        TriStateFilterOption("heartwarming", "Heartwarming"),
        TriStateFilterOption("hentai", "Hentai"),
        TriStateFilterOption("histoires courtes", "Histoires Courtes"),
        TriStateFilterOption("historical", "Historical"),
        TriStateFilterOption("historique", "Historique"),
        TriStateFilterOption("horreur", "Horreur"),
        TriStateFilterOption("horror", "Horror"),
        TriStateFilterOption("idols (male)", "Idols (Male)"),
        TriStateFilterOption("informative", "Informative"),
        TriStateFilterOption("inspirational", "Inspirational"),
        TriStateFilterOption("int'l manga", "Int'l Manga"),
        TriStateFilterOption("isekai", "Isekai"),
        TriStateFilterOption("iyashikei", "Iyashikei"),
        TriStateFilterOption("josei", "Josei"),
        TriStateFilterOption("kids", "Kids"),
        TriStateFilterOption("lgbtq", "LGBTQ"),
        TriStateFilterOption("lgbtq+", "LGBTQ+"),
        TriStateFilterOption("lolicon", "Lolicon"),
        TriStateFilterOption("love polygon", "Love Polygon"),
        TriStateFilterOption("love status quo", "Love Status Quo"),
        TriStateFilterOption("magic", "Magic"),
        TriStateFilterOption("magical girls", "Magical Girls"),
        TriStateFilterOption("magical sex shift", "Magical Sex Shift"),
        TriStateFilterOption("mahou shoujo", "Mahou Shoujo"),
        TriStateFilterOption("manga", "Manga"),
        TriStateFilterOption("manhwa", "Manhwa"),
        TriStateFilterOption("martial arts", "Martial Arts"),
        TriStateFilterOption("mature", "Mature"),
        TriStateFilterOption("mecha", "Mecha"),
        TriStateFilterOption("medical", "Medical"),
        TriStateFilterOption("military", "Military"),
        TriStateFilterOption("mod. romance", "Mod. Romance"),
        TriStateFilterOption("monster girls", "Monster Girls"),
        TriStateFilterOption("music", "Music"),
        TriStateFilterOption("mystery", "Mystery"),
        TriStateFilterOption("mystère", "Mystère"),
        TriStateFilterOption("myth", "Myth"),
        TriStateFilterOption("mythology", "Mythology"),
        TriStateFilterOption("new", "New"),
        TriStateFilterOption("noble romance", "Noble Romance"),
        TriStateFilterOption("non-human", "Non-human"),
        TriStateFilterOption("office", "Office"),
        TriStateFilterOption("one shot", "One Shot"),
        TriStateFilterOption("organized crime", "Organized Crime"),
        TriStateFilterOption("otaku culture", "Otaku Culture"),
        TriStateFilterOption("pets", "Pets"),
        TriStateFilterOption("philosophical", "Philosophical"),
        TriStateFilterOption("pocoexclucive", "Pocoexclucive"),
        TriStateFilterOption("psychological", "Psychological"),
        TriStateFilterOption("psychologique", "Psychologique"),
        TriStateFilterOption("reincarnation", "Reincarnation"),
        TriStateFilterOption("reverse harem", "Reverse Harem"),
        TriStateFilterOption("romance", "Romance"),
        TriStateFilterOption("royalty/nobility", "Royalty/Nobility"),
        TriStateFilterOption("school", "School"),
        TriStateFilterOption("school life", "School Life"),
        TriStateFilterOption("sci-fi", "Sci-fi"),
        TriStateFilterOption("science fiction", "Science Fiction"),
        TriStateFilterOption("seinen", "Seinen"),
        TriStateFilterOption("short", "Short"),
        TriStateFilterOption("short story", "Short Story"),
        TriStateFilterOption("shotacon", "Shotacon"),
        TriStateFilterOption("shoujo", "Shoujo"),
        TriStateFilterOption("shounen", "Shounen"),
        TriStateFilterOption("showbiz", "Showbiz"),
        TriStateFilterOption("shōjo-ai", "Shōjo-ai"),
        TriStateFilterOption("shōnen-ai", "Shōnen-ai"),
        TriStateFilterOption("slice of life", "Slice of Life"),
        TriStateFilterOption("smut", "Smut"),
        TriStateFilterOption("space", "Space"),
        TriStateFilterOption("sports", "Sports"),
        TriStateFilterOption("strategy game", "Strategy Game"),
        TriStateFilterOption("super power", "Super Power"),
        TriStateFilterOption("superhero", "Superhero"),
        TriStateFilterOption("supernatural", "Supernatural"),
        TriStateFilterOption("surnaturel", "Surnaturel"),
        TriStateFilterOption("survival", "Survival"),
        TriStateFilterOption("suspense", "Suspense"),
        TriStateFilterOption("team sports", "Team Sports"),
        TriStateFilterOption("teens' love", "Teens' Love"),
        TriStateFilterOption("thriller", "Thriller"),
        TriStateFilterOption("time travel", "Time Travel"),
        TriStateFilterOption("tragedy", "Tragedy"),
        TriStateFilterOption("tragique", "Tragique"),
        TriStateFilterOption("uncensored", "Uncensored"),
        TriStateFilterOption("urban fantasy", "Urban Fantasy"),
        TriStateFilterOption("vampire", "Vampire"),
        TriStateFilterOption("vanilla", "Vanilla"),
        TriStateFilterOption("video game", "Video Game"),
        TriStateFilterOption("video games / game adaptation / game elements", "Video Games / Game Adaptation / Game Elements"),
        TriStateFilterOption("villainess", "Villainess"),
        TriStateFilterOption("visual arts", "Visual Arts"),
        TriStateFilterOption("workplace", "Workplace"),
        TriStateFilterOption("wuxia", "Wuxia"),
        TriStateFilterOption("xuanhuan", "Xuanhuan"),
        TriStateFilterOption("yonkoma", "Yonkoma"),
    )
)

// Tag filter (tristate group, label/value mapping, full list omitted for brevity)
// Do not remove any comments!
class TagFilter : Filter.Group<TriStateFilterOption>(
    "Tag",
    listOf(
        // TriStateFilterOption("full color", "Full Color"),
        // TriStateFilterOption("long strip", "Long Strip"),
        // ...etc
    )
)
