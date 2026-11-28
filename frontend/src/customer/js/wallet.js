/**
 * Vigilia Platform — My Wallet Page Controller
 * wallet.js — Distinct Wallet, Allocation, Card Controls, Funding & Cash Flow
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
let isCardRevealed = false;
let isCardFrozen = false;
let currentFilter = 'all';
let allTransactions = [];
let linkedFundingCards = [];
let selectedFundingCard = null;

const WALLET_DATA = {
  totalBalance: 0.00,
  availableFunds: 0.00,
  onHoldFunds: 0.00,
  currency: 'EUR',
  rawAccount: '',
  last4: '0000',
};

// ── Helpers ─────────────────────────────────────────────────────────────────
const fmt = (n) => Number(n || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const getCurrencySymbol = (curr) => {
  const c = (curr || 'EUR').toUpperCase();
  if (c === 'EUR') return '€';
  if (c === 'GBP') return '£';
  if (c === 'MAD') return 'MAD ';
  return '$';
};

const getCurrencyLabel = (curr) => {
  const c = (curr || 'EUR').toUpperCase();
  if (c === 'EUR') return 'EUR — Euro';
  if (c === 'GBP') return 'GBP — British Pound';
  if (c === 'USD') return 'USD — US Dollar';
  return c;
};

let toastTimer = null;
function showToast(msg) {
  const el = document.getElementById('wltToast');
  if (!el) return;
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 2800);
}

function getLinkedCardsStorageKey() {
  const uid = user?.id || user?.email || user?.username || 'customer';
  return `vigilia_linked_cards_${uid}`;
}

function getLinkedCards() {
  try {
    const saved = localStorage.getItem(getLinkedCardsStorageKey());
    if (saved) {
      const parsed = JSON.parse(saved);
      if (Array.isArray(parsed)) return parsed;
    }
  } catch (e) { /* ignore */ }
  return [];
}

function saveLinkedCards(cards) {
  try {
    localStorage.setItem(getLinkedCardsStorageKey(), JSON.stringify(cards));
  } catch (e) { /* ignore */ }
}

// ── Render Allocation & Balances ─────────────────────────────────────────────
function renderAllocation() {
  const totalEl   = document.getElementById('wltTotalLedger');
  const availEl   = document.getElementById('wltAvailableFunds');
  const holdEl    = document.getElementById('wltOnHoldFunds');
  const availBar  = document.getElementById('wltAllocAvailBar');
  const heldBar   = document.getElementById('wltAllocHeldBar');
  const eyeIconEl = document.getElementById('wltEyeIcon');
  const prefixEl  = document.getElementById('wltTopUpPrefix');

  const sym = getCurrencySymbol(WALLET_DATA.currency);
  if (prefixEl) prefixEl.textContent = sym;

  if (isBalanceMasked) {
    if (totalEl) totalEl.textContent = `${sym}••••••`;
    if (availEl) availEl.textContent = `${sym}••••••`;
    if (holdEl)  holdEl.textContent  = `${sym}••••••`;
    if (eyeIconEl) {
      eyeIconEl.innerHTML = `
        <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/>
        <line x1="1" y1="1" x2="23" y2="23"/>`;
    }
  } else {
    if (totalEl) totalEl.textContent = `${sym}${fmt(WALLET_DATA.totalBalance)}`;
    if (availEl) availEl.textContent = `${sym}${fmt(WALLET_DATA.availableFunds)}`;
    if (holdEl)  holdEl.textContent  = `${sym}${fmt(WALLET_DATA.onHoldFunds)}`;
    if (eyeIconEl) {
      eyeIconEl.innerHTML = `
        <path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"/>
        <circle cx="12" cy="12" r="3"/>`;
    }
  }

  const availLbl  = document.getElementById('wltAvailPctLabel');
  const heldLbl   = document.getElementById('wltHeldPctLabel');
  const total = Math.max(WALLET_DATA.totalBalance, WALLET_DATA.availableFunds + WALLET_DATA.onHoldFunds);
  if (availBar && heldBar) {
    if (total > 0) {
      const availPct = Math.max(0, Math.min(100, (WALLET_DATA.availableFunds / total) * 100));
      const heldPct  = Math.max(0, 100 - availPct);
      availBar.style.width = `${availPct.toFixed(1)}%`;
      heldBar.style.width  = `${heldPct.toFixed(1)}%`;
      if (availLbl) availLbl.textContent = `Available (${availPct.toFixed(0)}%)`;
      if (heldLbl)  heldLbl.textContent  = `Held (${heldPct.toFixed(0)}%)`;
    } else {
      availBar.style.width = '100%';
      heldBar.style.width  = '0%';
      if (availLbl) availLbl.textContent = 'Available';
      if (heldLbl)  heldLbl.textContent  = 'Held / In Review';
    }
  }
}

