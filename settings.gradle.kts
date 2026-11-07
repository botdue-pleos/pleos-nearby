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

rootProject.name = "PleosNearby"
// core: 차 앱과 서버가 함께 쓰는 판정 규칙·통신 형식 / app: 차량 앱 / server: 차끼리 이어 주는 중계 서버
include(":core", ":app", ":server")
