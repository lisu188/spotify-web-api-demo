import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val kotlinVersion = "2.2.0"

plugins {
  id("org.springframework.boot") version "3.5.3"
  id("org.jetbrains.kotlin.jvm") version "2.2.0"
  id("org.jetbrains.kotlin.plugin.spring") version "2.2.0"
  id("org.jetbrains.kotlin.plugin.jpa") version "2.2.0"
  id("io.spring.dependency-management") version "1.1.7"
  // Apply the ktfmt plugin
  id("com.ncorti.ktfmt.gradle") version "0.23.0"
  id("jacoco")
}

group = "com.lis"

version = "1.0.0-SNAPSHOT"

java { sourceCompatibility = JavaVersion.VERSION_17 }

repositories { mavenCentral() }

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-websocket")
  implementation("org.springframework.boot:spring-boot-starter-aop")
  implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
  implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
  implementation("org.jsoup:jsoup:1.21.1")
  implementation("com.google.guava:guava:33.4.8-jre")

  testImplementation("org.jetbrains.kotlin:kotlin-test")
  testImplementation("io.mockk:mockk:1.14.3")
  testImplementation("org.junit.jupiter:junit-jupiter-api:5.12.2")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.12.2")
}

tasks.withType<KotlinCompile> {
  compilerOptions {
    freeCompilerArgs.add("-Xjsr305=strict")
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

tasks.withType<Test> { useJUnitPlatform() }

// Configure ktfmt to use Google Style
ktfmt { googleStyle() }

tasks.jacocoTestReport {
  dependsOn(tasks.test)
  reports {
    xml.required.set(true)
    html.required.set(true)
  }
}

tasks.jacocoTestCoverageVerification {
  dependsOn(tasks.jacocoTestReport)
  violationRules { rule { limit { minimum = "0.80".toBigDecimal() } } }
}

tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }
