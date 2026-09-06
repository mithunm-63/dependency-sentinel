package com.dependency.sentinel.project;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ScanRepository extends JpaRepository<Scan, Long> {
    List<Scan> findByProjectIdOrderByStartedAtDesc(Long projectId);
    Optional<Scan> findTopByProjectIdOrderByStartedAtDesc(Long projectId);
}
