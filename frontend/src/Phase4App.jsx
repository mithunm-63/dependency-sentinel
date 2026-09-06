import React, { useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './phase4.css';

const rawApi = import.meta.env.VITE_API_URL || 'http://localhost:8081/api';
const trimmedApi = rawApi.replace(/\/+$/, '');
const API = trimmedApi.endsWith('/api') ? trimmedApi : `${trimmedApi}/api`;

async function api(path, options) {
  const response = await fetch(`${API}${path}`, options);
  let data = null;
  try { data = await response.json(); } catch { /* no JSON body */ }
  if (!response.ok) throw new Error(data?.message || 'Request failed');
  return data;
}

function TreeNode({ node, expanded, toggle }) {
  const hasChildren = node.children?.length > 0;
  return (
    <div className="p4-tree-node">
      <div className="p4-tree-row">
        <button className="p4-tree-toggle" disabled={!hasChildren} onClick={() => hasChildren && toggle(node.id)}>
          {hasChildren ? (expanded.has(node.id) ? '−' : '+') : '·'}
        </button>
        <div className="p4-tree-name">
          <strong>{node.artifactId}</strong>
          <span>{node.groupId}:{node.artifactId}:{node.version}</span>
        </div>
        <span className={`p4-badge ${node.direct ? 'p4-badge-direct' : 'p4-badge-transitive'}`}>{node.direct ? 'Direct' : 'Transitive'}</span>
        <span className="p4-scope">{node.scope}</span>
      </div>
      {hasChildren && expanded.has(node.id) && (
        <div className="p4-tree-children">
          {node.children.map(child => <TreeNode key={child.id} node={child} expanded={expanded} toggle={toggle} />)}
        </div>
      )}
    </div>
  );
}

function GraphView({ graph }) {
  const maxVisible = 100;
  const nodes = graph?.nodes?.slice(0, maxVisible) || [];
  if (!nodes.length) return <div className="p4-empty">Run Analyze project to build the graph.</div>;
  const visibleIds = new Set(nodes.map(n => n.id));
  const edges = (graph.edges || []).filter(e => visibleIds.has(e.parentId) && visibleIds.has(e.childId));
  const groups = new Map();
  nodes.forEach(node => {
    if (!groups.has(node.depth)) groups.set(node.depth, []);
    groups.get(node.depth).push(node);
  });
  const positions = new Map();
  [...groups.entries()].sort((a, b) => a[0] - b[0]).forEach(([depth, depthNodes]) => {
    depthNodes.forEach((node, index) => positions.set(node.id, { x: 120 + depth * 260, y: 65 + index * 82 }));
  });
  const maxDepth = Math.max(0, ...nodes.map(n => n.depth));
  const maxRows = Math.max(1, ...[...groups.values()].map(v => v.length));
  const width = Math.max(900, 260 + (maxDepth + 1) * 260);
  const height = Math.max(430, 110 + maxRows * 82);

  return (
    <div className="p4-graph-wrap">
      <svg className="p4-graph" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Dependency graph">
        <defs><marker id="p4-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="currentColor" /></marker></defs>
        {edges.map((edge, index) => {
          const from = positions.get(edge.parentId), to = positions.get(edge.childId);
          if (!from || !to) return null;
          const mid = (to.x - from.x) * 0.5;
          return <path key={`${edge.parentId}-${edge.childId}-${index}`} className="p4-graph-edge" d={`M ${from.x + 92} ${from.y} C ${from.x + 92 + mid * 0.55} ${from.y}, ${to.x - 92 - mid * 0.55} ${to.y}, ${to.x - 92} ${to.y}`} markerEnd="url(#p4-arrow)" />;
        })}
        {nodes.map(node => {
          const point = positions.get(node.id);
          return <g key={node.id} transform={`translate(${point.x - 92},${point.y - 25})`}>
            <rect width="184" height="50" rx="12" className={`p4-graph-node ${node.direct ? 'p4-graph-direct' : ''}`} />
            <text x="12" y="21" className="p4-graph-title">{node.artifactId.length > 24 ? `${node.artifactId.slice(0, 21)}…` : node.artifactId}</text>
            <text x="12" y="38" className="p4-graph-meta">{node.version} · {node.direct ? 'Direct' : `Depth ${node.depth}`}</text>
          </g>;
        })}
      </svg>
      {graph.nodes.length > maxVisible && <div className="p4-graph-note">Showing the first {maxVisible} nodes for a responsive graph. Inventory and Tree retain the complete stored set.</div>}
    </div>
  );
}

function SeverityBadge({ severity }) {
  const value = String(severity || 'UNKNOWN').toLowerCase();
  return <span className={`p4-severity p4-severity-${value}`}>{severity || 'UNKNOWN'}</span>;
}

function ImpactPanel({ impact, loading }) {
  if (loading) return <div className="p4-impact"><div className="p4-loading">Calculating impact path…</div></div>;
  if (!impact) return <div className="p4-impact p4-impact-empty"><div className="p4-impact-icon">→</div><h3>Select a finding</h3><p>Dependency Sentinel will trace it back through your dependency graph and explain what to change.</p></div>;

  return (
    <div className="p4-impact">
      <div className="p4-impact-head">
        <div><span className="p4-eyebrow">IMPACT ANALYSIS</span><h3>{impact.affectedDependency}</h3></div>
        <SeverityBadge severity={impact.severity} />
      </div>
      <div className="p4-metrics">
        <div><span>Entry points</span><strong>{impact.directEntryPoints}</strong></div>
        <div><span>Upstream nodes</span><strong>{impact.upstreamDependencyCount}</strong></div>
        <div><span>Blast radius</span><strong>{impact.blastRadius}</strong></div>
        <div><span>Depth</span><strong>{impact.depth}</strong></div>
      </div>
      <section className="p4-impact-card"><span className="p4-card-label">Why this is in your app</span><p>{impact.explanation}</p></section>
      <section className="p4-impact-card"><span className="p4-card-label">Recommended fix</span><p>{impact.remediation}</p>{impact.fixedVersion && <div className="p4-fix-version">Target: <strong>{impact.fixedVersion}</strong></div>}</section>
      <section className="p4-impact-card">
        <div className="p4-card-title"><span className="p4-card-label">Shortest dependency paths</span><span>{impact.shortestPaths.length} shown</span></div>
        {impact.shortestPaths.length === 0 ? <p>No direct path was reconstructed from the stored graph.</p> : <div className="p4-paths">{impact.shortestPaths.map((path, index) => <div className="p4-path" key={index}>{path.map((item, i) => <React.Fragment key={`${item}-${i}`}><span>{item}</span>{i < path.length - 1 && <b>→</b>}</React.Fragment>)}</div>)}</div>}
      </section>
      <div className="p4-impact-foot"><span>{impact.osvId}</span><span>{impact.direct ? 'Direct dependency' : 'Transitive dependency'}</span></div>
    </div>
  );
}

function SecurityView({ security, onSecurityCheck, checking, selectedFinding, onSelectFinding, impact, impactLoading }) {
  if (!security || security.status === 'NOT_CHECKED') {
    return <div className="p4-security-empty"><div className="p4-security-icon">✓</div><span className="p4-eyebrow">READY FOR SECURITY CHECK</span><h3>Your dependency graph is ready.</h3><p>Run the security check to match every resolved Maven version against OSV vulnerability records.</p><button className="p4-primary" onClick={onSecurityCheck} disabled={checking}>{checking ? 'Checking OSV…' : 'Run security check'}</button></div>;
  }
  if (security.status === 'FAILED') {
    return <div className="p4-security-empty"><div className="p4-security-icon p4-warning">!</div><span className="p4-eyebrow">SECURITY CHECK FAILED</span><h3>The dependency graph is still available.</h3><p>OSV could not be reached for this check. Retry without re-uploading your pom.xml.</p><button className="p4-primary" onClick={onSecurityCheck} disabled={checking}>{checking ? 'Retrying…' : 'Retry security check'}</button></div>;
  }

  return (
    <div className="p4-security-layout">
      <div className="p4-security-main">
        <div className="p4-score-card">
          <div className={`p4-score-ring ${security.securityScore >= 90 ? 'good' : security.securityScore >= 75 ? 'moderate' : security.securityScore >= 50 ? 'high' : 'critical'}`}><strong>{security.securityScore}</strong><span>/ 100</span></div>
          <div><span className="p4-eyebrow">PROJECT RISK</span><h3>{security.riskLevel.replaceAll('_', ' ')}</h3><p>Based on the findings and their position in your dependency graph.</p></div>
          <button className="p4-ghost" onClick={onSecurityCheck} disabled={checking}>{checking ? 'Refreshing…' : 'Refresh findings'}</button>
        </div>
        <div className="p4-risk-grid">
          {['critical','high','medium','low'].map(level => <div key={level} className={`p4-risk ${level}`}><span>{level}</span><strong>{security[`${level}Count`]}</strong></div>)}
        </div>
        {security.findings.length === 0 ? <div className="p4-no-findings"><strong>No known vulnerabilities found for this scan.</strong><span>OSV returned no matching records for the resolved package versions.</span></div> : <div className="p4-findings">
          <div className="p4-section-head"><div><h3>Findings</h3><span>{security.vulnerabilityCount} vulnerability finding(s)</span></div><span className="p4-hint">Select one to see impact</span></div>
          <div className="p4-table-scroll"><table><thead><tr><th>Finding</th><th>Dependency</th><th>Severity</th><th>Fixed in</th><th>Risk</th></tr></thead><tbody>
            {security.findings.map(f => <tr key={f.id} className={selectedFinding?.id === f.id ? 'selected' : ''} onClick={() => onSelectFinding(f)}>
              <td><strong>{f.cve || f.osvId}</strong><small>{f.osvId}</small></td>
              <td><strong>{f.artifactId}</strong><small>{f.version} · {f.direct ? 'direct' : `depth ${f.depth}`}</small></td>
              <td><SeverityBadge severity={f.severity} /></td>
              <td>{f.fixedVersion || 'Not listed'}</td>
              <td><strong>{f.riskScore}</strong></td>
            </tr>)}
          </tbody></table></div>
        </div>}
      </div>
      <ImpactPanel impact={impact} loading={impactLoading} />
    </div>
  );
}

export default function Phase4App() {
  const [projects, setProjects] = useState([]);
  const [selected, setSelected] = useState(null);
  const [deps, setDeps] = useState([]);
  const [tree, setTree] = useState(null);
  const [graph, setGraph] = useState(null);
  const [security, setSecurity] = useState(null);
  const [selectedFinding, setSelectedFinding] = useState(null);
  const [impact, setImpact] = useState(null);
  const [tab, setTab] = useState('overview');
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('all');
  const [name, setName] = useState('');
  const [file, setFile] = useState(null);
  const [busy, setBusy] = useState(false);
  const [checkingSecurity, setCheckingSecurity] = useState(false);
  const [impactLoading, setImpactLoading] = useState(false);
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState(new Set());

  const loadProjects = async () => {
    const data = await api('/projects');
    setProjects(data);
    return data;
  };

  const loadProject = async id => {
    const [detail, dependencies, securityData] = await Promise.all([
      api(`/projects/${id}`), api(`/projects/${id}/dependencies`), api(`/projects/${id}/security`)
    ]);
    setSelected(detail); setDeps(dependencies); setSecurity(securityData); setTree(null); setGraph(null); setSelectedFinding(null); setImpact(null); setQuery(''); setFilter('all');
  };

  useEffect(() => { loadProjects().catch(() => setError('Backend is not reachable. Check Render and VITE_API_URL.')); }, []);

  const createProject = async event => {
    event.preventDefault();
    if (!name.trim()) return;
    setBusy(true); setError('');
    try {
      const project = await api('/projects', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: name.trim() }) });
      setName(''); await loadProjects(); await loadProject(project.id);
    } catch (e) { setError(e.message); } finally { setBusy(false); }
  };

  const analyze = async () => {
    if (!selected || !file) return;
    if (file.name.toLowerCase() !== 'pom.xml') { setError('Choose a file named pom.xml.'); return; }
    setBusy(true); setError('');
    try {
      const form = new FormData(); form.append('file', file);
      await api(`/projects/${selected.id}/scan`, { method: 'POST', body: form });
      await loadProjects(); await loadProject(selected.id); setTab('security'); setFile(null);
      const input = document.getElementById('p4-pom'); if (input) input.value = '';
    } catch (e) { setError(e.message || 'Analysis failed.'); } finally { setBusy(false); }
  };

  const securityCheck = async () => {
    if (!selected) return;
    setCheckingSecurity(true); setError('');
    try { const data = await api(`/projects/${selected.id}/security/rescan`, { method: 'POST' }); setSecurity(data); setSelected(await api(`/projects/${selected.id}`)); setSelectedFinding(null); setImpact(null); }
    catch (e) { setError(e.message || 'Security check failed.'); }
    finally { setCheckingSecurity(false); }
  };

  const selectFinding = async finding => {
    setSelectedFinding(finding); setImpact(null); setImpactLoading(true);
    try { setImpact(await api(`/projects/${selected.id}/vulnerabilities/${finding.id}/impact`)); }
    catch (e) { setError(e.message || 'Could not calculate impact.'); }
    finally { setImpactLoading(false); }
  };

  const loadTree = async () => {
    if (!selected) return;
    try { const data = await api(`/projects/${selected.id}/dependencies/tree`); setTree(data); setExpanded(new Set(data.roots?.map(root => root.id) || [])); }
    catch (e) { setError(e.message); }
  };

  const loadGraph = async () => {
    if (!selected) return;
    try { setGraph(await api(`/projects/${selected.id}/dependencies/graph`)); }
    catch (e) { setError(e.message); }
  };

  useEffect(() => {
    if (tab === 'tree' && selected && !tree) loadTree();
    if (tab === 'graph' && selected && !graph) loadGraph();
  }, [tab, selected]);

  const filteredDeps = useMemo(() => {
    const q = query.trim().toLowerCase();
    return deps.filter(d => {
      if (filter === 'direct' && !d.direct) return false;
      if (filter === 'transitive' && d.direct) return false;
      return !q || `${d.groupId}:${d.artifactId}:${d.version}`.toLowerCase().includes(q);
    });
  }, [deps, query, filter]);

  const toggle = id => setExpanded(current => { const next = new Set(current); next.has(id) ? next.delete(id) : next.add(id); return next; });

  return <div className="p4-shell">
    <header className="p4-header">
      <div><span className="p4-eyebrow">DEVELOPER SECURITY</span><h1>Dependency Sentinel</h1><p>From dependency graph to security impact and the next fix.</p></div>
      <div className="p4-phase"><span /> Phase 4</div>
    </header>

    {error && <div className="p4-error"><span>{error}</span><button onClick={() => setError('')}>Dismiss</button></div>}

    <main className="p4-main">
      <aside className="p4-sidebar">
        <div className="p4-panel">
          <div className="p4-side-title"><h2>Your projects</h2><span>{projects.length}</span></div>
          <form className="p4-create" onSubmit={createProject}><input value={name} onChange={e => setName(e.target.value)} placeholder="Project name"/><button disabled={busy}>Create</button></form>
          {projects.length === 0 ? <div className="p4-empty">Create your first project.</div> : projects.map(project => <button key={project.id} className={`p4-project ${selected?.id === project.id ? 'active' : ''}`} onClick={() => loadProject(project.id).catch(e => setError(e.message))}><span>{project.name}</span><small>{project.dependencyCount} deps</small></button>)}
        </div>
      </aside>

      <section className="p4-workspace">
        {!selected ? <div className="p4-welcome"><div className="p4-logo">DS</div><span className="p4-eyebrow">PHASE 4</span><h2>Know <span>what is affected</span> — not just what is vulnerable.</h2><p>Create a project, analyze its Maven file, then select a finding to see how it reaches your application and exactly what version the advisory recommends.</p><div className="p4-steps"><div><b>1</b><span>Analyze dependencies</span></div><div><b>2</b><span>Check known vulnerabilities</span></div><div><b>3</b><span>Trace impact & fix</span></div></div></div> : <div className="p4-content">
          <div className="p4-topbar"><div><span className="p4-eyebrow">PROJECT · MAVEN</span><h2>{selected.name}</h2><span className="p4-subtle">{selected.securityStatus === 'CHECKED' ? `Security checked · ${selected.vulnerabilityCount} finding(s)` : selected.dependencies ? 'Dependency graph ready · security check pending' : 'No scan yet'}</span></div><div className="p4-score"><strong>{selected.securityScore ?? '—'}</strong><span>{selected.securityScore == null ? 'security pending' : 'security score'}</span></div></div>
          <div className="p4-summary-cards"><div><span>Dependencies</span><strong>{selected.dependencies}</strong></div><div><span>Direct</span><strong>{selected.directDependencies}</strong></div><div><span>Transitive</span><strong>{selected.transitiveDependencies}</strong></div><div><span>Vulnerabilities</span><strong>{selected.vulnerabilities}</strong></div></div>
          <div className="p4-analyze-box"><div><span className="p4-eyebrow">MAVEN ANALYSIS</span><h3>Analyze a project snapshot</h3><p>Resolve direct and transitive dependencies from Maven Central. Your scan is stored so impact can be traced later.</p></div><label className="p4-upload"><input id="p4-pom" type="file" accept=".xml" onChange={e => setFile(e.target.files?.[0] || null)}/><span>{file ? file.name : 'Choose pom.xml'}</span></label><button className="p4-primary" onClick={analyze} disabled={!file || busy}>{busy ? 'Analyzing…' : 'Analyze project'}</button></div>

          <nav className="p4-tabs"><button className={tab === 'overview' ? 'active' : ''} onClick={() => setTab('overview')}>Overview</button><button className={tab === 'inventory' ? 'active' : ''} onClick={() => setTab('inventory')}>Inventory</button><button className={tab === 'tree' ? 'active' : ''} onClick={() => setTab('tree')}>Tree</button><button className={tab === 'graph' ? 'active' : ''} onClick={() => setTab('graph')}>Graph</button><button className={tab === 'security' ? 'active' : ''} onClick={() => setTab('security')}>Security</button></nav>

          {tab === 'overview' && <div className="p4-overview"><div className="p4-overview-card"><span className="p4-eyebrow">WHAT PHASE 4 ANSWERS</span><h3>“This CVE exists — but does it actually reach my application?”</h3><p>Dependency Sentinel traces a vulnerable package back to the direct dependencies that introduce it, counts the upstream dependency surface, and shows the shortest paths you can review.</p></div><div className="p4-overview-grid"><div><strong>{selected.directDependencies}</strong><span>direct entry points</span></div><div><strong>{selected.transitiveDependencies}</strong><span>transitive packages</span></div><div><strong>{selected.graphEdges}</strong><span>relationships</span></div><div><strong>{selected.vulnerabilities}</strong><span>known findings</span></div></div></div>}

          {tab === 'inventory' && <div className="p4-section"><div className="p4-section-head"><div><h3>Resolved inventory</h3><span>Search by coordinate, then separate direct and transitive packages.</span></div><div className="p4-filters"><input value={query} onChange={e => setQuery(e.target.value)} placeholder="Search dependencies"/><select value={filter} onChange={e => setFilter(e.target.value)}><option value="all">All</option><option value="direct">Direct</option><option value="transitive">Transitive</option></select></div></div>{filteredDeps.length === 0 ? <div className="p4-empty">No dependencies match your filter.</div> : <div className="p4-table-scroll"><table><thead><tr><th>Dependency</th><th>Version</th><th>Scope</th><th>Depth</th><th>Type</th></tr></thead><tbody>{filteredDeps.map(d => <tr key={d.id}><td><strong>{d.artifactId}</strong><small>{d.groupId}</small></td><td>{d.version}</td><td>{d.scope}</td><td>{d.depth}</td><td><span className={`p4-badge ${d.direct ? 'p4-badge-direct' : 'p4-badge-transitive'}`}>{d.direct ? 'Direct' : 'Transitive'}</span></td></tr>)}</tbody></table></div>}</div>}

          {tab === 'tree' && <div className="p4-section"><div className="p4-section-head"><div><h3>Dependency tree</h3><span>Follow a finding's package back to the direct dependency that introduced it.</span></div><button className="p4-ghost" onClick={() => setExpanded(new Set(tree?.roots?.flatMap(flattenIds) || []))}>Expand all</button></div>{!tree ? <div className="p4-empty">Loading tree…</div> : tree.roots.length === 0 ? <div className="p4-empty">Run Analyze project to build the tree.</div> : tree.roots.map(root => <TreeNode key={root.id} node={root} expanded={expanded} toggle={toggle}/>)}</div>}

          {tab === 'graph' && <div className="p4-section"><div className="p4-section-head"><div><h3>Dependency graph</h3><span>Relationships discovered by Maven Resolver.</span></div><button className="p4-ghost" onClick={loadGraph}>Refresh</button></div><GraphView graph={graph}/></div>}

          {tab === 'security' && <div className="p4-section p4-security-section"><SecurityView security={security} onSecurityCheck={securityCheck} checking={checkingSecurity} selectedFinding={selectedFinding} onSelectFinding={selectFinding} impact={impact} impactLoading={impactLoading}/></div>}
        </div>}
      </section>
    </main>
    <footer className="p4-footer">Phase 4 · impact analysis, dependency paths & actionable remediation</footer>
  </div>;
}

function flattenIds(node) { return [node.id, ...(node.children || []).flatMap(flattenIds)]; }

createRoot(document.getElementById('root')).render(<Phase4App />);
