plugins {
    java
    application
    id("com.google.protobuf") version "0.9.6"
    id("io.micronaut.application") version "4.4.4"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.example.poc"
version = "0.1.0-SNAPSHOT"

val micronautVersion: String by project
val grpcJavaVersion: String by project
val protobufVersion: String by project

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":proto"))

    annotationProcessor(platform("io.micronaut.platform:micronaut-platform:$micronautVersion"))
    annotationProcessor("io.micronaut:micronaut-inject-java")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    implementation(platform("io.micronaut.platform:micronaut-platform:$micronautVersion"))
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut.serde:micronaut-serde-jackson")

    implementation("io.projectreactor:reactor-core")

    implementation("io.grpc:grpc-netty-shaded:$grpcJavaVersion")
    implementation("io.grpc:grpc-protobuf:$grpcJavaVersion")
    implementation("io.grpc:grpc-stub:$grpcJavaVersion")
    implementation("io.grpc:grpc-services:$grpcJavaVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    runtimeOnly("ch.qos.logback:logback-classic:1.5.6")
    runtimeOnly("org.yaml:snakeyaml:2.2")
}

application {
    mainClass.set("com.example.poc.bff.BffApplication")
}

micronaut {
    version(micronautVersion)
    runtime("netty")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcJavaVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins { create("grpc") {} }
        }
    }
}

sourceSets {
    main {
        proto {
            srcDir(project(":proto").projectDir.resolve("schemas"))
        }
    }
}

tasks.named<JavaCompile>("compileJava") {
    options.encoding = "UTF-8"
}

tasks.named("extractProto") {
    dependsOn(":proto:compileJava")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("bff")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
}
