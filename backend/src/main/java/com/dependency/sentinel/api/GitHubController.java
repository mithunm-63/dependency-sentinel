package com.dependency.sentinel.api;

import com.dependency.sentinel.analysis.GradleBuildParser;
import com.dependency.sentinel.analysis.PomScannerService;
import com.dependency.sentinel.project.Project;
import com.dependency.sentinel.project.ProjectRepository;
import com.dependency.sentinel.project.Scan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@CrossOrigin(origins = "${FRONTEND_ORIGIN:http://localhost:5173}")
public class GitHubController {
    private static final int MAX_BUILD_FILE_BYTES = 2_000_000;
    private final PomScannerService scanner;
    private final ProjectRepository projectRepository;
    private final HttpClient httpClient;

    public GitHubController(PomScannerService scanner, ProjectRepository projectRepository) {
        this.scanner = scanner;
        this.projectRepository = projectRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public record GitHubScanRequest(@NotBlank String repoUrl, String branch, String buildFilePath) {}

    @PostMapping("/{id}/github/scan")
    public ResponseEntity<Map<String, Object>> scanFromGitHub(
            @PathVariable Long id,
            @Valid @RequestBody GitHubScanRequest request) {
        try {
            RepoRef repo = parseRepo(request.repoUrl());
            String branch = request.branch() == null || request.branch().isBlank()
                    ? "main"
                    : validateBranch(request.branch().trim());

            BuildFile buildFile = fetchBuildFile(repo, branch, request.buildFilePath());
            String buildSystem = buildFile.type();
            String pomContent;
            if ("Maven".equals(buildSystem)) {
                pomContent = new String(buildFile.content(), StandardCharsets.UTF_8);
            } else if ("Gradle".equals(buildSystem)) {
                pomContent = GradleBuildParser.toSyntheticPom(new String(buildFile.content(), StandardCharsets.UTF_8));
            } else {
                throw new IllegalArgumentException("Unsupported Java build file.");
            }

            MultipartFile file = new ByteArrayMultipartFile("file", "pom.xml", "application/xml", pomContent.getBytes(StandardCharsets.UTF_8));
            Scan scan = scanner.scan(id, file);
            Project project = projectRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Project not found"));
            project.setBuildTool(buildSystem.toUpperCase(Locale.ROOT));
            projectRepository.save(project);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", scan.getStatus());
            response.put("scanId", scan.getId());
            response.put("repository", repo.owner() + "/" + repo.repository());
            response.put("branch", branch);
            response.put("buildSystem", buildSystem);
            response.put("buildTool", buildSystem.toUpperCase(Locale.ROOT));
            response.put("buildFile", buildFile.path());
            response.put("buildFileUrl", "https://github.com/" + repo.owner() + "/" + repo.repository() + "/blob/" + branch + "/" + buildFile.path());
            response.put("dependencyCount", scan.getNodeCount());
            response.put("vulnerabilityCount", scan.getVulnerabilityCount());
            response.put("securityScore", scan.getSecurityScore());
            response.put("securityStatus", scan.getSecurityStatus());
            response.put("message", buildSystem + " project scanned successfully using " + buildFile.path() + ".");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.BAD_GATEWAY, "GitHub scan failed: " + safeMessage(e));
        }
    }

