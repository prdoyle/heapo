// Standalone generator — not a library, not depended on by other modules

plugins {
    java
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

val samplesOutputDir = layout.buildDirectory.dir("samples")

tasks.register<JavaExec>("generateSamples") {
    group = "heapo"
    description = "Generate sample HPROF files under build/samples/"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "heapo.samples.GenerateHprofFiles"
    jvmArgs("--enable-preview")
    systemProperty("output.dir", samplesOutputDir.get().asFile.absolutePath)
    outputs.dir(samplesOutputDir)
    doFirst {
        samplesOutputDir.get().asFile.mkdirs()
    }
}
