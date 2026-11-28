/**
 * Vigilia Platform — Transactions Page Controller
 * transactions.js — 100% wired to backend APIs (no hardcoded or mocked data)
 */

import { apiClient } from '../../js/api-client.js';
import { VigiliaAuth } from '../../js/auth.js';
import '../../js/components/customer/sidebar.js';
import '../../js/components/customer/top-navbar.js';

const user = VigiliaAuth.getUser();
if (!user || (user.role && user.role !== 'CUSTOMER')) {
  window.location.replace('/login.html');
}

const PAGE_SIZE = 8;

const state = {
  currency: 'EUR',
  typeFilter: 'all',
  statusFilter: 'all',
  dateFilter: 'all',
  categoryFilter: 'all',
  sortBy: 'newest',
  search: '',
  page: 1,
  loading: true,
  error: null,
  transactions: [],
  beneficiariesById: new Map(),
  expandedTxKey: null,
};

const fmt = (n) => Number(n || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

function getCurrencySymbol(curr) {
  const c = (curr || 'EUR').toUpperCase();
  if (c === 'EUR') return '€';
  if (c === 'GBP') return '£';
  if (c === 'MAD') return 'MAD ';
  return '$';
}

function escapeHtml(str) {
  return String(str ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function normalizeType(tx) {
  const rawType = (tx.type || 'PAYMENT').toUpperCase();
  const rawCat = (tx.category || '').toUpperCase();
  const merch = (tx.merchantName || '').toLowerCase();

  if (rawType === 'DEPOSIT' || rawType === 'TOPUP' || rawType === 'TOP_UP' || rawType === 'CREDIT' || rawCat === 'DEPOSIT' || merch.includes('deposit')) {
    return 'deposit';
  }
  if (rawType === 'TRANSFER' || rawType === 'P2P' || rawCat === 'TRANSFER') {
    return 'transfer';
  }
  return 'payment';
}

function normalizeStatus(tx) {
  const raw = (tx.status || 'APPROVED').toUpperCase();
  if (raw === 'PENDING_REVIEW' || raw === 'REVIEW' || raw === 'PENDING' || raw === 'HELD' || raw === 'UNDER_REVIEW' || raw === 'FLAGGED') {
    return 'review';
  }
  if (raw === 'DECLINED' || raw === 'REJECTED' || raw === 'BLOCKED' || raw === 'FAILED') {
    return 'declined';
  }
  return 'approved';
}

function isCreditTx(tx) {
  const typeKey = normalizeType(tx);
  const rawType = (tx.type || '').toUpperCase();
  return typeKey === 'deposit' || (Number(tx.amount || 0) < 0 && rawType === 'REFUND');
}

function getTitle(tx) {
  const typeKey = normalizeType(tx);
  if (typeKey === 'deposit') {
    return tx.merchantName || tx.senderName || 'Wallet Deposit';
  }
  if (typeKey === 'transfer') {
    if (tx.recipientCustomerId && state.beneficiariesById.has(tx.recipientCustomerId)) {
      const b = state.beneficiariesById.get(tx.recipientCustomerId);
      return b.nickname || b.recipientName || tx.recipientName || 'Transfer';
    }
    return tx.recipientName || tx.merchantName || tx.transferReference || 'Transfer';
  }
  return tx.merchantName || tx.productName || tx.description || 'Payment';
}

function getCategoryLabel(tx) {
  const typeKey = normalizeType(tx);
  if (tx.category) return tx.category.replace(/_/g, ' ');
  if (typeKey === 'transfer') return 'Transfer';
  if (typeKey === 'deposit') return 'Deposit';
  return 'Payment';
}

function getMethodLabel(tx) {
  if (tx.paymentMethod) return tx.paymentMethod;
  if (tx.cardLast4) return `Card ••${tx.cardLast4}`;
  if (tx.channel) return tx.channel;
  const typeKey = normalizeType(tx);
  if (typeKey === 'transfer') return 'Wallet Balance';
  if (typeKey === 'deposit') return 'Funding Source';
  return 'Vigilia Wallet';
}

function getReference(tx) {
  return tx.reference || tx.transferReference || tx.id || '—';
}

function getShortRef(tx) {
  const ref = getReference(tx);
  if (ref.length > 14 && ref.includes('-')) {
    return ref.slice(0, 8).toUpperCase();
  }
  return ref;
}

function getIconHtml(tx) {
  const title = getTitle(tx);
  const lower = title.toLowerCase();
  const type = normalizeType(tx);
  const catRaw = (tx.category || '').toUpperCase();

  if (type === 'deposit') {
    return `<div class="tx-icon deposit"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M12 5v14M5 12l7 7 7-7"/></svg></div>`;
  }
  if (lower.includes('bolt') || catRaw === 'TRAVEL') {
    return `<div class="tx-icon bolt"><svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M13 2L3 14h7v8l11-14h-8l1-6z"/></svg></div>`;
  }
  if (lower.includes('zara')) {
    return `<div class="tx-icon zara"><span>ZARA</span></div>`;
  }
  if (lower.includes('zalando')) {
    return `<div class="tx-icon zalando"><svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2L2 9.5 5.5 22h13L22 9.5 12 2zm0 3.2l6.8 5.1L16.2 19.5H7.8L5.2 10.3 12 5.2z"/></svg></div>`;
  }
  if (lower.includes('fnac')) {
    return `<div class="tx-icon fnac"><span>fnac</span></div>`;
  }
  if (lower.includes('deliveroo') || lower.includes('uber eats')) {
    return `<div class="tx-icon deliveroo"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M18 2v6a3 3 0 0 1-3 3 3 3 0 0 1-3-3V2"/><path d="M15 2v10"/><path d="M12 2v20"/><path d="M6 2v20"/><path d="M6 7h4"/></svg></div>`;
  }
  if (lower.includes('carrefour') || lower.includes('monoprix') || catRaw === 'GROCERY') {
    return `<div class="tx-icon grocery"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><circle cx="8" cy="21" r="1"/><circle cx="19" cy="21" r="1"/><path d="M2.05 2.05h2l2.66 12.42a2 2 0 0 0 2 1.58h9.78a2 2 0 0 0 1.95-1.57l1.65-7.43H5.12"/></svg></div>`;
  }
  if (lower.includes('ciné') || lower.includes('cinema') || catRaw === 'ENTERTAINMENT') {
    return `<div class="tx-icon entertainment"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><rect x="2" y="3" width="20" height="18" rx="2"/><path d="M7 3v18M17 3v18M2 8h5M2 13h5M2 18h5M17 8h5M17 13h5M17 18h5"/></svg></div>`;
  }
  if (lower.includes('netflix')) {
    return `<div class="tx-icon netflix"><span>N</span></div>`;
  }
  if (lower.includes('amazon')) {
    return `<div class="tx-icon amazon"><span>a</span></div>`;
  }
  if (lower.includes('apple')) {
    return `<div class="tx-icon apple"><svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><path d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.81-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M15.97 6.84c.64-.78 1.08-1.86.96-2.84-.93.04-2.06.62-2.73 1.4-.59.68-1.11 1.77-.97 2.81 1.04.08 2.1-.59 2.74-1.37z"/></svg></div>`;
  }
  if (lower.includes('spotify')) {
    return `<div class="tx-icon spotify"><svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.477 2 2 6.477 2 12s4.477 10 10 10 10-4.477 10-10S17.523 2 12 2zm4.586 14.424a.623.623 0 0 1-.857.207c-2.348-1.435-5.304-1.76-8.786-.964a.625.625 0 0 1-.28-1.218c3.808-.87 7.076-.496 9.716 1.118a.623.623 0 0 1 .207.857zm1.224-2.719a.78.78 0 0 1-1.072.257c-2.687-1.652-6.785-2.131-9.965-1.166a.78.78 0 0 1-.468-1.488c3.632-1.102 8.147-.568 11.248 1.325a.78.78 0 0 1 .257 1.072zm.105-2.835C14.692 8.95 9.375 8.775 6.297 9.71a.936.936 0 1 1-.546-1.79c3.536-1.074 9.404-.868 13.18 1.374a.936.936 0 1 1-.94 1.616l-.076-.04z"/></svg></div>`;
  }
  if (type === 'transfer') {
    const initials = title.split(/\s+/).filter(Boolean).slice(0, 2).map(w => w[0].toUpperCase()).join('');
    return initials.length === 2
      ? `<div class="tx-icon user">${escapeHtml(initials)}</div>`
      : `<div class="tx-icon transfer"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M7 16V4M7 4L3 8M7 4L11 8M17 8V20M17 20L21 16M17 20L13 16"/></svg></div>`;
  }
  const initial = title.replace(/[^\p{L}\p{N}]/gu, '').charAt(0).toUpperCase() || 'M';
  return `<div class="tx-icon merchant">${escapeHtml(initial)}</div>`;
}

function getStatusBadgeHtml(statusKey) {
  if (statusKey === 'review') {
    return `<span class="pg-badge amber"><span class="dot"></span>Under review</span>`;
  }
  if (statusKey === 'declined') {
    return `<span class="pg-badge red"><span class="dot"></span>Declined</span>`;
  }
  return `<span class="pg-badge green"><span class="dot"></span>Approved</span>`;
}

function getFilteredTransactions() {
  const q = state.search.trim().toLowerCase();
  const now = Date.now();
  const maxDays = state.dateFilter === 'all' ? null : Number(state.dateFilter);

  const list = state.transactions.filter(tx => {
    const typeKey = normalizeType(tx);
    const statusKey = normalizeStatus(tx);
    const catLabel = getCategoryLabel(tx);
    const catBucket = bucketCategory(tx);

    if (state.typeFilter !== 'all' && typeKey !== state.typeFilter) return false;
    if (state.statusFilter !== 'all' && statusKey !== state.statusFilter) return false;
    if (state.categoryFilter !== 'all' && catBucket.toLowerCase() !== state.categoryFilter.toLowerCase()) return false;

    if (maxDays) {
      const ts = new Date(tx.timestamp || tx.createdAt || now).getTime();
      if (now - ts > maxDays * 86400000) return false;
    }

    if (q) {
      const haystack = [
        getTitle(tx),
        getReference(tx),
        catLabel,
        getMethodLabel(tx),
        tx.memo,
        tx.productName,
        typeKey,
        statusKey,
      ].filter(Boolean).join(' ').toLowerCase();
      if (!haystack.includes(q)) return false;
    }

    return true;
  });

  return list.sort((a, b) => {
    if (state.sortBy === 'oldest') {
      return new Date(a.timestamp || a.createdAt || 0) - new Date(b.timestamp || b.createdAt || 0);
    }
    if (state.sortBy === 'highest') {
      return Math.abs(Number(b.amount || 0)) - Math.abs(Number(a.amount || 0));
    }
    if (state.sortBy === 'lowest') {
      return Math.abs(Number(a.amount || 0)) - Math.abs(Number(b.amount || 0));
    }
    return new Date(b.timestamp || b.createdAt || 0) - new Date(a.timestamp || a.createdAt || 0);
  });
}

function updateTabCounts() {
  const counts = { all: state.transactions.length, payment: 0, transfer: 0, deposit: 0 };
  state.transactions.forEach(tx => {
    counts[normalizeType(tx)]++;
  });
  document.getElementById('countAll').textContent = counts.all;
  document.getElementById('countPayment').textContent = counts.payment;
  document.getElementById('countTransfer').textContent = counts.transfer;
  document.getElementById('countDeposit').textContent = counts.deposit;
}

function renderSparkline(svgId, series, strokeColor, gradId) {
  const svg = document.getElementById(svgId);
  if (!svg) return;
  const vals = series.length >= 2 ? series : [0, ...series, 0];
  const max = Math.max(...vals, 1);
  const w = 88;
  const h = 42;
  const padTop = 6;
  const padBottom = 3;
  const usableH = h - padTop - padBottom;

  const pts = vals.map((v, i) => {
    const x = (i / (vals.length - 1)) * w;
    const y = h - padBottom - (v / max) * usableH;
    return [Number(x.toFixed(1)), Number(y.toFixed(1))];
  });

  const lineD = pts.map((p, i) => `${i === 0 ? 'M' : 'L'}${p[0]},${p[1]}`).join(' ');
  const areaD = `${lineD} L${w},${h} L0,${h} Z`;

  svg.innerHTML = `
    <defs>
      <linearGradient id="${gradId}" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="${strokeColor}" stop-opacity="0.22"/>
        <stop offset="100%" stop-color="${strokeColor}" stop-opacity="0.0"/>
      </linearGradient>
    </defs>
    <path d="${areaD}" fill="url(#${gradId})"/>
    <path d="${lineD}" fill="none" stroke="${strokeColor}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
  `;
}

function updateKpis(filtered) {
  const sym = getCurrencySymbol(state.currency);
  let spent = 0;
  let received = 0;
  let pending = 0;

  filtered.forEach(tx => {
    const amt = Math.abs(Number(tx.amount || 0));
    const statusKey = normalizeStatus(tx);
    if (statusKey === 'review') pending++;
    if (statusKey === 'declined') return;
    if (isCreditTx(tx)) received += amt;
    else spent += amt;
  });

  document.getElementById('kpiSpent').textContent = `${sym}${fmt(spent)}`;
  document.getElementById('kpiReceived').textContent = `${sym}${fmt(received)}`;
  document.getElementById('kpiCount').textContent = String(filtered.length);
  document.getElementById('kpiPending').textContent = String(pending);

  // Build real 10-bucket chronological series from filtered transactions for sparklines
  const buckets = 10;
  const spentSeries = Array(buckets).fill(0);
  const recSeries = Array(buckets).fill(0);
  const countSeries = Array(buckets).fill(0);
  const pendingSeries = Array(buckets).fill(0);

  const ordered = [...filtered].reverse(); // oldest -> newest
  ordered.forEach((tx, idx) => {
    const b = Math.min(buckets - 1, Math.floor((idx / Math.max(ordered.length, 1)) * buckets));
    const amt = Math.abs(Number(tx.amount || 0));
    const st = normalizeStatus(tx);
    countSeries[b] += 1;
    if (st === 'review') pendingSeries[b] += 1;
    if (st !== 'declined') {
      if (isCreditTx(tx)) recSeries[b] += amt;
      else spentSeries[b] += amt;
    }
  });

  renderSparkline('sparkSpent', spentSeries, '#ea6c3a', 'gradSpent');
  renderSparkline('sparkReceived', recSeries, '#10b981', 'gradReceived');
  renderSparkline('sparkCount', countSeries, '#4f46e5', 'gradCount');
  renderSparkline('sparkPending', pendingSeries, '#f59e0b', 'gradPending');

  // Real subtext (month-over-month when available, otherwise real counts)
  const now = new Date();
  const thisMonthStart = new Date(now.getFullYear(), now.getMonth(), 1).getTime();
  const prevMonthStart = new Date(now.getFullYear(), now.getMonth() - 1, 1).getTime();

  let curSpent = 0, prevSpent = 0, curRec = 0, prevRec = 0, curCnt = 0, prevCnt = 0;
  state.transactions.forEach(tx => {
    const ts = new Date(tx.timestamp || tx.createdAt || 0).getTime();
    const amt = Math.abs(Number(tx.amount || 0));
    const st = normalizeStatus(tx);
    if (ts >= thisMonthStart) {
      curCnt++;
      if (st !== 'declined') {
        if (isCreditTx(tx)) curRec += amt;
        else curSpent += amt;
      }
    } else if (ts >= prevMonthStart && ts < thisMonthStart) {
      prevCnt++;
      if (st !== 'declined') {
        if (isCreditTx(tx)) prevRec += amt;
        else prevSpent += amt;
      }
    }
  });

  const setTrendSub = (elId, cur, prev, fallback) => {
    const el = document.getElementById(elId);
    if (!el) return;
    if (prev > 0) {
      const pct = Math.round(((cur - prev) / prev) * 100);
      const arrow = pct >= 0 ? '↑' : '↓';
      el.textContent = `${arrow} ${pct >= 0 ? '+' : ''}${pct}% vs last month`;
      el.className = `tx-kpi-sub ${pct >= 0 ? 'up' : 'down'}`;
    } else {
      el.textContent = fallback;
      el.className = 'tx-kpi-sub';
    }
  };

  setTrendSub('kpiSpentSub', curSpent, prevSpent, `${filtered.filter(t => !isCreditTx(t)).length} outgoing debits`);
  setTrendSub('kpiReceivedSub', curRec, prevRec, `${filtered.filter(t => isCreditTx(t)).length} incoming credits`);
  setTrendSub('kpiCountSub', curCnt, prevCnt, `${state.transactions.length} total on ledger`);

  const pendingSub = document.getElementById('kpiPendingSub');
  if (pendingSub) {
    pendingSub.textContent = pending > 0 ? 'Requires attention' : 'All clear · low risk';
    pendingSub.className = `tx-kpi-sub ${pending > 0 ? 'down' : 'up'}`;
  }
}

// Fixed bucket colours — must stay in sync with overview page
const CAT_BUCKETS = [
  { name: 'Shopping',      color: '#2563eb' },
  { name: 'Transfers',     color: '#c25e2e' },
  { name: 'Food & Dining', color: '#8b5cf6' },
  { name: 'Travel',        color: '#64748b' },
  { name: 'Other',         color: '#f59e0b' },
];

function bucketCategory(tx) {
  const rawType = (tx.type || '').toUpperCase();
  const rawCat  = (tx.category || '').toUpperCase();
  const title   = (tx.merchantName || tx.merchant || '').toLowerCase();
  const product = (tx.productName || tx.memo || '').toLowerCase();

  if (rawType === 'TRANSFER' || rawCat === 'TRANSFER') return 'Transfers';

  if (rawCat === 'TRAVEL' ||
      title.includes('bolt') || title.includes('flight') || title.includes('hotel') ||
      title.includes('sncf') || title.includes('travel') || title.includes('airbnb') ||
      title.includes('uber') || product.includes('ride') || product.includes('train')) {
    return 'Travel';
  }

  if (rawCat === 'FOOD_DELIVERY' || rawCat === 'GROCERY' || rawCat === 'RESTAURANT' || rawCat === 'DINING' ||
      title.includes('deliveroo') || title.includes('uber eats') ||
      title.includes('carrefour') || title.includes('monoprix') ||
      title.includes('food') || title.includes('burger') || title.includes('pizza') ||
      product.includes('burger') || product.includes('pizza') ||
      product.includes('courses') || product.includes('snacks')) {
    return 'Food & Dining';
  }

  if (rawCat === 'SHOPPING' || rawCat === 'CLOTHING' || rawCat === 'ELECTRONICS' || rawCat === 'RETAIL' ||
      title.includes('zara') || title.includes('zalando') || title.includes('fnac') ||
      title.includes('amazon') || title.includes('apple') ||
      product.includes('vêtements') || product.includes('casque') ||
      product.includes('audio') || product.includes('commande') || product.includes('sneakers')) {
    return 'Shopping';
  }

  return 'Other';
}

function updateSidebarInsights(filtered) {
  const sym = getCurrencySymbol(state.currency);

  // 1. Pending Review Card (computed from full ledger)
  const pendingTxs = state.transactions.filter(tx => normalizeStatus(tx) === 'review');
  const pendingSum = pendingTxs.reduce((acc, tx) => acc + Math.abs(Number(tx.amount || 0)), 0);
  const reviewCard = document.getElementById('txReviewCard');
  const reviewIco = document.getElementById('reviewCardIco');
  const reviewTitle = document.getElementById('reviewCardTitle');
  const reviewSub = document.getElementById('reviewCardSub');
  const reviewDesc = document.getElementById('reviewCardDesc');
  const viewPendingBtn = document.getElementById('viewPendingBtn');

  if (pendingTxs.length > 0) {
    reviewCard?.classList.add('has-pending');
    if (reviewIco) reviewIco.className = 'tx-side-ico amber';
    if (reviewTitle) reviewTitle.textContent = 'Pending Review';
    if (reviewSub) reviewSub.textContent = `${pendingTxs.length} transaction${pendingTxs.length === 1 ? '' : 's'} · ${sym}${fmt(pendingSum)}`;
    if (reviewDesc) reviewDesc.textContent = 'Flagged by AI Fraud Shield for verification before final settlement.';
    if (viewPendingBtn) {
      viewPendingBtn.hidden = false;
      viewPendingBtn.textContent = state.statusFilter === 'review' ? 'Showing Pending' : 'View Pending';
    }
  } else {
    reviewCard?.classList.remove('has-pending');
    if (reviewIco) reviewIco.className = 'tx-side-ico green';
    if (reviewTitle) reviewTitle.textContent = 'AI Fraud Shield';
    if (reviewSub) reviewSub.textContent = 'All transactions verified';
    if (reviewDesc) reviewDesc.textContent = 'No transactions are currently held or awaiting manual review.';
    if (viewPendingBtn) viewPendingBtn.hidden = true;
  }

  // 2. Spending by Category — same 5 buckets as Overview page
  const bucketTotals = { Shopping: 0, Transfers: 0, 'Food & Dining': 0, Travel: 0, Other: 0 };
  let totalOutgoing = 0;
  let maxOutgoing = 0;
  let outgoingCount = 0;
  let totalIncoming = 0;

  filtered.forEach(tx => {
    const amt = Math.abs(Number(tx.amount || 0));
    if (normalizeStatus(tx) === 'declined') return;
    if (isCreditTx(tx)) {
      totalIncoming += amt;
      return;
    }
    totalOutgoing += amt;
    outgoingCount++;
    if (amt > maxOutgoing) maxOutgoing = amt;
    const bucket = bucketCategory(tx);
    bucketTotals[bucket] = (bucketTotals[bucket] || 0) + amt;
  });

  // Build sorted list (drop zero buckets), keeping fixed colours per bucket
  const sortedCats = CAT_BUCKETS
    .map(b => ({ name: b.name, color: b.color, val: bucketTotals[b.name] || 0 }))
    .filter(b => b.val > 0)
    .sort((a, b) => b.val - a.val);

  const barEl = document.getElementById('txCatBar');
  const listEl = document.getElementById('txCatList');
  const clearBtn = document.getElementById('catClearBtn');
  if (clearBtn) clearBtn.hidden = state.categoryFilter === 'all';

  if (!sortedCats.length || totalOutgoing === 0) {
    if (barEl) barEl.innerHTML = '';
    if (listEl) listEl.innerHTML = `<div class="tx-cat-empty">No outgoing spend in this view</div>`;
  } else {
    if (barEl) {
      barEl.innerHTML = sortedCats.map(({ name, color, val }) => {
        const pct = Math.max(4, (val / totalOutgoing) * 100);
        return `<div class="tx-cat-seg" style="width:${pct.toFixed(1)}%; background:${color}" title="${escapeHtml(name)}"></div>`;
      }).join('');
    }
    if (listEl) {
      listEl.innerHTML = sortedCats.map(({ name, color, val }) => {
        const pct = Math.round((val / totalOutgoing) * 100);
        const isActive = state.categoryFilter.toLowerCase() === name.toLowerCase();
        return `
          <button type="button" class="tx-cat-item ${isActive ? 'active' : ''}" data-cat="${escapeHtml(name)}">
            <span class="tx-cat-left">
              <span class="tx-cat-dot" style="background:${color}"></span>
              <span class="tx-cat-name">${escapeHtml(name)}</span>
            </span>
            <span class="tx-cat-right">
              <span class="tx-cat-pct">${pct}%</span>
              <span class="tx-cat-amt font-mono">${sym}${fmt(val)}</span>
            </span>
          </button>
        `;
      }).join('');

      listEl.querySelectorAll('.tx-cat-item').forEach(btn => {
        btn.addEventListener('click', () => {
          const cat = btn.dataset.cat;
          state.categoryFilter = state.categoryFilter.toLowerCase() === cat.toLowerCase() ? 'all' : cat;
          state.page = 1;
          render();
        });
      });
    }
  }

  // 3. Ledger Highlights
  const net = totalIncoming - totalOutgoing;
  const netEl = document.getElementById('hlNetFlow');
  if (netEl) {
    netEl.textContent = `${net >= 0 ? '+' : '-'}${sym}${fmt(Math.abs(net))}`;
    netEl.className = `tx-hl-val font-mono ${net >= 0 ? 'pos' : 'neg'}`;
  }
  const avgEl = document.getElementById('hlAvgSpend');
  if (avgEl) avgEl.textContent = outgoingCount > 0 ? `${sym}${fmt(totalOutgoing / outgoingCount)}` : '—';
  const maxEl = document.getElementById('hlMaxSpend');
  if (maxEl) maxEl.textContent = maxOutgoing > 0 ? `${sym}${fmt(maxOutgoing)}` : '—';
}

function render() {
  const tbody = document.getElementById('txTableBody');
  const emptyEl = document.getElementById('txEmptyState');
  const paginationEl = document.getElementById('txPagination');

  if (state.loading) {
    tbody.innerHTML = `
      <tr>
        <td colspan="7" style="text-align:center; padding: 36px; color: #64748b; font-size: 13.5px;">
          Loading transactions from ledger...
        </td>
      </tr>
    `;
    emptyEl.hidden = true;
    paginationEl.hidden = true;
    return;
  }

  if (state.error) {
    tbody.innerHTML = `
      <tr>
        <td colspan="7" style="text-align:center; padding: 36px; color: #dc2626; font-size: 13.5px;">
          ${escapeHtml(state.error)}
        </td>
      </tr>
    `;
    emptyEl.hidden = true;
    paginationEl.hidden = true;
    return;
  }

  const filtered = getFilteredTransactions();
  updateKpis(filtered);
  updateSidebarInsights(filtered);

  const hasActiveFilter = state.typeFilter !== 'all' || state.statusFilter !== 'all' || state.dateFilter !== 'all' || state.categoryFilter !== 'all' || state.search.trim() !== '';
  const resetBtn = document.getElementById('txResetBtn');
  if (resetBtn) resetBtn.hidden = !hasActiveFilter;

  const total = filtered.length;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  if (state.page > totalPages) state.page = totalPages;

  const startIdx = (state.page - 1) * PAGE_SIZE;
  const pageItems = filtered.slice(startIdx, startIdx + PAGE_SIZE);

  if (!total) {
    tbody.innerHTML = '';
    emptyEl.hidden = false;
    paginationEl.hidden = true;
    return;
  }

  emptyEl.hidden = true;
  paginationEl.hidden = false;

  tbody.innerHTML = pageItems.map(tx => {
    const typeKey = normalizeType(tx);
    const statusKey = normalizeStatus(tx);
    const isInflow = isCreditTx(tx);
    const sym = getCurrencySymbol(tx.currency || state.currency);
    const amt = fmt(Math.abs(Number(tx.amount || 0)));
    const title = getTitle(tx);
    const fullRef = getReference(tx);
    const shortRef = getShortRef(tx);
    const category = getCategoryLabel(tx);
    const method = getMethodLabel(tx);
    const isExpanded = state.expandedTxKey === fullRef;

    const rawTs = tx.timestamp || tx.createdAt;
    const dt = rawTs ? new Date(rawTs) : null;
    const dateStr = dt && !Number.isNaN(dt.getTime()) ? dt.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) : '—';
    const timeStr = dt && !Number.isNaN(dt.getTime()) ? dt.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }) : '';
    const fullDateTime = dt && !Number.isNaN(dt.getTime())
      ? dt.toLocaleString('en-US', { month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit' })
      : '—';

    const shieldClass = statusKey === 'review' ? 'review' : (statusKey === 'declined' ? 'risk' : 'safe');
    const shieldText = statusKey === 'review'
      ? 'Manual Review Pending'
      : (statusKey === 'declined' ? 'Blocked by Risk Engine' : 'Verified · Low Risk');

    const actionHref = typeKey === 'deposit'
      ? 'wallet.html'
      : (typeKey === 'transfer' ? 'send-money.html' : 'merchant-pay.html');
    const actionLabel = typeKey === 'deposit'
      ? 'Go to Wallet'
      : (typeKey === 'transfer' ? 'Repeat Transfer' : 'Pay Again');
    const noteText = tx.memo || tx.description || tx.productName || 'Processed via Vigilia Real-Time Ledger';
    const currCode = (tx.currency || state.currency).toUpperCase();

    const mainRow = `
      <tr class="tx-row ${isExpanded ? 'expanded' : ''}" data-tx-key="${escapeHtml(fullRef)}">
        <td>
          <div class="tx-merchant">
            ${getIconHtml(tx)}
            <div class="tx-info">
              <span class="tx-name">${escapeHtml(title)}</span>
              <span class="tx-meta-sub">${escapeHtml(category)} · ${escapeHtml(shortRef)}</span>
            </div>
          </div>
        </td>
        <td>
          <span class="tx-date-main">${escapeHtml(dateStr)}</span>
          <span class="tx-date-time">${escapeHtml(timeStr)}</span>
        </td>
        <td><span class="tx-type-pill ${typeKey}">${typeKey}</span></td>
        <td class="tx-method-cell">${escapeHtml(method)}</td>
        <td class="tx-amount font-mono ${isInflow ? 'pos' : 'neg'}">${isInflow ? '+' : '-'} ${sym}${amt}</td>
        <td>${getStatusBadgeHtml(statusKey)}</td>
        <td>
          <span class="tx-row-chevron" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="15" height="15">
              <polyline points="9 18 15 12 9 6"/>
            </svg>
          </span>
        </td>
      </tr>
    `;

    const detailRow = isExpanded ? `
      <tr class="tx-detail-row">
        <td colspan="7">
          <div class="tx-detail-card">
            <div class="tx-detail-top">
              <div class="tx-detail-title-group">
                <span class="tx-detail-heading">Transaction Receipt</span>
                <span class="tx-ref-pill font-mono" title="Transaction Reference ID">${escapeHtml(fullRef)}</span>
              </div>
              <span class="tx-shield-pill ${shieldClass}">● AI Fraud Shield: ${shieldText}</span>
            </div>

            <div class="tx-detail-grid">
              <div class="tx-detail-item">
                <span class="tx-detail-lbl">Timestamp</span>
                <span class="tx-detail-val">${escapeHtml(fullDateTime)}</span>
              </div>
              <div class="tx-detail-item">
                <span class="tx-detail-lbl">Category &amp; Type</span>
                <span class="tx-detail-val">${escapeHtml(category)} · ${escapeHtml(typeKey.charAt(0).toUpperCase() + typeKey.slice(1))}</span>
              </div>
              <div class="tx-detail-item">
                <span class="tx-detail-lbl">Payment Method</span>
                <span class="tx-detail-val">${escapeHtml(method)}</span>
              </div>
              <div class="tx-detail-item">
                <span class="tx-detail-lbl">Settlement Amount</span>
                <span class="tx-detail-val font-mono ${isInflow ? 'pos' : ''}">${isInflow ? '+' : '-'} ${sym}${amt} (${escapeHtml(currCode)})</span>
              </div>
            </div>

            <div class="tx-detail-footer">
              <span class="tx-detail-note">${escapeHtml(noteText)}</span>
              <div class="tx-detail-actions">
                <button type="button" class="btn btn-outline tx-copy-ref-btn" data-ref="${escapeHtml(fullRef)}">Copy Reference</button>
                <button type="button" class="btn btn-outline tx-receipt-btn" data-ref="${escapeHtml(fullRef)}">Download Receipt</button>
                <a href="${actionHref}" class="btn btn-primary tx-cta-btn">${actionLabel}</a>
              </div>
            </div>
          </div>
        </td>
      </tr>
    ` : '';

    return mainRow + detailRow;
  }).join('');

  tbody.querySelectorAll('.tx-row').forEach((row, idx) => {
    row.addEventListener('click', () => toggleRowDetails(pageItems[idx]));
  });

  tbody.querySelectorAll('.tx-copy-ref-btn').forEach(btn => {
    btn.addEventListener('click', async (e) => {
      e.stopPropagation();
      const ref = btn.dataset.ref;
      if (!ref || ref === '—') return;
      const copied = await copyTextSafe(ref);
      if (copied) {
        const orig = btn.textContent;
        btn.textContent = 'Copied!';
        setTimeout(() => { btn.textContent = orig; }, 1600);
        showToast(`Copied ${ref}`);
      } else {
        showToast(`Reference: ${ref}`);
      }
    });
  });

  tbody.querySelectorAll('.tx-receipt-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const ref = btn.dataset.ref;
      const tx = pageItems.find(item => getReference(item) === ref);
      if (tx) downloadReceipt(tx);
    });
  });

  const endIdx = Math.min(startIdx + PAGE_SIZE, total);
  document.getElementById('txPageInfo').textContent = `Showing ${startIdx + 1}–${endIdx} of ${total} transactions`;
  document.getElementById('txPrevBtn').disabled = state.page <= 1;
  document.getElementById('txNextBtn').disabled = state.page >= totalPages;

  const pageNumsEl = document.getElementById('txPageNums');
  pageNumsEl.innerHTML = Array.from({ length: totalPages }, (_, i) => {
    const p = i + 1;
    return `<button type="button" class="tx-page-num ${p === state.page ? 'active' : ''}" data-page="${p}">${p}</button>`;
  }).join('');

  pageNumsEl.querySelectorAll('.tx-page-num').forEach(btn => {
    btn.addEventListener('click', () => {
      state.page = Number(btn.dataset.page);
      render();
    });
  });
}

