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

rootProject.name = "litube"
include(":app")

// Local NewPipeExtractor (v0.26.0-aria fork) for in-tree patches during the rewrite.
includeBuild("external/NewPipeExtractor") {
    dependencySubstitution {
        substitute(module("net.newpipe:extractor")).using(project(":extractor"))
        substitute(module("com.github.HydeYYHH:NewPipeExtractor")).using(project(":extractor"))
    }
}
