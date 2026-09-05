package com.dependency.sentinel.project;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="scans")
public class Scan {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="project_id") private Project project;
    @Column(nullable=false) private Instant startedAt=Instant.now();
    @Column(nullable=false) private String status;
    private int dependencyCount;
    public Long getId(){return id;} public Project getProject(){return project;} public void setProject(Project p){project=p;}
    public Instant getStartedAt(){return startedAt;} public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public int getDependencyCount(){return dependencyCount;} public void setDependencyCount(int v){dependencyCount=v;}
}
