/**
 * Vigilia Platform — Review Queue Controller
 * Interactive Filtering, Real-time Countdown, and Claim Actions
 */

document.addEventListener('DOMContentLoaded', () => {
  const tableRows = Array.from(document.querySelectorAll('.rq-data-table tbody tr'));
  const searchInput = document.getElementById('rqSearchInput');
  const domainFilter = document.getElementById('rqDomainSelect');
  const riskFilter = document.getElementById('rqRiskSelect');
  const pillButtons = document.querySelectorAll('.rq-filter-pill-btn');

  let activePillFilter = 'all';

  // ── Filter Rows Function ──────────────────────────────────────────
  function applyFilters() {
    const query = (searchInput ? searchInput.value : '').toLowerCase().trim();
    const domain = domainFilter ? domainFilter.value : 'ALL';
    const risk = riskFilter ? riskFilter.value : 'ALL';

    tableRows.forEach(row => {
      const rowText = row.textContent.toLowerCase();
      const rowDomain = row.getAttribute('data-domain') || '';
      const rowRisk = Number.parseFloat(row.getAttribute('data-score') || '0');
      const isAmbiguous = row.getAttribute('data-ambiguous') === 'true';

      let matchesSearch = !query || rowText.includes(query);
      let matchesDomain = domain === 'ALL' || rowDomain === domain;
      let matchesRisk = true;
      if (risk === 'CRITICAL') matchesRisk = rowRisk >= 0.85;
      else if (risk === 'HIGH') matchesRisk = rowRisk >= 0.70 && rowRisk < 0.85;
      else if (risk === 'AMBIGUOUS') matchesRisk = isAmbiguous;

      let matchesPill = true;
      if (activePillFilter === 'purchase') matchesPill = rowDomain === 'PURCHASE';
      else if (activePillFilter === 'transfer') matchesPill = rowDomain === 'TRANSFER';
      else if (activePillFilter === 'ambiguous') matchesPill = isAmbiguous;

      if (matchesSearch && matchesDomain && matchesRisk && matchesPill) {
        row.style.display = '';
      } else {
        row.style.display = 'none';
      }
    });

    // Update active counter
    const visibleCount = tableRows.filter(r => r.style.display !== 'none').length;
    const counterPill = document.querySelector('.rq-counter-pill');
    if (counterPill) {
      counterPill.textContent = `${visibleCount} Transactions en attente`;
    }
  }

  if (searchInput) searchInput.addEventListener('input', applyFilters);
  if (domainFilter) domainFilter.addEventListener('change', applyFilters);
  if (riskFilter) riskFilter.addEventListener('change', applyFilters);

  pillButtons.forEach(btn => {
    btn.addEventListener('click', (e) => {
      pillButtons.forEach(b => b.classList.remove('active'));
      e.currentTarget.classList.add('active');
      activePillFilter = e.currentTarget.getAttribute('data-filter') || 'all';
      applyFilters();
    });
  });

  // ── Dynamic SLA Countdown ─────────────────────────────────────────
  const slaNodes = document.querySelectorAll('.rq-sla-timer-val');
  function tickSla() {
    slaNodes.forEach(node => {
      let secs = Number.parseInt(node.getAttribute('data-seconds') || '0', 10);
      if (secs > 0) {
        secs--;
        node.setAttribute('data-seconds', secs);
        const m = Math.floor(secs / 60);
        const s = secs % 60;
        node.textContent = `${m}m ${String(s).padStart(2, '0')}s`;
      }
    });
  }
  setInterval(tickSla, 1000);

  // ── Claim Action ──────────────────────────────────────────────────
  document.querySelectorAll('.rq-btn-claim').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const caseId = e.currentTarget.getAttribute('data-case');
      btn.textContent = 'En cours...';
      btn.disabled = true;

      setTimeout(() => {
        btn.textContent = 'Pris en charge ✓';
        btn.style.background = '#dcfce7';
        btn.style.color = '#15803d';
        btn.style.borderColor = '#86efac';

        // Redirect to investigation after 400ms
        setTimeout(() => {
          window.location.href = `case-investigation.html?caseId=${caseId}`;
        }, 400);
      }, 500);
    });
  });
});
