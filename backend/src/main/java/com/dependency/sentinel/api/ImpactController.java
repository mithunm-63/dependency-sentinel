package com.dependency.sentinel.api;

import com.dependency.sentinel.dependency.DependencyEdge;
import com.dependency.sentinel.dependency.DependencyEdgeRepository;
import com.dependency.sentinel.dependency.ResolvedDependency;
import com.dependency.sentinel.dependency.ResolvedDependencyRepository;
import com.dependency.sentinel.project.ProjectRepository;
import com.dependency.sentinel.project.Scan;
import com.dependency.sentinel.project.ScanRepository;
import com.dependency.sentinel.security.VulnerabilityFinding;
import com.dependency.sentinel.security.VulnerabilityFindingRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "${FRONTEND_ORIGIN:http://localhost:5173}")
public class ImpactController {
    private final ProjectRepository projects;
    private final ScanRepository scans;
    private final ResolvedDependencyRepository dependencies;
    private final DependencyEdgeRepository edges;
    private final VulnerabilityFindingRepository findings;

    public ImpactController(ProjectRepository projects,
                            ScanRepository scans,
                            ResolvedDependencyRepository dependencies,
                            DependencyEdgeRepository edges,
                            VulnerabilityFindingRepository findings) {
        this.projects = projects;
        this.scans = scans;
        this.dependencies = dependencies;
        this.edges = edges;
        this.findings = findings;
    }

    @Transactional(readOnly = true)
    @GetMapping("/projects/{projectId}/vulnerabilities/{findingId}/impact")
    public ImpactResponse impact(@PathVariable Long projectId, @PathVariable Long findingId) {
        projects.findById(projectId).orElseThrow(() -> new IllegalArgumentException("Project not found"));
        Scan scan = scans.findTopByProjectIdOrderByStartedAtDesc(projectId)
                .orElseThrow(() -> new IllegalArgumentException("No dependency scan found."));
        VulnerabilityFinding finding = findings.findById(findingId)
                .orElseThrow(() -> new IllegalArgumentException("Vulnerability finding not found."));
        if (!Objects.equals(finding.getScan().getId(), scan.getId())) {
            throw new IllegalArgumentException("Finding does not belong to the latest scan.");
        }

        List<ResolvedDependency> nodes = dependencies.findByScanIdOrderByDepthAscGroupIdAscArtifactIdAsc(scan.getId());
        Map<Long, ResolvedDependency> byId = nodes.stream().collect(Collectors.toMap(ResolvedDependency::getId, d -> d));
        ResolvedDependency target = byId.get(finding.getDependency().getId());
        if (target == null) throw new IllegalArgumentException("Affected dependency is not present in the latest graph.");

        Map<Long, List<Long>> parents = new HashMap<>();
        Map<Long, List<Long>> children = new HashMap<>();
        for (DependencyEdge edge : edges.findByScanId(scan.getId())) {
            if (edge.getParent() == null || edge.getChild() == null) continue;
            long parentId = edge.getParent().getId();
            long childId = edge.getChild().getId();
            if (!byId.containsKey(parentId) || !byId.containsKey(childId)) continue;
            parents.computeIfAbsent(childId, ignored -> new ArrayList<>()).add(parentId);
            children.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(childId);
        }

        Map<Long, Integer> distanceToTarget = reverseDistances(target.getId(), parents);
        List<ResolvedDependency> entryPoints = nodes.stream()
                .filter(ResolvedDependency::isDirect)
                .filter(d -> distanceToTarget.containsKey(d.getId()))
                .sorted(Comparator.comparing(ResolvedDependency::getGroupId)
                        .thenComparing(ResolvedDependency::getArtifactId))
                .toList();

        List<List<String>> paths = new ArrayList<>();
        for (ResolvedDependency root : entryPoints) {
            if (paths.size() >= 6) break;
            List<ResolvedDependency> path = new ArrayList<>();
            buildShortestPaths(root.getId(), target.getId(), children, distanceToTarget, byId,
                    new ArrayList<>(), paths, 6, path);
        }

        Set<Long> ancestors = new LinkedHashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        queue.add(target.getId());
        while (!queue.isEmpty()) {
            Long current = queue.removeFirst();
            for (Long parent : parents.getOrDefault(current, List.of())) {
                if (ancestors.add(parent)) queue.addLast(parent);
            }
        }

        List<String> entryNames = entryPoints.stream().map(this::coordinate).toList();
        boolean direct = target.isDirect();
        String explanation = direct
                ? String.format("%s is declared directly in pom.xml, so the project can remediate it without tracing another library first.", coordinate(target))
                : String.format("%s is transitive and enters this project through %d direct dependency path(s).", coordinate(target), entryPoints.size());

        String remediation = finding.getFixedVersion() == null || finding.getFixedVersion().isBlank()
                ? "No fixed release was listed by the advisory. Review the advisory and vendor guidance before changing the dependency."
                : String.format("Upgrade %s from %s to the advisory's fixed version %s, then run Analyze project again.",
                target.getArtifactId(), target.getVersion(), finding.getFixedVersion());

        return new ImpactResponse(
                finding.getId(), finding.getOsvId(), finding.getSeverity(), coordinate(target), direct, target.getDepth(),
                entryPoints.size(), ancestors.size(), ancestors.size() + 1, entryNames, paths, explanation, remediation,
                finding.getFixedVersion());
    }

    private Map<Long, Integer> reverseDistances(long targetId, Map<Long, List<Long>> parents) {
        Map<Long, Integer> distance = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        queue.add(targetId);
        distance.put(targetId, 0);
        while (!queue.isEmpty()) {
            long current = queue.removeFirst();
            int next = distance.get(current) + 1;
            for (Long parent : parents.getOrDefault(current, List.of())) {
                if (!distance.containsKey(parent)) {
                    distance.put(parent, next);
                    queue.addLast(parent);
                }
            }
        }
        return distance;
    }

    private void buildShortestPaths(Long currentId,
                                    Long targetId,
                                    Map<Long, List<Long>> children,
                                    Map<Long, Integer> distanceToTarget,
                                    Map<Long, ResolvedDependency> byId,
                                    List<Long> currentPath,
                                    List<List<String>> output,
                                    int maxPaths,
                                    List<ResolvedDependency> scratch) {
        if (output.size() >= maxPaths) return;
        currentPath.add(currentId);
        scratch.add(byId.get(currentId));
        if (Objects.equals(currentId, targetId)) {
            output.add(scratch.stream().filter(Objects::nonNull).map(this::coordinate).toList());
        } else {
            int nextDistance = distanceToTarget.getOrDefault(currentId, Integer.MAX_VALUE) - 1;
            for (Long child : children.getOrDefault(currentId, List.of())) {
                if (distanceToTarget.getOrDefault(child, Integer.MAX_VALUE) == nextDistance) {
                    buildShortestPaths(child, targetId, children, distanceToTarget, byId,
                            currentPath, output, maxPaths, scratch);
                    if (output.size() >= maxPaths) break;
                }
            }
        }
        scratch.remove(scratch.size() - 1);
        currentPath.remove(currentPath.size() - 1);
    }

    private String coordinate(ResolvedDependency d) {
        return d.getGroupId() + ":" + d.getArtifactId() + ":" + d.getVersion();
    }

    public record ImpactResponse(Long findingId,
                                String osvId,
                                String severity,
                                String affectedDependency,
                                boolean direct,
                                int depth,
                                int directEntryPoints,
                                int upstreamDependencyCount,
                                int blastRadius,
                                List<String> entryPoints,
                                List<List<String>> shortestPaths,
                                String explanation,
                                String remediation,
                                String fixedVersion) {}
}
