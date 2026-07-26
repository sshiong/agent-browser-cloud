plugins {
    java
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.5"
    id("org.flywaydb.flyway") version "10.22.0"
    id("com.google.protobuf") version "0.9.4"
    id("com.diffplug.spotless") version "6.25.0"
}

group = "io.browsercloud"
version = "0.1.0"

extra["flyway.version"] = "10.22.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // Database
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql:10.22.0")

    // Protobuf
    implementation("com.google.protobuf:protobuf-java:3.25.3")
    implementation("io.grpc:grpc-netty-shaded:1.62.2")
    implementation("io.grpc:grpc-protobuf:1.62.2")
    implementation("io.grpc:grpc-stub:1.62.2")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // API documentation
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("io.rest-assured:rest-assured")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:1.19.7")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.register<JavaExec>("coordinatorCapacityCertificate") {
    group = "verification"
    description = "Runs the bounded Coordinator Stage A capacity workload and writes a JSON certificate"
    dependsOn(tasks.testClasses)
    javaLauncher =
        javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(21)
        }
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "io.browsercloud.capacity.CoordinatorCapacityCertificateRunner"
    args(
        "--output",
        providers.gradleProperty("capacityOutput")
            .orElse(layout.buildDirectory.file("reports/capacity/coordinator-capacity.json").map { it.asFile.absolutePath })
            .get(),
        "--actors",
        providers.gradleProperty("capacityActors").orElse("50000").get(),
        "--build-id",
        providers.gradleProperty("capacityBuildId").orElse("control-plane-local").get(),
    )
}

sourceSets {
    main {
        proto {
            srcDir("../../packages/contracts/proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.62.2"
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                create("grpc")
            }
        }
    }
}

spotless {
    java {
        googleJavaFormat("1.22.0")
        target("src/**/*.java")
    }
    kotlinGradle {
        ktlint()
        target("*.gradle.kts")
    }
}

tasks.processResources {
    from("../../database/migrations") {
        into("db/migration")
    }
}

flyway {
    url = "jdbc:postgresql://localhost:5432/browsercloud"
    user = "browsercloud"
    password = "browsercloud"
    locations = arrayOf("filesystem:../../database/migrations")
}
