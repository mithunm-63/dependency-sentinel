package com.dependency.sentinel.dependency;

import com.dependency.sentinel.project.Scan;
import jakarta.persistence.*;

@Entity
@Table(name = "dependency_edges", uniqueConstraints = @UniqueConstraint(
        name = "uk_dependency_edge",
        columnNames = {"scan_id", "parent_id", "child_id"}
))
public class DependencyEdge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_id", nullable = false)
    private Scan scan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parent_id", nullable = false)
    private ResolvedDependency parent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "child_id", nullable = false)
    private ResolvedDependency child;

    public Long getId() { return id; }
    public Scan getScan() { return scan; }
    public void setScan(Scan scan) { this.scan = scan; }
    public ResolvedDependency getParent() { return parent; }
    public void setParent(ResolvedDependency parent) { this.parent = parent; }
    public ResolvedDependency getChild() { return child; }
    public void setChild(ResolvedDependency child) { this.child = child; }
}
