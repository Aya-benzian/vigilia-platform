import { apiClient } from '../../js/api-client.js';
import { VigiliaAuth } from '../../js/auth.js';
import '../../js/components/customer/sidebar.js';
import '../../js/components/customer/top-navbar.js';

const user = VigiliaAuth.getUser();
if (!user || (user.role && user.role !== 'CUSTOMER')) window.location.replace('/login.html');

let selectedMerchant = null;

document.addEventListener('DOMContentLoaded', () => {
  const amountSection = document.getElementById('mpAmountSection');
  const successSection = document.getElementById('mpSuccess');

  document.querySelectorAll('.mp-merchant-tile').forEach(tile => {
    tile.addEventListener('click', () => {
      document.querySelectorAll('.mp-merchant-tile').forEach(t => t.classList.remove('selected'));
      tile.classList.add('selected');
      selectedMerchant = { name: tile.dataset.name, id: tile.dataset.id };

      const nameEl = document.getElementById('mpSelectedName');
      const idEl   = document.getElementById('mpSelectedId');
      if (nameEl) nameEl.textContent = selectedMerchant.name;
      if (idEl)   idEl.textContent   = selectedMerchant.id;

      if (amountSection) amountSection.style.display = 'block';
      document.getElementById('mpAmount')?.focus();
    });
  });

  document.getElementById('mpClearBtn')?.addEventListener('click', () => {
    document.querySelectorAll('.mp-merchant-tile').forEach(t => t.classList.remove('selected'));
    selectedMerchant = null;
    if (amountSection) amountSection.style.display = 'none';
  });

  document.getElementById('mpPayBtn')?.addEventListener('click', async () => {
    const amount = parseFloat(document.getElementById('mpAmount')?.value) || 0;
    if (!amount || !selectedMerchant) return;

    try {
      await apiClient.post('/api/v1/transactions/pay', {
        merchantId: selectedMerchant.id,
        amount,
      });
    } catch (e) {
      console.info('[MerchantPay] API unavailable:', e.message);
    }

    const nameEl = document.getElementById('mpSuccessName');
    if (nameEl) nameEl.textContent = selectedMerchant.name;
    if (amountSection) amountSection.style.display = 'none';
    if (successSection) successSection.style.display = 'flex';
  });

  document.getElementById('mpPayAnotherBtn')?.addEventListener('click', () => {
    if (successSection) successSection.style.display = 'none';
    if (amountSection) amountSection.style.display = 'none';
    document.querySelectorAll('.mp-merchant-tile').forEach(t => t.classList.remove('selected'));
    const amountEl = document.getElementById('mpAmount');
    if (amountEl) amountEl.value = '';
    selectedMerchant = null;
  });
});

