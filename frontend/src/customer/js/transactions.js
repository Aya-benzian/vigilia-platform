import { apiClient } from '../../js/api-client.js';
import { VigiliaAuth } from '../../js/auth.js';
import '../../js/components/customer/sidebar.js';
import '../../js/components/customer/top-navbar.js';

const user = VigiliaAuth.getUser();
if (!user || (user.role && user.role !== 'CUSTOMER')) window.location.replace('/login.html');

document.addEventListener('DOMContentLoaded', () => {
  // Tab filter
  document.querySelectorAll('.pg-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.pg-tab').forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      const filter = tab.dataset.filter;
      document.querySelectorAll('.tx-row').forEach(row => {
        row.style.display = (filter === 'all' || row.dataset.type === filter) ? '' : 'none';
      });
    });
  });

  // Search
  document.getElementById('txSearch')?.addEventListener('input', e => {
    const q = e.target.value.toLowerCase();
    document.querySelectorAll('.tx-row').forEach(row => {
      row.style.display = row.textContent.toLowerCase().includes(q) ? '' : 'none';
    });
  });

  // Export placeholder
  document.getElementById('txExportBtn')?.addEventListener('click', () => {
    alert('Export feature coming soon.');
  });

  // Load from API (enrichment, non-blocking)
  apiClient.get('/api/v1/transactions?size=10').then(() => {
    // If backend has data, could dynamically render; seeded rows suffice for now
  }).catch(() => {});
});
