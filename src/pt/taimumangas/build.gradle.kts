plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Taimu Mangas"
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        name = "Taimu Mangas"
        lang = "pt-BR"
        baseUrl = "https://beta.taimumangas.com"
    }
}
