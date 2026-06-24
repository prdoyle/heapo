plugins {
    application
}

application {
    mainClass = "heapo.cli.Main"
    applicationName = "heapo"
    applicationDefaultJvmArgs = listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED"
    )
}

dependencies {
    implementation(project(":model"))
    implementation(project(":unpack"))
    implementation(project(":indexes"))
    implementation(project(":query-engine"))
    implementation(project(":session"))
    implementation("info.picocli:picocli:4.7.6")
    implementation("org.jline:jline:3.27.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}