function renderVirtualCard() {
  const numEl  = document.getElementById('wltCardNumber');
  const currEl = document.getElementById('wltCardCurrencyBadge');
  const boxEl  = document.getElementById('wltVirtualCardBox');
  const pillEl = document.getElementById('wltCardStatusPill');
  const freezeBtn  = document.getElementById('wltFreezeCardBtn');
  const freezeText = document.getElementById('wltFreezeText');
  const revealText = document.getElementById('wltRevealText');

  if (numEl) {
    if (isCardRevealed && WALLET_DATA.rawAccount) {
      numEl.textContent = WALLET_DATA.rawAccount;
    } else {
      numEl.textContent = `•••• •••• •••• ${WALLET_DATA.last4}`;
    }
  }
  if (currEl) currEl.textContent = (WALLET_DATA.currency || 'EUR').toUpperCase();
  if (revealText) revealText.textContent = isCardRevealed ? 'Hide' : 'Reveal';

  if (boxEl) boxEl.classList.toggle('frozen', isCardFrozen);
  if (pillEl) pillEl.textContent = isCardFrozen ? 'FROZEN' : 'ACTIVE';
  if (freezeBtn) freezeBtn.classList.toggle('frozen', isCardFrozen);
  if (freezeText) freezeText.textContent = isCardFrozen ? 'Unfreeze' : 'Freeze';
}

async function loadFundingSources() {
  try {
    const res = await apiClient.get('/api/v1/transactions/funding-cards');
    linkedFundingCards = Array.isArray(res) ? res : (res?.data || []);
  } catch (err) {
    console.warn('[Wallet] Funding cards load notice:', err.message);
    linkedFundingCards = [];
  }
  if (linkedFundingCards.length && !selectedFundingCard) {
    selectedFundingCard = linkedFundingCards.find(c => c.isDefault) || linkedFundingCards[0];
  }
  renderFundingSources();
}

function renderFundingSources() {
  const listEl = document.getElementById('wltFundingList');
  if (!listEl) return;

  if (!linkedFundingCards.length) {
    listEl.innerHTML = `<div class="wlt-empty-funding">No external bank card linked yet. Link a card first to enable wallet top-ups.</div>`;
    return;
  }

  listEl.innerHTML = linkedFundingCards.map((card, idx) => `
    <div class="wlt-funding-item">
      <div class="wlt-funding-left">
        <span class="wlt-brand-pill">${card.brand || 'VISA'}</span>
        <div class="wlt-funding-meta">
          <strong>•••• ${card.last4}</strong>
          <span>Expires ${card.exp} · Linked Bank Card</span>
        </div>
      </div>
      <div class="wlt-funding-actions">
        <button type="button" class="wlt-funding-topup" data-idx="${idx}">+ Top Up</button>
        <button type="button" class="wlt-funding-unlink" data-id="${card.id}" title="Unlink card">✕</button>
      </div>
    </div>
  `).join('');

  listEl.querySelectorAll('.wlt-funding-topup').forEach(btn => {
    btn.addEventListener('click', () => {
      const idx = Number.parseInt(btn.dataset.idx, 10);
      selectedFundingCard = linkedFundingCards[idx] || linkedFundingCards[0];
      updateTopUpSourceUI();
      const modal = document.getElementById('topUpModal');
      if (modal) modal.style.display = 'flex';
    });
  });

  listEl.querySelectorAll('.wlt-funding-unlink').forEach(btn => {
    btn.addEventListener('click', async () => {
      const id = btn.dataset.id;
      if (!id) return;
      try {
        await apiClient.delete(`/api/v1/transactions/funding-cards/${id}`);
        if (selectedFundingCard?.id === id) selectedFundingCard = null;
        await loadFundingSources();
        showToast('Funding card removed');
      } catch (err) {
        showToast(err.message || 'Unable to unlink card');
      }
    });
  });
}

