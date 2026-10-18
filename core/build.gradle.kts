plugins {
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(libs.spring.boot.starter.webmvc)
    api(libs.spring.boot.starter.validation)
    api(libs.spring.boot.starter.jdbc)
    api(libs.spring.boot.starter.security.oauth2.resource.server)
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.spring7.transaction)
    api(libs.jackson.module.kotlin)
    api(libs.kotlin.reflect)
    implementation(libs.postgresql)

    testFixturesApi(platform(libs.spring.boot.dependencies))
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesApi(libs.exposed.migration.jdbc)
    testFixturesApi(libs.spring.test)
    testFixturesImplementation(libs.assertj.core)
}