    private RepoRef parseRepo(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())) {
                throw new IllegalArgumentException("Enter a public GitHub repository URL such as https://github.com/owner/repository");
            }
            if (uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("GitHub repository URL must not contain a query or fragment.");
            }
            String[] segments = uri.getPath().replaceAll("^/+|/+$", "").split("/");
            if (segments.length != 2 || segments[0].isBlank() || segments[1].isBlank()) {
                throw new IllegalArgumentException("Enter the repository root URL, not a file or pull-request URL.");
            }
            String repository = segments[1].endsWith(".git")
                    ? segments[1].substring(0, segments[1].length() - 4)
                    : segments[1];
            if (!segments[0].matches("[A-Za-z0-9._-]+") || !repository.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("The GitHub owner/repository name contains unsupported characters.");
            }
            return new RepoRef(segments[0], repository);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Enter")) throw e;
            throw new IllegalArgumentException("Enter a valid public GitHub repository URL.");
        }
    }

    private String validateBranch(String branch) {
        if (!branch.matches("[A-Za-z0-9._/-]{1,120}") || branch.contains("..")) {
            throw new IllegalArgumentException("Branch contains unsupported characters.");
        }
        return branch;
    }

    private BuildFile fetchBuildFile(RepoRef repo, String branch, String requestedPath) throws Exception {
        if (requestedPath != null && !requestedPath.isBlank()) {
            String path = validateBuildFilePath(requestedPath.trim());
            HttpResponse<byte[]> response = fetchRaw(repo, branch, path);
            if (response.statusCode() == 404) {
                throw new IllegalArgumentException("No supported build file was found at '" + path + "' on branch '" + branch + "'.");
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("GitHub file request failed for '" + path + "' (HTTP " + response.statusCode() + ").");
            }
            return toBuildFile(path, response.body());
        }

        List<String> candidates = List.of(
                "pom.xml", "build.gradle", "build.gradle.kts",
                "backend/pom.xml", "backend/build.gradle", "backend/build.gradle.kts",
                "app/pom.xml", "app/build.gradle", "app/build.gradle.kts",
                "server/pom.xml", "server/build.gradle", "server/build.gradle.kts",
                "service/pom.xml", "service/build.gradle", "service/build.gradle.kts",
                "api/pom.xml", "api/build.gradle", "api/build.gradle.kts",
                "java/pom.xml", "java/build.gradle", "java/build.gradle.kts"
        );
        for (String candidate : candidates) {
            HttpResponse<byte[]> response = fetchRaw(repo, branch, candidate);
            if (response.statusCode() == 200) return toBuildFile(candidate, response.body());
            if (response.statusCode() == 403) {
                throw new IllegalStateException("GitHub denied access to repository contents (HTTP 403). Make sure the repository is public and try again later.");
            }
            if (response.statusCode() != 404) {
                throw new IllegalStateException("GitHub file request failed for " + candidate + " (HTTP " + response.statusCode() + ").");
            }
        }

        throw new IllegalArgumentException("No supported Java build file was found automatically. Add pom.xml, build.gradle, or build.gradle.kts to the repository root, or enter Build file path for a Maven/Gradle module such as backend/pom.xml.");
    }

    private String validateBuildFilePath(String path) {
        if (path.startsWith("/") || path.startsWith("\\") || path.contains("..") || path.contains("\\")
                || !path.matches("[A-Za-z0-9._/-]{1,240}")) {
            throw new IllegalArgumentException("Build file path contains unsupported characters.");
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith("/pom.xml") || lower.equals("pom.xml")
                || lower.endsWith("/build.gradle") || lower.equals("build.gradle")
                || lower.endsWith("/build.gradle.kts") || lower.equals("build.gradle.kts"))) {
            throw new IllegalArgumentException("Build file must point to pom.xml, build.gradle, or build.gradle.kts.");
        }
        return path;
    }

    private BuildFile toBuildFile(String path, byte[] content) {
        if (content.length == 0) throw new IllegalArgumentException("The detected build file is empty.");
        if (content.length > MAX_BUILD_FILE_BYTES) throw new IllegalArgumentException("The detected build file is larger than the 2 MB scan limit.");
        String lower = path.toLowerCase(Locale.ROOT);
        String type = lower.endsWith("pom.xml") ? "Maven" : "Gradle";
        return new BuildFile(path, type, content);
    }

    private HttpResponse<byte[]> fetchRaw(RepoRef repo, String branch, String path) throws Exception {
        URI uri = URI.create("https://raw.githubusercontent.com/" + repo.owner() + "/" + repo.repository() + "/" + branch + "/" + path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "dependency-sentinel")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "FAILED");
        response.put("message", message == null || message.isBlank() ? "GitHub scan failed." : message);
        return ResponseEntity.status(status).body(response);
    }

    private String safeMessage(Exception e) {
        String value = e.getMessage();
        return value == null || value.isBlank() ? "Unable to retrieve or analyze the GitHub repository." : value.substring(0, Math.min(350, value.length()));
    }

    private record RepoRef(String owner, String repository) {}
    private record BuildFile(String path, String type, byte[] content) {}

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