function updateTopUpSourceUI() {
  const card = selectedFundingCard || linkedFundingCards[0];
  const brandEl = document.getElementById('wltModalCardBrand');
  const last4El = document.getElementById('wltModalCardLast4');
  if (card) {
    if (brandEl) brandEl.textContent = card.brand || 'VISA';
    if (last4El) last4El.textContent = card.last4 || '••••';
  }
}

// ── Load Profile & Wallet from Backend ───────────────────────────────────────
async function loadWallet() {
  try {
    const [walletRes, profileRes] = await Promise.allSettled([
      apiClient.get('/api/v1/transactions/wallet'),
      apiClient.get('/api/v1/customers/me')
    ]);

    const wallet = walletRes.status === 'fulfilled' ? (walletRes.value?.data || walletRes.value) : null;
    const profile = profileRes.status === 'fulfilled' ? (profileRes.value?.data || profileRes.value) : null;

    if (profile) {
      const fullName = profile.fullName || user?.fullName || 'Customer';
      const holderLbl = document.getElementById('wltHolderLabel');
      const cardHolder = document.getElementById('wltCardHolder');
      const statusBadge = document.getElementById('wltStatusBadge');
      if (holderLbl) holderLbl.textContent = `Account Holder: ${fullName}`;
      if (cardHolder) cardHolder.textContent = fullName.toUpperCase();
      if (statusBadge && profile.status) {
        statusBadge.innerHTML = `<span class="dot"></span> ${profile.status}`;
      }
    }

    if (wallet) {
      WALLET_DATA.totalBalance   = parseFloat(wallet.totalBalance ?? wallet.balance ?? 0);
      WALLET_DATA.onHoldFunds    = parseFloat(wallet.heldBalance ?? wallet.onHoldAmount ?? 0);
      WALLET_DATA.availableFunds = parseFloat(wallet.availableBalance ?? (WALLET_DATA.totalBalance - WALLET_DATA.onHoldFunds));
      WALLET_DATA.currency       = wallet.currency || 'EUR';
      isCardFrozen               = Boolean(wallet.cardFrozen);

      const acct = wallet.accountNumber || profile?.accountNumber || '';
      if (acct) {
        WALLET_DATA.rawAccount = acct;
        WALLET_DATA.last4 = acct.replace(/-/g, '').slice(-4) || '0000';
      }
    }
  } catch (err) {
    console.warn('[Wallet] Live wallet synchronization:', err.message);
  }

  const acctEl = document.getElementById('wltAccountNum');
  if (acctEl) acctEl.textContent = WALLET_DATA.rawAccount || '—';

  const currSelect = document.getElementById('wltCurrencySelect');
  if (currSelect && WALLET_DATA.currency) {
    currSelect.value = WALLET_DATA.currency.toUpperCase();
  }

  renderAllocation();
  renderVirtualCard();
}

// ── Load Real Cash Flow & Ledger ─────────────────────────────────────────────
function isDepositTx(tx) {
  const type = (tx.type || '').toUpperCase();
  const cat = (tx.category || '').toUpperCase();
  const name = (tx.merchantName || tx.description || '').toLowerCase();
  return type === 'DEPOSIT' || cat === 'DEPOSIT' || name.includes('deposit') || name.includes('top-up');
}

function isHeldTx(tx) {
  const s = (tx.status || '').toUpperCase();
  return s === 'PENDING' || s === 'REVIEW' || s === 'UNDER_REVIEW'
      || s === 'FLAGGED' || s === 'HOLD' || s === 'PENDING_REVIEW';
}

const USD_RATES = {
  USD: 1.0,
  EUR: 0.9259,
  MAD: 10.0,
  GBP: 0.7937
};

function convertFx(amount, fromCurr, toCurr) {
  const from = (fromCurr || 'EUR').toUpperCase();
  const to = (toCurr || 'EUR').toUpperCase();
  if (from === to) return amount;
  const fromRate = USD_RATES[from] || 1.0;
  const toRate = USD_RATES[to] || 1.0;
  return (amount / fromRate) * toRate;
}

