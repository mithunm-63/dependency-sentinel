package com.dependency.sentinel.analysis;

import com.dependency.sentinel.project.Dependency;
import com.dependency.sentinel.project.DependencyRepository;
import com.dependency.sentinel.project.Project;
import com.dependency.sentinel.project.ProjectRepository;
import com.dependency.sentinel.project.Scan;
import com.dependency.sentinel.project.ScanRepository;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
public class PomScannerService {
    private final ProjectRepository projectRepository;
    private final DependencyRepository dependencyRepository;
    private final ScanRepository scanRepository;

    public PomScannerService(ProjectRepository projectRepository, DependencyRepository dependencyRepository, ScanRepository scanRepository) {
        this.projectRepository = projectRepository;
        this.dependencyRepository = dependencyRepository;
        this.scanRepository = scanRepository;
    }

    @Transactional
    public Scan scan(Long projectId, MultipartFile file) throws Exception {
        if (file.isEmpty()) throw new IllegalArgumentException("pom.xml is empty");
        if (file.getOriginalFilename() == null || !file.getOriginalFilename().equalsIgnoreCase("pom.xml")) {
            throw new IllegalArgumentException("Upload a file named pom.xml");
        }
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        Scan scan = new Scan();
        scan.setProject(project);
        scan.setStatus("SCANNING");
        scanRepository.save(scan);
        try (InputStream in = file.getInputStream()) {
            Model model = new MavenXpp3Reader().read(in);
            dependencyRepository.deleteByProject(project);
            List<Dependency> deps = new ArrayList<>();
            for (org.apache.maven.model.Dependency md : model.getDependencies()) {
                if (md.getGroupId() == null || md.getArtifactId() == null || md.getVersion() == null) continue;
                Dependency d = new Dependency();
                d.setProject(project);
                d.setGroupId(md.getGroupId());
                d.setArtifactId(md.getArtifactId());
                d.setVersion(resolveProperty(md.getVersion(), model));
                d.setScope(md.getScope() == null ? "compile" : md.getScope());
                deps.add(d);
            }
            dependencyRepository.saveAll(deps);
            scan.setDependencyCount(deps.size());
            scan.setStatus("READY");
            return scanRepository.save(scan);
        } catch (Exception e) {
            scan.setStatus("FAILED");
            scanRepository.save(scan);
            throw e;
        }
    }

    private String resolveProperty(String version, Model model) {
        if (!version.startsWith("${") || !version.endsWith("}")) return version;
        String key = version.substring(2, version.length() - 1);
        String value = model.getProperties().getProperty(key);
        return value == null ? version : value;
    }
}
