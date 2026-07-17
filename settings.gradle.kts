pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "PaperMC"
        }
        mavenCentral()
    }
}

rootProject.name = "AGMA"

include("paper-plugin")
include("client-mod")
include("standalone-client:core")
include("standalone-client:runtime-supervisor-core")
include("standalone-client:fabric-common")
include("standalone-client:fabric-mc12111")
include("standalone-client:fabric-mc1182")
