import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import groovy.util.Node
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
    id("net.minecrell.licenser") version "0.4.1"
    id("com.github.johnrengelman.shadow") version "5.0.0"
    id("net.kyori.blossom") version "1.1.0"
    `maven-publish`
}

group = "eu.mikroskeem"
version = "0.0.4.999-jploot-rc1"

val checkerQualVersion = "2.9.0"
val mavenMetaVersion = "3.6.1"
val mavenModelVersion = "3.6.1"
val slf4jApiVersion = "1.7.25"

val junitVersion = "5.5.1"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenLocal()
    mavenCentral()

    maven("https://repo.wut.ee/repository/mikroskeem-repo/")
}

dependencies {
    implementation("org.apache.maven:maven-repository-metadata:$mavenMetaVersion")
    implementation("org.apache.maven:maven-model:$mavenModelVersion")
    implementation("org.slf4j:slf4j-api:$slf4jApiVersion")

    compileOnly("org.checkerframework:checker-qual:$checkerQualVersion")

    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testRuntime("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    testRuntime("org.slf4j:slf4j-simple:$slf4jApiVersion")
}

license {
    header = rootProject.file("etc/HEADER")
    filter.include("**/*.java")
}

val sourcesJar by tasks.creating(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets["main"].allJava)
}

val javadoc by tasks.getting(Javadoc::class)

val javadocJar by tasks.creating(Jar::class) {
    dependsOn(javadoc)
    archiveClassifier.set("javadoc")
    from(javadoc.destinationDir)
}


val shadowJar by tasks.getting(ShadowJar::class) {
    archiveClassifier.set("shaded")

    val targetPackage = "eu.mikroskeem.picomaven.shaded"
    val relocations = listOf(
            "org.apache.maven",
            "org.codehaus.plexus"
    )

    relocations.forEach {
        relocate(it, "$targetPackage.$it")
    }

    minimize {
        dependency("org.apache.maven:maven-model")
        dependency("org.apache.maven:maven-repository-metadata")
        dependency("org.codehaus.plexus:plexus-utils")
    }
}

val test by tasks.getting(Test::class) {
    useJUnitPlatform()
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "trace")

    // Show output
    testLogging {
        showStandardStreams = true
        exceptionFormat = TestExceptionFormat.FULL
    }

    // Verbose
    beforeTest(closureOf<Any> { logger.lifecycle("Running test: $this") })
}

blossom {
    replaceToken("__PICOMAVEN_VERSION__", "${rootProject.version}")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "picomaven"

            from(components["java"])
            artifact(shadowJar)
            artifact(sourcesJar)
            artifact(javadocJar)

            pom.withXml {
                builder {
                    "name"("PicoMaven")
                    "description"("Library to download libraries from Maven repository on app startup")

                    "repositories" {
                        "repository" {
                            "id"("mikroskeem-repo")
                            "url"("https://repo.wut.ee/repository/mikroskeem-repo")
                        }
                    }

                    "issueManagement" {
                        "system"("GitHub Issues")
                        "url"("https://github.com/mikroskeem/PicoMaven/issues")
                    }

                    "licenses" {
                        "license" {
                            "name"("MIT License")
                            "url"("https://opensource.org/licenses/MIT")
                        }
                    }

                    "developers" {
                        "developer" {
                            "id"("mikroskeem")
                            "name"("Mark Vainomaa")
                            "email"("mikroskeem@mikroskeem.eu")
                        }
                    }

                    "scm" {
                        "connection"("scm:git@github.com:mikroskeem/PicoMaven.git")
                        "developerConnection"("scm:git@github.com:mikroskeem/PicoMaven.git")
                        "url"("https://github.com/mikroskeem/PicoMaven")
                    }
                }
            }
        }
    }

    repositories {
        mavenLocal()
        if (rootProject.hasProperty("wutee.repository.deploy.username") && rootProject.hasProperty("wutee.repository.deploy.password")) {
            maven("https://repo.wut.ee/repository/mikroskeem-repo").credentials {
                username = rootProject.properties["wutee.repository.deploy.username"]!! as String
                password = rootProject.properties["wutee.repository.deploy.password"]!! as String
            }
        } else if (rootProject.hasProperty("jploot.repository.deploy.username") && rootProject.hasProperty("jploot.repository.deploy.password")) {
            maven("https://nexus.tools.kobalt.fr/repository/jploot/").credentials {
                username = rootProject.properties["jploot.repository.deploy.username"]!! as String
                password = rootProject.properties["jploot.repository.deploy.password"]!! as String
            }
        } else {
            var url = if (System.getenv().containsKey("RELEASE")) "http://localhost:8081/repository/jploot-releases/" else "http://localhost:8081/repository/jploot-snapshots/"
            maven(url).credentials {
                username = "admin"
                password = "admin"
            }
        }
    }
}

tasks["build"].dependsOn("licenseFormat", shadowJar)

fun XmlProvider.builder(builder: GroovyBuilderScope.() -> Unit) {
    (asNode().children().last() as Node).plus(delegateClosureOf<Any> {
        withGroovyBuilder(builder)
    })
}
