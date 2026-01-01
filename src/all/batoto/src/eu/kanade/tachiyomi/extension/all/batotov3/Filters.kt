package eu.kanade.tachiyomi.extension.all.batotov3

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

val filters = FilterList(
    SortFilter(),
    OriginalStatusFilter(),
    BatoStatusFilter(),
    GenreGroupFilter(),
    OriginGroupFilter(),
    ChapterCountFilter(),
)

class SortFilter(
    default: String? = null,
) : SelectFilter(
    "Sort",
    getSortFilter(),
    default,
)

class OriginalStatusFilter(
    default: String? = null,
) : SelectFilter(
    "Original Work Status",
    getStatusFilter(),
    default,
)

class BatoStatusFilter(
    default: String? = null,
) : SelectFilter(
    "Bato UploadStatus",
    getStatusFilter(),
    default,
)

class OriginGroupFilter : CheckboxGroupFilter(
    "Origin",
    getOriginFilter(),
)

class GenreGroupFilter : TriStateGroupFilter(
    "Genre",
    getGenreFilter(),
)

class ChapterCountFilter(
    default: String? = null,
) : SelectFilter(
    "Number of Chapters",
    getChapterCountFilter(),
    default,
)

abstract class SelectFilter(
    name: String,
    private val options: List<SelectFilterOption>,
    private val default: String? = null,
) : Filter.Select<String>(
    name,
    options.map { it.name }.toTypedArray(),
    options.indexOfFirst { it.value == default }.takeIf { it != -1 } ?: 0,
) {
    val selected: String
        get() = default ?: options[state].value
}

abstract class CheckboxGroupFilter(
    name: String,
    options: List<CheckboxFilterOption>,
) : Filter.Group<CheckboxFilterOption>(
    name,
    options,
) {
    val selected: List<String>
        get() = state.filter { it.state }.map { it.value }
}

abstract class TriStateGroupFilter(
    name: String,
    options: List<TriStateFilterOption>,
) : Filter.Group<TriStateFilterOption>(
    name,
    options,
) {
    val included: List<String>
        get() = state.filter { it.isIncluded() }.map { it.value }

    val excluded: List<String>
        get() = state.filter { it.isExcluded() }.map { it.value }
}

class SelectFilterOption(
    val name: String,
    val value: String,
)

class CheckboxFilterOption(
    val value: String,
    name: String,
    default: Boolean = false,
) : Filter.CheckBox(
    name,
    default,
)

class TriStateFilterOption(
    val value: String,
    name: String,
    default: Int = 0,
) : Filter.TriState(
    name,
    default,
)

private fun getSortFilter() = listOf(
    SelectFilterOption("Rating Score", "field_score"),
    SelectFilterOption("Most Follows", "field_follow"),
    SelectFilterOption("Most Reviews", "field_review"),
    SelectFilterOption("Most Comments", "field_comment"),
    SelectFilterOption("Most Chapters", "field_chapter"),
    SelectFilterOption("Last Upload", "field_upload"),
    SelectFilterOption("Recently Created", "field_public"),
    SelectFilterOption("Name A-Z", "field_name"),

    SelectFilterOption("Views 60 minutes", "views_h001"),
    SelectFilterOption("Views 6 hours", "views_h006"),
    SelectFilterOption("Views 12 hours", "views_h012"),
    SelectFilterOption("Views 24 hours", "views_h024"),
    SelectFilterOption("Views 7 days", "views_d007"),
    SelectFilterOption("Views 30 days", "views_d030"),
    SelectFilterOption("Views 90 days", "views_d090"),
    SelectFilterOption("Views 180 days", "views_d180"),
    SelectFilterOption("Views 360 days", "views_d360"),
    SelectFilterOption("Views Total", "views_d000"),

    SelectFilterOption("User Status: Planning", "status_wish"),
    SelectFilterOption("User Status: Reading", "status_doing"),
    SelectFilterOption("User Status: Completed", "status_completed"),
    SelectFilterOption("User Status: On Hold", "status_on_hold"),
    SelectFilterOption("User Status: Dropped", "status_dropped"),
    SelectFilterOption("User Status: Re-Reading", "status_repeat"),

    SelectFilterOption("Emotion: Awesome", "emotion_upvote"),
    SelectFilterOption("Emotion: Funny", "emotion_funny"),
    SelectFilterOption("Emotion: Love", "emotion_love"),
    SelectFilterOption("Emotion: Scared", "emotion_surprised"),
    SelectFilterOption("Emotion: Angry", "emotion_angry"),
    SelectFilterOption("Emotion: Sad", "emotion_sad"),
)

