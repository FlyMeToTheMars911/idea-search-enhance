plugins {
    java
}

group = "com.zhoucb.searchenhance"
version = "1.0.0"

// Offline SDK dependency: no Gradle IntelliJ plugin and no IDE downloads.
// Use Gradle 7.3+ on Java 17, or scripts/build.ps1 without Gradle.
val ideaHome = providers.gradleProperty("ideaHome")
    .orElse(providers.environmentVariable("IDEA_HOME"))
    .getOrElse("D:/application/IntelliJ IDEA 2023.3.8")
val ideaSdkJars = fileTree("$ideaHome/lib") {
    include("*.jar")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(ideaSdkJars)
    testCompileOnly(ideaSdkJars)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
    options.compilerArgs.add("-proc:none")
}

tasks.jar {
    archiveBaseName.set("search-enhance")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Regression tests are a plain Java main class and need no downloaded runner.
val runQueryTests by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + ideaSdkJars
    mainClass.set("com.zhoucb.searchenhance.query.QueryTestRunner")
    enableAssertions = true
    systemProperty("file.encoding", "UTF-8")
}

tasks.test {
    enabled = false
}

tasks.check {
    dependsOn(runQueryTests)
}

tasks.register<Zip>("buildPlugin") {
    dependsOn(tasks.jar, runQueryTests)
    archiveBaseName.set("search-enhance")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(tasks.jar) {
        into("search-enhance/lib")
        rename { "search-enhance.jar" }
    }
}
