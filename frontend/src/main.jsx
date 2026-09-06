import React, { useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';

const API = import.meta.env.VITE_API_URL || 'http://localhost:8081/api';

async function api(path, options) {
  const response = await fetch(`${API}${path}`, options);
  let data = null;
  try { data = await response.json(); } catch { /* empty response */ }
  if (!response.ok) throw new Error(data?.message || 'Request failed');
  return data;
}

function TreeNode({ node, expanded, toggle }) {
  const hasChildren = node.children?.length > 0;
  return (
    <div className="treeNode">
      <div className="treeRow">
        <button className={`treeToggle ${hasChildren ? '' : 'placeholder'}`} onClick={() => hasChildren && toggle(node.id)} aria-label={hasChildren ? (expanded.has(node.id) ? 'Collapse' : 'Expand') : undefined}>
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
    depthNodes.forEach((node, index) => positions.set(node.id, {
      x: 55 + depth * 270,
      y: 70 + index * 92
    }));
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
            <defs>
              <marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
                <path d="M0,0 L8,4 L0,8 z" fill="currentColor" />
              </marker>
            </defs>
            {edges.map((edge, index) => {
              const from = positions.get(edge.parentId); const to = positions.get(edge.childId);
              if (!from || !to) return null;
              const mid = (to.x - from.x) * 0.5;
              return <path key={`${edge.parentId}-${edge.childId}-${index}`} className="graphEdge" d={`M ${from.x + 94} ${from.y} C ${from.x + 94 + mid * 0.55} ${from.y}, ${to.x - 94 - mid * 0.55} ${to.y}, ${to.x - 94} ${to.y}`} markerEnd="url(#arrow)" />;
            })}
            {nodes.map(node => {
              const p = positions.get(node.id);
              return (
                <g key={node.id} transform={`translate(${p.x - 94},${p.y - 25})`}>
                  <rect className={`graphNode ${node.direct ? 'graphDirect' : ''}`} width="188" height="50" rx="11" />
                  <text className="graphTitle" x="12" y="21">{node.artifactId.length > 25 ? `${node.artifactId.slice(0, 22)}…` : node.artifactId}</text>
                  <text className="graphMeta" x="12" y="38">{node.version} · {node.direct ? 'Direct' : `Depth ${node.depth}`}</text>
                </g>
              );
            })}
          </svg>
          {graph.nodes.length > maxVisible && <div className="graphNote">Showing the first {maxVisible} nodes for a responsive graph. The full dependency set is available in Inventory and Tree.</div>}
        </>
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
  const [tab, setTab] = useState('inventory');
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('all');
  const [name, setName] = useState('');
  const [file, setFile] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState(new Set());

  const loadProjects = async () => {
    const data = await api('/projects');
    setProjects(data);
    return data;
  };

  const loadProject = async (id) => {
    const [detail, dependencies] = await Promise.all([
      api(`/projects/${id}`),
      api(`/projects/${id}/dependencies`)
    ]);
    setSelected(detail);
    setDeps(dependencies);
    setTree(null);
    setGraph(null);
    setSearch('');
    setFilter('all');
  };

  useEffect(() => {
    loadProjects().catch(() => setError('Backend is not reachable. Start the API and try again.'));
  }, []);

  const create = async (event) => {
    event.preventDefault();
    if (!name.trim()) return;
    setBusy(true); setError('');
    try {
      const project = await api('/projects', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: name.trim() }) });
      setName('');
      await loadProjects();
      await loadProject(project.id);
    } catch (e) { setError(e.message || 'Could not create the project.'); }
    finally { setBusy(false); }
  };

  const scan = async () => {
    if (!selected || !file) return;
    if (file.name.toLowerCase() !== 'pom.xml') { setError('Please choose a file named pom.xml.'); return; }
    setBusy(true); setError('');
    try {
      const fd = new FormData(); fd.append('file', file);
      await api(`/projects/${selected.id}/scan`, { method: 'POST', body: fd });
      await loadProjects();
      await loadProject(selected.id);
      setTab('inventory');
      setFile(null);
      const input = document.getElementById('pom'); if (input) input.value = '';
    } catch (e) { setError(e.message || 'Scan failed.'); }
    finally { setBusy(false); }
  };

  const loadTree = async () => {
    if (!selected) return;
    try {
      const data = await api(`/projects/${selected.id}/dependencies/tree`);
      setTree(data);
      setExpanded(new Set(data.roots?.map(root => root.id) || []));
    } catch (e) { setError(e.message || 'Could not load the dependency tree.'); }
  };

  const loadGraph = async () => {
    if (!selected) return;
    try { setGraph(await api(`/projects/${selected.id}/dependencies/graph`)); }
    catch (e) { setError(e.message || 'Could not load the dependency graph.'); }
  };

  useEffect(() => {
    if (tab === 'tree' && selected && !tree) loadTree();
    if (tab === 'graph' && selected && !graph) loadGraph();
  }, [tab, selected]);

  const filteredDeps = useMemo(() => {
    const q = search.trim().toLowerCase();
    return deps.filter(d => {
      if (filter === 'direct' && !d.direct) return false;
      if (filter === 'transitive' && d.direct) return false;
      if (!q) return true;
      return `${d.groupId}:${d.artifactId}:${d.version}`.toLowerCase().includes(q);
    });
  }, [deps, search, filter]);

  const toggle = id => setExpanded(current => {
    const next = new Set(current); next.has(id) ? next.delete(id) : next.add(id); return next;
  });

  return (
    <div className="shell">
      <header>
        <div>
          <span className="eyebrow">DEVELOPER SECURITY</span>
          <h1>Dependency Sentinel</h1>
          <p>Understand every dependency in your Java project — direct, transitive, and connected.</p>
        </div>
        <div className="status"><span className="dot" /> Phase 2</div>
      </header>

      {error && <div className="error"><span>{error}</span><button onClick={() => setError('')}>Dismiss</button></div>}

      <main>
        <aside>
          <div className="panel">
            <h2>Your projects</h2>
            <form onSubmit={create} className="create">
              <input value={name} onChange={e => setName(e.target.value)} placeholder="Project name" aria-label="Project name" />
              <button disabled={busy}>Create</button>
            </form>
            {projects.length === 0 ? <div className="empty">Create your first project.</div> : projects.map(project => (
              <button className={`project ${selected?.id === project.id ? 'active' : ''}`} key={project.id} onClick={() => loadProject(project.id).catch(e => setError(e.message))}>
                <span>{project.name}</span><small>{project.dependencyCount} deps</small>
              </button>
            ))}
          </div>
        </aside>

        <section>
          {!selected ? (
            <div className="hero">
              <div className="heroIcon">DS</div>
              <h2>See your dependency <span>tree</span></h2>
              <p>Create a project, upload its Maven <code>pom.xml</code>, and Dependency Sentinel will resolve the dependency graph so you can explore exactly where libraries come from.</p>
              <div className="steps">
                <div><b>1</b><span>Create project</span></div>
                <div><b>2</b><span>Upload pom.xml</span></div>
                <div><b>3</b><span>Explore dependencies</span></div>
              </div>
            </div>
          ) : (
            <div className="content">
              <div className="top">
                <div><span className="eyebrow">PROJECT · MAVEN</span><h2>{selected.name}</h2></div>
                <div className="score"><strong>{selected.dependencies}</strong><span>resolved nodes</span></div>
              </div>

              <div className="cards">
                <div><span>Total dependencies</span><strong>{selected.dependencies}</strong></div>
                <div><span>Direct</span><strong>{selected.directDependencies}</strong></div>
                <div><span>Transitive</span><strong>{selected.transitiveDependencies}</strong></div>
                <div><span>Relationships</span><strong>{selected.graphEdges}</strong></div>
              </div>

              <div className="scanbox">
                <div><h3>Analyze Maven project</h3><p>Phase 2 resolves transitives from Maven Central and keeps the scan snapshot for history.</p></div>
                <label className="upload"><input id="pom" type="file" accept=".xml" onChange={e => setFile(e.target.files?.[0] || null)} /><span>{file ? file.name : 'Choose pom.xml'}</span></label>
                <button onClick={scan} disabled={!file || busy}>{busy ? 'Analyzing…' : 'Analyze project'}</button>
              </div>

              {selected.truncated && <div className="notice">This project exceeded the Phase 2 graph safety cap. The stored graph is intentionally truncated for predictable scan times.</div>}

              <div className="tabs">
                <button className={tab === 'inventory' ? 'tab activeTab' : 'tab'} onClick={() => setTab('inventory')}>Inventory</button>
                <button className={tab === 'tree' ? 'tab activeTab' : 'tab'} onClick={() => setTab('tree')}>Dependency Tree</button>
                <button className={tab === 'graph' ? 'tab activeTab' : 'tab'} onClick={() => setTab('graph')}>Graph</button>
              </div>

              {tab === 'inventory' && (
                <div className="tablePanel">
                  <div className="toolbar"><div><h3>Resolved dependencies</h3><p>Search and separate direct from transitive dependencies.</p></div><div className="filters"><input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search group, artifact, version" /><select value={filter} onChange={e => setFilter(e.target.value)}><option value="all">All</option><option value="direct">Direct</option><option value="transitive">Transitive</option></select></div></div>
                  {filteredDeps.length === 0 ? <div className="empty">No dependencies match your search.</div> : <div className="tableScroll"><table><thead><tr><th>Dependency</th><th>Version</th><th>Scope</th><th>Depth</th><th>Type</th></tr></thead><tbody>{filteredDeps.map(d => <tr key={d.id}><td><b>{d.artifactId}</b><small>{d.groupId}</small></td><td>{d.version}</td><td>{d.scope}</td><td>{d.depth}</td><td><span className={`typeBadge ${d.direct ? 'directBadge' : 'transitiveBadge'}`}>{d.direct ? 'Direct' : 'Transitive'}</span></td></tr>)}</tbody></table></div>}
                </div>
              )}

              {tab === 'tree' && (
                <div className="treePanel">
                  <div className="toolbar"><div><h3>Dependency tree</h3><p>Expand a direct dependency to follow why each transitive library is present.</p></div><button className="ghost" onClick={() => setExpanded(new Set(tree?.roots?.flatMap(r => flattenIds(r)) || []))}>Expand all</button></div>
                  {!tree ? <div className="empty">Loading dependency tree…</div> : tree.roots.length === 0 ? <div className="empty">Run a scan to build the tree.</div> : tree.roots.map(root => <TreeNode key={root.id} node={root} expanded={expanded} toggle={toggle} />)}
                </div>
              )}

              {tab === 'graph' && <div className="graphPanel"><div className="toolbar"><div><h3>Dependency graph</h3><p>Each line represents a relationship discovered by Maven Resolver.</p></div><button className="ghost" onClick={loadGraph}>Refresh</button></div><GraphView graph={graph} /></div>}
            </div>
          )}
        </section>
      </main>
      <footer>Phase 2 • Maven dependency intelligence • Security findings arrive in Phase 3</footer>
    </div>
  );
}

function flattenIds(node) {
  const ids = [node.id];
  (node.children || []).forEach(child => ids.push(...flattenIds(child)));
  return ids;
}

createRoot(document.getElementById('root')).render(<App />);
