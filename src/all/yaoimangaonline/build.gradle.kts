plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Yaoi Manga Online"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        lang = "all"
        baseUrl = "https://yaoimangaonline.com"
    }
}
