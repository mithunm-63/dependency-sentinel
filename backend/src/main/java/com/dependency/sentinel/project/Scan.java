package com.dependency.sentinel.project;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "scans")
public class Scan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(nullable = false)
    private Instant startedAt = Instant.now();

    @Column(nullable = false)
    private String status;

    private int dependencyCount;
    private int nodeCount;
    private int edgeCount;
    private int transitiveCount;
    private boolean truncated;

    private int vulnerabilityCount;
    private int criticalCount;
    private int highCount;
    private int mediumCount;
    private int lowCount;
    private Integer securityScore;

    @Column(nullable = false, length = 40)
    private String securityStatus = "NOT_CHECKED";

    @Column(columnDefinition = "text")
    private String message;

    public Long getId() { return id; }
    public Project getProject() { return project; }
    public void setProject(Project p) { project = p; }
    public Instant getStartedAt() { return startedAt; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public int getDependencyCount() { return dependencyCount; }
    public void setDependencyCount(int v) { dependencyCount = v; }
    public int getNodeCount() { return nodeCount; }
    public void setNodeCount(int v) { nodeCount = v; }
    public int getEdgeCount() { return edgeCount; }
    public void setEdgeCount(int v) { edgeCount = v; }
    public int getTransitiveCount() { return transitiveCount; }
    public void setTransitiveCount(int v) { transitiveCount = v; }
    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean v) { truncated = v; }
    public int getVulnerabilityCount() { return vulnerabilityCount; }
    public void setVulnerabilityCount(int v) { vulnerabilityCount = v; }
    public int getCriticalCount() { return criticalCount; }
    public void setCriticalCount(int v) { criticalCount = v; }
    public int getHighCount() { return highCount; }
    public void setHighCount(int v) { highCount = v; }
    public int getMediumCount() { return mediumCount; }
    public void setMediumCount(int v) { mediumCount = v; }
    public int getLowCount() { return lowCount; }
    public void setLowCount(int v) { lowCount = v; }
    public Integer getSecurityScore() { return securityScore; }
    public void setSecurityScore(Integer v) { securityScore = v; }
    public String getSecurityStatus() { return securityStatus; }
    public void setSecurityStatus(String v) { securityStatus = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
}
