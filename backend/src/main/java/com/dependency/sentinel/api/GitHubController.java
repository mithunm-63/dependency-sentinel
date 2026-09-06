package com.dependency.sentinel.api;

import com.dependency.sentinel.analysis.PomScannerService;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@CrossOrigin(origins = "${FRONTEND_ORIGIN:http://localhost:5173}")
public class GitHubController {
    private final PomScannerService scanner;
    private final HttpClient httpClient;

    public GitHubController(PomScannerService scanner) {
        this.scanner = scanner;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public record GitHubScanRequest(@NotBlank String repoUrl, String branch) {}

    @PostMapping("/{id}/github/scan")
    public ResponseEntity<Map<String, Object>> scanFromGitHub(
            @PathVariable Long id,
            @Valid @RequestBody GitHubScanRequest request) {
        try {
            RepoRef repo = parseRepo(request.repoUrl());
            String branch = request.branch() == null || request.branch().isBlank()
                    ? defaultBranch(repo)
                    : validateBranch(request.branch().trim());

            byte[] pom = fetchPom(repo, branch);
            MultipartFile file = new ByteArrayMultipartFile("file", "pom.xml", "application/xml", pom);
            Scan scan = scanner.scan(id, file);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", scan.getStatus());
            response.put("scanId", scan.getId());
            response.put("repository", repo.owner() + "/" + repo.repository());
            response.put("branch", branch);
            response.put("pomUrl", "https://github.com/" + repo.owner() + "/" + repo.repository() + "/blob/" + branch + "/pom.xml");
            response.put("dependencyCount", scan.getNodeCount());
            response.put("vulnerabilityCount", scan.getVulnerabilityCount());
            response.put("securityScore", scan.getSecurityScore());
            response.put("securityStatus", scan.getSecurityStatus());
            response.put("message", "GitHub pom.xml scanned successfully.");
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

    private String defaultBranch(RepoRef repo) throws Exception {
        URI uri = URI.create("https://api.github.com/repos/" + repo.owner() + "/" + repo.repository());
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "dependency-sentinel")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            if (response.statusCode() == 404) throw new IllegalArgumentException("GitHub repository was not found or is private.");
            throw new IllegalStateException("GitHub repository metadata could not be retrieved (HTTP " + response.statusCode() + ").");
        }
        String marker = "\"default_branch\":\"";
        int start = response.body().indexOf(marker);
        if (start < 0) throw new IllegalStateException("GitHub did not return a default branch.");
        start += marker.length();
        int end = response.body().indexOf('"', start);
        if (end <= start) throw new IllegalStateException("GitHub returned an invalid default branch.");
        return validateBranch(response.body().substring(start, end));
    }

    private byte[] fetchPom(RepoRef repo, String branch) throws Exception {
        URI uri = URI.create("https://raw.githubusercontent.com/" + repo.owner() + "/" + repo.repository() + "/" + branch + "/pom.xml");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "dependency-sentinel")
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) {
            throw new IllegalArgumentException("No pom.xml was found at that branch. Check the branch name or repository URL.");
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GitHub raw file request failed (HTTP " + response.statusCode() + ").");
        }
        if (response.body().length == 0) throw new IllegalArgumentException("The GitHub pom.xml is empty.");
        if (response.body().length > 2_000_000) throw new IllegalArgumentException("The GitHub pom.xml is larger than the 2 MB scan limit.");
        return response.body();
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