private fun getStatusFilter() = listOf(
    SelectFilterOption("All", ""),
    SelectFilterOption("Pending", "pending"),
    SelectFilterOption("Ongoing", "ongoing"),
    SelectFilterOption("Completed", "completed"),
    SelectFilterOption("Hiatus", "hiatus"),
    SelectFilterOption("Cancelled", "cancelled"),
)

private fun getOriginFilter() = listOf(
    // Values exported from publish.bato.to
    CheckboxFilterOption("zh", "Chinese"),
    CheckboxFilterOption("en", "English"),
    CheckboxFilterOption("ja", "Japanese"),
    CheckboxFilterOption("ko", "Korean"),
    CheckboxFilterOption("af", "Afrikaans"),
    CheckboxFilterOption("sq", "Albanian"),
    CheckboxFilterOption("am", "Amharic"),
    CheckboxFilterOption("ar", "Arabic"),
    CheckboxFilterOption("hy", "Armenian"),
    CheckboxFilterOption("az", "Azerbaijani"),
    CheckboxFilterOption("be", "Belarusian"),
    CheckboxFilterOption("bn", "Bengali"),
    CheckboxFilterOption("bs", "Bosnian"),
    CheckboxFilterOption("bg", "Bulgarian"),
    CheckboxFilterOption("my", "Burmese"),
    CheckboxFilterOption("km", "Cambodian"),
    CheckboxFilterOption("ca", "Catalan"),
    CheckboxFilterOption("ceb", "Cebuano"),
    CheckboxFilterOption("zh_hk", "Chinese (Cantonese)"),
    CheckboxFilterOption("zh_tw", "Chinese (Traditional)"),
    CheckboxFilterOption("hr", "Croatian"),
    CheckboxFilterOption("cs", "Czech"),
    CheckboxFilterOption("da", "Danish"),
    CheckboxFilterOption("nl", "Dutch"),
    CheckboxFilterOption("en_us", "English (United States)"),
    CheckboxFilterOption("eo", "Esperanto"),
    CheckboxFilterOption("et", "Estonian"),
    CheckboxFilterOption("fo", "Faroese"),
    CheckboxFilterOption("fil", "Filipino"),
    CheckboxFilterOption("fi", "Finnish"),
    CheckboxFilterOption("fr", "French"),
    CheckboxFilterOption("ka", "Georgian"),
    CheckboxFilterOption("de", "German"),
    CheckboxFilterOption("el", "Greek"),
    CheckboxFilterOption("gn", "Guarani"),
    CheckboxFilterOption("gu", "Gujarati"),
    CheckboxFilterOption("ht", "Haitian Creole"),
    CheckboxFilterOption("ha", "Hausa"),
    CheckboxFilterOption("he", "Hebrew"),
    CheckboxFilterOption("hi", "Hindi"),
    CheckboxFilterOption("hu", "Hungarian"),
    CheckboxFilterOption("is", "Icelandic"),
    CheckboxFilterOption("ig", "Igbo"),
    CheckboxFilterOption("id", "Indonesian"),
    CheckboxFilterOption("ga", "Irish"),
    CheckboxFilterOption("it", "Italian"),
    CheckboxFilterOption("jv", "Javanese"),
    CheckboxFilterOption("kn", "Kannada"),
    CheckboxFilterOption("kk", "Kazakh"),
    CheckboxFilterOption("ku", "Kurdish"),
    CheckboxFilterOption("ky", "Kyrgyz"),
    CheckboxFilterOption("lo", "Laothian"),
    CheckboxFilterOption("lv", "Latvian"),
    CheckboxFilterOption("lt", "Lithuanian"),
    CheckboxFilterOption("lb", "Luxembourgish"),
    CheckboxFilterOption("mk", "Macedonian"),
    CheckboxFilterOption("mg", "Malagasy"),
    CheckboxFilterOption("ms", "Malay"),
    CheckboxFilterOption("ml", "Malayalam"),
    CheckboxFilterOption("mt", "Maltese"),
    CheckboxFilterOption("mi", "Maori"),
    CheckboxFilterOption("mr", "Marathi"),
    CheckboxFilterOption("mo", "Moldavian"),
    CheckboxFilterOption("mn", "Mongolian"),
    CheckboxFilterOption("ne", "Nepali"),
    CheckboxFilterOption("no", "Norwegian"),
    CheckboxFilterOption("ny", "Nyanja"),
    CheckboxFilterOption("ps", "Pashto"),
    CheckboxFilterOption("fa", "Persian"),
    CheckboxFilterOption("pl", "Polish"),
    CheckboxFilterOption("pt", "Portuguese"),
    CheckboxFilterOption("pt_br", "Portuguese (Brazil)"),
    CheckboxFilterOption("ro", "Romanian"),
    CheckboxFilterOption("rm", "Romansh"),
    CheckboxFilterOption("ru", "Russian"),
    CheckboxFilterOption("sm", "Samoan"),
    CheckboxFilterOption("sr", "Serbian"),
    CheckboxFilterOption("sh", "Serbo-Croatian"),
    CheckboxFilterOption("st", "Sesotho"),
    CheckboxFilterOption("sn", "Shona"),
    CheckboxFilterOption("sd", "Sindhi"),
    CheckboxFilterOption("si", "Sinhalese"),
    CheckboxFilterOption("sk", "Slovak"),
    CheckboxFilterOption("sl", "Slovenian"),
    CheckboxFilterOption("so", "Somali"),
    CheckboxFilterOption("es", "Spanish"),
    CheckboxFilterOption("es_419", "Spanish (Latin America)"),
    CheckboxFilterOption("sw", "Swahili"),
    CheckboxFilterOption("sv", "Swedish"),
    CheckboxFilterOption("tg", "Tajik"),
    CheckboxFilterOption("ta", "Tamil"),
    CheckboxFilterOption("th", "Thai"),
    CheckboxFilterOption("ti", "Tigrinya"),
    CheckboxFilterOption("to", "Tonga"),
    CheckboxFilterOption("tr", "Turkish"),
    CheckboxFilterOption("tk", "Turkmen"),
    CheckboxFilterOption("uk", "Ukrainian"),
    CheckboxFilterOption("ur", "Urdu"),
    CheckboxFilterOption("uz", "Uzbek"),
    CheckboxFilterOption("vi", "Vietnamese"),
    CheckboxFilterOption("yo", "Yoruba"),
    CheckboxFilterOption("zu", "Zulu"),
    CheckboxFilterOption("_t", "Other"),
)

