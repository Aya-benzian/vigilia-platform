import '../../js/components/customer/sidebar.js';
import '../../js/components/customer/top-navbar.js';
import { VigiliaAuth } from '../../js/auth.js';

const user = VigiliaAuth.getUser();
if (!user || (user.role && user.role !== 'CUSTOMER')) window.location.replace('/login.html');

document.addEventListener('DOMContentLoaded', () => {
  // Animate the security score circle on load
  const circle = document.querySelector('.sec-score-ring');
  if (circle) {
    setTimeout(() => circle.classList.add('animated'), 100);
  }

  // Revoke session buttons
  document.querySelectorAll('.sec-revoke-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      if (confirm('Revoke this session? The device will be signed out.')) {
        btn.closest('.sec-device-card')?.remove();
      }
    });
  });
});

