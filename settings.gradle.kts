pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// Opt in with -PnextlibPath=../nextlib to test unpublished decoder changes.
providers.gradleProperty("nextlibPath").orNull?.let { nextlibPath ->
    includeBuild(nextlibPath) {
        dependencySubstitution {
            substitute(
                module("io.github.anilbeesetti:nextlib-media3ext"),
            ).using(project(":media3ext"))
            substitute(
                module("io.github.anilbeesetti:nextlib-mediainfo"),
            ).using(project(":mediainfo"))
        }
    }
}

rootProject.name = "MyFileReader"
include(":app")
include(":core:cast")
include(":core:common")
include(":core:data")
include(":core:database")
include(":core:datastore")
include(":core:domain")
include(":core:media")
include(":core:model")
include(":core:ui")
include(":feature:player")
include(":feature:settings")
// 融合：离线文档查看器（PDF / Office）与它的编译期桩
include(":feature:docviewer")
include(":splitstubs")
include(":ktlint-rules")
project(":ktlint-rules").projectDir = file("tools/ktlint-rules")
