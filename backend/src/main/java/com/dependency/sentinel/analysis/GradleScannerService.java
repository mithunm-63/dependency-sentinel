package com.dependency.sentinel.analysis;

import com.dependency.sentinel.dependency.DependencyEdge;
import com.dependency.sentinel.dependency.DependencyEdgeRepository;
import com.dependency.sentinel.dependency.ResolvedDependency;
import com.dependency.sentinel.dependency.ResolvedDependencyRepository;
import com.dependency.sentinel.project.Dependency;
import com.dependency.sentinel.project.DependencyRepository;
import com.dependency.sentinel.project.Project;
import com.dependency.sentinel.project.ProjectRepository;
import com.dependency.sentinel.project.Scan;
import com.dependency.sentinel.project.ScanRepository;
import com.dependency.sentinel.security.OsVulnerabilityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GradleScannerService {
    private static final Pattern DEPENDENCY_PATTERN = Pattern.compile(
            "\\b(implementation|api|compileOnly|runtimeOnly|runtime|testImplementation|testRuntimeOnly|annotationProcessor|kapt)"
                    + "\\s*\\(\\s*(?:(?:platform|enforcedPlatform)\\s*\\(\\s*)?['\\\"]([^'\\\"]+)['\\\"]",
            Pattern.MULTILINE);
    private static final String DEPS_DEV = "https://api.deps.dev/v3/systems/MAVEN/packages/";

    private final ProjectRepository projectRepository;
    private final DependencyRepository dependencyRepository;
    private final ScanRepository scanRepository;
    private final ResolvedDependencyRepository resolvedRepository;
    private final DependencyEdgeRepository edgeRepository;
    private final OsVulnerabilityService vulnerabilityService;
    private final ObjectMapper mapper;
    private final HttpClient client;

    @Value("${dependency-scan.max-nodes:500}")
    private int maxNodes;

    @Value("${dependency-scan.max-depth:20}")
    private int maxDepth;

    public GradleScannerService(ProjectRepository projectRepository,
                                DependencyRepository dependencyRepository,
                                ScanRepository scanRepository,
                                ResolvedDependencyRepository resolvedRepository,
                                DependencyEdgeRepository edgeRepository,
                                OsVulnerabilityService vulnerabilityService,
                                ObjectMapper mapper) {
        this.projectRepository = projectRepository;
        this.dependencyRepository = dependencyRepository;
        this.scanRepository = scanRepository;
        this.resolvedRepository = resolvedRepository;
        this.edgeRepository = edgeRepository;
        this.vulnerabilityService = vulnerabilityService;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    @Transactional
    public Scan scan(Long projectId, MultipartFile file) throws Exception {
        validateFile(file);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        project.setBuildTool("GRADLE");

        Scan scan = new Scan();
        scan.setProject(project);
        scan.setStatus("SCANNING");
        scan.setSecurityStatus("NOT_CHECKED");
        scanRepository.saveAndFlush(scan);

        String source = new String(file.getBytes(), StandardCharsets.UTF_8);
        List<DirectDependency> directDependencies = parseDirectDependencies(source);
        if (directDependencies.isEmpty()) {
            scan.setStatus("FAILED");
            scan.setSecurityStatus("FAILED");
            scan.setMessage("No explicit external Gradle dependencies with literal group:artifact:version coordinates were found. Version catalogs and project() dependencies require their resolved values before scanning.");
            scanRepository.save(scan);
            throw new IllegalArgumentException(scan.getMessage());
        }

        dependencyRepository.deleteByProject(project);
        List<Dependency> legacyRows = new ArrayList<>();
        for (DirectDependency direct : directDependencies) {
            Dependency row = new Dependency();
            row.setProject(project);
            row.setGroupId(direct.groupId());
            row.setArtifactId(direct.artifactId());
            row.setVersion(direct.version());
            row.setScope(direct.scope());
            legacyRows.add(row);
        }
        dependencyRepository.saveAll(legacyRows);
        scan.setDependencyCount(legacyRows.size());

        Map<String, NodeData> nodes = new LinkedHashMap<>();
        Set<String> edgeKeys = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();

        for (DirectDependency direct : directDependencies.stream().limit(50).toList()) {
            String rootKey = coordinate(direct.groupId(), direct.artifactId(), direct.version());
            NodeData root = nodes.computeIfAbsent(rootKey,
                    ignored -> new NodeData(direct.groupId(), direct.artifactId(), direct.version(), direct.scope(), true, 0));
            root.direct = true;
            root.depth = 0;

            try {
                JsonNode graph = fetchDependencies(direct.groupId() + ":" + direct.artifactId(), direct.version());
                addDepsDevGraph(graph, rootKey, direct.scope(), nodes, edgeKeys);
            } catch (Exception e) {
                warnings.add("Could not resolve transitive dependencies for " + rootKey + ": " + safeMessage(e));
            }
        }

        if (directDependencies.size() > 50) {
            warnings.add("Only the first 50 direct Gradle dependencies were expanded to keep the scan predictable.");
        }

        List<ResolvedDependency> savedNodes = new ArrayList<>();
        for (NodeData data : nodes.values().stream().limit(maxNodes).toList()) {
            ResolvedDependency row = new ResolvedDependency();
            row.setScan(scan);
            row.setGroupId(data.groupId);
            row.setArtifactId(data.artifactId);
            row.setVersion(data.version);
            row.setScope(data.scope == null || data.scope.isBlank() ? "compile" : data.scope);
            row.setDirect(data.direct);
            row.setDepth(Math.min(data.depth, maxDepth));
            savedNodes.add(row);
        }
        boolean truncated = nodes.size() > maxNodes;
        resolvedRepository.saveAll(savedNodes);

        Map<String, ResolvedDependency> byKey = new HashMap<>();
        for (ResolvedDependency row : savedNodes) {
            byKey.put(coordinate(row.getGroupId(), row.getArtifactId(), row.getVersion()), row);
        }

        List<DependencyEdge> edges = new ArrayList<>();
        for (String edgeKey : edgeKeys) {
            int split = edgeKey.indexOf('\\u0000');
            String parentKey = edgeKey.substring(0, split);
            String childKey = edgeKey.substring(split + 1);
            ResolvedDependency parent = byKey.get(parentKey);
            ResolvedDependency child = byKey.get(childKey);
            if (parent == null || child == null) continue;
            DependencyEdge edge = new DependencyEdge();
            edge.setScan(scan);
            edge.setParent(parent);
            edge.setChild(child);
            edges.add(edge);
        }
        edgeRepository.saveAll(edges);

        scan.setNodeCount(savedNodes.size());
        scan.setEdgeCount(edges.size());
        scan.setTransitiveCount(Math.max(0, savedNodes.size() - directDependencies.size()));
        scan.setTruncated(truncated);

        try {
            OsVulnerabilityService.SecurityResult security = vulnerabilityService.scan(scan, savedNodes);
            scan.setVulnerabilityCount(security.vulnerabilityCount());
            scan.setCriticalCount(security.criticalCount());
            scan.setHighCount(security.highCount());
            scan.setMediumCount(security.mediumCount());
            scan.setLowCount(security.lowCount());
            scan.setSecurityScore(security.securityScore());
            scan.setSecurityStatus("CHECKED");
        } catch (Exception securityError) {
            scan.setSecurityStatus("FAILED");
            scan.setSecurityScore(null);
            warnings.add("Vulnerability intelligence could not be retrieved from OSV.");
        }

        if (!warnings.isEmpty()) scan.setMessage(String.join(" ", warnings));
        scan.setStatus("READY");
        return scanRepository.save(scan);
    }

    private List<DirectDependency> parseDirectDependencies(String source) {
        Matcher matcher = Pattern.compile("(?m)\\b(implementation|api|compileOnly|runtimeOnly|runtime|testImplementation|testRuntimeOnly|annotationProcessor|kapt)\\s*(?:\\(\\s*)?(?:(?:platform|enforcedPlatform)\\s*\\(\\s*)?['\\\"]([^'\\\"]+)['\\\"]").matcher(source);
        Map<String, DirectDependency> unique = new LinkedHashMap<>();
        while (matcher.find()) {
            String configuration = matcher.group(1);
            String coordinate = matcher.group(2).trim();
            String[] parts = coordinate.split(":");
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) continue;
            if (parts[2].contains("$") || parts[2].contains("{") || parts[2].contains("}")) continue;
            String key = parts[0] + ":" + parts[1] + ":" + parts[2];
            unique.putIfAbsent(key, new DirectDependency(parts[0], parts[1], parts[2], scope(configuration)));
        }
        return new ArrayList<>(unique.values());
    }

    private JsonNode fetchDependencies(String packageName, String version) throws Exception {
        String encodedName = URLEncoder.encode(packageName, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedVersion = URLEncoder.encode(version, StandardCharsets.UTF_8).replace("+", "%20");
        URI uri = URI.create(DEPS_DEV + encodedName + "/versions/" + encodedVersion + ":dependencies");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "dependency-sentinel")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("deps.dev returned HTTP " + response.statusCode());
        }
        return mapper.readTree(response.body());
    }

    private void addDepsDevGraph(JsonNode graph, String directRoot, String directScope,
                                 Map<String, NodeData> nodes, Set<String> edgeKeys) {
        JsonNode graphNodes = graph.path("nodes");
        JsonNode graphEdges = graph.path("edges");
        if (!graphNodes.isArray() || graphNodes.isEmpty()) return;

        Map<Integer, String> indexToKey = new HashMap<>();
        for (int i = 0; i < graphNodes.size(); i++) {
            JsonNode node = graphNodes.get(i);
            JsonNode versionKey = node.path("versionKey");
            String name = versionKey.path("name").asText("");
            String version = versionKey.path("version").asText("");
            String[] parts = name.split(":", 2);
            if (parts.length != 2 || version.isBlank()) continue;
            String key = coordinate(parts[0], parts[1], version);
            indexToKey.put(i, key);
            boolean self = "SELF".equalsIgnoreCase(node.path("relation").asText()) || i == 0;
            NodeData data = nodes.computeIfAbsent(key,
                    ignored -> new NodeData(parts[0], parts[1], version, self ? directScope : "compile", self, self ? 0 : maxDepth));
            if (self) {
                data.direct = true;
                data.depth = 0;
                data.scope = directScope;
            }
        }

        Map<Integer, List<Integer>> adjacency = new HashMap<>();
        if (graphEdges.isArray()) {
            for (JsonNode edge : graphEdges) {
                int from = edge.path("fromNode").asInt(-1);
                int to = edge.path("toNode").asInt(-1);
                String fromKey = indexToKey.get(from);
                String toKey = indexToKey.get(to);
                if (fromKey == null || toKey == null) continue;
                edgeKeys.add(fromKey + "\\u0000" + toKey);
                adjacency.computeIfAbsent(from, ignored -> new ArrayList<>()).add(to);
            }
        }

        Map<Integer, Integer> depths = new HashMap<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        depths.put(0, 0);
        queue.add(0);
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            int nextDepth = depths.get(current) + 1;
            for (Integer child : adjacency.getOrDefault(current, List.of())) {
                if (nextDepth > maxDepth) continue;
                if (depths.putIfAbsent(child, nextDepth) == null) queue.addLast(child);
            }
        }
        for (Map.Entry<Integer, Integer> entry : depths.entrySet()) {
            String key = indexToKey.get(entry.getKey());
            NodeData data = key == null ? null : nodes.get(key);
            if (data != null && entry.getValue() < data.depth) data.depth = entry.getValue();
        }
    }

    private String scope(String configuration) {
        if (configuration.startsWith("test")) return "test";
        if (configuration.equals("runtime") || configuration.equals("runtimeOnly")) return "runtime";
        if (configuration.equals("compileOnly")) return "provided";
        return "compile";
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Gradle build file is empty");
        String name = file.getOriginalFilename();
        if (name == null || !(name.equalsIgnoreCase("build.gradle") || name.equalsIgnoreCase("build.gradle.kts"))) {
            throw new IllegalArgumentException("Upload build.gradle or build.gradle.kts");
        }
        if (file.getSize() > 2_000_000) throw new IllegalArgumentException("Gradle build file is larger than the 2 MB scan limit.");
    }

    private String coordinate(String groupId, String artifactId, String version) {
        return groupId + ":" + artifactId + ":" + version;
    }

    private String safeMessage(Exception e) {
        String value = e.getMessage();
        return value == null || value.isBlank() ? "dependency metadata lookup failed" : value.substring(0, Math.min(180, value.length()));
    }

    private static final class NodeData {
        private final String groupId;
        private final String artifactId;
        private final String version;
        private String scope;
        private boolean direct;
        private int depth;

        private NodeData(String groupId, String artifactId, String version, String scope, boolean direct, int depth) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.scope = scope;
            this.direct = direct;
            this.depth = depth;
        }
    }

    private record DirectDependency(String groupId, String artifactId, String version, String scope) {}
}
