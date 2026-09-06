package com.dependency.sentinel.dependency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DependencyEdgeRepository extends JpaRepository<DependencyEdge, Long> {
    List<DependencyEdge> findByScanId(Long scanId);
}