function computeCashFlow(items) {
  const targetCurr = (WALLET_DATA.currency || 'EUR').toUpperCase();
  const sym = getCurrencySymbol(targetCurr);
  let inflows = 0, inflowCount = 0;
  let outflows = 0, outflowCount = 0;

  items.forEach(tx => {
    const rawAmt = Math.abs(Number(tx.amount || 0));
    const amt = convertFx(rawAmt, tx.currency || targetCurr, targetCurr);
    if (isDepositTx(tx)) {
      inflows += amt;
      inflowCount += 1;
    } else {
      outflows += amt;
      outflowCount += 1;
    }
  });

  const net = inflows - outflows;
  const inEl  = document.getElementById('wltTotalInflows');
  const outEl = document.getElementById('wltTotalOutflows');
  const netEl = document.getElementById('wltNetFlow');
  const inSub = document.getElementById('wltInflowCount');
  const outSub = document.getElementById('wltOutflowCount');

  if (inEl)  inEl.textContent = `+ ${sym}${fmt(inflows)}`;
  if (outEl) outEl.textContent = `- ${sym}${fmt(outflows)}`;
  if (netEl) {
    netEl.textContent = `${net >= 0 ? '+' : '-'} ${sym}${fmt(Math.abs(net))}`;
    netEl.className = `wlt-cf-val font-mono ${net >= 0 ? 'positive' : ''}`;
  }
  if (inSub)  inSub.textContent = `${inflowCount} deposit${inflowCount === 1 ? '' : 's'}`;
  if (outSub) outSub.textContent = `${outflowCount} debit${outflowCount === 1 ? '' : 's'}`;
}

