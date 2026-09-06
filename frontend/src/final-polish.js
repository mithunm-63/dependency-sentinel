(() => {
  const FINAL_FOOTER = 'Phase 6 · GitHub scanning, security intelligence & project health';

  function patchChrome() {
    const pill = document.querySelector('.p4-phase');
    if (pill && pill.textContent.trim() !== 'Phase 6') {
      pill.innerHTML = '<span></span> Phase 6';
    }

    const footer = document.querySelector('.p4-footer');
    if (footer && footer.textContent !== FINAL_FOOTER) {
      footer.textContent = FINAL_FOOTER;
    }

    document.title = 'Dependency Sentinel';
  }

  function patchUploadMessaging() {
    const upload = document.querySelector('.p4-upload span');
    if (upload && !upload.dataset.finalPolish) {
      upload.dataset.finalPolish = 'true';
      if (!upload.textContent.trim() || upload.textContent.includes('Choose pom.xml')) {
        upload.textContent = 'Choose Maven POM';
      }
    }

    const input = document.getElementById('p4-pom');
    if (input) {
      input.setAttribute('aria-label', 'Choose Maven POM file');
      input.setAttribute('accept', '.xml,application/xml,text/xml');
    }
  }

  function patchSecurityLabels() {
    document.querySelectorAll('.p4-subtle').forEach(node => {
      if (node.textContent?.includes('undefined finding')) {
        const card = document.querySelector('.p4-summary-cards > div:nth-child(4) strong');
        const count = card?.textContent?.trim() || '0';
        node.textContent = node.textContent.replace(/undefined finding\(s\)/, `${count} finding(s)`);
      }
    });
  }

  function patchA11y() {
    document.querySelectorAll('.p4-tabs button, .p5-health-tab, .p6-github-tab').forEach(button => {
      if (!button.getAttribute('aria-label')) button.setAttribute('aria-label', button.textContent.trim());
    });
  }

  function patch() {
    patchChrome();
    patchUploadMessaging();
    patchSecurityLabels();
    patchA11y();
  }

  const observer = new MutationObserver(patch);
  observer.observe(document.body, { childList: true, subtree: true, characterData: true });
  patch();
})();
