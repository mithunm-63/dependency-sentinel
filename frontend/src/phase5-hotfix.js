(() => {
  const normalizePomInput = input => {
    const file = input?.files?.[0];
    if (!file || file.name.toLowerCase() === 'pom.xml') return;
    try {
      const renamed = new File([file], 'pom.xml', {
        type: file.type || 'application/xml',
        lastModified: file.lastModified
      });
      const transfer = new DataTransfer();
      transfer.items.add(renamed);
      input.files = transfer.files;
    } catch {
      // Leave the original file untouched if the browser disallows replacing FileList.
    }
  };

  const patchUi = () => {
    document.querySelectorAll('.p4-subtle').forEach(node => {
      if (!node.textContent?.includes('undefined finding')) return;
      const vulnerabilityCard = document.querySelector('.p4-summary-cards > div:nth-child(4) strong');
      const count = vulnerabilityCard?.textContent?.trim() || '0';
      node.textContent = node.textContent.replace(/undefined finding\(s\)/, `${count} finding(s)`);
    });

    const phase = document.querySelector('.p4-phase');
    if (phase && /Phase 4/.test(phase.textContent || '')) {
      phase.innerHTML = '<span></span> Phase 5';
    }

    const footer = document.querySelector('.p4-footer');
    if (footer && /Phase 4/.test(footer.textContent || '')) {
      footer.textContent = 'Phase 5 · continuous project health, scan comparison & dependency drift';
    }
  };

  document.addEventListener('change', event => {
    const input = event.target;
    if (input instanceof HTMLInputElement && input.id === 'p4-pom') normalizePomInput(input);
  }, true);

  const observer = new MutationObserver(patchUi);
  observer.observe(document.body, { childList: true, subtree: true, characterData: true });
  patchUi();
})();
