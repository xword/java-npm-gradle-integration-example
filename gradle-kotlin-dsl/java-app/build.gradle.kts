buildscript {

    val springBootVersion by extra("2.1.1.RELEASE")

    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.springframework.boot:spring-boot-gradle-plugin:${springBootVersion}")
    }
}

apply(plugin = "java")
apply(plugin = "eclipse")
apply(plugin = "org.springframework.boot")
apply(plugin = "io.spring.dependency-management")

group = "eu.xword.labs.gc"
version = "0.0.1-SNAPSHOT"

configure<JavaPluginConvention> {
    sourceCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
}


dependencies {
    "implementation"("org.springframework.boot:spring-boot-starter-web")
    "testImplementation"("org.springframework.boot:spring-boot-starter-test")

    "runtimeOnly"(project(":npm-app"))
}
