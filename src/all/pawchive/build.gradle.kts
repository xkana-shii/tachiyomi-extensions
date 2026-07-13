import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Pawchive"
    versionCode = 1
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
