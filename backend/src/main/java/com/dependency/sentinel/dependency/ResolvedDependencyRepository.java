package com.dependency.sentinel.dependency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResolvedDependencyRepository extends JpaRepository<ResolvedDependency, Long> {
    List<ResolvedDependency> findByScanIdOrderByDepthAscGroupIdAscArtifactIdAsc(Long scanId);
    long countByScanId(Long scanId);
    long countByScanIdAndDirectTrue(Long scanId);
}
