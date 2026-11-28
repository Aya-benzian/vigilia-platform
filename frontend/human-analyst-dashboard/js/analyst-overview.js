/**
 * Vigilia Platform — Human Analyst Overview Controller
 * Interactivity: Dynamic Countdown Timers, Live Clock, Claim Actions
 */

document.addEventListener('DOMContentLoaded', () => {
  // ── 1. Live UTC Clock in Hero Section ─────────────────────────────
  const statusLine = document.querySelector('.ao-status-line span:last-child');
  function updateUtcClock() {
    const now = new Date();
    const utcHours = String(now.getUTCHours()).padStart(2, '0');
    const utcMins = String(now.getUTCMinutes()).padStart(2, '0');
    const utcSecs = String(now.getUTCSeconds()).padStart(2, '0');
    if (statusLine) {
      statusLine.textContent = `Système ML Actif | Files synchronisées – ${utcHours}:${utcMins}:${utcSecs} UTC`;
    }
  }
  setInterval(updateUtcClock, 1000);
  updateUtcClock();

  // ── 2. Dynamic SLA Timers in Priority Queue ──────────────────────
  const slaElements = [
    { id: 'sla-2048', totalSecs: 14 * 60 + 22 },
    { id: 'sla-2045', totalSecs: 17 * 60 + 50 },
    { id: 'sla-2039', totalSecs: 22 * 60 + 15 },
    { id: 'sla-2036', totalSecs: 25 * 60 + 3 },
    { id: 'sla-2031', totalSecs: 28 * 60 + 47 },
  ];

  function tickSla() {
    slaElements.forEach(item => {
      const el = document.getElementById(item.id);
      if (!el) return;
      if (item.totalSecs > 0) item.totalSecs--;
      const m = Math.floor(item.totalSecs / 60);
      const s = item.totalSecs % 60;
      el.textContent = `${m}m ${String(s).padStart(2, '0')}s`;
    });
  }
  setInterval(tickSla, 1000);

  // ── 3. Claim Case Action Handler ──────────────────────────────────
  document.querySelectorAll('.ao-btn-claim').forEach(button => {
    button.addEventListener('click', (e) => {
      const caseId = e.currentTarget.getAttribute('data-case');
      button.textContent = 'En cours...';
      button.disabled = true;
      button.style.opacity = '0.7';

      setTimeout(() => {
        button.textContent = 'Assigné ✓';
        button.style.backgroundColor = '#DCFCE7';
        button.style.color = '#15803D';
        button.style.borderColor = '#86EFAC';
        
        // Show brief notification
        showToast(`Dossier ${caseId} assigné avec succès à Aïcha Belk.`);
      }, 500);
    });
  });

  // ── 4. Keyboard Shortcut [Q] to go to Review Queue ────────────────
  document.addEventListener('keydown', (e) => {
    if ((e.key === 'q' || e.key === 'Q') && !['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName)) {
      e.preventDefault();
      const cta = document.querySelector('.ao-cta-review-queue');
      if (cta) {
        cta.click();
      }
    }
  });

  // ── 5. Toast Notification Utility ─────────────────────────────────
  function showToast(msg) {
    let toast = document.getElementById('aoToast');
    if (!toast) {
      toast = document.createElement('div');
      toast.id = 'aoToast';
      toast.style.position = 'fixed';
      toast.style.bottom = '24px';
      toast.style.right = '24px';
      toast.style.backgroundColor = '#0F172A';
      toast.style.color = '#FFFFFF';
      toast.style.fontSize = '0.84rem';
      toast.style.fontWeight = '500';
      toast.style.padding = '12px 20px';
      toast.style.borderRadius = '10px';
      toast.style.boxShadow = '0 10px 25px -5px rgba(0, 0, 0, 0.2)';
      toast.style.zIndex = '9999';
      toast.style.transition = 'all 0.25s ease';
      toast.style.transform = 'translateY(20px)';
      toast.style.opacity = '0';
      document.body.appendChild(toast);
    }
    toast.textContent = msg;
    toast.style.transform = 'translateY(0)';
    toast.style.opacity = '1';

    setTimeout(() => {
      toast.style.transform = 'translateY(20px)';
      toast.style.opacity = '0';
    }, 3000);
  }
});
