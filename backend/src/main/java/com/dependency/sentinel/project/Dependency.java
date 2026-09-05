package com.dependency.sentinel.project;

import jakarta.persistence.*;

@Entity
@Table(name="dependencies", uniqueConstraints=@UniqueConstraint(columnNames={"project_id","group_id","artifact_id","version"}))
public class Dependency {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="project_id") private Project project;
    @Column(name="group_id",nullable=false) private String groupId;
    @Column(name="artifact_id",nullable=false) private String artifactId;
    @Column(nullable=false) private String version;
    @Column(nullable=false) private String scope;
    @Column(nullable=false) private boolean direct=true;
    public Long getId(){return id;} public String getGroupId(){return groupId;} public void setGroupId(String v){groupId=v;}
    public String getArtifactId(){return artifactId;} public void setArtifactId(String v){artifactId=v;}
    public String getVersion(){return version;} public void setVersion(String v){version=v;}
    public String getScope(){return scope;} public void setScope(String v){scope=v;}
    public boolean isDirect(){return direct;} public Project getProject(){return project;} public void setProject(Project p){project=p;}
}