private fun getGenreFilter() = listOf(
    TriStateFilterOption("artbook", "Artbook"),
    TriStateFilterOption("cartoon", "Cartoon"),
    TriStateFilterOption("comic", "Comic"),
    TriStateFilterOption("doujinshi", "Doujinshi"),
    TriStateFilterOption("imageset", "Imageset"),
    TriStateFilterOption("manga", "Manga"),
    TriStateFilterOption("manhua", "Manhua"),
    TriStateFilterOption("manhwa", "Manhwa"),
    TriStateFilterOption("webtoon", "Webtoon"),
    TriStateFilterOption("western", "Western"),

    TriStateFilterOption("shoujo", "Shoujo(G)"),
    TriStateFilterOption("shounen", "Shounen(B)"),
    TriStateFilterOption("josei", "Josei(W)"),
    TriStateFilterOption("seinen", "Seinen(M)"),
    TriStateFilterOption("yuri", "Yuri(GL)"),
    TriStateFilterOption("yaoi", "Yaoi(BL)"),
    TriStateFilterOption("futa", "Futa(WL)"),
    TriStateFilterOption("bara", "Bara(ML)"),

    TriStateFilterOption("gore", "Gore"),
    TriStateFilterOption("bloody", "Bloody"),
    TriStateFilterOption("violence", "Violence"),
    TriStateFilterOption("ecchi", "Ecchi"),
    TriStateFilterOption("adult", "Adult"),
    TriStateFilterOption("mature", "Mature"),
    TriStateFilterOption("smut", "Smut"),
    TriStateFilterOption("hentai", "Hentai"),

    TriStateFilterOption("_4_koma", "4-Koma"),
    TriStateFilterOption("action", "Action"),
    TriStateFilterOption("adaptation", "Adaptation"),
    TriStateFilterOption("adventure", "Adventure"),
    TriStateFilterOption("age_gap", "Age Gap"),
    TriStateFilterOption("aliens", "Aliens"),
    TriStateFilterOption("animals", "Animals"),
    TriStateFilterOption("anthology", "Anthology"),
    TriStateFilterOption("beasts", "Beasts"),
    TriStateFilterOption("bodyswap", "Bodyswap"),
    TriStateFilterOption("cars", "cars"),
    TriStateFilterOption("cheating_infidelity", "Cheating/Infidelity"),
    TriStateFilterOption("childhood_friends", "Childhood Friends"),
    TriStateFilterOption("college_life", "College Life"),
    TriStateFilterOption("comedy", "Comedy"),
    TriStateFilterOption("contest_winning", "Contest Winning"),
    TriStateFilterOption("cooking", "Cooking"),
    TriStateFilterOption("crime", "crime"),
    TriStateFilterOption("crossdressing", "Crossdressing"),
    TriStateFilterOption("delinquents", "Delinquents"),
    TriStateFilterOption("dementia", "Dementia"),
    TriStateFilterOption("demons", "Demons"),
    TriStateFilterOption("drama", "Drama"),
    TriStateFilterOption("dungeons", "Dungeons"),
    TriStateFilterOption("emperor_daughte", "Emperor's Daughter"),
    TriStateFilterOption("fantasy", "Fantasy"),
    TriStateFilterOption("fan_colored", "Fan-Colored"),
    TriStateFilterOption("fetish", "Fetish"),
    TriStateFilterOption("full_color", "Full Color"),
    TriStateFilterOption("game", "Game"),
    TriStateFilterOption("gender_bender", "Gender Bender"),
    TriStateFilterOption("genderswap", "Genderswap"),
    TriStateFilterOption("ghosts", "Ghosts"),
    TriStateFilterOption("gyaru", "Gyaru"),
    TriStateFilterOption("harem", "Harem"),
    TriStateFilterOption("harlequin", "Harlequin"),
    TriStateFilterOption("historical", "Historical"),
    TriStateFilterOption("horror", "Horror"),
    TriStateFilterOption("incest", "Incest"),
    TriStateFilterOption("isekai", "Isekai"),
    TriStateFilterOption("kids", "Kids"),
    TriStateFilterOption("loli", "Loli"),
    TriStateFilterOption("magic", "Magic"),
    TriStateFilterOption("magical_girls", "Magical Girls"),
    TriStateFilterOption("martial_arts", "Martial Arts"),
    TriStateFilterOption("mecha", "Mecha"),
    TriStateFilterOption("medical", "Medical"),
    TriStateFilterOption("military", "Military"),
    TriStateFilterOption("monster_girls", "Monster Girls"),
    TriStateFilterOption("monsters", "Monsters"),
    TriStateFilterOption("music", "Music"),
    TriStateFilterOption("mystery", "Mystery"),
    TriStateFilterOption("netorare", "Netorare/NTR"),
    TriStateFilterOption("ninja", "Ninja"),
    TriStateFilterOption("office_workers", "Office Workers"),
    TriStateFilterOption("omegaverse", "Omegaverse"),
    TriStateFilterOption("oneshot", "Oneshot"),
    TriStateFilterOption("parody", "parody"),
    TriStateFilterOption("philosophical", "Philosophical"),
    TriStateFilterOption("police", "Police"),
    TriStateFilterOption("post_apocalyptic", "Post-Apocalyptic"),
    TriStateFilterOption("psychological", "Psychological"),
    TriStateFilterOption("regression", "Regression"),
    TriStateFilterOption("reincarnation", "Reincarnation"),
    TriStateFilterOption("reverse_harem", "Reverse Harem"),
    TriStateFilterOption("reverse_isekai", "Reverse Isekai"),
    TriStateFilterOption("romance", "Romance"),
    TriStateFilterOption("royal_family", "Royal Family"),
    TriStateFilterOption("royalty", "Royalty"),
    TriStateFilterOption("samurai", "Samurai"),
    TriStateFilterOption("school_life", "School Life"),
    TriStateFilterOption("sci_fi", "Sci-Fi"),
    TriStateFilterOption("shota", "Shota"),
    TriStateFilterOption("shoujo_ai", "Shoujo Ai"),
    TriStateFilterOption("shounen_ai", "Shounen Ai"),
    TriStateFilterOption("showbiz", "Showbiz"),
    TriStateFilterOption("slice_of_life", "Slice of Life"),
    TriStateFilterOption("sm_bdsm", "SM/BDSM/SUB-DOM"),
    TriStateFilterOption("space", "Space"),
    TriStateFilterOption("sports", "Sports"),
    TriStateFilterOption("super_power", "Super Power"),
    TriStateFilterOption("superhero", "Superhero"),
    TriStateFilterOption("supernatural", "Supernatural"),
    TriStateFilterOption("survival", "Survival"),
    TriStateFilterOption("thriller", "Thriller"),
    TriStateFilterOption("time_travel", "Time Travel"),
    TriStateFilterOption("tower_climbing", "Tower Climbing"),
    TriStateFilterOption("traditional_games", "Traditional Games"),
    TriStateFilterOption("tragedy", "Tragedy"),
    TriStateFilterOption("transmigration", "Transmigration"),
    TriStateFilterOption("vampires", "Vampires"),
    TriStateFilterOption("villainess", "Villainess"),
    TriStateFilterOption("video_games", "Video Games"),
    TriStateFilterOption("virtual_reality", "Virtual Reality"),
    TriStateFilterOption("wuxia", "Wuxia"),
    TriStateFilterOption("xianxia", "Xianxia"),
    TriStateFilterOption("xuanhuan", "Xuanhuan"),
    TriStateFilterOption("zombies", "Zombies"),
    // Hidden Genres
    TriStateFilterOption("shotacon", "shotacon"),
    TriStateFilterOption("lolicon", "lolicon"),
    TriStateFilterOption("award_winning", "Award Winning"),
    TriStateFilterOption("youkai", "Youkai"),
    TriStateFilterOption("uncategorized", "Uncategorized"),
)

