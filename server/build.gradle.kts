plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("com.botdue.nearby.server.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.logback)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.junit)
}