function renderLedgerTable() {
  const tbody = document.getElementById('wltRecentTxBody');
  if (!tbody) return;

  let filtered = allTransactions;
  if (currentFilter === 'deposits') filtered = allTransactions.filter(isDepositTx);
  if (currentFilter === 'held')     filtered = allTransactions.filter(isHeldTx);

  if (!filtered.length) {
    const msg = currentFilter === 'held'
      ? 'No held or pending transactions.'
      : (currentFilter === 'deposits' ? 'No deposits recorded yet.' : 'No transactions recorded yet.');
    tbody.innerHTML = `<tr><td colspan="5" style="text-align:center; padding: 28px; color: #94a3b8; font-size: 13px;">${msg}</td></tr>`;
    return;
  }

  tbody.innerHTML = filtered.slice(0, 12).map(tx => {
    const isDep = isDepositTx(tx);
    const type = (tx.type || 'PAYMENT').toUpperCase();
    const sym = getCurrencySymbol(tx.currency || WALLET_DATA.currency);
    const amt = fmt(Math.abs(Number(tx.amount || 0)));
    const dateStr = new Date(tx.timestamp || tx.createdAt || Date.now()).toLocaleDateString('en-US', {
      month: 'short', day: 'numeric', year: 'numeric'
    });

    const rawTitle = isDep
      ? (tx.merchantName || 'Card Top-Up')
      : (tx.merchantName || tx.recipientName || tx.transferReference || tx.description || 'Transaction');
    const lower = rawTitle.toLowerCase();
    const catRaw = (tx.category || '').toUpperCase();

    let brandKey = 'payment';
    if (isDep) brandKey = 'deposit';
    else if (type === 'TRANSFER') brandKey = 'transfer';
    else if (lower.includes('bolt') || catRaw === 'TRAVEL') brandKey = 'bolt';
    else if (lower.includes('zara')) brandKey = 'zara';
    else if (lower.includes('zalando')) brandKey = 'zalando';
    else if (lower.includes('fnac')) brandKey = 'fnac';
    else if (lower.includes('deliveroo') || lower.includes('uber eats')) brandKey = 'deliveroo';
    else if (lower.includes('carrefour') || lower.includes('monoprix') || catRaw === 'GROCERY') brandKey = 'grocery';
    else if (lower.includes('ciné') || lower.includes('cinema') || catRaw === 'ENTERTAINMENT') brandKey = 'entertainment';
    else if (lower.includes('netflix')) brandKey = 'netflix';
    else if (lower.includes('amazon')) brandKey = 'amazon';
    else if (lower.includes('apple')) brandKey = 'apple';
    else if (lower.includes('spotify')) brandKey = 'spotify';

    let iconClass = 'payment';
    if (isDep) iconClass = 'deposit';
    else if (type === 'TRANSFER') iconClass = 'transfer';
    else if (catRaw.includes('TRAVEL') || catRaw.includes('TRANS')) iconClass = 'travel';
    else if (catRaw.includes('CLOTH') || catRaw.includes('SHOP')) iconClass = 'shopping';
    else if (catRaw.includes('GROCER') || catRaw.includes('FOOD')) iconClass = 'grocery';

    let avatarHtml = '';
    if (brandKey === 'deposit') {
      avatarHtml = `<div class="wlt-merchant-icon wlt-brand-deposit"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M12 5v14M5 12l7 7 7-7"/></svg></div>`;
    } else if (brandKey === 'bolt') {
      avatarHtml = `<div class="wlt-merchant-icon wlt-brand-bolt"><svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M13 2L3 14h7v8l11-14h-8l1-6z"/></svg></div>`;
    } else if (brandKey === 'zara') {
      avatarHtml = `<div class="wlt-merchant-icon wlt-brand-zara"><span>ZARA</span></div>`;
    } else if (brandKey === 'zalando') {
      avatarHtml = `<div class="wlt-merchant-icon wlt-brand-zalando"><svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2L2 9.5 5.5 22h13L22 9.5 12 2zm0 3.2l6.8 5.1L16.2 19.5H7.8L5.2 10.3 12 5.2z"/></svg></div>`;
    } else if (brandKey === 'fnac') {
      avatarHtml = `<div class="wlt-merchant-icon wlt-brand-fnac"><span>fnac</span></div>`;
    } else if (brandKey === 'deliveroo') {
      avatarHtml = `<div class="wlt-merchant-icon wlt-brand-deliveroo"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M18 2v6a3 3 0 0 1-3 3 3 3 0 0 1-3-3V2"/><path d="M15 2v10"/><path d="M12 2v20"/><path d="M6 2v20"/><path d="M6 7h4"/></svg></div>`;
    } else if (brandKey === 'grocery') {
      avatarHtml = `<div class="wlt-merchant-icon wlt-brand-grocery"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><circle cx="8" cy="21" r="1"/><circle cx="19" cy="21" r="1"/><path d="M2.05 2.05h2l2.66 12.42a2 2 0 0 0 2 1.58h9.78a2 2 0 0 0 1.95-1.57l1.65-7.43H5.12"/></svg></div>`;
    } else if (brandKey === 'entertainment') {
      avatarHtml = `<div class="wlt-merchant-icon wlt-brand-entertainment"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><rect x="2" y="3" width="20" height="18" rx="2"/><path d="M7 3v18M17 3v18M2 8h5M2 13h5M2 18h5M17 8h5M17 13h5M17 18h5"/></svg></div>`;
    } else if (brandKey === 'transfer') {
      avatarHtml = `<div class="wlt-merchant-icon wlt-brand-transfer"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M7 16V4M7 4L3 8M7 4L11 8M17 8V20M17 20L21 16M17 20L13 16"/></svg></div>`;
    } else if (brandKey === 'netflix') {
      avatarHtml = `<div class="wlt-merchant-icon wlt-brand-netflix"><span>N</span></div>`;
    } else if (brandKey === 'amazon') {
      avatarHtml = `<div class="wlt-merchant-icon wlt-brand-amazon"><span>a</span></div>`;
    } else if (brandKey === 'apple') {
      avatarHtml = `<div class="wlt-merchant-icon wlt-brand-apple"><svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><path d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.81-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M15.97 6.84c.64-.78 1.08-1.86.96-2.84-.93.04-2.06.62-2.73 1.4-.59.68-1.11 1.77-.97 2.81 1.04.08 2.1-.59 2.74-1.37z"/></svg></div>`;
    } else if (brandKey === 'spotify') {
      avatarHtml = `<div class="wlt-merchant-icon wlt-brand-spotify"><svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.477 2 2 6.477 2 12s4.477 10 10 10 10-4.477 10-10S17.523 2 12 2zm4.586 14.424a.623.623 0 0 1-.857.207c-2.348-1.435-5.304-1.76-8.786-.964a.625.625 0 0 1-.28-1.218c3.808-.87 7.076-.496 9.716 1.118a.623.623 0 0 1 .207.857zm1.224-2.719a.78.78 0 0 1-1.072.257c-2.687-1.652-6.785-2.131-9.965-1.166a.78.78 0 0 1-.468-1.488c3.632-1.102 8.147-.568 11.248 1.325a.78.78 0 0 1 .257 1.072zm.105-2.835C14.692 8.95 9.375 8.775 6.297 9.71a.936.936 0 1 1-.546-1.79c3.536-1.074 9.404-.868 13.18 1.374a.936.936 0 1 1-.94 1.616l-.076-.04z"/></svg></div>`;
    } else {
      const cleanInitial = rawTitle.replace(/[^\p{L}\p{N}]/gu, '').charAt(0).toUpperCase() || 'T';
      avatarHtml = `<div class="wlt-merchant-icon ${iconClass}">${cleanInitial}</div>`;
    }

    const statusRaw = (tx.status || 'APPROVED').toUpperCase();
    let badgeClass = 'green';
    let statusLabel = 'Approved';
    if (isHeldTx(tx)) {
      badgeClass = 'amber';
      statusLabel = 'Under review';
    } else if (statusRaw === 'DECLINED' || statusRaw === 'REJECTED' || statusRaw === 'FAILED') {
      badgeClass = 'red';
      statusLabel = 'Declined';
    }

    const catLabel = isDep ? 'Deposit' : (type === 'TRANSFER' ? 'Transfer' : (tx.category || 'Payment').toLowerCase());

    return `
      <tr>
        <td>
          <div class="wlt-merchant">
            ${avatarHtml}
            <span title="${rawTitle}">${rawTitle}</span>
          </div>
        </td>
        <td class="wlt-date-cell">${dateStr}</td>
        <td><span class="wlt-cat-pill ${iconClass}">${catLabel}</span></td>
        <td class="wlt-amount-cell font-mono ${isDep ? 'positive' : ''}">${isDep ? '+' : '-'} ${sym}${amt}</td>
        <td><span class="pg-badge ${badgeClass}"><span class="dot"></span>${statusLabel}</span></td>
      </tr>
    `;
  }).join('');
}

