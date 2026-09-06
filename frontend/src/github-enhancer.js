import './phase6-github.css';

const rawApi = import.meta.env.VITE_API_URL || 'http://localhost:8081/api';
const trimmedApi = rawApi.replace(/\/+$/, '');
const API = trimmedApi.endsWith('/api') ? trimmedApi : `${trimmedApi}/api`;

function activeProjectId() {
  const active = document.querySelector('.p4-project.active');
  return active?.dataset?.projectId || null;
}

function ensureProjectIds() {
  const buttons = [...document.querySelectorAll('.p4-project')];
  buttons.forEach((button, index) => {
    if (button.dataset.healthProjectId) button.dataset.projectId = button.dataset.healthProjectId;
    else if (!button.dataset.projectId && buttons[index]?.dataset.healthProjectId) button.dataset.projectId = buttons[index].dataset.healthProjectId;
  });
}

function ensurePhase6Labels() {
  const phase = document.querySelector('.p4-phase');
  if (phase) phase.innerHTML = '<span /> Phase 6';
  const footer = document.querySelector('.p4-footer');
  if (footer && footer.textContent.includes('Phase 4')) footer.textContent = 'Phase 6 · GitHub integration & DevSecOps workflow';
}

function closeModal() {
  document.querySelector('.p6-overlay')?.remove();
}

function openModal() {
  closeModal();
  const id = activeProjectId();
  if (!id) {
    window.alert('Select a project before connecting GitHub.');
    return;
  }

  const overlay = document.createElement('div');
  overlay.className = 'p6-overlay';
  overlay.innerHTML = `
    <div class="p6-modal" role="dialog" aria-modal="true" aria-labelledby="p6-title">
      <button class="p6-close" type="button" aria-label="Close">×</button>
      <div class="p6-kicker">PHASE 6 · GITHUB / DEVSECOPS</div>
      <h2 id="p6-title">Scan a public GitHub repository</h2>
      <p class="p6-subtitle">Connect a public Maven repository and Dependency Sentinel will fetch its <code>pom.xml</code>, resolve the dependency graph, and run the security check.</p>
      <label class="p6-label">GitHub repository URL</label>
      <input class="p6-input" data-repo value="" placeholder="https://github.com/owner/repository" autocomplete="url" />
      <label class="p6-label">Branch <span>(leave empty for the repository default)</span></label>
      <input class="p6-input" data-branch value="" placeholder="main" autocomplete="off" />
      <div class="p6-help">Only public GitHub repositories are supported in this phase. The repository root must contain <code>pom.xml</code>.</div>
      <div class="p6-status" data-status hidden></div>
      <div class="p6-actions">
        <button class="p6-btn" type="button" data-cancel>Cancel</button>
        <button class="p6-btn primary" type="button" data-scan>Connect &amp; scan</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);

  const status = overlay.querySelector('[data-status]');
  const scanButton = overlay.querySelector('[data-scan]');
  const repoInput = overlay.querySelector('[data-repo]');
  const branchInput = overlay.querySelector('[data-branch]');

  const setStatus = (message, kind = 'info') => {
    status.hidden = !message;
    status.className = `p6-status ${kind}`;
    status.textContent = message;
  };

  const scan = async () => {
    const repoUrl = repoInput.value.trim();
    const branch = branchInput.value.trim();
    if (!repoUrl) {
      setStatus('Enter a GitHub repository URL.', 'error');
      repoInput.focus();
      return;
    }
    scanButton.disabled = true;
    setStatus('Fetching pom.xml and running the dependency/security scan…', 'info');
    try {
      const response = await fetch(`${API}/projects/${id}/github/scan`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ repoUrl, branch: branch || null })
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.message || 'GitHub scan failed.');
      setStatus(`Connected ${data.repository} (${data.branch}). ${data.dependencyCount ?? 0} dependencies and ${data.vulnerabilityCount ?? 0} findings recorded.`, 'success');
      setTimeout(() => window.location.reload(), 900);
    } catch (error) {
      scanButton.disabled = false;
      setStatus(error.message || 'GitHub scan failed.', 'error');
    }
  };

  overlay.querySelector('.p6-close').addEventListener('click', closeModal);
  overlay.querySelector('[data-cancel]').addEventListener('click', closeModal);
  scanButton.addEventListener('click', scan);
  repoInput.addEventListener('keydown', event => { if (event.key === 'Enter') scan(); });
  branchInput.addEventListener('keydown', event => { if (event.key === 'Enter') scan(); });
  overlay.addEventListener('click', event => { if (event.target === overlay) closeModal(); });
  repoInput.focus();
}

function ensureGitHubTab() {
  const tabs = document.querySelector('.p4-tabs');
  if (!tabs || tabs.querySelector('[data-phase6-github]')) return;
  const security = [...tabs.querySelectorAll('button')].find(button => button.textContent.trim() === 'Security');
  const button = document.createElement('button');
  button.type = 'button';
  button.textContent = 'GitHub';
  button.dataset.phase6Github = 'true';
  button.className = 'p6-github-tab';
  button.addEventListener('click', openModal);
  security?.insertAdjacentElement('afterend', button) || tabs.appendChild(button);
}

const observer = new MutationObserver(() => {
  ensureProjectIds();
  ensurePhase6Labels();
  ensureGitHubTab();
});
observer.observe(document.body, { childList: true, subtree: true });
ensureProjectIds();
ensurePhase6Labels();
ensureGitHubTab();
