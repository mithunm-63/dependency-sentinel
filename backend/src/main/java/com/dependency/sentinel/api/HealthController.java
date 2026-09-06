package com.dependency.sentinel.api;

import com.dependency.sentinel.dependency.ResolvedDependency;
import com.dependency.sentinel.dependency.ResolvedDependencyRepository;
import com.dependency.sentinel.project.Project;
import com.dependency.sentinel.project.ProjectRepository;
import com.dependency.sentinel.project.Scan;
import com.dependency.sentinel.project.ScanRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects")
@CrossOrigin(origins = "${FRONTEND_ORIGIN:http://localhost:5173}")
public class HealthController {
    private final ProjectRepository projects;
    private final ScanRepository scans;
    private final ResolvedDependencyRepository dependencies;

    public HealthController(ProjectRepository projects,
                            ScanRepository scans,
                            ResolvedDependencyRepository dependencies) {
        this.projects = projects;
        this.scans = scans;
        this.dependencies = dependencies;
    }

    @Transactional(readOnly = true)
    @GetMapping("/{id}/health")
    public HealthResponse health(@PathVariable Long id) {
        Project project = projects.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        List<Scan> history = scans.findByProjectIdOrderByStartedAtDesc(id);
        Scan latest = history.isEmpty() ? null : history.get(0);
        Scan previous = history.size() > 1 ? history.get(1) : null;

        if (latest == null) {
            return new HealthResponse(project.getId(), project.getName(), 100, "NOT_SCANNED",
                    0, null, null, emptyChangeSet(), emptySecurityChange(),
                    List.of("Run your first dependency analysis to establish a project health baseline."));
        }

        ChangeSet changes = compare(latest, previous);
        SecurityChange securityChange = securityChange(latest, previous);
        int healthScore = calculateHealthScore(latest, changes, securityChange);
        String level = healthLevel(healthScore);

        List<String> highlights = buildHighlights(latest, previous, changes, securityChange, healthScore);

        return new HealthResponse(
                project.getId(), project.getName(), healthScore, level,
                history.size(), latest.startedAt, previous == null ? null : previous.startedAt,
                changes, securityChange, highlights);
    }

