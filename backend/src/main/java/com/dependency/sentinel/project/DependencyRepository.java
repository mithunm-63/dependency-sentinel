package com.dependency.sentinel.project;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DependencyRepository extends JpaRepository<Dependency, Long> {
    void deleteByProject(Project project);
    long countByProjectId(Long projectId);
    List<Dependency> findByProjectIdOrderByGroupIdAscArtifactIdAsc(Long projectId);
}