async function loadLedger() {
  try {
    const res = await apiClient.get('/api/v1/transactions?size=100');
    const items = Array.isArray(res) ? res : (res?.content || res?.data || []);
    allTransactions = items;
    computeCashFlow(allTransactions);
    renderLedgerTable();
  } catch (err) {
    console.warn('[Wallet] Ledger load notice:', err.message);
    allTransactions = [];
    renderLedgerTable();
  }
}

// ── Modals & Controls ────────────────────────────────────────────────────────
function initControls() {
  document.getElementById('wltEyeBtn')?.addEventListener('click', () => {
    isBalanceMasked = !isBalanceMasked;
    renderAllocation();
  });

  const currSelect = document.getElementById('wltCurrencySelect');
  currSelect?.addEventListener('change', async (e) => {
    const targetCurrency = e.target.value;
    if (!targetCurrency || targetCurrency === WALLET_DATA.currency) return;
    currSelect.disabled = true;
    try {
      await apiClient.patch('/api/v1/transactions/wallet/currency', { currency: targetCurrency });
      await Promise.all([loadWallet(), loadLedger()]);
      showToast(`Wallet currency switched to ${targetCurrency}`);
    } catch (err) {
      showToast(err.message || 'Unable to switch wallet currency');
      currSelect.value = WALLET_DATA.currency;
    } finally {
      currSelect.disabled = false;
    }
  });

  document.getElementById('wltToggleCardDetailsBtn')?.addEventListener('click', () => {
    isCardRevealed = !isCardRevealed;
    renderVirtualCard();
  });

  const freezeBtn = document.getElementById('wltFreezeCardBtn');
  freezeBtn?.addEventListener('click', async () => {
    const nextFrozen = !isCardFrozen;
    freezeBtn.disabled = true;
    try {
      const updated = await apiClient.patch('/api/v1/transactions/wallet/freeze', { frozen: nextFrozen });
      isCardFrozen = Boolean(updated?.cardFrozen ?? nextFrozen);
      renderVirtualCard();
      showToast(isCardFrozen ? 'Virtual card frozen — outgoing payments & transfers paused' : 'Virtual card unfrozen');
    } catch (err) {
      showToast(err.message || 'Unable to update virtual card status');
    } finally {
      freezeBtn.disabled = false;
    }
  });

  document.querySelectorAll('#wltLedgerTabs .wlt-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('#wltLedgerTabs .wlt-tab').forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      currentFilter = tab.dataset.filter || 'all';
      renderLedgerTable();
    });
  });

  const copyBtn = document.getElementById('walletCopyBtn');
  copyBtn?.addEventListener('click', () => {
    if (!WALLET_DATA.rawAccount) return;
    navigator.clipboard.writeText(WALLET_DATA.rawAccount).catch(() => {});
    showToast('Account number copied to clipboard');
    copyBtn.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2.5" width="13" height="13"><polyline points="20 6 9 17 4 12"/></svg>`;
    setTimeout(() => {
      copyBtn.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="13" height="13"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>`;
    }, 2000);
  });

  // Top Up Modal (requires at least one linked external funding card)
  const topUpModal = document.getElementById('topUpModal');
  const linkModal  = document.getElementById('wltLinkCardModal');
  const openLink   = () => { if (linkModal) linkModal.style.display = 'flex'; };
  const closeLink  = () => { if (linkModal) linkModal.style.display = 'none'; };

  const openTopUp  = () => {
    if (!linkedFundingCards.length) {
      showToast('Please link a bank card first');
      openLink();
      return;
    }
    if (!selectedFundingCard) selectedFundingCard = linkedFundingCards[0];
    updateTopUpSourceUI();
    if (topUpModal) topUpModal.style.display = 'flex';
  };
  const closeTopUp = () => { if (topUpModal) topUpModal.style.display = 'none'; };

  document.querySelectorAll('.wlt-preset-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.wlt-preset-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      const input = document.getElementById('topUpAmount');
      if (input) input.value = `${btn.dataset.amt}.00`;
    });
  });

  document.getElementById('walletTopUpBtn')?.addEventListener('click', openTopUp);
  document.getElementById('closeTopUpModal')?.addEventListener('click', closeTopUp);
  document.getElementById('cancelTopUp')?.addEventListener('click', closeTopUp);
  topUpModal?.addEventListener('click', (e) => { if (e.target === topUpModal) closeTopUp(); });

  const submitTopUp = document.getElementById('submitTopUp');
  submitTopUp?.addEventListener('click', async () => {
    if (!linkedFundingCards.length) {
      closeTopUp();
      openLink();
      return;
    }
    const card = selectedFundingCard || linkedFundingCards[0];
    const amountInput = document.getElementById('topUpAmount');
    const amount = parseFloat(amountInput?.value) || 0;
    if (amount <= 0) { amountInput?.focus(); return; }
    submitTopUp.disabled = true;
    try {
      await apiClient.post('/api/v1/transactions/topup', {
        amount,
        currency: WALLET_DATA.currency || 'EUR',
        cardBrand: card.brand,
        cardLast4: card.last4
      });
      await Promise.all([loadWallet(), loadLedger()]);
      closeTopUp();
      showToast(`Deposited ${getCurrencySymbol(WALLET_DATA.currency)}${fmt(amount)} from ${card.brand} •••• ${card.last4}`);
    } catch (err) {
      console.warn('[Wallet] Top-up notice:', err.message);
      showToast(err.message || 'Top-up failed. Please ensure a funding card is linked.');
    } finally {
      submitTopUp.disabled = false;
    }
  });

  // Link Card Modal (persisted in backend DB)
  document.getElementById('wltOpenLinkCardBtn')?.addEventListener('click', openLink);
  document.getElementById('wltCloseLinkCardModal')?.addEventListener('click', closeLink);
  document.getElementById('wltCancelLinkCard')?.addEventListener('click', closeLink);
  linkModal?.addEventListener('click', (e) => { if (e.target === linkModal) closeLink(); });

  const submitLinkBtn = document.getElementById('wltSubmitLinkCard');
  submitLinkBtn?.addEventListener('click', async () => {
    const num = (document.getElementById('wltNewCardNumber')?.value || '').replace(/\D/g, '');
    if (num.length < 13) return;
    const exp = document.getElementById('wltNewCardExp')?.value || '12/28';
    submitLinkBtn.disabled = true;
    try {
      const created = await apiClient.post('/api/v1/transactions/funding-cards', {
        cardNumber: num,
        expiry: exp
      });
      selectedFundingCard = created;
      await loadFundingSources();
      closeLink();
    } catch (err) {
      alert(err.message || 'Unable to link card');
    } finally {
      submitLinkBtn.disabled = false;
    }
  });
}

// ── Init ─────────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
  initControls();
  void loadFundingSources();
  void loadWallet();
  void loadLedger();
});
