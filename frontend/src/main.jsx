import React, { useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';

const rawApi = import.meta.env.VITE_API_URL || 'http://localhost:8081/api';
const API = rawApi.replace(/\/+$/, '') + (rawApi.replace(/\/+$/, '').endsWith('/api') ? '' : '/api');

async function api(path, options) {
  const response = await fetch(`${API}${path}`, options);
  let data = null;
  try { data = await response.json(); } catch { /* empty response */ }
  if (!response.ok) throw new Error(data?.message || 'Request failed');
  return data;
}

function severityClass(value) {
  return `severity ${String(value || 'UNKNOWN').toLowerCase()}`;
}

function TreeNode({ node, expanded, toggle }) {
  const hasChildren = node.children?.length > 0;
  return (
    <div className="treeNode">
      <div className="treeRow">
        <button className={`treeToggle ${hasChildren ? '' : 'placeholder'}`} onClick={() => hasChildren && toggle(node.id)}>
          {hasChildren ? (expanded.has(node.id) ? '−' : '+') : '•'}
        </button>
        <div className="treeMain">
          <strong>{node.artifactId}</strong>
          <span>{node.groupId}:{node.artifactId}:{node.version}</span>
        </div>
        <span className={`typeBadge ${node.direct ? 'directBadge' : 'transitiveBadge'}`}>
          {node.direct ? 'Direct' : 'Transitive'}
        </span>
        <span className="scopeBadge">{node.scope}</span>
      </div>
      {expanded.has(node.id) && hasChildren && (
        <div className="treeChildren">
          {node.children.map(child => <TreeNode key={child.id} node={child} expanded={expanded} toggle={toggle} />)}
        </div>
      )}
    </div>
  );
}

function GraphView({ graph }) {
  const maxVisible = 120;
  const nodes = graph?.nodes?.slice(0, maxVisible) || [];
  const ids = new Set(nodes.map(n => n.id));
  const edges = (graph?.edges || []).filter(e => ids.has(e.parentId) && ids.has(e.childId));
  const byDepth = new Map();
  nodes.forEach(node => {
    if (!byDepth.has(node.depth)) byDepth.set(node.depth, []);
    byDepth.get(node.depth).push(node);
  });
  const positions = new Map();
  [...byDepth.entries()].sort((a, b) => a[0] - b[0]).forEach(([depth, depthNodes]) => {
    depthNodes.forEach((node, index) => positions.set(node.id, { x: 55 + depth * 270, y: 70 + index * 92 }));
  });
  const maxDepth = Math.max(0, ...nodes.map(n => n.depth));
  const maxRows = Math.max(1, ...[...byDepth.values()].map(a => a.length));
  const width = Math.max(900, 130 + (maxDepth + 1) * 270);
  const height = Math.max(430, 130 + maxRows * 92);

  return (
    <div className="graphWrap">
      {nodes.length === 0 ? <div className="empty">Run a scan to see the dependency graph.</div> : (
        <>
          <svg className="graph" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Dependency graph">
            <defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="currentColor" /></marker></defs>
            {edges.map((edge, index) => {
              const from = positions.get(edge.parentId), to = positions.get(edge.childId);
              if (!from || !to) return null;
              const mid = (to.x - from.x) * 0.5;
              return <path key={`${edge.parentId}-${edge.childId}-${index}`} className="graphEdge" d={`M ${from.x + 94} ${from.y} C ${from.x + 94 + mid * 0.55} ${from.y}, ${to.x - 94 - mid * 0.55} ${to.y}, ${to.x - 94} ${to.y}`} markerEnd="url(#arrow)" />;
            })}
            {nodes.map(node => {
              const p = positions.get(node.id);
              return <g key={node.id} transform={`translate(${p.x - 94},${p.y - 25})`}>
                <rect className={`graphNode ${node.direct ? 'graphDirect' : ''}`} width="188" height="50" rx="11" />
                <text className="graphTitle" x="12" y="21">{node.artifactId.length > 25 ? `${node.artifactId.slice(0, 22)}…` : node.artifactId}</text>
                <text className="graphMeta" x="12" y="38">{node.version} · {node.direct ? 'Direct' : `Depth ${node.depth}`}</text>
              </g>;
            })}
          </svg>
          {graph.nodes.length > maxVisible && <div className="graphNote">Showing the first {maxVisible} nodes for responsiveness. Inventory and Tree contain the full set.</div>}
        </>
      )}
    </div>
  );
}

function SecurityPanel({ security, selectedFinding, setSelectedFinding, refresh }) {
  if (!security || security.status === 'NOT_CHECKED') {
    return <div className="securityEmpty"><div className="securityIcon">✓</div><h3>Security intelligence starts with a scan</h3><p>Run Analyze project to check every resolved Maven dependency against OSV's current vulnerability data.</p></div>;
  }

  if (security.status === 'FAILED') {
    return <div className="securityEmpty"><div className="securityIcon warningIcon">!</div><h3>Dependency graph is ready</h3><p>OSV vulnerability intelligence could not be retrieved for this scan. Your dependency inventory is still available.</p><button className="primaryButton" onClick={refresh}>Retry security check</button></div>;
  }

  return (
    <div className="securityPanel">
      <div className="securitySummary">
        <div className={`securityScore ${security.securityScore < 50 ? 'criticalScore' : security.securityScore < 75 ? 'highScore' : security.securityScore < 90 ? 'moderateScore' : 'goodScore'}`}>
          <span>Security score</span><strong>{security.securityScore}</strong><small>/ 100 · {security.riskLevel.replaceAll('_', ' ')}</small>
        </div>
        <div className="securityText"><h3>Known vulnerabilities</h3><p>Matched against OSV package/version records for this exact dependency snapshot.</p><button className="ghost" onClick={refresh}>Refresh findings</button></div>
      </div>
      <div className="securityCards">
        <div className="riskCard critical"><span>Critical</span><strong>{security.criticalCount}</strong></div>
        <div className="riskCard high"><span>High</span><strong>{security.highCount}</strong></div>
        <div className="riskCard medium"><span>Medium</span><strong>{security.mediumCount}</strong></div>
        <div className="riskCard low"><span>Low</span><strong>{security.lowCount}</strong></div>
      </div>
      {security.findings.length === 0 ? <div className="noFinding"><strong>No known vulnerabilities found.</strong><span>That means OSV returned no matching records for the versions in this scan.</span></div> : (
        <div className="findingLayout">
          <div className="findingTableWrap">
            <div className="tableHead"><div><h3>Security findings</h3><span>{security.vulnerabilityCount} finding(s)</span></div></div>
            <div className="tableScroll"><table><thead><tr><th>Finding</th><th>Dependency</th><th>Severity</th><th>Fixed in</th><th>Risk</th></tr></thead><tbody>
              {security.findings.map(f => <tr key={f.id} className={selectedFinding?.id === f.id ? 'selectedFinding' : ''} onClick={() => setSelectedFinding(f)}>
                <td><b>{f.cve || f.osvId}</b><small>{f.osvId}</small></td>
                <td><b>{f.artifactId}</b><small>{f.version}{f.direct ? ' · direct' : ` · depth ${f.depth}`}</small></td>
                <td><span className={severityClass(f.severity)}>{f.severity}</span></td>
                <td>{f.fixedVersion || 'Not listed'}</td>
                <td><strong>{f.riskScore}</strong></td>
              </tr>)}
            </tbody></table></div>
          </div>
          {selectedFinding && <div className="findingDetail"><div className="detailEyebrow">VULNERABILITY</div><h3>{selectedFinding.cve || selectedFinding.osvId}</h3><span className={severityClass(selectedFinding.severity)}>{selectedFinding.severity}</span><p className="findingSummary">{selectedFinding.summary || 'No summary supplied by OSV.'}</p><div className="detailGrid"><div><span>Dependency</span><strong>{selectedFinding.groupId}:{selectedFinding.artifactId}</strong></div><div><span>Version</span><strong>{selectedFinding.version}</strong></div><div><span>Risk points</span><strong>{selectedFinding.riskScore}</strong></div><div><span>Fixed version</span><strong>{selectedFinding.fixedVersion || 'Not listed'}</strong></div></div>{selectedFinding.cvssVector && <div className="detailBlock"><span>CVSS</span><code>{selectedFinding.cvssVector}</code></div>}<div className="detailBlock"><span>Details</span><p>{selectedFinding.details || 'No additional details supplied.'}</p></div>{selectedFinding.referenceUrl && <a className="referenceLink" href={selectedFinding.referenceUrl} target="_blank" rel="noreferrer">Open advisory reference ↗</a>}</div>}
        </div>
      )}
    </div>
  );
}

function App() {
  const [projects, setProjects] = useState([]);
  const [selected, setSelected] = useState(null);
  const [deps, setDeps] = useState([]);
  const [tree, setTree] = useState(null);
  const [graph, setGraph] = useState(null);
  const [security, setSecurity] = useState(null);
  const [selectedFinding, setSelectedFinding] = useState(null);
  const [tab, setTab] = useState('inventory');
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('all');
  const [name, setName] = useState('');
  const [file, setFile] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState(new Set());

  const loadProjects = async () => { const data = await api('/projects'); setProjects(data); return data; };

  const loadProject = async id => {
    const [detail, dependencies, securityData] = await Promise.all([
      api(`/projects/${id}`),
      api(`/projects/${id}/dependencies`),
      api(`/projects/${id}/security`)
    ]);
    setSelected(detail); setDeps(dependencies); setSecurity(securityData); setSelectedFinding(null); setTree(null); setGraph(null); setSearch(''); setFilter('all');
  };

  useEffect(() => { loadProjects().catch(() => setError('Backend is not reachable. Check the Render API and VITE_API_URL.')); }, []);

  const create = async event => {
    event.preventDefault(); if (!name.trim()) return;
    setBusy(true); setError('');
    try { const project = await api('/projects', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: name.trim() }) }); setName(''); await loadProjects(); await loadProject(project.id); }
    catch (e) { setError(e.message || 'Could not create the project.'); } finally { setBusy(false); }
  };

  const scan = async () => {
    if (!selected || !file) return;
    if (file.name.toLowerCase() !== 'pom.xml') { setError('Please choose a file named pom.xml.'); return; }
    setBusy(true); setError('');
    try { const fd = new FormData(); fd.append('file', file); await api(`/projects/${selected.id}/scan`, { method: 'POST', body: fd }); await loadProjects(); await loadProject(selected.id); setTab('security'); setFile(null); const input = document.getElementById('pom'); if (input) input.value = ''; }
    catch (e) { setError(e.message || 'Scan failed.'); } finally { setBusy(false); }
  };

  const loadTree = async () => { if (!selected) return; try { const data = await api(`/projects/${selected.id}/dependencies/tree`); setTree(data); setExpanded(new Set(data.roots?.map(root => root.id) || [])); } catch (e) { setError(e.message); } };
  const loadGraph = async () => { if (!selected) return; try { setGraph(await api(`/projects/${selected.id}/dependencies/graph`)); } catch (e) { setError(e.message); } };
  const refreshSecurity = async () => { if (!selected) return; try { setSecurity(await api(`/projects/${selected.id}/security`)); } catch (e) { setError(e.message); } };

  useEffect(() => { if (tab === 'tree' && selected && !tree) loadTree(); if (tab === 'graph' && selected && !graph) loadGraph(); }, [tab, selected]);

  const filteredDeps = useMemo(() => {
    const q = search.trim().toLowerCase();
    return deps.filter(d => { if (filter === 'direct' && !d.direct) return false; if (filter === 'transitive' && d.direct) return false; return !q || `${d.groupId}:${d.artifactId}:${d.version}`.toLowerCase().includes(q); });
  }, [deps, search, filter]);

  const toggle = id => setExpanded(current => { const next = new Set(current); next.has(id) ? next.delete(id) : next.add(id); return next; });
  const score = selected?.securityScore;

  return <div className="shell">
    <header><div><span className="eyebrow">DEVELOPER SECURITY</span><h1>Dependency Sentinel</h1><p>Understand every dependency in your Java project — now with known vulnerability intelligence.</p></div><div className="status"><span className="dot" /> Phase 3</div></header>
    {error && <div className="error"><span>{error}</span><button onClick={() => setError('')}>Dismiss</button></div>}
    <main>
      <aside><div className="panel"><h2>Your projects</h2><form onSubmit={create} className="create"><input value={name} onChange={e => setName(e.target.value)} placeholder="Project name" /><button disabled={busy}>Create</button></form>{projects.length === 0 ? <div className="empty">Create your first project.</div> : projects.map(project => <button className={`project ${selected?.id === project.id ? 'active' : ''}`} key={project.id} onClick={() => loadProject(project.id).catch(e => setError(e.message))}><span>{project.name}</span><small>{project.dependencyCount} deps</small></button>)}</div></aside>
      <section>
        {!selected ? <div className="hero"><div className="heroIcon">DS</div><h2>Find <span>known risk</span> in your dependencies</h2><p>Create a project, upload its Maven <code>pom.xml</code>, and Dependency Sentinel will resolve the graph and check package versions against OSV.</p><div className="steps"><div><b>1</b><span>Create project</span></div><div><b>2</b><span>Upload pom.xml</span></div><div><b>3</b><span>Review security</span></div></div></div> :
        <div className="content"><div className="top"><div><span className="eyebrow">PROJECT · MAVEN</span><h2>{selected.name}</h2></div><div className="score"><strong>{score ?? '—'}</strong><span>{score == null ? 'security score pending' : 'security score'}</span></div></div>
          <div className="cards"><div><span>Total dependencies</span><strong>{selected.dependencies}</strong></div><div><span>Direct</span><strong>{selected.directDependencies}</strong></div><div><span>Transitive</span><strong>{selected.transitiveDependencies}</strong></div><div><span>Vulnerabilities</span><strong>{selected.vulnerabilities}</strong></div></div>
          <div className="scanbox"><div><h3>Analyze Maven project</h3><p>Resolve transitives, then query OSV for known vulnerabilities.</p></div><label className="upload"><input id="pom" type="file" accept=".xml" onChange={e => setFile(e.target.files?.[0] || null)} /><span>{file ? file.name : 'Choose pom.xml'}</span></label><button onClick={scan} disabled={!file || busy}>{busy ? 'Analyzing…' : 'Analyze project'}</button></div>
          {selected.truncated && <div className="notice">The dependency graph exceeded its safety cap. Review the stored graph before increasing limits.</div>}
          {selected.securityStatus === 'FAILED' && <div className="notice">The dependency graph is ready, but the OSV security check failed. You can retry it from the Security tab.</div>}
          <div className="tabs"><button className={tab === 'inventory' ? 'tab activeTab' : 'tab'} onClick={() => setTab('inventory')}>Inventory</button><button className={tab === 'tree' ? 'tab activeTab' : 'tab'} onClick={() => setTab('tree')}>Dependency Tree</button><button className={tab === 'graph' ? 'tab activeTab' : 'tab'} onClick={() => setTab('graph')}>Graph</button><button className={tab === 'security' ? 'tab activeTab' : 'tab'} onClick={() => setTab('security')}>Security {selected.vulnerabilities > 0 ? <span className="tabCount">{selected.vulnerabilities}</span> : null}</button></div>

          {tab === 'inventory' && <div className="tablePanel"><div className="toolbar"><div><h3>Resolved dependencies</h3><p>Search and separate direct from transitive dependencies.</p></div><div className="filters"><input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search group, artifact, version" /><select value={filter} onChange={e => setFilter(e.target.value)}><option value="all">All</option><option value="direct">Direct</option><option value="transitive">Transitive</option></select></div></div>{filteredDeps.length === 0 ? <div className="empty">No dependencies match your search.</div> : <div className="tableScroll"><table><thead><tr><th>Dependency</th><th>Version</th><th>Scope</th><th>Depth</th><th>Type</th></tr></thead><tbody>{filteredDeps.map(d => <tr key={d.id}><td><b>{d.artifactId}</b><small>{d.groupId}</small></td><td>{d.version}</td><td>{d.scope}</td><td>{d.depth}</td><td><span className={`typeBadge ${d.direct ? 'directBadge' : 'transitiveBadge'}`}>{d.direct ? 'Direct' : 'Transitive'}</span></td></tr>)}</tbody></table></div>}</div>}
          {tab === 'tree' && <div className="treePanel"><div className="toolbar"><div><h3>Dependency tree</h3><p>Follow where each transitive library comes from.</p></div><button className="ghost" onClick={() => setExpanded(new Set(tree?.roots?.flatMap(r => flattenIds(r)) || []))}>Expand all</button></div>{!tree ? <div className="empty">Loading dependency tree…</div> : tree.roots.length === 0 ? <div className="empty">Run a scan to build the tree.</div> : tree.roots.map(root => <TreeNode key={root.id} node={root} expanded={expanded} toggle={toggle} />)}</div>}
          {tab === 'graph' && <div className="graphPanel"><div className="toolbar"><div><h3>Dependency graph</h3><p>Each line represents a Maven relationship.</p></div><button className="ghost" onClick={loadGraph}>Refresh</button></div><GraphView graph={graph} /></div>}
          {tab === 'security' && <SecurityPanel security={security} selectedFinding={selectedFinding} setSelectedFinding={setSelectedFinding} refresh={refreshSecurity} />}
        </div>}
      </section>
    </main>
    <footer>Phase 3 • OSV vulnerability intelligence • Impact and fixes arrive in Phase 4</footer>
  </div>;
}

function flattenIds(node) { const ids = [node.id]; (node.children || []).forEach(child => ids.push(...flattenIds(child))); return ids; }

createRoot(document.getElementById('root')).render(<App />);
