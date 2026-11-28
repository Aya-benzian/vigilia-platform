import { apiClient } from '../../js/api-client.js';
import { VigiliaAuth } from '../../js/auth.js';
import '../../js/components/customer/sidebar.js';
import '../../js/components/customer/top-navbar.js';

const user = VigiliaAuth.getUser();
if (!user || (user.role && user.role !== 'CUSTOMER')) {
  window.location.replace('/login.html');
}

const AVATAR_CLASSES = ['copper', 'blue', 'green', 'violet'];

const REGION_NAMES = {
  FR: 'France',
  DE: 'Germany',
  ES: 'Spain',
  MA: 'Morocco',
  GB: 'United Kingdom',
  US: 'United States',
  IT: 'Italy',
  BE: 'Belgium',
};

const state = {
  beneficiaries: [],
  transfers: [],
  currency: 'EUR',
  filterTab: 'all', // 'all' | 'recent' | 'unused'
  searchQuery: '',
  sortBy: 'newest', // 'newest' | 'recent_tx' | 'most_sent' | 'name_asc'
  viewMode: 'grid', // 'grid' | 'table'
  pendingRemove: null,
  verifiedLookup: null,
};

function escapeHtml(str) {
  return String(str ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function normalizeAccount(raw) {
  return String(raw || '').replace(/\s+/g, '').toUpperCase();
}

function maskAccount(raw) {
  const clean = normalizeAccount(raw);
  if (!clean) return '—';
  if (clean.length <= 8) return clean;
  return `${clean.slice(0, 4)}-${clean.slice(4, 8)}-••••-${clean.slice(-4)}`;
}

function extractRegionCode(account) {
  const clean = normalizeAccount(account).replace(/-/g, '');
  if (clean.startsWith('VGL') && clean.length >= 5) {
    const code = clean.slice(3, 5);
    if (/^[A-Z]{2}$/.test(code)) return code;
  }
  return 'VG';
}

function computeInitials(name) {
  const clean = String(name || 'VG').replace(/[^\p{L}\p{N}\s]/gu, '').trim() || 'VG';
  return clean.split(/\s+/).map(p => Array.from(p)[0] || '').join('').slice(0, 2).toUpperCase();
}

function getCurrencySymbol(curr) {
  const c = (curr || 'EUR').toUpperCase();
  if (c === 'EUR') return '€';
  if (c === 'USD') return '$';
  if (c === 'GBP') return '£';
  if (c === 'MAD') return 'MAD ';
  return `${c} `;
}

function fmtMoney(val, curr = state.currency) {
  const num = Number(val || 0);
  return `${getCurrencySymbol(curr)}${num.toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

function fmtDate(dateStr) {
  if (!dateStr) return '—';
  const d = new Date(dateStr);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

let toastTimer = null;
function showToast(message) {
  const el = document.getElementById('benToast');
  if (!el) return;
  el.textContent = message;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    el.hidden = true;
  }, 2600);
}

function getTransfersForBeneficiary(ben) {
  const recCustId = ben?.recipientCustomerId ? String(ben.recipientCustomerId) : '';
  const recAcct = normalizeAccount(ben?.recipientAccountNumber || ben?.accountNumber || '');

  return state.transfers.filter(tx => {
    if (recCustId && tx.recipientCustomerId && String(tx.recipientCustomerId) === recCustId) {
      return true;
    }
    const txAcct = normalizeAccount(tx.recipientIban || tx.recipientAccountNumber || '');
    return Boolean(recAcct && txAcct && txAcct === recAcct);
  });
}

function enrichBeneficiaries() {
  return state.beneficiaries.map((b, idx) => {
    const account = normalizeAccount(b.recipientAccountNumber || b.accountNumber || '');
    const displayName = (b.nickname || b.recipientName || '').trim() || `Account ${account.slice(-4)}`;
    const txs = getTransfersForBeneficiary(b);
    const totalSent = txs.reduce((sum, tx) => sum + Math.abs(Number(tx.amount || 0)), 0);
    const lastTx = txs[0] || null;
    const lastTxTime = lastTx ? new Date(lastTx.timestamp || lastTx.createdAt || 0).getTime() : 0;
    const regionCode = extractRegionCode(account);

    return {
      ...b,
      account,
      displayName,
      initials: computeInitials(displayName),
      avClass: AVATAR_CLASSES[idx % AVATAR_CLASSES.length],
      regionCode,
      regionLabel: REGION_NAMES[regionCode] || 'Vigilia Network',
      txs,
      txCount: txs.length,
      totalSent,
      lastTx,
      lastTxTime,
      createdTime: new Date(b.createdAt || 0).getTime() || 0,
    };
  });
}

function updateKpis(enriched) {
  const totalContacts = enriched.length;
  const activeContacts = enriched.filter(b => b.txCount > 0).length;
  const totalSent = enriched.reduce((sum, b) => sum + b.totalSent, 0);
  const totalTxCount = enriched.reduce((sum, b) => sum + b.txCount, 0);

  const top = [...enriched]
    .filter(b => b.txCount > 0)
    .sort((a, b) => b.totalSent - a.totalSent || b.txCount - a.txCount)[0];

  const elTotal = document.getElementById('kpiTotalContacts');
  const elActive = document.getElementById('kpiActiveContacts');
  const elSent = document.getElementById('kpiTotalSent');
  const elSentSub = document.getElementById('kpiTotalSentSub');
  const elTop = document.getElementById('kpiTopRecipient');
  const elTopSub = document.getElementById('kpiTopRecipientSub');

  if (elTotal) elTotal.textContent = String(totalContacts);
  if (elActive) elActive.textContent = String(activeContacts);
  if (elSent) elSent.textContent = fmtMoney(totalSent);
  if (elSentSub) elSentSub.textContent = `Across ${totalTxCount} transfer${totalTxCount === 1 ? '' : 's'}`;
  if (elTop) elTop.textContent = top ? top.displayName : '—';
  if (elTopSub) {
    elTopSub.textContent = top
      ? `${fmtMoney(top.totalSent)} (${top.txCount} transfer${top.txCount === 1 ? '' : 's'})`
      : 'No transfers yet';
  }

  const cAll = document.getElementById('tabCountAll');
  const cRecent = document.getElementById('tabCountRecent');
  const cUnused = document.getElementById('tabCountUnused');
  if (cAll) cAll.textContent = String(totalContacts);
  if (cRecent) cRecent.textContent = String(activeContacts);
  if (cUnused) cUnused.textContent = String(totalContacts - activeContacts);
}

function buildSendUrl(b) {
  const params = new URLSearchParams({
    iban: b.account,
    name: b.displayName,
  });
  return `send-money.html?${params.toString()}`;
}

function renderSidebar(enriched) {
  const quickList = document.getElementById('benQuickList');
  const corridorList = document.getElementById('benCorridorList');
  const corridorLabel = document.getElementById('corridorCountLabel');
  const recentFeed = document.getElementById('benRecentFeed');

  if (quickList) {
    if (enriched.length === 0) {
      quickList.innerHTML = `<div style="font-size:12px;color:#94a3b8;">Add a beneficiary to enable 1-click transfers.</div>`;
    } else {
      const topQuick = [...enriched]
        .sort((a, b) => b.txCount - a.txCount || b.createdTime - a.createdTime)
        .slice(0, 3);
      quickList.innerHTML = topQuick.map(b => `
        <a href="${buildSendUrl(b)}" class="ben-quick-item">
          <div class="ben-quick-item-left">
            <div class="ben-quick-av">${escapeHtml(b.initials)}</div>
            <span class="ben-quick-name">${escapeHtml(b.displayName)}</span>
          </div>
          <span class="ben-quick-go">Send →</span>
        </a>
      `).join('');
    }
  }

  if (corridorList) {
    const counts = new Map();
    enriched.forEach(b => {
      const label = b.regionLabel;
      counts.set(label, (counts.get(label) || 0) + 1);
    });
    const entries = [...counts.entries()].sort((a, b) => b[1] - a[1]);
    if (corridorLabel) {
      corridorLabel.textContent = `${entries.length} region${entries.length === 1 ? '' : 's'}`;
    }
    if (entries.length === 0) {
      corridorList.innerHTML = `<span style="font-size:12px;color:#94a3b8;">No corridors yet.</span>`;
    } else {
      const total = Math.max(1, enriched.length);
      corridorList.innerHTML = entries.map(([label, count]) => {
        const pct = Math.round((count / total) * 100);
        return `
          <div class="ben-corridor-row">
            <div class="ben-corridor-top">
              <span>${escapeHtml(label)}</span>
              <span class="font-mono">${count} (${pct}%)</span>
            </div>
            <div class="ben-corridor-bar">
              <div class="ben-corridor-fill" style="width:${pct}%"></div>
            </div>
          </div>
        `;
      }).join('');
    }
  }

  if (recentFeed) {
    const allContactTxs = [];
    enriched.forEach(b => {
      b.txs.forEach(tx => {
        allContactTxs.push({ ben: b, tx });
      });
    });
    allContactTxs.sort(
      (a, b) =>
        new Date(b.tx.timestamp || b.tx.createdAt || 0).getTime() -
        new Date(a.tx.timestamp || a.tx.createdAt || 0).getTime()
    );

    if (allContactTxs.length === 0) {
      recentFeed.innerHTML = `<span style="font-size:12px;color:#94a3b8;">No recent transfers to saved contacts.</span>`;
    } else {
      recentFeed.innerHTML = allContactTxs.slice(0, 4).map(({ ben, tx }) => `
        <div class="ben-feed-item">
          <div>
            <span class="ben-feed-title">${escapeHtml(ben.displayName)}</span>
            <span class="ben-feed-date">${escapeHtml(fmtDate(tx.timestamp || tx.createdAt))}</span>
          </div>
          <span class="ben-feed-amt font-mono">${escapeHtml(fmtMoney(Math.abs(Number(tx.amount || 0)), tx.currency || state.currency))}</span>
        </div>
      `).join('');
    }
  }
}

function getFilteredSorted(enriched) {
  let list = enriched;

  if (state.filterTab === 'recent') {
    list = list.filter(b => b.txCount > 0);
  } else if (state.filterTab === 'unused') {
    list = list.filter(b => b.txCount === 0);
  }

  const q = state.searchQuery.trim().toLowerCase();
  if (q) {
    list = list.filter(b =>
      b.displayName.toLowerCase().includes(q) ||
      b.account.toLowerCase().includes(q) ||
      b.regionLabel.toLowerCase().includes(q)
    );
  }

  const sorted = [...list];
  if (state.sortBy === 'recent_tx') {
    sorted.sort((a, b) => b.lastTxTime - a.lastTxTime || b.createdTime - a.createdTime);
  } else if (state.sortBy === 'most_sent') {
    sorted.sort((a, b) => b.totalSent - a.totalSent || b.txCount - a.txCount);
  } else if (state.sortBy === 'name_asc') {
    sorted.sort((a, b) => a.displayName.localeCompare(b.displayName));
  } else {
    sorted.sort((a, b) => b.createdTime - a.createdTime);
  }

  return sorted;
}

function render() {
  const loadingEl = document.getElementById('benLoading');
  const gridEl = document.getElementById('benGrid');
  const tableWrapEl = document.getElementById('benTableWrap');
  const tableBodyEl = document.getElementById('benTableBody');
  const emptyEl = document.getElementById('benEmpty');
  const noMatchEl = document.getElementById('benNoMatch');

  if (loadingEl) loadingEl.hidden = true;

  const enriched = enrichBeneficiaries();
  updateKpis(enriched);
  renderSidebar(enriched);

  if (enriched.length === 0) {
    if (gridEl) gridEl.hidden = true;
    if (tableWrapEl) tableWrapEl.hidden = true;
    if (noMatchEl) noMatchEl.hidden = true;
    if (emptyEl) emptyEl.hidden = false;
    return;
  }

  if (emptyEl) emptyEl.hidden = true;
  const visible = getFilteredSorted(enriched);

  if (visible.length === 0) {
    if (gridEl) gridEl.hidden = true;
    if (tableWrapEl) tableWrapEl.hidden = true;
    if (noMatchEl) noMatchEl.hidden = false;
    return;
  }

  if (noMatchEl) noMatchEl.hidden = true;

  if (state.viewMode === 'grid') {
    if (tableWrapEl) tableWrapEl.hidden = true;
    if (gridEl) {
      gridEl.hidden = false;
      gridEl.innerHTML = visible.map(b => `
        <article class="ben-card accent-${b.avClass}" data-id="${escapeHtml(b.id)}">
          <div class="ben-card-top">
            <div class="ben-avatar ${b.avClass}">${escapeHtml(b.initials)}</div>
            <div class="ben-info">
              <div class="ben-name-row">
                <strong class="ben-name" title="${escapeHtml(b.displayName)}">${escapeHtml(b.displayName)}</strong>
                <span class="ben-region-pill">${escapeHtml(b.regionCode)}</span>
              </div>
              <span class="ben-added-sub">
                <span class="ben-verified-dot"></span>
                Verified · ${escapeHtml(fmtDate(b.createdAt))}
              </span>
            </div>
            <a href="${buildSendUrl(b)}" class="ben-send-pill" title="Send money to ${escapeHtml(b.displayName)}">
              Send →
            </a>
          </div>

          <div class="ben-account-box">
            <span class="ben-account-num font-mono" title="${escapeHtml(b.account)}">${escapeHtml(maskAccount(b.account))}</span>
            <button type="button" class="ben-copy-btn" data-copy="${escapeHtml(b.account)}">
              Copy
            </button>
          </div>

          <div class="ben-card-metrics">
            <div>
              <span class="ben-metric-label">Last sent</span>
              <span class="ben-metric-val ${b.lastTx ? '' : 'muted'}">${b.lastTx ? escapeHtml(fmtDate(b.lastTx.timestamp || b.lastTx.createdAt)) : 'Ready for transfer'}</span>
            </div>
            <div>
              <span class="ben-metric-label">Total volume</span>
              <span class="ben-metric-val font-mono ${b.txCount > 0 ? '' : 'muted'}">${b.txCount > 0 ? `${escapeHtml(fmtMoney(b.totalSent))} (${b.txCount})` : '€0.00'}</span>
            </div>
          </div>

          <div class="ben-card-footer">
            <button type="button" class="ben-detail-link" data-detail-id="${escapeHtml(b.id)}">View activity</button>
            <button type="button" class="ben-remove-icon-btn" data-remove-id="${escapeHtml(b.id)}">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="13" height="13">
                <polyline points="3 6 5 6 21 6"/>
                <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>
              </svg>
              Remove
            </button>
          </div>
        </article>
      `).join('');
    }
  } else {
    if (gridEl) gridEl.hidden = true;
    if (tableWrapEl && tableBodyEl) {
      tableWrapEl.hidden = false;
      tableBodyEl.innerHTML = visible.map(b => `
        <tr data-id="${escapeHtml(b.id)}">
          <td>
            <div class="ben-table-recipient">
              <div class="ben-avatar ${b.avClass}">${escapeHtml(b.initials)}</div>
              <div>
                <strong style="display:block;color:#0f172a">${escapeHtml(b.displayName)}</strong>
                <span style="font-size:11.5px;color:#94a3b8">Added ${escapeHtml(fmtDate(b.createdAt))}</span>
              </div>
            </div>
          </td>
          <td><span class="ben-region-pill">${escapeHtml(b.regionLabel)}</span></td>
          <td>
            <div style="display:flex;align-items:center;gap:6px">
              <span class="font-mono" style="font-size:12px">${escapeHtml(maskAccount(b.account))}</span>
              <button type="button" class="ben-copy-btn" data-copy="${escapeHtml(b.account)}">Copy</button>
            </div>
          </td>
          <td>${b.txCount}</td>
          <td class="font-mono" style="font-weight:700">${escapeHtml(fmtMoney(b.totalSent))}</td>
          <td>
            <div class="ben-table-actions">
              <button type="button" class="ben-detail-link" data-detail-id="${escapeHtml(b.id)}">Activity</button>
              <a href="${buildSendUrl(b)}" class="ben-send-pill">Send →</a>
              <button type="button" class="ben-remove-icon-btn" data-remove-id="${escapeHtml(b.id)}">Remove</button>
            </div>
          </td>
        </tr>
      `).join('');
    }
  }

  wireDynamicRowEvents(enriched);
}

function wireDynamicRowEvents(enriched) {
  document.querySelectorAll('[data-copy]').forEach(btn => {
    btn.addEventListener('click', async () => {
      const val = btn.dataset.copy;
      if (!val) return;
      try {
        await navigator.clipboard.writeText(val);
        btn.textContent = 'Copied!';
        btn.classList.add('copied');
        showToast('Account ID copied to clipboard');
        setTimeout(() => {
          btn.textContent = 'Copy';
          btn.classList.remove('copied');
        }, 1500);
      } catch {
        showToast('Could not copy to clipboard');
      }
    });
  });

  document.querySelectorAll('[data-remove-id]').forEach(btn => {
    btn.addEventListener('click', () => {
      const ben = enriched.find(item => String(item.id) === String(btn.dataset.removeId));
      if (ben) openRemoveModal(ben);
    });
  });

  document.querySelectorAll('[data-detail-id]').forEach(btn => {
    btn.addEventListener('click', () => {
      const ben = enriched.find(item => String(item.id) === String(btn.dataset.detailId));
      if (ben) openDetailModal(ben);
    });
  });
}

async function loadData() {
  const [benRes, txRes, walletRes] = await Promise.allSettled([
    apiClient.get('/api/v1/customers/me/beneficiaries'),
    apiClient.get('/api/v1/transactions?size=50'),
    apiClient.get('/api/v1/wallets/me'),
  ]);

  if (walletRes.status === 'fulfilled') {
    const w = walletRes.value?.data || walletRes.value;
    if (w?.currency) state.currency = String(w.currency).toUpperCase();
  }

  if (benRes.status === 'fulfilled') {
    const list = Array.isArray(benRes.value) ? benRes.value : (benRes.value?.data || []);
    state.beneficiaries = Array.isArray(list) ? list : [];
  } else {
    state.beneficiaries = [];
  }

  if (txRes.status === 'fulfilled') {
    const list = Array.isArray(txRes.value)
      ? txRes.value
      : (txRes.value?.data?.content || txRes.value?.content || txRes.value?.data || []);
    state.transfers = (Array.isArray(list) ? list : [])
      .filter(tx => String(tx.type || '').toUpperCase() === 'TRANSFER')
      .sort((a, b) => new Date(b.timestamp || b.createdAt || 0).getTime() - new Date(a.timestamp || a.createdAt || 0).getTime());
  } else {
    state.transfers = [];
  }

  render();
}

/* ── Add Beneficiary Modal ── */
function setVerifyBanner(type, text) {
  const banner = document.getElementById('benVerifyBanner');
  if (!banner) return;
  if (!type) {
    banner.hidden = true;
    banner.className = 'ben-verify-banner';
    banner.textContent = '';
    return;
  }
  banner.hidden = false;
  banner.className = `ben-verify-banner ${type}`;
  banner.textContent = text;
}

function setFormError(id, msg) {
  const el = document.getElementById(id);
  if (!el) return;
  el.hidden = !msg;
  el.textContent = msg || '';
}

function openAddModal() {
  const modal = document.getElementById('addBenModal');
  const accInput = document.getElementById('benAccountInput');
  const nickInput = document.getElementById('benNicknameInput');
  state.verifiedLookup = null;
  if (accInput) accInput.value = '';
  if (nickInput) nickInput.value = '';
  setVerifyBanner(null);
  setFormError('addBenError', '');
  if (modal) modal.hidden = false;
  accInput?.focus();
}

function closeAddModal() {
  const modal = document.getElementById('addBenModal');
  if (modal) modal.hidden = true;
}

async function verifyAccountNumber(raw) {
  const clean = normalizeAccount(raw);
  if (clean.length < 6 || clean.length > 34 || !/^[A-Z0-9-]+$/.test(clean)) {
    setVerifyBanner('error', 'Enter 6 to 34 uppercase alphanumeric characters or hyphens.');
    state.verifiedLookup = null;
    return false;
  }

  setVerifyBanner('loading', 'Checking Vigilia account directory...');
  try {
    const res = await apiClient.get(`/api/v1/customers/lookup?account_number=${encodeURIComponent(clean)}`);
    const data = res?.data || res;
    if (data && (data.exists === true || data.found === true)) {
      state.verifiedLookup = data;
      const holder = data.display_name || data.maskedName || data.fullName || 'Verified Vigilia Customer';
      setVerifyBanner('success', `Account verified · Holder: ${holder}`);
      return true;
    }
    state.verifiedLookup = null;
    setVerifyBanner('error', 'No Vigilia account found with this number.');
    return false;
  } catch (err) {
    state.verifiedLookup = null;
    setVerifyBanner('error', err.message || 'Account verification failed.');
    return false;
  }
}

/* ── Remove Beneficiary Modal ── */
function openRemoveModal(ben) {
  state.pendingRemove = ben;
  const preview = document.getElementById('removePreview');
  if (preview) {
    preview.innerHTML = `
      <div class="ben-avatar ${ben.avClass}">${escapeHtml(ben.initials)}</div>
      <div class="ben-info">
        <strong class="ben-name">${escapeHtml(ben.displayName)}</strong>
        <span class="ben-added-sub font-mono">${escapeHtml(maskAccount(ben.account))}</span>
      </div>
    `;
  }
  setFormError('removeBenError', '');
  const modal = document.getElementById('removeBenModal');
  if (modal) modal.hidden = false;
}

function closeRemoveModal() {
  state.pendingRemove = null;
  const modal = document.getElementById('removeBenModal');
  if (modal) modal.hidden = true;
}

/* ── Detail Modal ── */
function openDetailModal(ben) {
  const header = document.getElementById('detailModalHeader');
  const body = document.getElementById('detailModalBody');
  const sendBtn = document.getElementById('detailSendBtn');

  if (header) {
    header.innerHTML = `
      <div class="ben-avatar ${ben.avClass}">${escapeHtml(ben.initials)}</div>
      <div>
        <h3 id="detailModalTitle">${escapeHtml(ben.displayName)}</h3>
        <p class="ben-modal-sub font-mono">${escapeHtml(ben.account)}</p>
      </div>
    `;
  }

  if (sendBtn) {
    sendBtn.href = buildSendUrl(ben);
  }

  if (body) {
    const txHtml = ben.txs.length === 0
      ? `<p style="font-size:12.5px;color:#94a3b8;padding:12px 0;">No transfers recorded with this recipient yet.</p>`
      : ben.txs.slice(0, 6).map(tx => `
          <div class="ben-detail-tx-item">
            <div>
              <strong style="display:block;color:#0f172a">${escapeHtml(tx.transferReference || tx.description || 'P2P Transfer')}</strong>
              <span style="font-size:11.5px;color:#94a3b8">${escapeHtml(fmtDate(tx.timestamp || tx.createdAt))}</span>
            </div>
            <span class="font-mono" style="font-weight:700;color:#0f172a">${escapeHtml(fmtMoney(Math.abs(Number(tx.amount || 0)), tx.currency || state.currency))}</span>
          </div>
        `).join('');

    body.innerHTML = `
      <div class="ben-detail-stats">
        <div class="ben-detail-stat">
          <span class="ben-metric-label">Added On</span>
          <span class="ben-metric-val">${escapeHtml(fmtDate(ben.createdAt))}</span>
        </div>
        <div class="ben-detail-stat">
          <span class="ben-metric-label">Transfers</span>
          <span class="ben-metric-val font-mono">${ben.txCount}</span>
        </div>
        <div class="ben-detail-stat">
          <span class="ben-metric-label">Total Sent</span>
          <span class="ben-metric-val font-mono">${escapeHtml(fmtMoney(ben.totalSent))}</span>
        </div>
      </div>
      <div>
        <span class="ben-label" style="display:block;margin-bottom:8px;">Recent Transfer Activity</span>
        <div class="ben-detail-tx-list">${txHtml}</div>
      </div>
    `;
  }

  const modal = document.getElementById('detailBenModal');
  if (modal) modal.hidden = false;
}

function closeDetailModal() {
  const modal = document.getElementById('detailBenModal');
  if (modal) modal.hidden = true;
}

function wireEvents() {
  document.getElementById('openAddModal')?.addEventListener('click', openAddModal);
  document.getElementById('openAddModalEmpty')?.addEventListener('click', openAddModal);
  document.getElementById('closeAddModal')?.addEventListener('click', closeAddModal);
  document.getElementById('cancelAddModal')?.addEventListener('click', closeAddModal);
  document.getElementById('addBenModal')?.addEventListener('click', e => {
    if (e.target.id === 'addBenModal') closeAddModal();
  });

  document.getElementById('closeRemoveModal')?.addEventListener('click', closeRemoveModal);
  document.getElementById('cancelRemoveModal')?.addEventListener('click', closeRemoveModal);
  document.getElementById('removeBenModal')?.addEventListener('click', e => {
    if (e.target.id === 'removeBenModal') closeRemoveModal();
  });

  document.getElementById('closeDetailModal')?.addEventListener('click', closeDetailModal);
  document.getElementById('closeDetailModalBtn')?.addEventListener('click', closeDetailModal);
  document.getElementById('detailBenModal')?.addEventListener('click', e => {
    if (e.target.id === 'detailBenModal') closeDetailModal();
  });

  document.querySelectorAll('.ben-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.ben-tab').forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      state.filterTab = tab.dataset.filter || 'all';
      render();
    });
  });

  const searchInput = document.getElementById('benSearch');
  const clearBtn = document.getElementById('benSearchClear');
  searchInput?.addEventListener('input', () => {
    state.searchQuery = searchInput.value;
    if (clearBtn) clearBtn.hidden = !searchInput.value;
    render();
  });
  clearBtn?.addEventListener('click', () => {
    if (searchInput) searchInput.value = '';
    state.searchQuery = '';
    clearBtn.hidden = true;
    render();
    searchInput?.focus();
  });

  document.getElementById('benResetFilters')?.addEventListener('click', () => {
    if (searchInput) searchInput.value = '';
    if (clearBtn) clearBtn.hidden = true;
    state.searchQuery = '';
    state.filterTab = 'all';
    document.querySelectorAll('.ben-tab').forEach(t => t.classList.toggle('active', t.dataset.filter === 'all'));
    render();
  });

  document.getElementById('benSort')?.addEventListener('change', e => {
    state.sortBy = e.target.value;
    render();
  });

  const gridBtn = document.getElementById('viewGridBtn');
  const tableBtn = document.getElementById('viewTableBtn');
  gridBtn?.addEventListener('click', () => {
    state.viewMode = 'grid';
    gridBtn.classList.add('active');
    gridBtn.setAttribute('aria-pressed', 'true');
    tableBtn?.classList.remove('active');
    tableBtn?.setAttribute('aria-pressed', 'false');
    render();
  });
  tableBtn?.addEventListener('click', () => {
    state.viewMode = 'table';
    tableBtn.classList.add('active');
    tableBtn.setAttribute('aria-pressed', 'true');
    gridBtn?.classList.remove('active');
    gridBtn?.setAttribute('aria-pressed', 'false');
    render();
  });

  const accInput = document.getElementById('benAccountInput');
  accInput?.addEventListener('input', () => {
    setFormError('addBenError', '');
    setVerifyBanner(null);
    state.verifiedLookup = null;
  });
  document.getElementById('verifyAccountBtn')?.addEventListener('click', () => {
    void verifyAccountNumber(accInput?.value || '');
  });

  document.getElementById('addBenForm')?.addEventListener('submit', async e => {
    e.preventDefault();
    setFormError('addBenError', '');

    const accountNumber = normalizeAccount(accInput?.value || '');
    const nickname = document.getElementById('benNicknameInput')?.value.trim() || '';

    if (accountNumber.length < 6 || accountNumber.length > 34 || !/^[A-Z0-9-]+$/.test(accountNumber)) {
      setFormError('addBenError', 'Account number must be 6 to 34 uppercase alphanumeric characters or hyphens.');
      return;
    }

    const submitBtn = document.getElementById('submitAddBen');
    if (submitBtn) {
      submitBtn.disabled = true;
      submitBtn.textContent = 'Saving...';
    }

    try {
      await apiClient.post('/api/v1/customers/me/beneficiaries', {
        recipientAccountNumber: accountNumber,
        nickname: nickname || null,
      });
      closeAddModal();
      showToast('Beneficiary saved successfully');
      await loadData();
    } catch (err) {
      setFormError('addBenError', err.message || 'Could not save beneficiary. Please check the account number.');
    } finally {
      if (submitBtn) {
        submitBtn.disabled = false;
        submitBtn.textContent = 'Save Beneficiary';
      }
    }
  });

  document.getElementById('confirmRemoveBen')?.addEventListener('click', async () => {
    if (!state.pendingRemove) return;
    const btn = document.getElementById('confirmRemoveBen');
    if (btn) {
      btn.disabled = true;
      btn.textContent = 'Removing...';
    }
    setFormError('removeBenError', '');

    try {
      await apiClient.delete(`/api/v1/customers/me/beneficiaries/${encodeURIComponent(state.pendingRemove.id)}`);
      closeRemoveModal();
      showToast('Beneficiary removed');
      await loadData();
    } catch (err) {
      setFormError('removeBenError', err.message || 'Failed to remove beneficiary.');
    } finally {
      if (btn) {
        btn.disabled = false;
        btn.textContent = 'Remove Beneficiary';
      }
    }
  });
}

document.addEventListener('DOMContentLoaded', async () => {
  wireEvents();
  await loadData();
});
