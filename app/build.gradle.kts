plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(projects.core)
    implementation(projects.identity)
    implementation(projects.catalog)
    implementation(projects.engagement)
    implementation(projects.circulation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(projects.core))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val apiDocsFile = rootProject.layout.projectDirectory.file("docs/api/openapi.json")

tasks.test {
    useJUnitPlatform()
    systemProperty("lms.api-docs.file", apiDocsFile.asFile.absolutePath)
    inputs.files(apiDocsFile).withPropertyName("apiDocs")
}

tasks.register<Test>("exportApiDocs") {
    description = "Writes the OpenAPI document of the current endpoints to docs/api/openapi.json."
    group = "documentation"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("com.coursework.lms.ApiDocsTest") }
    systemProperty("lms.api-docs.file", apiDocsFile.asFile.absolutePath)
    systemProperty("lms.api-docs.write", "true")
    outputs.upToDateWhen { false }
}
