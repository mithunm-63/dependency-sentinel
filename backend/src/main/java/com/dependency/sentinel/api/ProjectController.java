package com.dependency.sentinel.api;

import com.dependency.sentinel.analysis.PomScannerService;
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
    private final PomScannerService scanner;

    public ProjectController(ProjectRepository projects,
                              DependencyRepository legacyDependencies,
                              ResolvedDependencyRepository resolvedDependencies,
                              DependencyEdgeRepository edges,
                              ScanRepository scans,
                              PomScannerService scanner) {
        this.projects = projects;
        this.legacyDependencies = legacyDependencies;
        this.resolvedDependencies = resolvedDependencies;
        this.edges = edges;
        this.scans = scans;
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
        Scan latest = scans.findTopByProjectIdOrderByStartedAtDesc(id).orElse(null);
        return details(p, latest);
    }

    @PostMapping(value = "/projects/{id}/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> scan(@PathVariable Long id, @RequestPart("file") MultipartFile file) throws Exception {
        Scan scan = scanner.scan(id, file);
        Project p = findProject(id);
        return details(p, scan);
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
                .filter(d -> query.isBlank() ||
                        (d.getGroupId() + ":" + d.getArtifactId() + ":" + d.getVersion())
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
        List<DependencyEdge> graphEdges = edges.findByScanId(latest.getId());
        Map<Long, ResolvedDependency> byId = nodes.stream()
                .collect(Collectors.toMap(ResolvedDependency::getId, Function.identity()));
        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        Set<Long> childIds = new HashSet<>();
        for (DependencyEdge edge : graphEdges) {
            if (edge.getParent() == null || edge.getChild() == null) continue;
            Long parentId = edge.getParent().getId();
            Long childId = edge.getChild().getId();
            if (!byId.containsKey(parentId) || !byId.containsKey(childId)) continue;
            childrenByParent.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(childId);
            childIds.add(childId);
        }

        List<Long> rootIds = nodes.stream()
                .filter(ResolvedDependency::isDirect)
                .sorted(Comparator.comparing(ResolvedDependency::getGroupId)
                        .thenComparing(ResolvedDependency::getArtifactId)
                        .thenComparing(ResolvedDependency::getVersion))
                .map(ResolvedDependency::getId)
                .toList();

        List<TreeDependency> roots = rootIds.stream()
                .map(rootId -> buildTree(rootId, byId, childrenByParent, new HashSet<>()))
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
        List<DependencyEdge> graphEdges = edges.findByScanId(latest.getId());
        Set<Long> nodeIds = nodes.stream().map(ResolvedDependency::getId).collect(Collectors.toSet());
        List<GraphNode> graphNodes = nodes.stream().map(GraphNode::new).toList();
        List<GraphEdge> graphLinks = graphEdges.stream()
                .filter(e -> e.getParent() != null && e.getChild() != null)
                .filter(e -> nodeIds.contains(e.getParent().getId()) && nodeIds.contains(e.getChild().getId()))
                .map(e -> new GraphEdge(e.getParent().getId(), e.getChild().getId()))
                .toList();
        return new DependencyGraphResponse(new ScanSummary(latest), graphNodes, graphLinks);
    }

    @Transactional(readOnly = true)
    @GetMapping("/projects/{id}/scans")
    public List<ScanSummary> scans(@PathVariable Long id) {
        findProject(id);
        return scans.findByProjectIdOrderByStartedAtDesc(id).stream().map(ScanSummary::new).toList();
    }

    private ProjectSummary summary(Project p) {
        Scan latest = scans.findTopByProjectIdOrderByStartedAtDesc(p.getId()).orElse(null);
        int count = latest == null ? (int) legacyDependencies.countByProjectId(p.getId()) : (int) resolvedDependencies.countByScanId(latest.getId());
        return new ProjectSummary(p.getId(), p.getName(), p.getBuildTool(), count, p.getCreatedAt());
    }

    private Map<String, Object> details(Project p, Scan latest) {
        Map<String, Object> out = new LinkedHashMap<>();
        int total = latest == null ? 0 : latest.getNodeCount();
        out.put("id", p.getId());
        out.put("name", p.getName());
        out.put("buildTool", p.getBuildTool());
        out.put("securityScore", 100);
        out.put("vulnerabilities", 0);
        out.put("outdated", 0);
        out.put("conflicts", 0);
        out.put("dependencies", total);
        out.put("directDependencies", latest == null ? 0 : latest.getDependencyCount());
        out.put("transitiveDependencies", latest == null ? 0 : latest.getTransitiveCount());
        out.put("graphEdges", latest == null ? 0 : latest.getEdgeCount());
        out.put("truncated", latest != null && latest.isTruncated());
        out.put("latestScan", latest == null ? null : new ScanSummary(latest));
        out.put("phase", "Phase 2");
        out.put("nextStep", "Security intelligence arrives in Phase 3.");
        return out;
    }

    private Scan latestScan(Long projectId) {
        return scans.findTopByProjectIdOrderByStartedAtDesc(projectId).orElse(null);
    }

    private Project findProject(Long id) {
        return projects.findById(id).orElseThrow(() -> new IllegalArgumentException("Project not found"));
    }

    private TreeDependency buildTree(Long id,
                                     Map<Long, ResolvedDependency> byId,
                                     Map<Long, List<Long>> childrenByParent,
                                     Set<Long> path) {
        ResolvedDependency node = byId.get(id);
        if (node == null) return null;
        if (!path.add(id)) return new TreeDependency(node.getId(), node.getGroupId(), node.getArtifactId(),
                node.getVersion(), node.getScope(), node.isDirect(), node.getDepth(), List.of());

        List<TreeDependency> children = childrenByParent.getOrDefault(id, List.of()).stream()
                .distinct()
                .sorted(Comparator.comparing(childId -> byId.get(childId).getGroupId())
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

    public record ScanSummary(Long id, Instant startedAt, String status, int dependencyCount,
                              int nodeCount, int transitiveCount, int edgeCount, boolean truncated) {
        public ScanSummary(Scan s) {
            this(s.getId(), s.getStartedAt(), s.getStatus(), s.getDependencyCount(), s.getNodeCount(),
                    s.getTransitiveCount(), s.getEdgeCount(), s.isTruncated());
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
}