async function toggleRowDetails(tx) {
  if (!tx) return;
  const key = getReference(tx);
  if (state.expandedTxKey === key) {
    state.expandedTxKey = null;
    render();
    return;
  }

  state.expandedTxKey = key;
  render();

  // Enrich with full transaction details from GET /api/v1/transactions/{id} if available
  if (tx.id && !tx._enriched) {
    try {
      const fullTx = await apiClient.get(`/api/v1/transactions/${encodeURIComponent(tx.id)}`);
      if (fullTx) {
        Object.assign(tx, fullTx, { _enriched: true });
        if (state.expandedTxKey === key) render();
      }
    } catch {
      // Keep list item data if detail endpoint fails
    }
  }
}

let toastTimer = null;
function showToast(msg) {
  const el = document.getElementById('txToast');
  if (!el) return;
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 2600);
}

async function copyTextSafe(text) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch { /* fallback below */ }
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok;
  } catch {
    return false;
  }
}

function downloadReceipt(tx) {
  const ref = getReference(tx);
  const typeKey = normalizeType(tx);
  const statusKey = normalizeStatus(tx);
  const isInflow = isCreditTx(tx);
  const curr = (tx.currency || state.currency).toUpperCase();
  const amt = fmt(Math.abs(Number(tx.amount || 0)));
  const rawTs = tx.timestamp || tx.createdAt;
  const dt = rawTs ? new Date(rawTs).toISOString() : '—';

  const lines = [
    '========================================',
    '        VIGILIA TRANSACTION RECEIPT     ',
    '========================================',
    `Reference ID : ${ref}`,
    `Merchant/To  : ${getTitle(tx)}`,
    `Date (UTC)   : ${dt}`,
    `Type         : ${typeKey.toUpperCase()}`,
    `Category     : ${getCategoryLabel(tx)}`,
    `Method       : ${getMethodLabel(tx)}`,
    `Amount       : ${isInflow ? '+' : '-'}${amt} ${curr}`,
    `Status       : ${statusKey.toUpperCase()}`,
    '========================================',
  ];

  const blob = new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `vigilia-receipt-${getShortRef(tx)}.txt`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
  showToast(`Downloaded receipt for ${getShortRef(tx)}`);
}

