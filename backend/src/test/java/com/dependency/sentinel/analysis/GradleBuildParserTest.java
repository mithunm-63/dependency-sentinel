package com.dependency.sentinel.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GradleBuildParserTest {
    @Test
    void parsesCommonGroovyDeclarations() {
        String buildGradle = """
                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-web:3.5.5'
                    runtimeOnly 'org.postgresql:postgresql:42.7.7'
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.13.4'
                }
                """;

        var dependencies = GradleBuildParser.parse(buildGradle);

        assertEquals(3, dependencies.size());
        assertEquals("spring-boot-starter-web", dependencies.get(0).artifactId());
        assertEquals("runtimeOnly", dependencies.get(1).configuration());
        assertEquals("5.13.4", dependencies.get(2).version());
    }

    @Test
    void parsesKotlinDslAndIgnoresDynamicCoordinates() {
        String buildGradle = """
                dependencies {
                    implementation("org.springframework:spring-core:6.2.10")
                    api("com.fasterxml.jackson.core:jackson-databind:2.19.2")
                    implementation("org.example:dynamic:${'$'}someVersion")
                    implementation(libs.someCatalogAlias)
                }
                """;

        var dependencies = GradleBuildParser.parse(buildGradle);

        assertEquals(2, dependencies.size());
        assertEquals("spring-core", dependencies.get(0).artifactId());
        assertEquals("api", dependencies.get(1).configuration());
    }

    @Test
    void buildsSyntheticPomWithMappedScopes() {
        String buildGradle = "implementation 'com.example:demo:1.0.0'\nruntimeOnly 'org.example:runtime:2.0.0'";

        String pom = GradleBuildParser.toSyntheticPom(buildGradle);

        assertTrue(pom.contains("<groupId>com.example</groupId>"));
        assertTrue(pom.contains("<artifactId>demo</artifactId>"));
        assertTrue(pom.contains("<scope>compile</scope>"));
        assertTrue(pom.contains("<scope>runtime</scope>"));
    }

    @Test
    void rejectsGradleFilesWithoutSupportedLiteralCoordinates() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GradleBuildParser.toSyntheticPom("dependencies { implementation(libs.web) }"));

        assertTrue(error.getMessage().contains("No supported literal Gradle dependencies"));
    }
}
