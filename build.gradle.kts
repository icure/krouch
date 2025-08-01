import com.github.jk1.license.render.CsvReportRenderer
import com.github.jk1.license.render.ReportRenderer
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/*
 *    Copyright 2020 Taktik SA
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

val repoUsername: String by project
val repoPassword: String by project
val mavenReleasesRepository: String by project

val kotlinVersion = "1.9.25"
val kotlinCoroutinesVersion = "1.8.1"
val asyncHttpVersion = "0.2.23-g050f3d219b"
val jacksonVersion = "2.19.1"
val nettyVersion = "4.1.122.Final"
val reactorNettyVersion = "1.2.7"
val slf4jVersion = "2.0.16"
val commonsCodecVersion = "1.16.1"
val guavaVersion = "33.4.8-jre"
val reactorVersion = "3.7.7"
val logbackVersion = "1.5.18"

plugins {
    kotlin("jvm") version "1.8.10"
    id("com.taktik.gradle.maven-repository") version "1.0.7"
    id("com.taktik.gradle.git-version") version "2.0.8-gb47b2d0e35"
    id("com.github.jk1.dependency-license-report") version "2.0"
}

licenseReport {
    renderers = arrayOf<ReportRenderer>(CsvReportRenderer())
}

buildscript {
    repositories {
        mavenLocal()
        mavenCentral()
        maven { url = uri("https://maven.taktik.be/content/groups/public") }
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.10")
        classpath("org.jetbrains.kotlin:kotlin-allopen:1.8.10")
        classpath("com.taktik.gradle:gradle-plugin-maven-repository:1.0.7")
        classpath("com.taktik.gradle:gradle-plugin-git-version:2.0.8-gb47b2d0e35")
    }
}

val gitVersion: String? by project
group = "org.taktik.couchdb"
version = gitVersion ?: "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

apply(plugin = "kotlin")
apply(plugin = "maven-publish")

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
         jvmTarget = "17"
    }
}

dependencies {
    api(group = "io.icure", name = "async-jackson-http-client", version = asyncHttpVersion)

    implementation(group = "com.fasterxml.jackson.core", name = "jackson-databind", version = jacksonVersion)
    implementation(group = "com.fasterxml.jackson.module", name = "jackson-module-kotlin", version = jacksonVersion)

    implementation(group = "org.jetbrains.kotlin", name = "kotlin-stdlib", version = kotlinVersion)
    implementation(group = "org.jetbrains.kotlin", name = "kotlin-reflect", version = kotlinVersion)
    implementation(group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version = kotlinCoroutinesVersion)
    implementation(group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-reactor", version = kotlinCoroutinesVersion)
    implementation(group = "org.jetbrains.kotlinx", name = "kotlinx-collections-immutable-jvm", version = "0.3.8")

    implementation(group = "org.slf4j", name = "slf4j-api", version = slf4jVersion)
    implementation(group = "commons-codec", name = "commons-codec", version = commonsCodecVersion)

    implementation(group = "com.google.guava", name = "guava", version = guavaVersion)

    implementation(group = "io.projectreactor", name = "reactor-core", version = reactorVersion)
    implementation(group = "io.projectreactor.netty", name = "reactor-netty", version = reactorNettyVersion)

    // Logging
    testImplementation(group = "org.slf4j", name = "jul-to-slf4j", version = slf4jVersion)
    testImplementation(group = "org.slf4j", name = "jcl-over-slf4j", version = slf4jVersion)
    testImplementation(group = "org.slf4j", name = "log4j-over-slf4j", version = slf4jVersion)
    testImplementation(group = "ch.qos.logback", name = "logback-classic", version = logbackVersion)
    testImplementation(group = "ch.qos.logback", name = "logback-access", version = logbackVersion)

    testImplementation(group = "io.projectreactor", name = "reactor-tools", version = reactorVersion)
    testImplementation(group = "org.junit.jupiter", name = "junit-jupiter", version = "5.8.0")
}
