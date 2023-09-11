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

plugins {
    kotlin("jvm") version "1.8.10"
    id("com.taktik.gradle.maven-repository") version "1.0.7"
    id("com.taktik.gradle.git-version") version "2.0.8-gb47b2d0e35"
}

buildscript {
    repositories {
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
    implementation(group = "io.icure", name = "async-jackson-http-client", version = "0.1.20-9412eb88cf")

    implementation(group = "com.fasterxml.jackson.core", name = "jackson-databind", version = "2.13.5")
    implementation(group = "com.fasterxml.jackson.module", name = "jackson-module-kotlin", version = "2.13.5")

    implementation(group = "org.jetbrains.kotlin", name = "kotlin-stdlib", version = "1.8.10")
    implementation(group = "org.jetbrains.kotlin", name = "kotlin-reflect", version = "1.8.10")
    implementation(group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version = "1.6.4")
    implementation(group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-reactor", version = "1.6.4")
    implementation(group = "org.jetbrains.kotlinx", name = "kotlinx-collections-immutable-jvm", version = "0.3.4")

    implementation(group = "org.slf4j", name = "slf4j-api", version = "1.7.32")
    implementation(group = "commons-codec", name = "commons-codec", version = "1.16.0")

    implementation(group = "com.google.guava", name = "guava", version = "30.1.1-jre")

    implementation(group = "io.projectreactor", name = "reactor-core", version = "3.4.10")
    implementation(group = "io.projectreactor.netty", name = "reactor-netty", version = "1.0.30")

    // Logging
    testImplementation(group = "org.slf4j", name = "jul-to-slf4j", version = "1.7.32")
    testImplementation(group = "org.slf4j", name = "jcl-over-slf4j", version = "1.7.32")
    testImplementation(group = "org.slf4j", name = "log4j-over-slf4j", version = "1.7.32")
    testImplementation(group = "ch.qos.logback", name = "logback-classic", version = "1.2.12")
    testImplementation(group = "ch.qos.logback", name = "logback-access", version = "1.2.12")

    testImplementation(group = "io.projectreactor", name = "reactor-tools", version = "3.4.10")
    testImplementation(group = "org.junit.jupiter", name = "junit-jupiter", version = "5.8.0")
}
