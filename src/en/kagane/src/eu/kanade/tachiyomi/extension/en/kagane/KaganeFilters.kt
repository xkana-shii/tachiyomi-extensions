package eu.kanade.tachiyomi.extension.en.kagane

import eu.kanade.tachiyomi.source.model.Filter

class SelectFilterOption(val name: String, val value: String)
class CheckboxFilterOption(val value: String, name: String, default: Boolean = false) : Filter.CheckBox(name, default)
class TriStateFilterOption(val value: String, name: String, default: Int = 0) : Filter.TriState(name, default)

abstract class SelectFilter(name: String, private val options: List<SelectFilterOption>, default: Int = 0) : Filter.Select<String>(name, options.map { it.name }.toTypedArray(), default) {
    val selected: String
        get() = options[state].value
}

abstract class CheckboxGroupFilter(name: String, options: List<CheckboxFilterOption>) : Filter.Group<CheckboxFilterOption>(name, options) {
    val selected: List<String>
        get() = state.filter { it.state }.map { it.value }
}

abstract class TriStateGroupFilter(name: String, options: List<TriStateFilterOption>) : Filter.Group<TriStateFilterOption>(name, options) {
    val included: List<String>
        get() = state.filter { it.isIncluded() }.map { it.value }
    val excluded: List<String>
        get() = state.filter { it.isExcluded() }.map { it.value }
}

class SortFilter(sortables: Array<String>) : Filter.Sort("Sort", sortables, Selection(5, false))
class SourceFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Source", options, default)
class OriginGroupFilter(options: List<CheckboxFilterOption>) : CheckboxGroupFilter("Origin", options)
class GenreGroupFilter(options: List<TriStateFilterOption>) : TriStateGroupFilter("Genre", options)
class TagGroupFilter(options: List<TriStateFilterOption>) : TriStateGroupFilter("Tag", options)

fun getSortFilter() = listOf(
    SelectFilterOption("Relevance", "relevance"),
    SelectFilterOption("Latest", "latest"),
    SelectFilterOption("By Name", "name"),
    SelectFilterOption("Books count", "books-count"),
    SelectFilterOption("Created at", "created-at"),
)

fun getSourceFilter() = listOf(
    CheckboxFilterOption("All", ""),
    CheckboxFilterOption("Comikey", "Comikey"),
    CheckboxFilterOption("Day Comics", "Day Comics"),
    CheckboxFilterOption("INKR Comics", "INKR Comics"),
    CheckboxFilterOption("Lezhin", "Lezhin"),
    CheckboxFilterOption("Manta", "Manta"),
    CheckboxFilterOption("Others", "Others"),
    CheckboxFilterOption("Pocket Comics", "Pocket Comics"),
    CheckboxFilterOption("Tapas", "Tapas"),
    CheckboxFilterOption("Toomics", "Toomics"),
    CheckboxFilterOption("Webtoon", "Webtoon"),
)

fun getGenreFilter() = listOf(
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

fun getTagFilter() = listOf(
    TriStateFilterOption("", "All"),
    TriStateFilterOption("action", "Action"),
)
