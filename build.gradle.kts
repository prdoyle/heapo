plugins {
    java
}

val javaVersion = JavaVersion.VERSION_25

allprojects {
    group = "heapo"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("--enable-preview"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("--enable-preview")
    }

    tasks.withType<JavaExec>().configureEach {
        jvmArgs("--enable-preview")
    }

    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }
}

// ── hprof-samples generation task ──────────────────────────────────────────

val generateHprofFiles = tasks.register<JavaExec>("generateHprofFiles") {
    dependsOn(project(":hprof-samples").tasks.named("classes"))
    classpath = project(":hprof-samples").sourceSets["main"].runtimeClasspath
    mainClass = "heapo.samples.GenerateHprofFiles"
    val outDir = layout.buildDirectory.dir("hprof-samples")
    outputs.dir(outDir)
    systemProperty("output.dir", outDir.get().asFile.absolutePath)
    jvmArgs("--enable-preview")
}

listOf("unpack", "indexes", "query-engine").forEach { moduleName ->
    project(":$moduleName") {
        tasks.named<Test>("test") {
            dependsOn(generateHprofFiles)
            systemProperty(
                "hprof.samples.dir",
                rootProject.layout.buildDirectory.dir("hprof-samples").get().asFile.absolutePath
            )
        }
    }
}