    private ChangeSet compare(Scan latest, Scan previous) {
        if (previous == null) return emptyChangeSet();

        Map<String, ResolvedDependency> current = dependencyMap(latest.getId());
        Map<String, ResolvedDependency> prior = dependencyMap(previous.getId());

        List<DependencyChange> added = new ArrayList<>();
        List<DependencyChange> removed = new ArrayList<>();
        List<DependencyChange> updated = new ArrayList<>();
        int unchanged = 0;

        for (Map.Entry<String, ResolvedDependency> entry : current.entrySet()) {
            ResolvedDependency now = entry.getValue();
            ResolvedDependency before = prior.get(entry.getKey());
            if (before == null) {
                added.add(DependencyChange.from(now, null));
            } else if (!Objects.equals(now.getVersion(), before.getVersion())
                    || !Objects.equals(now.getScope(), before.getScope())
                    || now.isDirect() != before.isDirect()) {
                updated.add(DependencyChange.from(now, before.getVersion()));
            } else {
                unchanged++;
            }
        }

        for (Map.Entry<String, ResolvedDependency> entry : prior.entrySet()) {
            if (!current.containsKey(entry.getKey())) {
                removed.add(DependencyChange.from(entry.getValue(), null));
            }
        }

        Comparator<DependencyChange> comparator = Comparator
                .comparing(DependencyChange::coordinate, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(DependencyChange::version, Comparator.nullsFirst(String::compareTo));
        added.sort(comparator);
        removed.sort(comparator);
        updated.sort(comparator);

        return new ChangeSet(added.size(), removed.size(), updated.size(), unchanged,
                added.stream().limit(12).toList(), removed.stream().limit(12).toList(), updated.stream().limit(12).toList());
    }

    private Map<String, ResolvedDependency> dependencyMap(Long scanId) {
        return dependencies.findByScanIdOrderByDepthAscGroupIdAscArtifactIdAsc(scanId).stream()
                .collect(Collectors.toMap(
                        d -> d.getGroupId() + ":" + d.getArtifactId(),
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    private SecurityChange securityChange(Scan latest, Scan previous) {
        Integer currentScore = latest.getSecurityScore();
        Integer previousScore = previous == null ? null : previous.getSecurityScore();
        int currentVulnerabilities = latest.getVulnerabilityCount();
        int previousVulnerabilities = previous == null ? 0 : previous.getVulnerabilityCount();
        int delta = currentVulnerabilities - previousVulnerabilities;
        Integer scoreDelta = currentScore == null || previousScore == null ? null : currentScore - previousScore;
        return new SecurityChange(currentVulnerabilities, previousVulnerabilities, delta,
                latest.getCriticalCount(), latest.getHighCount(), latest.getMediumCount(), latest.getLowCount(),
                currentScore, previousScore, scoreDelta);
    }

    private int calculateHealthScore(Scan latest, ChangeSet changes, SecurityChange security) {
        double securityScore = latest.getSecurityScore() == null ? 100 : latest.getSecurityScore();
        double driftPenalty = Math.min(15, changes.added() * 0.5 + changes.updated() * 0.25);
        double newRiskPenalty = Math.min(15, Math.max(0, security.newVulnerabilities()) * 3.0
                + Math.max(0, security.criticalDelta()) * 5.0);
        return (int) Math.max(0, Math.min(100, Math.round(securityScore * 0.8 + (100 - driftPenalty - newRiskPenalty) * 0.2)));
    }

    private String healthLevel(int score) {
        if (score >= 90) return "HEALTHY";
        if (score >= 75) return "WATCH";
        if (score >= 50) return "AT_RISK";
        return "CRITICAL";
    }

    private List<String> buildHighlights(Scan latest, Scan previous, ChangeSet changes,
                                         SecurityChange security, int healthScore) {
        List<String> highlights = new ArrayList<>();
        if (previous == null) {
            highlights.add("This is your baseline scan. Run another scan after dependency changes to see drift.");
        } else if (changes.added() == 0 && changes.removed() == 0 && changes.updated() == 0) {
            highlights.add("Dependency inventory is unchanged since the previous scan.");
        } else {
            if (changes.added() > 0) highlights.add(changes.added() + " dependency entr" + (changes.added() == 1 ? "y was" : "ies were") + " added.");
            if (changes.removed() > 0) highlights.add(changes.removed() + " dependency entr" + (changes.removed() == 1 ? "y was" : "ies were") + " removed.");
            if (changes.updated() > 0) highlights.add(changes.updated() + " dependency version/scope change" + (changes.updated() == 1 ? " was" : "s were") + " detected.");
        }
        if (security.newVulnerabilities() > 0) {
            highlights.add(security.newVulnerabilities() + " more vulnerability finding" + (security.newVulnerabilities() == 1 ? " appeared" : "s appeared") + " than in the previous scan.");
        } else if (security.newVulnerabilities() < 0) {
            highlights.add(Math.abs(security.newVulnerabilities()) + " vulnerability finding" + (Math.abs(security.newVulnerabilities()) == 1 ? " was" : "s were") + " resolved since the previous scan.");
        } else if (latest.getVulnerabilityCount() == 0) {
            highlights.add("No known vulnerabilities are recorded for the latest resolved versions.");
        }
        if (security.scoreDelta() != null) {
            if (security.scoreDelta() > 0) highlights.add("Security score improved by " + security.scoreDelta() + " points.");
            if (security.scoreDelta() < 0) highlights.add("Security score dropped by " + Math.abs(security.scoreDelta()) + " points.");
        }
        if (highlights.isEmpty()) highlights.add("Health score is " + healthScore + "/100 based on the latest scan.");
        return highlights.stream().limit(5).toList();
    }

    private static ChangeSet emptyChangeSet() {
        return new ChangeSet(0, 0, 0, 0, List.of(), List.of(), List.of());
    }

    private static SecurityChange emptySecurityChange() {
        return new SecurityChange(0, 0, 0, 0, 0, 0, 0, null, null, null);
    }

    public record HealthResponse(Long projectId, String projectName, int healthScore, String healthLevel,
                                 int scanCount, Instant latestScanAt, Instant previousScanAt,
                                 ChangeSet dependencyChanges, SecurityChange security,
                                 List<String> highlights) {}

    public record ChangeSet(int added, int removed, int updated, int unchanged,
                            List<DependencyChange> addedItems,
                            List<DependencyChange> removedItems,
                            List<DependencyChange> updatedItems) {}

    public record DependencyChange(String coordinate, String version, String previousVersion,
                                   String scope, boolean direct, int depth) {
        static DependencyChange from(ResolvedDependency dependency, String previousVersion) {
            return new DependencyChange(dependency.getGroupId() + ":" + dependency.getArtifactId(),
                    dependency.getVersion(), previousVersion, dependency.getScope(), dependency.isDirect(), dependency.getDepth());
        }
    }

    public record SecurityChange(int currentVulnerabilities, int previousVulnerabilities, int newVulnerabilities,
                                 int critical, int high, int medium, int low,
                                 Integer currentScore, Integer previousScore, Integer scoreDelta) {
        int criticalDelta() {
            return critical - (previousScore == null ? 0 : 0);
        }
    }
}
