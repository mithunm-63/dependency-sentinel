package com.dependency.sentinel.api;

import com.dependency.sentinel.analysis.PomScannerService;
import com.dependency.sentinel.dependency.DependencyEdge;
import com.dependency.sentinel.dependency.DependencyEdgeRepository;
import com.dependency.sentinel.dependency.ResolvedDependency;
import com.dependency.sentinel.dependency.ResolvedDependencyRepository;
import com.dependency.sentinel.project.DependencyRepository;
import com.dependency.sentinel.project.Project;
import com.dependency.sentinel.project.ProjectRepository;
import com.dependency.sentinel.project.Scan;
import com.dependency.sentinel.project.ScanRepository;
import com.dependency.sentinel.security.VulnerabilityFinding;
import com.dependency.sentinel.security.VulnerabilityFindingRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "${FRONTEND_ORIGIN:http://localhost:5173}")
public class ProjectController {
    private final ProjectRepository projects;
    private final DependencyRepository legacyDependencies;
    private final ResolvedDependencyRepository resolvedDependencies;
    private final DependencyEdgeRepository edges;
    private final ScanRepository scans;
    private final VulnerabilityFindingRepository findings;
    private final PomScannerService scanner;

    public ProjectController(ProjectRepository projects,
                             DependencyRepository legacyDependencies,
                             ResolvedDependencyRepository resolvedDependencies,
                             DependencyEdgeRepository edges,
                             ScanRepository scans,
                             VulnerabilityFindingRepository findings,
                             PomScannerService scanner) {
        this.projects = projects;
        this.legacyDependencies = legacyDependencies;
        this.resolvedDependencies = resolvedDependencies;
        this.edges = edges;
        this.scans = scans;
        this.findings = findings;
        this.scanner = scanner;
    }

