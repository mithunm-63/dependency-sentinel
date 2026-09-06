package com.dependency.sentinel.api;

import com.dependency.sentinel.dependency.ResolvedDependency;
import com.dependency.sentinel.dependency.ResolvedDependencyRepository;
import com.dependency.sentinel.project.Project;
import com.dependency.sentinel.project.ProjectRepository;
import com.dependency.sentinel.project.Scan;
import com.dependency.sentinel.project.ScanRepository;
import com.dependency.sentinel.security.OsVulnerabilityService;
import com.dependency.sentinel.security.VulnerabilityFinding;
import com.dependency.sentinel.security.VulnerabilityFindingRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "${FRONTEND_ORIGIN:http://localhost:5173}")
public class SecurityController {
    private final ProjectRepository projects;
    private final ScanRepository scans;
    private final ResolvedDependencyRepository dependencies;
    private final VulnerabilityFindingRepository findings;
    private final OsVulnerabilityService vulnerabilityService;

    public SecurityController(ProjectRepository projects,
                              ScanRepository scans,
                              ResolvedDependencyRepository dependencies,
                              VulnerabilityFindingRepository findings,
                              OsVulnerabilityService vulnerabilityService) {
        this.projects = projects;
        this.scans = scans;
        this.dependencies = dependencies;
        this.findings = findings;
        this.vulnerabilityService = vulnerabilityService;
    }

    @PostMapping("/projects/{id}/security/rescan")
    @Transactional
    public ProjectController.SecurityResponse rescan(@PathVariable Long id) throws Exception {
        Project project = projects.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        Scan scan = scans.findTopByProjectIdOrderByStartedAtDesc(id)
                .orElseThrow(() -> new IllegalArgumentException("Run a dependency scan before checking security."));
        List<ResolvedDependency> nodes = dependencies.findByScanIdOrderByDepthAscGroupIdAscArtifactIdAsc(scan.getId());

        try {
            OsVulnerabilityService.SecurityResult result = vulnerabilityService.scan(scan, nodes);
            applyResult(scan, result);
            scan.setStatus("READY");
            scans.save(scan);
        } catch (Exception e) {
            scan.setSecurityStatus("FAILED");
            scan.setSecurityScore(null);
            scan.setMessage("Dependency graph is ready, but vulnerability intelligence could not be retrieved from OSV.");
            scans.save(scan);
            throw new IllegalArgumentException("OSV security check failed. Try again in a moment.");
        }

        return response(project, scan);
    }

    private void applyResult(Scan scan, OsVulnerabilityService.SecurityResult result) {
        scan.setVulnerabilityCount(result.vulnerabilityCount());
        scan.setCriticalCount(result.criticalCount());
        scan.setHighCount(result.highCount());
        scan.setMediumCount(result.mediumCount());
        scan.setLowCount(result.lowCount());
        scan.setSecurityScore(result.securityScore());
        scan.setSecurityStatus("CHECKED");
    }

    private ProjectController.SecurityResponse response(Project project, Scan scan) {
        List<ProjectController.FindingSummary> rows = findings.findByScanIdOrderByRiskScoreDescSeverityAsc(scan.getId()).stream()
                .map(ProjectController.FindingSummary::new)
                .toList();
        String risk = riskLabel(scan);
        return new ProjectController.SecurityResponse(
                new ProjectController.ScanSummary(scan), scan.getSecurityStatus(), scan.getSecurityScore(),
                scan.getVulnerabilityCount(), scan.getCriticalCount(), scan.getHighCount(), scan.getMediumCount(),
                scan.getLowCount(), risk, rows);
    }

    private String riskLabel(Scan scan) {
        if (!"CHECKED".equals(scan.getSecurityStatus()) || scan.getSecurityScore() == null) return "UNKNOWN";
        int score = scan.getSecurityScore();
        if (score >= 90) return "LOW_RISK";
        if (score >= 75) return "MODERATE_RISK";
        if (score >= 50) return "HIGH_RISK";
        return "CRITICAL_RISK";
    }
}
