import { apiClient } from '../../js/api-client.js';
import { VigiliaAuth } from '../../js/auth.js';
import '../../js/components/customer/sidebar.js';
import '../../js/components/customer/top-navbar.js';

const user = VigiliaAuth.getUser();
if (!user || (user.role && user.role !== 'CUSTOMER')) window.location.replace('/login.html');

let selectedRecipient = null;

function goTo(stepId) {
  ['stepRecipient', 'stepAmount', 'stepConfirm', 'stepSuccess'].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.hidden = (id !== stepId);
  });

  const stepNum = { stepRecipient: 1, stepAmount: 2, stepConfirm: 3, stepSuccess: 3 };
  document.querySelectorAll('.sm-step').forEach((el, i) => {
    const n = i + 1;
    el.classList.toggle('active', n === stepNum[stepId]);
    el.classList.toggle('done', n < stepNum[stepId]);
  });
}

document.addEventListener('DOMContentLoaded', () => {
  goTo('stepRecipient');

  document.querySelectorAll('.sm-recipient-card').forEach(card => {
    card.addEventListener('click', () => {
      document.querySelectorAll('.sm-recipient-card').forEach(c => c.classList.remove('selected'));
      card.classList.add('selected');
      selectedRecipient = {
        name: card.dataset.name,
        email: card.dataset.email,
        initials: card.dataset.initials,
        color: card.dataset.color,
      };
      const btn = document.getElementById('btnStep1Continue');
      if (btn) btn.disabled = false;
    });
  });

  document.getElementById('btnStep1Continue')?.addEventListener('click', () => {
    if (!selectedRecipient) return;
    document.getElementById('selectedRecipientName').textContent = selectedRecipient.name;
    document.getElementById('selectedRecipientEmail').textContent = selectedRecipient.email;
    const av = document.getElementById('selectedAvatar');
    if (av) {
      av.textContent = selectedRecipient.initials;
      av.className = `sm-sel-avatar ${selectedRecipient.color}`;
    }
    goTo('stepAmount');
  });

  document.getElementById('btnChangeRecipient')?.addEventListener('click', () => goTo('stepRecipient'));
  document.getElementById('btnStep2Back')?.addEventListener('click', () => goTo('stepRecipient'));

  document.getElementById('btnStep2Continue')?.addEventListener('click', () => {
    const amount = parseFloat(document.getElementById('sendAmount')?.value) || 0;
    if (amount <= 0) return;
    const note = document.getElementById('sendNote')?.value || '—';
    document.getElementById('confirmName').textContent = selectedRecipient?.name || '';
    document.getElementById('confirmEmail').textContent = selectedRecipient?.email || '';
    document.getElementById('confirmNote').textContent = note || '—';
    const fmtAmt = '$' + amount.toFixed(2);
    document.getElementById('confirmAmount').textContent = fmtAmt;
    document.getElementById('confirmTotal').textContent = fmtAmt;
    const sendBtn = document.getElementById('btnSend');
    if (sendBtn) sendBtn.textContent = `Send ${fmtAmt}`;
    goTo('stepConfirm');
  });

  document.getElementById('btnStep3Back')?.addEventListener('click', () => goTo('stepAmount'));

  document.getElementById('btnSend')?.addEventListener('click', async () => {
    const amount = parseFloat(document.getElementById('sendAmount')?.value) || 0;
    try {
      await apiClient.post('/api/v1/transactions/transfer', {
        recipientEmail: selectedRecipient?.email,
        amount,
      });
    } catch (e) {
      console.info('[SendMoney] API unavailable:', e.message);
    }
    document.getElementById('successAmount').textContent = '$' + amount.toFixed(2);
    document.getElementById('successName').textContent = selectedRecipient?.name || '';
    goTo('stepSuccess');
  });

  document.getElementById('btnSendAnother')?.addEventListener('click', () => {
    selectedRecipient = null;
    const amountEl = document.getElementById('sendAmount');
    const noteEl = document.getElementById('sendNote');
    if (amountEl) amountEl.value = '';
    if (noteEl) noteEl.value = '';
    document.querySelectorAll('.sm-recipient-card').forEach(c => c.classList.remove('selected'));
    const btn = document.getElementById('btnStep1Continue');
    if (btn) btn.disabled = true;
    goTo('stepRecipient');
  });
});
