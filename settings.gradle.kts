pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "youtube lite"
include(":app")

// Local composite build for the NewPipeExtractor fork during development.
// The extractor module publishes as net.newpipe:extractor locally, but litube depends on
// com.github.HydeYYHH:NewPipeExtractor via JitPack. Substitute both coordinates so the local
// sources replace the published artifact.
includeBuild("../NewPipeExtractor") {
    dependencySubstitution {
        substitute(module("com.github.HydeYYHH:NewPipeExtractor")).using(project(":extractor"))
        substitute(module("net.newpipe:extractor")).using(project(":extractor"))
    }
}
