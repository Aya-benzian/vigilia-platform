import '../../js/components/customer/sidebar.js';
import '../../js/components/customer/top-navbar.js';
import { VigiliaAuth } from '../../js/auth.js';

const user = VigiliaAuth.getUser();
if (!user || (user.role && user.role !== 'CUSTOMER')) window.location.replace('/login.html');

document.addEventListener('DOMContentLoaded', () => {
  document.getElementById('markAllReadBtn')?.addEventListener('click', () => {
    document.querySelectorAll('.notif-item.unread').forEach(el => el.classList.remove('unread'));
    // Update badge count in sidebar
    const badge = document.querySelector('customer-sidebar')?.shadowRoot?.querySelector('.notif-badge');
    if (badge) badge.style.display = 'none';
  });

  document.querySelectorAll('.notif-dismiss').forEach(btn => {
    btn.addEventListener('click', () => btn.closest('.notif-item')?.remove());
  });
});

