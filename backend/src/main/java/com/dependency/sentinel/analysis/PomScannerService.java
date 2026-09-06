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
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.util.*;

@Service
public class PomScannerService {
    private final ProjectRepository projectRepository;
    private final DependencyRepository dependencyRepository;
    private final ScanRepository scanRepository;
    private final ResolvedDependencyRepository resolvedRepository;
    private final DependencyEdgeRepository edgeRepository;
    private final RepositorySystem repositorySystem;
    private final RemoteRepository mavenCentralRepository;
    private final OsVulnerabilityService vulnerabilityService;

    @Value("${dependency-scan.max-nodes:500}")
    private int maxNodes;

    @Value("${dependency-scan.max-depth:20}")
    private int maxDepth;

    public PomScannerService(ProjectRepository projectRepository,
                             DependencyRepository dependencyRepository,
                             ScanRepository scanRepository,
                             ResolvedDependencyRepository resolvedRepository,
                             DependencyEdgeRepository edgeRepository,
                             RepositorySystem repositorySystem,
                             RemoteRepository mavenCentralRepository,
                             OsVulnerabilityService vulnerabilityService) {
        this.projectRepository = projectRepository;
        this.dependencyRepository = dependencyRepository;
        this.scanRepository = scanRepository;
        this.resolvedRepository = resolvedRepository;
        this.edgeRepository = edgeRepository;
        this.repositorySystem = repositorySystem;
        this.mavenCentralRepository = mavenCentralRepository;
        this.vulnerabilityService = vulnerabilityService;
    }

    @Transactional
    public Scan scan(Long projectId, MultipartFile file) throws Exception {
        validateFile(file);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        Scan scan = new Scan();
        scan.setProject(project);
        scan.setStatus("SCANNING");
        scan.setSecurityStatus("NOT_CHECKED");
        scanRepository.saveAndFlush(scan);

        try (InputStream in = file.getInputStream()) {
            Model model = new MavenXpp3Reader().read(in);
            List<org.apache.maven.model.Dependency> directDependencies = model.getDependencies();

            dependencyRepository.deleteByProject(project);
            List<Dependency> legacyRows = new ArrayList<>();
            for (org.apache.maven.model.Dependency md : directDependencies) {
                if (isIncomplete(md)) continue;
                Dependency d = new Dependency();
                d.setProject(project);
                d.setGroupId(md.getGroupId());
                d.setArtifactId(md.getArtifactId());
                d.setVersion(resolveProperty(md.getVersion(), model));
                d.setScope(md.getScope() == null ? "compile" : md.getScope());
                legacyRows.add(d);
            }
            dependencyRepository.saveAll(legacyRows);
            scan.setDependencyCount(legacyRows.size());

            RepositorySystemSession session = newSession();
            CollectRequest request = new CollectRequest();
            request.setRepositories(List.of(mavenCentralRepository));

            for (org.apache.maven.model.Dependency md : directDependencies) {
                if (isIncomplete(md)) continue;
                request.addDependency(toResolverDependency(md, model));
            }
            if (model.getDependencyManagement() != null) {
                for (org.apache.maven.model.Dependency md : model.getDependencyManagement().getDependencies()) {
                    if (isIncomplete(md)) continue;
                    request.addManagedDependency(toResolverDependency(md, model));
                }
            }

            DependencyNode root = repositorySystem.collectDependencies(session, request).getRoot();
            GraphSnapshot snapshot = flatten(root, scan);
            List<ResolvedDependency> savedNodes = resolvedRepository.saveAll(snapshot.nodes());

            Map<String, ResolvedDependency> byKey = new HashMap<>();
            for (ResolvedDependency node : savedNodes) {
                byKey.put(key(node.getGroupId(), node.getArtifactId(), node.getVersion()), node);
            }

            List<DependencyEdge> edges = new ArrayList<>();
            for (String edgeKey : snapshot.edgeKeys()) {
                int split = edgeKey.indexOf('\u0000');
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

            long directCount = resolvedRepository.countByScanIdAndDirectTrue(scan.getId());
            scan.setNodeCount(savedNodes.size());
            scan.setEdgeCount(edges.size());
            scan.setTransitiveCount(Math.max(0, savedNodes.size() - (int) directCount));
            scan.setTruncated(snapshot.truncated());

            try {
                OsVulnerabilityService.SecurityResult security = vulnerabilityService.scan(scan, savedNodes);
                scan.setVulnerabilityCount(security.vulnerabilityCount());
                scan.setCriticalCount(security.criticalCount());
                scan.setHighCount(security.highCount());
                scan.setMediumCount(security.mediumCount());
                scan.setLowCount(security.lowCount());
                scan.setSecurityScore(security.securityScore());
                scan.setSecurityStatus("CHECKED");
                if (security.capped()) {
                    scan.setMessage(appendMessage(scan.getMessage(),
                            "Security findings were capped for predictable scan time."));
                }
            } catch (Exception securityError) {
                scan.setSecurityStatus("FAILED");
                scan.setSecurityScore(null);
                scan.setMessage(appendMessage(scan.getMessage(),
                        "Dependency graph is ready, but vulnerability intelligence could not be retrieved from OSV."));
            }

            scan.setStatus("READY");
            if (scan.isTruncated()) {
                scan.setMessage(appendMessage(scan.getMessage(),
                        "Dependency graph was capped for performance. Increase dependency-scan.max-nodes or max-depth for larger projects."));
            }
            return scanRepository.save(scan);
        } catch (Exception e) {
            scan.setStatus("FAILED");
            scan.setMessage(safeMessage(e));
            scan.setSecurityStatus("FAILED");
            scanRepository.save(scan);
            throw e;
        }
    }

    private GraphSnapshot flatten(DependencyNode root, Scan scan) {
        Map<String, ResolvedDependency> nodes = new LinkedHashMap<>();
        Set<String> edgeKeys = new LinkedHashSet<>();
        boolean[] truncated = {false};
        if (root != null && root.getChildren() != null) {
            for (DependencyNode child : root.getChildren()) {
                visit(child, null, 0, new HashSet<>(), nodes, edgeKeys, truncated, scan);
            }
        }
        return new GraphSnapshot(new ArrayList<>(nodes.values()), edgeKeys, truncated[0]);
    }

    private void visit(DependencyNode node, String parentKey, int depth, Set<String> path,
                       Map<String, ResolvedDependency> nodes, Set<String> edgeKeys,
                       boolean[] truncated, Scan scan) {
        if (node == null || node.getDependency() == null || node.getDependency().getArtifact() == null) return;
        if (depth > maxDepth) { truncated[0] = true; return; }

        var artifact = node.getDependency().getArtifact();
        String groupId = artifact.getGroupId();
        String artifactId = artifact.getArtifactId();
        String version = artifact.getVersion();
        String nodeKey = key(groupId, artifactId, version);

        if (parentKey != null) edgeKeys.add(parentKey + "\u0000" + nodeKey);

        ResolvedDependency existing = nodes.get(nodeKey);
        if (existing == null) {
            if (nodes.size() >= maxNodes) { truncated[0] = true; return; }
            existing = new ResolvedDependency();
            existing.setScan(scan);
            existing.setGroupId(groupId);
            existing.setArtifactId(artifactId);
            existing.setVersion(version);
            existing.setScope(normalizeScope(node.getDependency().getScope()));
            existing.setDirect(depth == 0);
            existing.setDepth(depth);
            nodes.put(nodeKey, existing);
        } else {
            if (depth < existing.getDepth()) existing.setDepth(depth);
            if (depth == 0) existing.setDirect(true);
        }

        if (path.contains(nodeKey)) return;
        Set<String> nextPath = new HashSet<>(path);
        nextPath.add(nodeKey);
        if (node.getChildren() != null) {
            for (DependencyNode child : node.getChildren()) {
                String nextKey = childKey(child);
                if (nodes.size() >= maxNodes && !nodes.containsKey(nextKey)) {
                    truncated[0] = true;
                    break;
                }
                visit(child, nodeKey, depth + 1, nextPath, nodes, edgeKeys, truncated, scan);
            }
        }
    }

    private String childKey(DependencyNode node) {
        if (node == null || node.getDependency() == null || node.getDependency().getArtifact() == null) return "";
        var a = node.getDependency().getArtifact();
        return key(a.getGroupId(), a.getArtifactId(), a.getVersion());
    }

    private RepositorySystemSession newSession() {
        var session = org.apache.maven.repository.internal.MavenRepositorySystemUtils.newSession();
        File localRepositoryDir = new File(System.getProperty("java.io.tmpdir"), "dependency-sentinel-m2");
        session.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(session, new LocalRepository(localRepositoryDir)));
        return session;
    }

