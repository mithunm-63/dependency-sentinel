import './phase5.css';

const rawApi = import.meta.env.VITE_API_URL || 'http://localhost:8081/api';
const trimmedApi = rawApi.replace(/\/+$/, '');
const API = trimmedApi.endsWith('/api') ? trimmedApi : `${trimmedApi}/api`;

let projects = [];
let modal = null;

async function getJson(path) {
  const response = await fetch(`${API}${path}`);
  let data = null;
  try { data = await response.json(); } catch { /* empty */ }
  if (!response.ok) throw new Error(data?.message || 'Request failed');
  return data;
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

function healthClass(score) {
  if (score >= 90) return 'good';
  if (score >= 75) return 'watch';
  if (score >= 50) return 'risk';
  return 'critical';
}

function levelLabel(level) {
  return String(level || 'NOT_SCANNED').replaceAll('_', ' ');
}

function dateLabel(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
}

function activeProjectId() {
  const buttons = [...document.querySelectorAll('.p4-project')];
  const active = buttons.find(button => button.classList.contains('active'));
  if (!active) return null;
  const directId = active.dataset.healthProjectId;
  if (directId) return Number(directId);
  const index = buttons.indexOf(active);
  return projects[index]?.id ?? null;
}

function ensureProjectIds() {
  const buttons = [...document.querySelectorAll('.p4-project')];
  buttons.forEach((button, index) => {
    if (projects[index]) button.dataset.healthProjectId = String(projects[index].id);
  });
}

function promotePhaseLabel() {
  const pill = document.querySelector('.p4-phase');
  if (pill && pill.textContent.trim() !== 'Phase 5') pill.innerHTML = '<span /> Phase 5';
  const footer = document.querySelector('.p4-footer');
  const footerText = 'Phase 5 · continuous project health, scan comparison & dependency drift';
  if (footer && footer.textContent !== footerText) footer.textContent = footerText;
  const welcomeKicker = document.querySelector('.p4-welcome .p4-eyebrow');
  if (welcomeKicker && welcomeKicker.textContent.trim() !== 'PHASE 5') welcomeKicker.textContent = 'PHASE 5';
}

function listItems(items, type, emptyText) {
  if (!items?.length) return `<div class="p5-empty">${escapeHtml(emptyText)}</div>`;
  return `<div class="p5-list">${items.map(item => {
    const version = item.previousVersion && item.previousVersion !== item.version
      ? `${item.previousVersion} → ${item.version}`
      : item.version;
    const detail = [version, item.scope, item.direct ? 'direct' : `depth ${item.depth}`].filter(Boolean).join(' · ');
    return `<div class="p5-item ${type}"><strong>${escapeHtml(item.coordinate)}</strong><span>${escapeHtml(detail)}</span></div>`;
  }).join('')}</div>`;
}

function renderModal(data) {
  const changes = data.dependencyChanges || {};
  const security = data.security || {};
  const score = Number(data.healthScore ?? 0);
  const health = healthClass(score);

  modal = document.createElement('div');
  modal.className = 'p5-overlay';
  modal.innerHTML = `
    <div class="p5-modal" role="dialog" aria-modal="true" aria-label="Project health">
      <div class="p5-header">
        <div><span class="p5-kicker">PHASE 5 · CONTINUOUS PROJECT HEALTH</span><h2>${escapeHtml(data.projectName)}</h2><p>See what changed between scans and whether the project is getting safer or riskier.</p></div>
        <button class="p5-close" type="button" aria-label="Close">×</button>
      </div>
      <div class="p5-summary">
        <div class="p5-score"><div class="p5-score-ring ${health}"><strong>${score}</strong><span>/ 100</span></div><div class="p5-level">${escapeHtml(levelLabel(data.healthLevel))}</div></div>
        <div class="p5-meta-grid">
          <div class="p5-meta-card"><span>Scans recorded</span><strong>${data.scanCount}</strong></div>
          <div class="p5-meta-card"><span>Latest scan</span><strong>${escapeHtml(dateLabel(data.latestScanAt))}</strong></div>
          <div class="p5-meta-card"><span>Previous scan</span><strong>${escapeHtml(dateLabel(data.previousScanAt))}</strong></div>
          <div class="p5-meta-card"><span>Vulnerabilities now</span><strong>${security.currentVulnerabilities ?? 0}</strong></div>
        </div>
      </div>
      <div class="p5-change-grid">
        <div class="p5-change-card added"><span>Added</span><strong>${changes.added ?? 0}</strong></div>
        <div class="p5-change-card removed"><span>Removed</span><strong>${changes.removed ?? 0}</strong></div>
        <div class="p5-change-card updated"><span>Updated</span><strong>${changes.updated ?? 0}</strong></div>
        <div class="p5-change-card unchanged"><span>Unchanged</span><strong>${changes.unchanged ?? 0}</strong></div>
      </div>
      <section class="p5-section"><div class="p5-section-title"><h3>What changed</h3><span>Latest scan vs previous scan</span></div><div class="p5-highlights">${(data.highlights || []).map(text => `<div class="p5-highlight">${escapeHtml(text)}</div>`).join('') || '<div class="p5-highlight">No health highlights available.</div>'}</div></section>
      <section class="p5-section"><div class="p5-section-title"><h3>Dependency movement</h3><span>Top changes shown; the scan keeps the complete snapshot history.</span></div><div class="p5-change-layout">
        <div class="p5-list-card"><h4>New dependencies</h4>${listItems(changes.addedItems, 'added', 'No dependencies added.')}</div>
        <div class="p5-list-card"><h4>Removed dependencies</h4>${listItems(changes.removedItems, 'removed', 'No dependencies removed.')}</div>
        <div class="p5-list-card"><h4>Updated dependencies</h4>${listItems(changes.updatedItems, 'updated', 'No version or scope changes.')}</div>
      </div></section>
      <section class="p5-section"><div class="p5-section-title"><h3>Security movement</h3><span>${security.scoreDelta == null ? 'Comparison becomes available after a second security-checked scan.' : `Score change: ${security.scoreDelta > 0 ? '+' : ''}${security.scoreDelta}`}</span></div><div class="p5-meta-grid">
        <div class="p5-meta-card"><span>Current findings</span><strong>${security.currentVulnerabilities ?? 0}</strong></div>
        <div class="p5-meta-card"><span>Previous findings</span><strong>${security.previousVulnerabilities ?? 0}</strong></div>
        <div class="p5-meta-card"><span>New / resolved</span><strong>${security.newVulnerabilities > 0 ? '+' : ''}${security.newVulnerabilities ?? 0}</strong></div>
        <div class="p5-meta-card"><span>Current security score</span><strong>${security.currentScore ?? '—'}</strong></div>
      </div></section>
      <div class="p5-error" data-health-error hidden></div>
      <div class="p5-footer"><button type="button" class="p5-btn" data-open-security>Open Security</button><button type="button" class="p5-btn" data-refresh-health>Refresh</button><button type="button" class="p5-btn primary" data-close-health>Done</button></div>
    </div>`;

  modal.querySelector('.p5-close').addEventListener('click', closeModal);
  modal.querySelector('[data-close-health]').addEventListener('click', closeModal);
  modal.querySelector('[data-refresh-health]').addEventListener('click', async () => {
    const error = modal.querySelector('[data-health-error]');
    const id = data.projectId;
    try {
      const fresh = await getJson(`/projects/${id}/health`);
      closeModal();
      renderModal(fresh);
      document.body.appendChild(modal);
    } catch (e) {
      error.hidden = false;
      error.textContent = e.message || 'Could not refresh health.';
    }
  });
  modal.querySelector('[data-open-security]').addEventListener('click', () => {
    closeModal();
    const securityTab = [...document.querySelectorAll('.p4-tabs button')].find(button => button.textContent.trim() === 'Security');
    securityTab?.click();
  });
  modal.addEventListener('click', event => { if (event.target === modal) closeModal(); });
}

function closeModal() {
  modal?.remove();
  modal = null;
}

async function openHealth() {
  closeModal();
  try {
    projects = await getJson('/projects');
    ensureProjectIds();
    const id = activeProjectId();
    if (!id) return;
    const data = await getJson(`/projects/${id}/health`);
    renderModal(data);
    document.body.appendChild(modal);
  } catch (e) {
    window.alert(e.message || 'Could not load project health.');
  }
}

function ensureHealthButton() {
  const tabs = document.querySelector('.p4-tabs');
  if (!tabs) return;
  if (tabs.querySelector('[data-phase5-health]')) return;
  const button = document.createElement('button');
  button.type = 'button';
  button.textContent = 'Health';
  button.dataset.phase5Health = 'true';
  button.className = 'p5-health-tab';
  button.addEventListener('click', openHealth);
  tabs.appendChild(button);
}

async function boot() {
  try { projects = await getJson('/projects'); } catch { projects = []; }
  const observer = new MutationObserver(() => {
    ensureProjectIds();
    ensureHealthButton();
    promotePhaseLabel();
  });
  observer.observe(document.body, { childList: true, subtree: true });
  ensureProjectIds();
  ensureHealthButton();
  promotePhaseLabel();
}

boot();
