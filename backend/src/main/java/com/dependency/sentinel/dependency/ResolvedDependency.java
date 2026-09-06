package com.dependency.sentinel.dependency;

import com.dependency.sentinel.project.Scan;
import jakarta.persistence.*;

@Entity
@Table(name = "dependency_nodes", uniqueConstraints = @UniqueConstraint(
        name = "uk_dependency_scan_coordinate",
        columnNames = {"scan_id", "group_id", "artifact_id", "version"}
))
public class ResolvedDependency {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_id", nullable = false)
    private Scan scan;

    @Column(name = "group_id", nullable = false)
    private String groupId;

    @Column(name = "artifact_id", nullable = false)
    private String artifactId;

    @Column(nullable = false)
    private String version;

    @Column(nullable = false)
    private String scope;

    @Column(nullable = false)
    private boolean direct;

    @Column(nullable = false)
    private int depth;

    public Long getId() { return id; }
    public Scan getScan() { return scan; }
    public void setScan(Scan scan) { this.scan = scan; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getArtifactId() { return artifactId; }
    public void setArtifactId(String artifactId) { this.artifactId = artifactId; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public boolean isDirect() { return direct; }
    public void setDirect(boolean direct) { this.direct = direct; }
    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }
}
