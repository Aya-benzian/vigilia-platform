import '../../js/components/customer/sidebar.js';
import '../../js/components/customer/top-navbar.js';
import { VigiliaAuth } from '../../js/auth.js';

const user = VigiliaAuth.getUser();
if (!user || (user.role && user.role !== 'CUSTOMER')) window.location.replace('/login.html');

function checkEmpty() {
  const grid = document.getElementById('benGrid');
  const empty = document.getElementById('benEmpty');
  if (!grid || !empty) return;
  const hasCards = grid.querySelectorAll('.ben-card').length > 0;
  empty.style.display = hasCards ? 'none' : 'flex';
}

document.addEventListener('DOMContentLoaded', () => {
  const modal = document.getElementById('addBenModal');
  const open = () => { if (modal) modal.style.display = 'flex'; };
  const close = () => { if (modal) modal.style.display = 'none'; };

  document.getElementById('openAddModal')?.addEventListener('click', open);
  document.getElementById('openAddModal2')?.addEventListener('click', open);
  document.getElementById('closeAddModal')?.addEventListener('click', close);
  document.getElementById('cancelAddModal')?.addEventListener('click', close);
  modal?.addEventListener('click', e => { if (e.target === modal) close(); });

  document.getElementById('benSearch')?.addEventListener('input', e => {
    const q = e.target.value.toLowerCase();
    document.querySelectorAll('.ben-card').forEach(card => {
      card.style.display = card.textContent.toLowerCase().includes(q) ? '' : 'none';
    });
  });

  document.querySelectorAll('.ben-remove-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      btn.closest('.pg-card')?.remove();
      checkEmpty();
    });
  });

  checkEmpty();
});