function exportCsv() {
  const rows = getFilteredTransactions();
  if (!rows.length) {
    showToast('No transactions to export');
    return;
  }
  const headers = ['Reference', 'Merchant/Recipient', 'Date', 'Type', 'Category', 'Method', 'Currency', 'Amount', 'Status'];
  const csvEscape = (v) => `"${String(v ?? '').replace(/"/g, '""')}"`;

  const lines = [
    headers.join(','),
    ...rows.map(tx => {
      const typeKey = normalizeType(tx);
      const statusKey = normalizeStatus(tx);
      const sign = isCreditTx(tx) ? '' : '-';
      const rawTs = tx.timestamp || tx.createdAt;
      const dt = rawTs ? new Date(rawTs).toISOString() : '';
      return [
        csvEscape(getReference(tx)),
        csvEscape(getTitle(tx)),
        csvEscape(dt),
        csvEscape(typeKey),
        csvEscape(getCategoryLabel(tx)),
        csvEscape(getMethodLabel(tx)),
        csvEscape(tx.currency || state.currency),
        csvEscape(`${sign}${Math.abs(Number(tx.amount || 0)).toFixed(2)}`),
        csvEscape(statusKey),
      ].join(',');
    })
  ];

  const blob = new Blob([lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `vigilia-transactions-${new Date().toISOString().slice(0, 10)}.csv`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
  showToast(`Exported ${rows.length} transactions to CSV`);
}

function resetFilters() {
  state.typeFilter = 'all';
  state.statusFilter = 'all';
  state.dateFilter = 'all';
  state.categoryFilter = 'all';
  state.sortBy = 'newest';
  state.search = '';
  state.page = 1;

  document.querySelectorAll('.tx-tab').forEach(t => t.classList.toggle('active', t.dataset.filter === 'all'));
  document.getElementById('txSearch').value = '';
  document.getElementById('statusFilter').value = 'all';
  document.getElementById('dateFilter').value = 'all';
  const sortEl = document.getElementById('sortFilter');
  if (sortEl) sortEl.value = 'newest';
  render();
}

function bindEvents() {
  document.querySelectorAll('.tx-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.tx-tab').forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      state.typeFilter = tab.dataset.filter;
      state.page = 1;
      render();
    });
  });

  document.getElementById('txSearch')?.addEventListener('input', e => {
    state.search = e.target.value;
    state.page = 1;
    render();
  });

  document.getElementById('statusFilter')?.addEventListener('change', e => {
    state.statusFilter = e.target.value;
    state.page = 1;
    render();
  });

  document.getElementById('dateFilter')?.addEventListener('change', e => {
    state.dateFilter = e.target.value;
    state.page = 1;
    render();
  });

  document.getElementById('sortFilter')?.addEventListener('change', e => {
    state.sortBy = e.target.value;
    state.page = 1;
    render();
  });

  document.getElementById('viewPendingBtn')?.addEventListener('click', () => {
    state.statusFilter = state.statusFilter === 'review' ? 'all' : 'review';
    const statusEl = document.getElementById('statusFilter');
    if (statusEl) statusEl.value = state.statusFilter;
    state.page = 1;
    render();
  });

  document.getElementById('catClearBtn')?.addEventListener('click', () => {
    state.categoryFilter = 'all';
    state.page = 1;
    render();
  });

  document.getElementById('txResetBtn')?.addEventListener('click', resetFilters);
  document.getElementById('txEmptyResetBtn')?.addEventListener('click', resetFilters);
  document.getElementById('txExportBtn')?.addEventListener('click', exportCsv);

  document.getElementById('txPrevBtn')?.addEventListener('click', () => {
    if (state.page > 1) { state.page--; render(); }
  });
  document.getElementById('txNextBtn')?.addEventListener('click', () => {
    state.page++; render();
  });
}

