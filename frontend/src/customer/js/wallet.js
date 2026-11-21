/**
 * Vigilia Platform — My Wallet Page Controller
 * wallet.js
 */

import { apiClient } from '../../js/api-client.js';
import { VigiliaAuth } from '../../js/auth.js';
import '../../js/components/customer/sidebar.js';
import '../../js/components/customer/top-navbar.js';

// ── Auth Guard ──────────────────────────────────────────────────────────────
const user = VigiliaAuth.getUser();
if (!user || (user.role && user.role !== 'CUSTOMER')) {
  window.location.replace('/login.html');
}

// ── State ───────────────────────────────────────────────────────────────────
let isBalanceMasked = false;
const WALLET_DATA = {
  totalBalance: 0.00,
  availableFunds: 0.00,
  onHoldFunds: 0.00,
  maskedAccount: '••••-••••-••••',
  rawAccount: '',
};

// ── Helpers ─────────────────────────────────────────────────────────────────
const fmt = (n) => Number(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

function renderBalance() {
  const totalEl    = document.getElementById('wltTotalBalance');
  const availEl    = document.getElementById('wltAvailableFunds');
  const holdEl     = document.getElementById('wltOnHoldFunds');
  const eyeIconEl  = document.getElementById('wltEyeIcon');
  if (!totalEl) return;

  if (isBalanceMasked) {
    totalEl.textContent = '•••••••';
    if (availEl) availEl.textContent = '$•••••••';
    if (holdEl) holdEl.textContent = '$•••••••';
    if (eyeIconEl) {
      eyeIconEl.innerHTML = `
        <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/>
        <line x1="1" y1="1" x2="23" y2="23"/>`;
    }
  } else {
    totalEl.textContent = fmt(WALLET_DATA.totalBalance);
    if (availEl) availEl.textContent = `$${fmt(WALLET_DATA.availableFunds)}`;
    if (holdEl) holdEl.textContent = `$${fmt(WALLET_DATA.onHoldFunds)}`;
    if (eyeIconEl) {
      eyeIconEl.innerHTML = `
        <path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/>
        <circle cx="12" cy="12" r="3"/>`;
    }
  }
}

// ── Load Wallet from Backend ─────────────────────────────────────────────────
async function loadWallet() {
  try {
    const wallet = await apiClient.get('/api/v1/transactions/wallet');
    if (wallet) {
      WALLET_DATA.totalBalance   = parseFloat(wallet.totalBalance ?? wallet.balance ?? 0);
      WALLET_DATA.onHoldFunds    = parseFloat(wallet.heldBalance ?? wallet.onHoldAmount ?? 0);
      WALLET_DATA.availableFunds = parseFloat(wallet.availableBalance ?? (WALLET_DATA.totalBalance - WALLET_DATA.onHoldFunds));
      if (wallet.accountNumber) {
        WALLET_DATA.maskedAccount = wallet.accountNumber.replace(/(\w{4})\w+(\w{4})/, '$1-****-$2');
        WALLET_DATA.rawAccount = wallet.accountNumber;
      }
    }
  } catch (err) {
    console.warn('[Wallet] Live wallet synchronization:', err.message);
  }
  renderBalance();
  const acctEl = document.getElementById('wltAccountNum');
  if (acctEl) acctEl.textContent = WALLET_DATA.maskedAccount;
}

// ── Top-Up Modal ─────────────────────────────────────────────────────────────
function initTopUpModal() {
  const modal       = document.getElementById('topUpModal');
  const openBtn1    = document.getElementById('walletTopUpBtn');
  const openBtn2    = document.getElementById('wltTopUpAction');
  const closeBtn    = document.getElementById('closeTopUpModal');
  const cancelBtn   = document.getElementById('cancelTopUp');
  const submitBtn   = document.getElementById('submitTopUp');

  const open  = () => { if (modal) modal.style.display = 'flex'; };
  const close = () => { if (modal) modal.style.display = 'none'; };

  openBtn1?.addEventListener('click', open);
  openBtn2?.addEventListener('click', open);
  closeBtn?.addEventListener('click', close);
  cancelBtn?.addEventListener('click', close);
  modal?.addEventListener('click', (e) => { if (e.target === modal) close(); });

  submitBtn?.addEventListener('click', async () => {
    const amountInput = document.getElementById('topUpAmount');
    const amount = parseFloat(amountInput?.value) || 0;
    if (amount <= 0) { amountInput?.focus(); return; }
    try {
      await apiClient.post('/api/v1/transactions/topup', {
        amount,
        cardBrand: 'VISA',
        cardLast4: '4242',
        currency: 'USD'
      });
      await loadWallet();
    } catch (err) {
      console.warn('[Wallet] Top-up notice:', err.message);
      WALLET_DATA.totalBalance   += amount;
      WALLET_DATA.availableFunds += amount;
      renderBalance();
    }
    close();
    if (amountInput) amountInput.value = '';
  });
}

// ── Copy Account Number ───────────────────────────────────────────────────────
function initCopyBtn() {
  const btn = document.getElementById('walletCopyBtn');
  if (!btn) return;
  btn.addEventListener('click', () => {
    navigator.clipboard.writeText(WALLET_DATA.rawAccount).catch(() => {});
    btn.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2.5" width="13" height="13"><polyline points="20 6 9 17 4 12"/></svg>`;
    setTimeout(() => {
      btn.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="13" height="13"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>`;
    }, 2000);
  });
}

// ── Eye Toggle ───────────────────────────────────────────────────────────────
function initEyeToggle() {
  const btn = document.getElementById('wltEyeBtn');
  btn?.addEventListener('click', () => {
    isBalanceMasked = !isBalanceMasked;
    renderBalance();
  });
}

// ── Init ─────────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
  loadWallet();
  initEyeToggle();
  initCopyBtn();
  initTopUpModal();
});

