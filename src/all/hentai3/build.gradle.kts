plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "3Hentai"
    className = "Hentai3Factory"
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    kmkVersionCode = 3

    deeplink {
        host("3hentai.net")
        path("/d/..*")
    }
}

dependencies {

    implementation(project(":lib:randomua"))
}