    public record CreateProjectRequest(@NotBlank String name) {}

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "dependency-sentinel");
    }

    @PostMapping("/projects")
    public ProjectSummary create(@Valid @RequestBody CreateProjectRequest request) {
        Project p = new Project();
        p.setName(request.name().trim());
        p = projects.save(p);
        return summary(p);
    }

    @GetMapping("/projects")
    public List<ProjectSummary> list() {
        return projects.findAll().stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    @GetMapping("/projects/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        Project p = findProject(id);
        return details(p, latestScan(id));
    }

    @PostMapping(value = "/projects/{id}/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> scan(@PathVariable Long id, @RequestPart("file") MultipartFile file) throws Exception {
        Scan scan = scanner.scan(id, file);
        return details(findProject(id), scan);
    }

    @Transactional(readOnly = true)
    @GetMapping("/projects/{id}/dependencies")
    public List<ResolvedDependencySummary> dependencies(
            @PathVariable Long id,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "all") String type) {
        findProject(id);
        Scan latest = latestScan(id);
        if (latest == null) return List.of();
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        boolean directOnly = "direct".equalsIgnoreCase(type);
        boolean transitiveOnly = "transitive".equalsIgnoreCase(type);

        return resolvedDependencies.findByScanIdOrderByDepthAscGroupIdAscArtifactIdAsc(latest.getId()).stream()
                .filter(d -> !directOnly || d.isDirect())
                .filter(d -> !transitiveOnly || !d.isDirect())
                .filter(d -> query.isBlank() || (d.getGroupId() + ":" + d.getArtifactId() + ":" + d.getVersion())
                        .toLowerCase(Locale.ROOT).contains(query))
                .map(ResolvedDependencySummary::new)
                .toList();
    }

    @Transactional(readOnly = true)
    @GetMapping("/projects/{id}/dependencies/tree")
    public DependencyTreeResponse tree(@PathVariable Long id) {
        findProject(id);
        Scan latest = latestScan(id);
        if (latest == null) return new DependencyTreeResponse(null, List.of());

        List<ResolvedDependency> nodes = resolvedDependencies.findByScanIdOrderByDepthAscGroupIdAscArtifactIdAsc(latest.getId());
        Map<Long, ResolvedDependency> byId = nodes.stream().collect(Collectors.toMap(ResolvedDependency::getId, Function.identity()));
        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        for (DependencyEdge edge : edges.findByScanId(latest.getId())) {
            if (edge.getParent() == null || edge.getChild() == null) continue;
            Long parentId = edge.getParent().getId();
            Long childId = edge.getChild().getId();
            if (byId.containsKey(parentId) && byId.containsKey(childId)) {
                childrenByParent.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(childId);
            }
        }

        List<TreeDependency> roots = nodes.stream()
                .filter(ResolvedDependency::isDirect)
                .sorted(Comparator.comparing(ResolvedDependency::getGroupId)
                        .thenComparing(ResolvedDependency::getArtifactId)
                        .thenComparing(ResolvedDependency::getVersion))
                .map(ResolvedDependency::getId)
                .map(rootId -> buildTree(rootId, byId, childrenByParent, new HashSet<>()))
                .filter(Objects::nonNull)
                .toList();
        return new DependencyTreeResponse(new ScanSummary(latest), roots);
    }

    @Transactional(readOnly = true)
    @GetMapping("/projects/{id}/dependencies/graph")
    public DependencyGraphResponse graph(@PathVariable Long id) {
        findProject(id);
        Scan latest = latestScan(id);
        if (latest == null) return new DependencyGraphResponse(null, List.of(), List.of());

        List<ResolvedDependency> nodes = resolvedDependencies.findByScanIdOrderByDepthAscGroupIdAscArtifactIdAsc(latest.getId());
        Set<Long> nodeIds = nodes.stream().map(ResolvedDependency::getId).collect(Collectors.toSet());
        List<GraphNode> graphNodes = nodes.stream().map(GraphNode::new).toList();
        List<GraphEdge> graphLinks = edges.findByScanId(latest.getId()).stream()
                .filter(e -> e.getParent() != null && e.getChild() != null)
                .filter(e -> nodeIds.contains(e.getParent().getId()) && nodeIds.contains(e.getChild().getId()))
                .map(e -> new GraphEdge(e.getParent().getId(), e.getChild().getId()))
                .toList();
        return new DependencyGraphResponse(new ScanSummary(latest), graphNodes, graphLinks);
    }

    @Transactional(readOnly = true)
    @GetMapping("/projects/{id}/security")
    public SecurityResponse security(@PathVariable Long id) {
        findProject(id);
        Scan latest = latestScan(id);
        if (latest == null) return new SecurityResponse(null, "NOT_CHECKED", null, 0, 0, 0, 0, 0, "NOT_SCANNED", List.of());
        List<FindingSummary> rows = findings.findByScanIdOrderByRiskScoreDescSeverityAsc(latest.getId()).stream()
                .map(FindingSummary::new)
                .toList();
        return new SecurityResponse(
                new ScanSummary(latest), latest.getSecurityStatus(), latest.getSecurityScore(), latest.getVulnerabilityCount(),
                latest.getCriticalCount(), latest.getHighCount(), latest.getMediumCount(), latest.getLowCount(),
                riskLabel(latest), rows);
    }

    @Transactional(readOnly = true)
    @GetMapping("/projects/{id}/vulnerabilities")
    public List<FindingSummary> vulnerabilities(@PathVariable Long id,
                                                @RequestParam(required = false) String severity) {
        findProject(id);
        Scan latest = latestScan(id);
        if (latest == null) return List.of();
        return findings.findByScanIdOrderByRiskScoreDescSeverityAsc(latest.getId()).stream()
                .filter(f -> severity == null || severity.isBlank() || severity.equalsIgnoreCase(f.getSeverity()))
                .map(FindingSummary::new)
                .toList();
    }

    @Transactional(readOnly = true)
    @GetMapping("/projects/{id}/scans")
    public List<ScanSummary> scans(@PathVariable Long id) {
        findProject(id);
        return scans.findByProjectIdOrderByStartedAtDesc(id).stream().map(ScanSummary::new).toList();
    }

    private ProjectSummary summary(Project p) {
        Scan latest = latestScan(p.getId());
        int count = latest == null ? (int) legacyDependencies.countByProjectId(p.getId()) :
                Math.max(latest.getNodeCount(), latest.getDependencyCount());
        return new ProjectSummary(p.getId(), p.getName(), p.getBuildTool(), count, p.getCreatedAt());
    }

    private Map<String, Object> details(Project p, Scan latest) {
        Map<String, Object> out = new LinkedHashMap<>();
        int total = latest == null ? 0 : Math.max(latest.getNodeCount(), latest.getDependencyCount());
        out.put("id", p.getId());
        out.put("name", p.getName());
        out.put("buildTool", p.getBuildTool());
        out.put("securityScore", latest == null ? null : latest.getSecurityScore());
        out.put("securityStatus", latest == null ? "NOT_CHECKED" : latest.getSecurityStatus());
        out.put("vulnerabilities", latest == null ? 0 : latest.getVulnerabilityCount());
        out.put("critical", latest == null ? 0 : latest.getCriticalCount());
        out.put("high", latest == null ? 0 : latest.getHighCount());
        out.put("medium", latest == null ? 0 : latest.getMediumCount());
        out.put("low", latest == null ? 0 : latest.getLowCount());
        out.put("outdated", 0);
        out.put("conflicts", 0);
        out.put("dependencies", total);
        out.put("directDependencies", latest == null ? 0 : latest.getDependencyCount());
        out.put("transitiveDependencies", latest == null ? 0 : latest.getTransitiveCount());
        out.put("graphEdges", latest == null ? 0 : latest.getEdgeCount());
        out.put("truncated", latest != null && latest.isTruncated());
        out.put("latestScan", latest == null ? null : new ScanSummary(latest));
        out.put("phase", "Phase 3");
        out.put("nextStep", "Impact analysis and fixes arrive in Phase 4.");
        return out;
    }

    private String riskLabel(Scan scan) {
        if (!"CHECKED".equals(scan.getSecurityStatus()) || scan.getSecurityScore() == null) return "UNKNOWN";
        int score = scan.getSecurityScore();
        if (score >= 90) return "LOW_RISK";
        if (score >= 75) return "MODERATE_RISK";
        if (score >= 50) return "HIGH_RISK";
        return "CRITICAL_RISK";
    }

    private Scan latestScan(Long projectId) {
        return scans.findTopByProjectIdOrderByStartedAtDesc(projectId).orElse(null);
    }

    private Project findProject(Long id) {
        return projects.findById(id).orElseThrow(() -> new IllegalArgumentException("Project not found"));
    }

    private TreeDependency buildTree(Long id, Map<Long, ResolvedDependency> byId,
                                     Map<Long, List<Long>> childrenByParent, Set<Long> path) {
        ResolvedDependency node = byId.get(id);
        if (node == null) return null;
        if (!path.add(id)) {
            return new TreeDependency(node.getId(), node.getGroupId(), node.getArtifactId(), node.getVersion(),
                    node.getScope(), node.isDirect(), node.getDepth(), List.of());
        }
        List<TreeDependency> children = childrenByParent.getOrDefault(id, List.of()).stream()
                .distinct()
                .sorted(Comparator.comparing((Long childId) -> byId.get(childId).getGroupId())
                        .thenComparing(childId -> byId.get(childId).getArtifactId())
                        .thenComparing(childId -> byId.get(childId).getVersion()))
                .map(childId -> buildTree(childId, byId, childrenByParent, new HashSet<>(path)))
                .filter(Objects::nonNull)
                .toList();
        return new TreeDependency(node.getId(), node.getGroupId(), node.getArtifactId(), node.getVersion(),
                node.getScope(), node.isDirect(), node.getDepth(), children);
    }

    public record ProjectSummary(Long id, String name, String buildTool, int dependencyCount, Instant createdAt) {}

    public record ResolvedDependencySummary(Long id, String groupId, String artifactId, String version,
                                            String scope, boolean direct, int depth) {
        public ResolvedDependencySummary(ResolvedDependency d) {
            this(d.getId(), d.getGroupId(), d.getArtifactId(), d.getVersion(), d.getScope(), d.isDirect(), d.getDepth());
        }
    }

    public record ScanSummary(Long id, Instant startedAt, String status, int dependencyCount, int nodeCount,
                              int transitiveCount, int edgeCount, boolean truncated, String securityStatus,
                              Integer securityScore, int vulnerabilityCount, int criticalCount, int highCount,
                              int mediumCount, int lowCount) {
        public ScanSummary(Scan s) {
            this(s.getId(), s.getStartedAt(), s.getStatus(), s.getDependencyCount(), s.getNodeCount(),
                    s.getTransitiveCount(), s.getEdgeCount(), s.isTruncated(), s.getSecurityStatus(), s.getSecurityScore(),
                    s.getVulnerabilityCount(), s.getCriticalCount(), s.getHighCount(), s.getMediumCount(), s.getLowCount());
        }
    }

    public record TreeDependency(Long id, String groupId, String artifactId, String version,
                                 String scope, boolean direct, int depth, List<TreeDependency> children) {}

    public record DependencyTreeResponse(ScanSummary scan, List<TreeDependency> roots) {}

    public record GraphNode(Long id, String groupId, String artifactId, String version,
                            String scope, boolean direct, int depth) {
        public GraphNode(ResolvedDependency d) {
            this(d.getId(), d.getGroupId(), d.getArtifactId(), d.getVersion(), d.getScope(), d.isDirect(), d.getDepth());
        }
    }

    public record GraphEdge(Long parentId, Long childId) {}

    public record DependencyGraphResponse(ScanSummary scan, List<GraphNode> nodes, List<GraphEdge> edges) {}

    public record FindingSummary(Long id, String osvId, String cve, String artifactId, String groupId, String version,
                                 String severity, String summary, String details, String fixedVersion,
                                 String aliases, String cvssVector, String referenceUrl, boolean direct,
                                 int depth, int riskScore) {
        public FindingSummary(VulnerabilityFinding f) {
            this(f.getId(), f.getOsvId(), cveAlias(f.getAliases()), f.getDependency().getArtifactId(),
                    f.getDependency().getGroupId(), f.getDependency().getVersion(), f.getSeverity(), f.getSummary(),
                    f.getDetails(), f.getFixedVersion(), f.getAliases(), f.getCvssVector(), f.getReferenceUrl(),
                    f.getDependency().isDirect(), f.getDependency().getDepth(), f.getRiskScore());
        }

        private static String cveAlias(String aliases) {
            if (aliases == null) return null;
            return Arrays.stream(aliases.split(",")).map(String::trim)
                    .filter(value -> value.startsWith("CVE-")).findFirst().orElse(null);
        }
    }

    public record SecurityResponse(ScanSummary scan, String status, Integer securityScore, int vulnerabilityCount,
                                   int criticalCount, int highCount, int mediumCount, int lowCount,
                                   String riskLevel, List<FindingSummary> findings) {}
}
