package com.dependency.sentinel.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts common Gradle dependency declarations into a synthetic Maven POM.
 * This intentionally supports only literal group:artifact:version coordinates so
 * dependency resolution stays deterministic and reuses the existing Maven Resolver pipeline.
 */
public final class GradleBuildParser {
    private static final Pattern DEPENDENCY = Pattern.compile(
            "(?m)^\\s*(implementation|api|runtimeOnly|compileOnly|testImplementation|testRuntimeOnly)\\s*(?:\\(\\s*)?[\\\"']([^\\\"']+)[\\\"']\\s*\\)?\\s*$"
    );

    private GradleBuildParser() {}

    public record ParsedDependency(String configuration, String groupId, String artifactId, String version) {}

    public static String toSyntheticPom(String source) {
        List<ParsedDependency> dependencies = parse(source);
        if (dependencies.isEmpty()) {
            throw new IllegalArgumentException(
                    "No supported literal Gradle dependencies were found. Use group:artifact:version declarations or scan the project's Maven pom.xml.");
        }

        StringBuilder pom = new StringBuilder();
        pom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\" ")
                .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
                .append("xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 ")
                .append("https://maven.apache.org/xsd/maven-4.0.0.xsd\">")
                .append("<modelVersion>4.0.0</modelVersion>")
                .append("<groupId>dependency.sentinel</groupId>")
                .append("<artifactId>gradle-imported-project</artifactId>")
                .append("<version>1.0.0</version>")
                .append("<dependencies>");

        Map<String, ParsedDependency> unique = new LinkedHashMap<>();
        for (ParsedDependency dependency : dependencies) {
            unique.putIfAbsent(dependency.groupId() + ":" + dependency.artifactId() + ":" + dependency.version(), dependency);
        }

        for (ParsedDependency dependency : unique.values()) {
            pom.append("<dependency>")
                    .append("<groupId>").append(xml(dependency.groupId())).append("</groupId>")
                    .append("<artifactId>").append(xml(dependency.artifactId())).append("</artifactId>")
                    .append("<version>").append(xml(dependency.version())).append("</version>")
                    .append("<scope>").append(scope(dependency.configuration())).append("</scope>")
                    .append("</dependency>");
        }
        return pom.append("</dependencies></project>").toString();
    }

    public static List<ParsedDependency> parse(String source) {
        String clean = stripComments(source == null ? "" : source);
        Matcher matcher = DEPENDENCY.matcher(clean);
        List<ParsedDependency> result = new ArrayList<>();
        while (matcher.find()) {
            String configuration = matcher.group(1);
            String coordinate = matcher.group(2).trim();
            String[] parts = coordinate.split(":");
            if (parts.length != 3) continue;
            String group = parts[0].trim();
            String artifact = parts[1].trim();
            String version = parts[2].trim();
            if (group.isBlank() || artifact.isBlank() || version.isBlank()) continue;
            if (version.contains("$") || version.startsWith("libs.")) continue;
            result.add(new ParsedDependency(configuration, group, artifact, version));
        }
        return result;
    }

    private static String scope(String configuration) {
        return switch (configuration) {
            case "runtimeOnly" -> "runtime";
            case "compileOnly" -> "provided";
            case "testImplementation", "testRuntimeOnly" -> "test";
            default -> "compile";
        };
    }

    private static String stripComments(String value) {
        return value
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