    private org.eclipse.aether.graph.Dependency toResolverDependency(org.apache.maven.model.Dependency md, Model model) {
        String version = resolveProperty(md.getVersion(), model);
        String type = md.getType() == null || md.getType().isBlank() ? "jar" : md.getType();
        if ("test-jar".equals(type)) type = "jar";
        List<org.eclipse.aether.graph.Exclusion> exclusions = new ArrayList<>();
        for (Exclusion e : md.getExclusions()) {
            exclusions.add(new org.eclipse.aether.graph.Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*"));
        }
        var artifact = new DefaultArtifact(
                md.getGroupId(), md.getArtifactId(), md.getClassifier() == null ? "" : md.getClassifier(), type, version);
        return new org.eclipse.aether.graph.Dependency(
                artifact,
                md.getScope() == null ? "compile" : md.getScope(),
                Boolean.parseBoolean(md.getOptional()),
                exclusions);
    }

    private boolean isIncomplete(org.apache.maven.model.Dependency md) {
        return md.getGroupId() == null || md.getArtifactId() == null || md.getVersion() == null || md.getVersion().isBlank();
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("pom.xml is empty");
        if (file.getOriginalFilename() == null || !file.getOriginalFilename().equalsIgnoreCase("pom.xml")) {
            throw new IllegalArgumentException("Upload a file named pom.xml");
        }
    }

    private String resolveProperty(String version, Model model) {
        if (version == null || !version.startsWith("${") || !version.endsWith("}")) return version;
        String key = version.substring(2, version.length() - 1);
        String value = model.getProperties().getProperty(key);
        return value == null ? version : value;
    }

    private String normalizeScope(String scope) {
        return scope == null || scope.isBlank() ? "compile" : scope;
    }

    private String appendMessage(String existing, String next) {
        if (existing == null || existing.isBlank()) return next;
        return existing + " " + next;
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "Dependency resolution failed." : message.substring(0, Math.min(message.length(), 500));
    }

    private String key(String groupId, String artifactId, String version) {
        return groupId + ":" + artifactId + ":" + version;
    }

    private record GraphSnapshot(List<ResolvedDependency> nodes, Set<String> edgeKeys, boolean truncated) {}
}
