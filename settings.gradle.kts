// Notscape TV — a simple, lightweight general Android TV browser.
// Start page: news, stocks, weather from free sources (no API keys).
// Copyright (c) 2026 Notscape contributors. All rights reserved.

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
    }
}

rootProject.name = "NotscapeTV"
include(":app")
