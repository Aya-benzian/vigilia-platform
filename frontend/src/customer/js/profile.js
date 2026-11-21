import '../../js/components/customer/sidebar.js';
import '../../js/components/customer/top-navbar.js';
import { VigiliaAuth } from '../../js/auth.js';

const user = VigiliaAuth.getUser();
if (!user || (user.role && user.role !== 'CUSTOMER')) window.location.replace('/login.html');

document.addEventListener('DOMContentLoaded', () => {
  const sections = {
    personal:     'sectionPersonal',
    security:     'sectionSecurity',
    preferences:  'sectionPreferences',
  };

  // Nav tab switching
  document.querySelectorAll('.profile-nav-item').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.profile-nav-item').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      Object.values(sections).forEach(id => {
        const el = document.getElementById(id);
        if (el) el.hidden = true;
      });
      const target = sections[btn.dataset.section];
      if (target) {
        const el = document.getElementById(target);
        if (el) el.hidden = false;
      }
    });
  });

  // Load user info into profile card
  const authUser = VigiliaAuth.getUser();
  if (authUser) {
    const name = authUser.fullName || authUser.name || authUser.username || 'Aya Belk';
    const initials = name.split(' ').map(p => p[0]).join('').slice(0, 2).toUpperCase();
    const avatarEl = document.querySelector('.profile-avatar-circle');
    const nameEl   = document.querySelector('.profile-name');
    const emailEl  = document.querySelector('.profile-email');
    if (avatarEl) avatarEl.textContent = initials;
    if (nameEl)   nameEl.textContent = name;
    if (emailEl && authUser.email) emailEl.textContent = authUser.email;
  }
});

