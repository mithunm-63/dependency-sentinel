package com.dependency.sentinel.api;

import com.dependency.sentinel.analysis.PomScannerService;
import com.dependency.sentinel.project.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "${FRONTEND_ORIGIN:http://localhost:5173}")
public class ProjectController {
    private final ProjectRepository projects;
    private final DependencyRepository dependencies;
    private final ScanRepository scans;
    private final PomScannerService scanner;

    public ProjectController(ProjectRepository projects, DependencyRepository dependencies, ScanRepository scans, PomScannerService scanner) {
        this.projects=projects; this.dependencies=dependencies; this.scans=scans; this.scanner=scanner;
    }

    public record CreateProjectRequest(@NotBlank String name) {}

    @GetMapping("/health")
    public Map<String,String> health(){ return Map.of("status","UP","service","dependency-sentinel"); }

    @PostMapping("/projects")
    public ProjectSummary create(@Valid @RequestBody CreateProjectRequest request){
        Project p=new Project(); p.setName(request.name().trim()); p=projects.save(p); return summary(p);
    }

    @GetMapping("/projects")
    public List<ProjectSummary> list(){ return projects.findAll().stream().map(this::summary).toList(); }

    @GetMapping("/projects/{id}")
    public Map<String,Object> get(@PathVariable Long id){
        Project p=projects.findById(id).orElseThrow(()->new IllegalArgumentException("Project not found"));
        Scan latest=scans.findByProjectIdOrderByStartedAtDesc(id).stream().findFirst().orElse(null);
        return details(p,latest);
    }

    @PostMapping(value="/projects/{id}/scan", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> scan(@PathVariable Long id,@RequestPart("file") MultipartFile file)throws Exception{
        Scan scan=scanner.scan(id,file);
        Project p=projects.findById(id).orElseThrow();
        return details(p,scan);
    }

    @GetMapping("/projects/{id}/dependencies")
    public List<DependencySummary> dependencies(@PathVariable Long id){
        if(!projects.existsById(id)) throw new IllegalArgumentException("Project not found");
        return dependencies.findByProjectIdOrderByGroupIdAscArtifactIdAsc(id).stream().map(DependencySummary::new).toList();
    }

    @GetMapping("/projects/{id}/scans")
    public List<ScanSummary> scans(@PathVariable Long id){
        if(!projects.existsById(id)) throw new IllegalArgumentException("Project not found");
        return scans.findByProjectIdOrderByStartedAtDesc(id).stream().map(ScanSummary::new).toList();
    }

    private ProjectSummary summary(Project p){ return new ProjectSummary(p.getId(),p.getName(),p.getBuildTool(),(int)dependencies.countByProjectId(p.getId()),p.getCreatedAt()); }
    private Map<String,Object> details(Project p,Scan latest){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("id",p.getId()); out.put("name",p.getName()); out.put("buildTool",p.getBuildTool());
        out.put("securityScore",100); out.put("vulnerabilities",0); out.put("outdated",0); out.put("conflicts",0);
        out.put("dependencies",(int)dependencies.countByProjectId(p.getId())); out.put("latestScan",latest==null?null:new ScanSummary(latest));
        out.put("phase","Phase 1"); out.put("nextStep","Vulnerability intelligence arrives in Phase 3.");
        return out;
    }

    public record ProjectSummary(Long id,String name,String buildTool,int dependencyCount,java.time.Instant createdAt) {}
    public record DependencySummary(Long id,String groupId,String artifactId,String version,String scope,boolean direct){ DependencySummary(Dependency d){this(d.getId(),d.getGroupId(),d.getArtifactId(),d.getVersion(),d.getScope(),d.isDirect());} }
    public record ScanSummary(Long id,java.time.Instant startedAt,String status,int dependencyCount){ ScanSummary(Scan s){this(s.getId(),s.getStartedAt(),s.getStatus(),s.getDependencyCount());} }
}
