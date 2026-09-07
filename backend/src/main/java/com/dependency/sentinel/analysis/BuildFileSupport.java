package com.dependency.sentinel.analysis;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Normalizes supported Java build descriptors into the existing Maven scan pipeline. */
public final class BuildFileSupport {
    private BuildFileSupport() {}

    public static NormalizedBuildFile normalize(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Build file is empty.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().trim();
        String lower = name.toLowerCase(Locale.ROOT);

        if ("pom.xml".equals(lower)) {
            return new NormalizedBuildFile(file, "MAVEN", "pom.xml");
        }
        if ("build.gradle".equals(lower) || "build.gradle.kts".equals(lower)) {
            String source = new String(file.getBytes(), StandardCharsets.UTF_8);
            String pom = GradleBuildParser.toSyntheticPom(source);
            return new NormalizedBuildFile(
                    new ByteArrayMultipartFile("file", "pom.xml", "application/xml", pom.getBytes(StandardCharsets.UTF_8)),
                    "GRADLE",
                    name
            );
        }

        throw new IllegalArgumentException(
                "Unsupported build file. Dependency Sentinel currently supports Maven pom.xml, Gradle build.gradle, and Gradle build.gradle.kts."
        );
    }

    public record NormalizedBuildFile(MultipartFile file, String buildTool, String sourceFileName) {}

    private static final class ByteArrayMultipartFile implements MultipartFile {
        private final String name;
        private final String originalFilename;
        private final String contentType;
        private final byte[] content;

        private ByteArrayMultipartFile(String name, String originalFilename, String contentType, byte[] content) {
            this.name = name;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.content = content;
        }

        @Override public String getName() { return name; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
        @Override public void transferTo(File dest) throws IOException { java.nio.file.Files.write(dest.toPath(), content); }
    }
}