async function fetchAllTransactions() {
  const firstPage = await apiClient.get('/api/v1/transactions?page=0&size=100');
  if (Array.isArray(firstPage)) {
    return firstPage;
  }

  const firstItems = firstPage?.content || firstPage?.data?.content || firstPage?.data || [];
  const totalPages = Number(firstPage?.totalPages || firstPage?.data?.totalPages || 1);

  if (totalPages <= 1) {
    return Array.isArray(firstItems) ? firstItems : [];
  }

  const pageRequests = [];
  for (let p = 1; p < totalPages; p++) {
    pageRequests.push(apiClient.get(`/api/v1/transactions?page=${p}&size=100`));
  }

  const restPages = await Promise.all(pageRequests);
  const allItems = [...firstItems];
  restPages.forEach(res => {
    const items = Array.isArray(res) ? res : (res?.content || res?.data?.content || res?.data || []);
    if (Array.isArray(items)) allItems.push(...items);
  });

  return allItems;
}

async function init() {
  bindEvents();
  render();

  const [walletRes, beneficiariesRes, txRes] = await Promise.allSettled([
    apiClient.get('/api/v1/transactions/wallet'),
    apiClient.get('/api/v1/customers/me/beneficiaries'),
    fetchAllTransactions(),
  ]);

  if (walletRes.status === 'fulfilled' && walletRes.value?.currency) {
    state.currency = walletRes.value.currency;
  }

  if (beneficiariesRes.status === 'fulfilled') {
    const bens = Array.isArray(beneficiariesRes.value)
      ? beneficiariesRes.value
      : (beneficiariesRes.value?.content || beneficiariesRes.value?.data || []);
    bens.forEach(b => {
      if (b.recipientCustomerId) state.beneficiariesById.set(b.recipientCustomerId, b);
      if (b.customerId) state.beneficiariesById.set(b.customerId, b);
    });
  }

  state.loading = false;

  if (txRes.status === 'fulfilled') {
    state.transactions = Array.isArray(txRes.value) ? txRes.value : [];
    state.error = null;
  } else {
    state.transactions = [];
    state.error = txRes.reason?.message || 'Unable to load transactions from server.';
  }

  updateTabCounts();
  render();
}

document.addEventListener('DOMContentLoaded', init);