private fun getChapterCountFilter() = listOf(
    SelectFilterOption("", ""),
    SelectFilterOption("0", "0"),
    SelectFilterOption("1+", "1+"),
    SelectFilterOption("10+", "10+"),
    SelectFilterOption("20+", "20+"),
    SelectFilterOption("30+", "30+"),
    SelectFilterOption("40+", "40+"),
    SelectFilterOption("50+", "50+"),
    SelectFilterOption("60+", "60+"),
    SelectFilterOption("70+", "70+"),
    SelectFilterOption("80+", "80+"),
    SelectFilterOption("90+", "90+"),
    SelectFilterOption("100+", "100+"),
    SelectFilterOption("200+", "200+"),
    SelectFilterOption("300+", "300+"),
    SelectFilterOption("299~200", "299~200"),
    SelectFilterOption("199~100", "199~100"),
    SelectFilterOption("99~90", "99~90"),
    SelectFilterOption("89~80", "89~80"),
    SelectFilterOption("79~70", "79~70"),
    SelectFilterOption("69~60", "69~60"),
    SelectFilterOption("59~50", "59~50"),
    SelectFilterOption("49~40", "49~40"),
    SelectFilterOption("39~30", "39~30"),
    SelectFilterOption("29~20", "29~20"),
    SelectFilterOption("19~10", "19~10"),
    SelectFilterOption("9~1", "9~1"),
)
