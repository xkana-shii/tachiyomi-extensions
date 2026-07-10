plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Pawchive"
    versionCode = 0
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    theme = "kemono"

    source {
        lang = "all"
        baseUrl {
            mirrors(
                "https://pawchive.pw",
                "https://pawchive.st",
            )
        }
    }
}
