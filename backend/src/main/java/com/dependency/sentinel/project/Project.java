package com.dependency.sentinel.project;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name="projects")
public class Project {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;
    @Column(nullable=false) private String name;
    @Column(nullable=false) private String buildTool = "MAVEN";
    @Column(nullable=false, updatable=false) private Instant createdAt = Instant.now();
    @OneToMany(mappedBy="project", cascade=CascadeType.ALL, orphanRemoval=true)
    private List<Dependency> dependencies = new ArrayList<>();
    @OneToMany(mappedBy="project", cascade=CascadeType.ALL, orphanRemoval=true)
    private List<Scan> scans = new ArrayList<>();

    public Long getId(){return id;} public String getName(){return name;} public void setName(String name){this.name=name;}
    public String getBuildTool(){return buildTool;} public Instant getCreatedAt(){return createdAt;}
    public List<Dependency> getDependencies(){return dependencies;} public List<Scan> getScans(){return scans;}
}
