import { apiClient } from '../../js/api-client.js';
import { VigiliaAuth } from '../../js/auth.js';
import '../../js/components/customer/sidebar.js';
import '../../js/components/customer/top-navbar.js';

const user = VigiliaAuth.getUser();
if (!user || (user.role && user.role !== 'CUSTOMER')) {
  window.location.replace('/login.html');
}

const AVATAR_CLASSES = ['sm-av-slate', 'sm-av-copper', 'sm-av-blue', 'sm-av-teal'];

const state = {
  wallet: {
    loaded: false,
    customerId: '',
    availableBalance: 0,
    totalBalance: 0,
    heldBalance: 0,
    currency: 'EUR',
    accountNumber: '',
    cardFrozen: false,
  },
  beneficiaries: [],
  recentTransfers: [],
  mode: 'saved', // 'saved' | 'manual'
  pendingRecipient: null,
  selectedRecipient: null, // { name, iban, recipientCustomerId, initials, avClass }
  submitting: false,
};

function getCurrencySymbol(curr) {
  const c = (curr || 'EUR').toUpperCase();
  if (c === 'EUR') return '€';
  if (c === 'USD') return '$';
  if (c === 'GBP') return '£';
  if (c === 'MAD') return 'MAD ';
  return `${c} `;
}

function fmtMoney(val, curr = state.wallet.currency) {
  const num = Number(val || 0);
  const sym = getCurrencySymbol(curr);
  return `${sym}${num.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function normalizeIban(raw) {
  return String(raw || '').replace(/\s+/g, '').toUpperCase();
}

function formatIban(iban) {
  const clean = normalizeIban(iban);
  if (!clean) return '—';
  return clean.replace(/(.{4})/g, '$1 ').trim();
}

function maskWalletId(iban) {
  const clean = normalizeIban(iban);
  if (!clean) return '—';
  if (clean.length <= 8) return clean;
  return `${clean.slice(0, 4)}-${clean.slice(4, 8)}-••••-${clean.slice(-4)}`;
}

function computeInitials(name) {
  const clean = String(name || 'VG').replace(/[^\p{L}\p{N}\s]/gu, '').trim() || 'VG';
  return clean.split(/\s+/).map(p => Array.from(p)[0] || '').join('').slice(0, 2).toUpperCase();
}

function fmtDate(dateStr) {
  if (!dateStr) return '';
  const d = new Date(dateStr);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

function escapeHtml(str) {
  return String(str ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

const SEND_ICON_SVG = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="13" height="13"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>`;

function goTo(stepId) {
  ['stepRecipient', 'stepAmount', 'stepConfirm', 'stepSuccess'].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.hidden = (id !== stepId);
  });

  const stepNum = { stepRecipient: 1, stepAmount: 2, stepConfirm: 3, stepSuccess: 4 };
  const current = stepNum[stepId] || 1;

  document.querySelectorAll('.sm-step').forEach((el, idx) => {
    const n = idx + 1;
    el.classList.toggle('active', n === current && stepId !== 'stepSuccess');
    el.classList.toggle('done', n < current || stepId === 'stepSuccess');
  });

  document.querySelectorAll('.sm-step-line').forEach((line, idx) => {
    const n = idx + 1;
    line.classList.toggle('done', n < current || stepId === 'stepSuccess');
  });

  const fillEl = document.getElementById('smStepsFill');
  if (fillEl) {
    fillEl.style.width = `${(current / 4) * 100}%`;
  }

  document.querySelectorAll('.sm-tl-item').forEach((item, idx) => {
    const n = idx + 1;
    item.classList.toggle('active', n === current && stepId !== 'stepSuccess');
    item.classList.toggle('done', n < current || stepId === 'stepSuccess');
  });

  updateSummaryTimeline();
}

function updateSummaryTimeline() {
  const tlRec = document.getElementById('tlRecipientDesc');
  const tlAmt = document.getElementById('tlAmountDesc');
  const tlRev = document.getElementById('tlReviewDesc');
  const tlCon = document.getElementById('tlConfirmDesc');

  if (tlRec) {
    tlRec.textContent = state.selectedRecipient
      ? `${state.selectedRecipient.name} (${maskWalletId(state.selectedRecipient.iban)})`
      : 'Select a recipient from your list or search';
  }
  if (tlAmt) {
    const amt = Number.parseFloat(document.getElementById('sendAmount')?.value || '0');
    tlAmt.textContent = amt > 0
      ? `${fmtMoney(amt)} · Free instant transfer`
      : 'Enter the amount and optional note';
  }
  if (tlRev) {
    tlRev.textContent = state.wallet.loaded && state.wallet.accountNumber
      ? `From Wallet ·••• ${state.wallet.accountNumber.slice(-4)} (${fmtMoney(state.wallet.availableBalance)} avail.)`
      : 'Confirm the details and available balance';
  }
  if (tlCon) {
    tlCon.textContent = 'Complete the transfer';
  }
}

function renderWalletState() {
  const { loaded, availableBalance, currency, accountNumber, cardFrozen } = state.wallet;
  const frozenBanner = document.getElementById('smFrozenBanner');
  const sourceAcct = document.getElementById('smSourceAccount');
  const availEl = document.getElementById('smAvailableBalance');
  const sideBalEl = document.getElementById('sideWalletBalance');
  const currLabel = document.getElementById('smAmountCurrLabel');
  const symEl = document.getElementById('smAmountSymbol');
  const codeEl = document.getElementById('smAmountCode');

  if (frozenBanner) frozenBanner.hidden = !cardFrozen;
  if (sourceAcct) {
    sourceAcct.textContent = loaded && accountNumber
      ? `Vigilia Wallet · •••• ${accountNumber.slice(-4)}`
      : 'Vigilia Wallet';
  }
  if (availEl) availEl.textContent = loaded ? fmtMoney(availableBalance, currency) : 'Unavailable';
  if (sideBalEl) sideBalEl.textContent = loaded ? fmtMoney(availableBalance, currency) : 'Unavailable';
  if (currLabel) currLabel.textContent = currency;
  if (symEl) symEl.textContent = getCurrencySymbol(currency).trim();
  if (codeEl) codeEl.textContent = currency;

  const sym = getCurrencySymbol(currency);
  document.querySelectorAll('.sm-preset-pill').forEach(pill => {
    pill.textContent = `${sym}${pill.dataset.amt}`;
  });

  updateRemainingPreview();
  updateSummaryTimeline();
}

function updateRemainingPreview() {
  const el = document.getElementById('smRemainingPreview');
  if (!el) return;
  const amt = Number.parseFloat(document.getElementById('sendAmount')?.value || '0');
  if (!state.wallet.loaded || Number.isNaN(amt) || amt <= 0) {
    el.textContent = '';
    el.classList.remove('over');
    return;
  }
  const diff = state.wallet.availableBalance - amt;
  if (diff < 0) {
    el.textContent = `Exceeds balance by ${fmtMoney(Math.abs(diff))}`;
    el.classList.add('over');
  } else {
    el.textContent = `Remaining: ${fmtMoney(diff)}`;
    el.classList.remove('over');
  }
}

async function loadWallet() {
  try {
    const res = await apiClient.get('/api/v1/transactions/wallet');
    const wallet = res?.data || res;
    if (wallet) {
      const total = Number(wallet.totalBalance ?? wallet.balance ?? 0);
      const held = Number(wallet.heldBalance ?? wallet.onHoldAmount ?? 0);
      const avail = Number(wallet.availableBalance ?? Math.max(0, total - held));
      state.wallet = {
        loaded: true,
        customerId: wallet.customerId || user?.customerId || user?.id || '',
        availableBalance: avail,
        totalBalance: total,
        heldBalance: held,
        currency: (wallet.currency || 'EUR').toUpperCase(),
        accountNumber: wallet.accountNumber || '',
        cardFrozen: wallet.cardFrozen === true || wallet.cardFrozen === 'true',
      };
    }
  } catch (err) {
    console.warn('[SendMoney] Wallet load failed:', err.message);
    state.wallet.loaded = false;
  }
  renderWalletState();
}

function findLastTransferForBeneficiary(ben) {
  const recCustId = ben?.recipientCustomerId ? String(ben.recipientCustomerId) : '';
  const recIban = normalizeIban(ben?.recipientAccountNumber || ben?.accountNumber || '');

  return state.recentTransfers.find(tx => {
    if (recCustId && tx.recipientCustomerId && String(tx.recipientCustomerId) === recCustId) {
      return true;
    }
    const txIban = normalizeIban(tx.recipientIban || tx.recipientAccountNumber || '');
    return Boolean(recIban && txIban && txIban === recIban);
  }) || null;
}

function selectRecipientAndContinue(recipient) {
  state.selectedRecipient = recipient;
  populateStep2Recipient();
  updateSummaryTimeline();
  goTo('stepAmount');
}

function renderRecipients(filter = '') {
  const recentGrid = document.getElementById('recentRecipientsGrid');
  const tableBody = document.getElementById('allRecipientsBody');
  const badgeEl = document.getElementById('beneficiaryCountBadge');
  const q = filter.trim().toLowerCase();

  const filtered = state.beneficiaries.filter(b => {
    const name = (b.nickname || b.recipientName || '').toLowerCase();
    const iban = (b.recipientAccountNumber || b.accountNumber || '').toLowerCase();
    return !q || name.includes(q) || iban.includes(q);
  });

  if (badgeEl) {
    badgeEl.textContent = `${filtered.length} saved`;
  }

  // Sort copy by most recent transfer timestamp for "Recent recipients"
  const sortedRecent = [...filtered].sort((a, b) => {
    const txA = findLastTransferForBeneficiary(a);
    const txB = findLastTransferForBeneficiary(b);
    const tA = txA ? new Date(txA.timestamp || txA.createdAt || 0).getTime() : 0;
    const tB = txB ? new Date(txB.timestamp || txB.createdAt || 0).getTime() : 0;
    if (tB !== tA) return tB - tA;
    return new Date(b.createdAt || 0).getTime() - new Date(a.createdAt || 0).getTime();
  });

  // Render 4-column Recent Recipients Grid
  if (recentGrid) {
    if (sortedRecent.length === 0) {
      recentGrid.innerHTML = `
        <div class="sm-loading-box">
          ${state.beneficiaries.length === 0
            ? 'No saved beneficiaries yet. Switch to <strong>Direct IBAN</strong> above or add one in <a href="beneficiaries.html" style="color:#b45328;text-decoration:underline;">Beneficiaries</a>.'
            : 'No recipients match your search.'}
        </div>
      `;
    } else {
      recentGrid.innerHTML = sortedRecent.slice(0, 4).map((b, idx) => {
        const name = b.nickname || b.recipientName || 'Vigilia Recipient';
        const iban = normalizeIban(b.recipientAccountNumber || b.accountNumber || '');
        const initials = computeInitials(name);
        const avClass = AVATAR_CLASSES[idx % AVATAR_CLASSES.length];
        const lastTx = findLastTransferForBeneficiary(b);
        const lastBadge = lastTx
          ? fmtMoney(Math.abs(Number(lastTx.amount || 0)), lastTx.currency || state.wallet.currency)
          : 'Saved';
        const isSelected = state.pendingRecipient && state.pendingRecipient.iban === iban;
        const shortIban = iban.length >= 4 ? `IBAN •••• ${iban.slice(-4)}` : maskWalletId(iban);

        return `
          <button type="button" class="sm-recent-card${isSelected ? ' selected' : ''}"
            data-name="${escapeHtml(name)}"
            data-iban="${escapeHtml(iban)}"
            data-cid="${escapeHtml(b.recipientCustomerId || '')}"
            data-initials="${escapeHtml(initials)}"
            data-av="${avClass}">
            <div class="sm-avatar ${avClass}">${escapeHtml(initials)}</div>
            <div class="sm-recent-info">
              <strong class="sm-recent-name">${escapeHtml(name)}</strong>
              <span class="sm-recent-id font-mono">${escapeHtml(shortIban)}</span>
            </div>
            <span class="sm-recent-last-pill font-mono">${escapeHtml(lastBadge)}</span>
          </button>
        `;
      }).join('');

      recentGrid.querySelectorAll('.sm-recent-card').forEach(card => {
        card.addEventListener('click', () => {
          recentGrid.querySelectorAll('.sm-recent-card').forEach(c => c.classList.remove('selected'));
          card.classList.add('selected');
          state.pendingRecipient = {
            name: card.dataset.name,
            iban: card.dataset.iban,
            recipientCustomerId: card.dataset.cid || null,
            initials: card.dataset.initials,
            avClass: card.dataset.av,
          };
          const continueBtn = document.getElementById('btnStep1Continue');
          if (continueBtn) continueBtn.disabled = false;
          updateSummaryTimeline();
        });
        card.addEventListener('dblclick', () => {
          selectRecipientAndContinue({
            name: card.dataset.name,
            iban: card.dataset.iban,
            recipientCustomerId: card.dataset.cid || null,
            initials: card.dataset.initials,
            avClass: card.dataset.av,
          });
        });
      });
    }
  }

  // Render All Recipients Table
  if (tableBody) {
    if (filtered.length === 0) {
      tableBody.innerHTML = `
        <tr>
          <td colspan="5" class="sm-table-empty">
            ${state.beneficiaries.length === 0 ? 'No saved beneficiaries found.' : 'No matching recipients.'}
          </td>
        </tr>
      `;
    } else {
      tableBody.innerHTML = filtered.map((b, idx) => {
        const name = b.nickname || b.recipientName || 'Vigilia Recipient';
        const iban = normalizeIban(b.recipientAccountNumber || b.accountNumber || '');
        const initials = computeInitials(name);
        const avClass = AVATAR_CLASSES[idx % AVATAR_CLASSES.length];
        const lastTx = findLastTransferForBeneficiary(b);
        const lastText = lastTx
          ? `${fmtDate(lastTx.timestamp || lastTx.createdAt)} · ${fmtMoney(Math.abs(Number(lastTx.amount || 0)), lastTx.currency || state.wallet.currency)}`
          : 'No transfers yet';

        return `
          <tr>
            <td>
              <div class="sm-table-name-cell">
                <div class="sm-avatar ${avClass}">${escapeHtml(initials)}</div>
                <strong>${escapeHtml(name)}</strong>
              </div>
            </td>
            <td class="sm-table-id"><span class="sm-id-chip font-mono">${escapeHtml(maskWalletId(iban))}</span></td>
            <td class="sm-table-last">${escapeHtml(lastText)}</td>
            <td><span class="sm-status-active">Active</span></td>
            <td class="sm-table-action-cell">
              <button type="button" class="sm-row-send-btn"
                data-name="${escapeHtml(name)}"
                data-iban="${escapeHtml(iban)}"
                data-cid="${escapeHtml(b.recipientCustomerId || '')}"
                data-initials="${escapeHtml(initials)}"
                data-av="${avClass}">
                ${SEND_ICON_SVG}
                Send
              </button>
            </td>
          </tr>
        `;
      }).join('');

      tableBody.querySelectorAll('.sm-row-send-btn').forEach(btn => {
        btn.addEventListener('click', () => {
          selectRecipientAndContinue({
            name: btn.dataset.name,
            iban: btn.dataset.iban,
            recipientCustomerId: btn.dataset.cid || null,
            initials: btn.dataset.initials,
            avClass: btn.dataset.av,
          });
        });
      });
    }
  }
}

async function loadBeneficiaries() {
  try {
    const res = await apiClient.get('/api/v1/customers/me/beneficiaries');
    const list = Array.isArray(res) ? res : (res?.data || []);
    state.beneficiaries = Array.isArray(list) ? list : [];
  } catch (err) {
    console.warn('[SendMoney] Beneficiaries load failed:', err.message);
    state.beneficiaries = [];
  }
  renderRecipients(document.getElementById('recipientSearch')?.value || '');
}

async function loadRecentTransfers() {
  try {
    const res = await apiClient.get('/api/v1/transactions?size=50');
    const list = Array.isArray(res) ? res : (res?.data?.content || res?.content || res?.data || []);
    state.recentTransfers = (Array.isArray(list) ? list : [])
      .filter(tx => String(tx.type || '').toUpperCase() === 'TRANSFER')
      .sort((a, b) => new Date(b.timestamp || b.createdAt || 0).getTime() - new Date(a.timestamp || a.createdAt || 0).getTime());
  } catch (err) {
    console.warn('[SendMoney] Recent transfers load failed:', err.message);
    state.recentTransfers = [];
  }
  renderRecipients(document.getElementById('recipientSearch')?.value || '');
}

function populateStep2Recipient() {
  if (!state.selectedRecipient) return;
  const nameEl = document.getElementById('selectedRecipientName');
  const ibanEl = document.getElementById('selectedRecipientIban');
  const avEl = document.getElementById('selectedAvatar');
  if (nameEl) nameEl.textContent = state.selectedRecipient.name;
  if (ibanEl) ibanEl.textContent = formatIban(state.selectedRecipient.iban);
  if (avEl) {
    avEl.textContent = state.selectedRecipient.initials;
    avEl.className = 'sm-sel-avatar';
  }
}

function showError(elId, msg) {
  const el = document.getElementById(elId);
  if (!el) return;
  if (!msg) {
    el.hidden = true;
    el.textContent = '';
  } else {
    el.hidden = false;
    el.textContent = msg;
  }
}

function renderOutcome(status, txData, amount) {
  const normalized = String(status || 'APPROVED').toUpperCase();
  const iconEl = document.getElementById('smOutcomeIcon');
  const badgeEl = document.getElementById('smOutcomeBadge');
  const titleEl = document.getElementById('smOutcomeTitle');
  const descEl = document.getElementById('smOutcomeDesc');

  const isReview = normalized === 'PENDING_REVIEW' || normalized === 'REVIEW';
  const isDeclined = normalized === 'DECLINED' || normalized === 'REJECTED';
  const variant = isDeclined ? 'declined' : (isReview ? 'review' : 'approved');

  if (iconEl) {
    iconEl.className = `sm-outcome-icon ${variant}`;
    if (isReview) {
      iconEl.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" width="30" height="30"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>`;
    } else if (isDeclined) {
      iconEl.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" width="30" height="30"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>`;
    } else {
      iconEl.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" width="30" height="30"><polyline points="20 6 9 17 4 12"/></svg>`;
    }
  }

  if (badgeEl) {
    badgeEl.className = `sm-outcome-badge ${variant}`;
    badgeEl.textContent = normalized;
  }

  if (titleEl) {
    titleEl.textContent = isReview
      ? 'Transfer Held for Security Review'
      : (isDeclined ? 'Transfer Declined' : 'Transfer Completed');
  }

  const fmtAmt = fmtMoney(amount, txData?.currency || state.wallet.currency);
  const recName = escapeHtml(state.selectedRecipient?.name || 'Recipient');
  const reason = txData?.decisionReason ? ` (${escapeHtml(txData.decisionReason)})` : '';
  if (descEl) {
    if (isReview) {
      descEl.innerHTML = `Your transfer of <strong>${escapeHtml(fmtAmt)}</strong> to <strong>${recName}</strong> is under review${reason}. You'll be notified once a decision is made.`;
    } else if (isDeclined) {
      descEl.innerHTML = `Your transfer of <strong>${escapeHtml(fmtAmt)}</strong> to <strong>${recName}</strong> was declined${reason}.`;
    } else {
      descEl.innerHTML = `Your transfer of <strong>${escapeHtml(fmtAmt)}</strong> to <strong>${recName}</strong> was completed.`;
    }
  }

  const txIdEl = document.getElementById('receiptTxId');
  const recIbanEl = document.getElementById('receiptIban');
  const newBalEl = document.getElementById('receiptNewBalance');
  if (txIdEl) txIdEl.textContent = txData?.id || txData?.transactionId || '—';
  if (recIbanEl) recIbanEl.textContent = formatIban(state.selectedRecipient?.iban);
  if (newBalEl) newBalEl.textContent = state.wallet.loaded ? fmtMoney(state.wallet.availableBalance) : '—';
}

function wireEvents() {
  const tabSaved = document.getElementById('tabSavedMode');
  const tabManual = document.getElementById('tabManualMode');
  const paneSaved = document.getElementById('paneSavedRecipients');
  const paneManual = document.getElementById('paneManualRecipient');

  const setMode = (mode) => {
    state.mode = mode;
    tabSaved?.classList.toggle('active', mode === 'saved');
    tabManual?.classList.toggle('active', mode === 'manual');
    if (paneSaved) paneSaved.hidden = mode !== 'saved';
    if (paneManual) paneManual.hidden = mode !== 'manual';
    showError('manualRecipientError', '');
  };

  tabSaved?.addEventListener('click', () => setMode('saved'));
  tabManual?.addEventListener('click', () => setMode('manual'));

  const searchInput = document.getElementById('recipientSearch');
  const clearSearchBtn = document.getElementById('btnClearSearch');
  searchInput?.addEventListener('input', (e) => {
    const val = e.target.value;
    if (clearSearchBtn) clearSearchBtn.hidden = !val;
    renderRecipients(val);
  });
  clearSearchBtn?.addEventListener('click', () => {
    if (!searchInput) return;
    searchInput.value = '';
    clearSearchBtn.hidden = true;
    renderRecipients('');
    searchInput.focus();
  });

  document.getElementById('btnStep1Cancel')?.addEventListener('click', () => {
    state.pendingRecipient = null;
    document.querySelectorAll('.sm-recent-card.selected').forEach(c => c.classList.remove('selected'));
    const continueBtn = document.getElementById('btnStep1Continue');
    if (continueBtn) continueBtn.disabled = true;
    if (searchInput && searchInput.value) {
      searchInput.value = '';
      if (clearSearchBtn) clearSearchBtn.hidden = true;
      renderRecipients('');
    }
    updateSummaryTimeline();
  });

  document.getElementById('btnStep1Continue')?.addEventListener('click', () => {
    if (state.pendingRecipient) {
      selectRecipientAndContinue(state.pendingRecipient);
    }
  });

  document.querySelectorAll('.sm-step').forEach(stepEl => {
    stepEl.addEventListener('click', () => {
      if (!stepEl.classList.contains('done')) return;
      const successVisible = !document.getElementById('stepSuccess')?.hidden;
      if (successVisible) return;
      const stepMap = { '1': 'stepRecipient', '2': 'stepAmount', '3': 'stepConfirm' };
      const target = stepMap[stepEl.dataset.step];
      if (target) goTo(target);
    });
  });

  const manualIbanEl = document.getElementById('manualIban');
  const btnManualContinue = document.getElementById('btnManualContinue');
  manualIbanEl?.addEventListener('input', () => {
    showError('manualRecipientError', '');
    if (btnManualContinue) {
      btnManualContinue.disabled = normalizeIban(manualIbanEl.value).length < 6;
    }
  });

  btnManualContinue?.addEventListener('click', async () => {
    const iban = normalizeIban(manualIbanEl?.value || '');
    if (iban.length < 6 || iban.length > 34 || !/^[A-Z0-9]+$/.test(iban)) {
      showError('manualRecipientError', 'Please enter a valid IBAN or Wallet ID (6 to 34 uppercase alphanumeric characters).');
      return;
    }

    btnManualContinue.disabled = true;
    btnManualContinue.textContent = 'Verifying account...';
    showError('manualRecipientError', '');

    let verifiedName = '';
    try {
      const res = await apiClient.get(`/api/v1/customers/lookup?account_number=${encodeURIComponent(iban)}`);
      const lookup = res?.data || res;
      verifiedName = lookup?.maskedName || lookup?.fullName || lookup?.name || '';
    } catch (err) {
      showError('manualRecipientError', err.message || 'Destination account not found on Vigilia.');
      btnManualContinue.disabled = false;
      btnManualContinue.textContent = 'Continue to Details →';
      return;
    }

    const customNickname = document.getElementById('manualNickname')?.value.trim();
    const nickname = customNickname || verifiedName || `Account ${iban.slice(-4)}`;

    const saveChecked = document.getElementById('manualSaveBeneficiary')?.checked;
    if (saveChecked) {
      const exists = state.beneficiaries.some(
        b => normalizeIban(b.recipientAccountNumber || b.accountNumber) === iban
      );
      if (!exists) {
        try {
          await apiClient.post('/api/v1/customers/me/beneficiaries', {
            recipientAccountNumber: iban,
            nickname,
          });
          await loadBeneficiaries();
        } catch (err) {
          console.warn('[SendMoney] Could not save beneficiary:', err.message);
        }
      }
    }

    btnManualContinue.disabled = false;
    btnManualContinue.textContent = 'Continue to Details →';

    selectRecipientAndContinue({
      name: nickname,
      iban,
      recipientCustomerId: null,
      initials: computeInitials(nickname),
      avClass: 'sm-av-copper',
    });
  });

  document.getElementById('btnChangeRecipient')?.addEventListener('click', () => goTo('stepRecipient'));
  document.getElementById('btnStep2Back')?.addEventListener('click', () => goTo('stepRecipient'));

  const amountInput = document.getElementById('sendAmount');
  const noteInput = document.getElementById('sendNote');

  amountInput?.addEventListener('input', () => {
    showError('amountError', '');
    document.querySelectorAll('.sm-preset-pill').forEach(p => {
      p.classList.toggle('active', Number(p.dataset.amt) === Number(amountInput.value));
    });
    updateRemainingPreview();
    updateSummaryTimeline();
  });

  document.querySelectorAll('.sm-preset-pill').forEach(pill => {
    pill.addEventListener('click', () => {
      if (!amountInput) return;
      amountInput.value = Number(pill.dataset.amt).toFixed(2);
      document.querySelectorAll('.sm-preset-pill').forEach(p => p.classList.remove('active'));
      pill.classList.add('active');
      showError('amountError', '');
      updateRemainingPreview();
      updateSummaryTimeline();
    });
  });

  document.getElementById('btnSendMax')?.addEventListener('click', () => {
    if (!amountInput) return;
    amountInput.value = Math.max(0, state.wallet.availableBalance).toFixed(2);
    document.querySelectorAll('.sm-preset-pill').forEach(p => p.classList.remove('active'));
    showError('amountError', '');
    updateRemainingPreview();
    updateSummaryTimeline();
  });

  noteInput?.addEventListener('input', () => {
    const countEl = document.getElementById('noteCharCount');
    if (countEl) countEl.textContent = `${noteInput.value.length} / 120`;
  });

  document.querySelectorAll('.sm-ref-chip').forEach(chip => {
    chip.addEventListener('click', () => {
      if (!noteInput) return;
      noteInput.value = chip.dataset.ref || chip.textContent;
      const countEl = document.getElementById('noteCharCount');
      if (countEl) countEl.textContent = `${noteInput.value.length} / 120`;
    });
  });

  document.getElementById('btnStep2Continue')?.addEventListener('click', () => {
    const amount = Number.parseFloat(amountInput?.value || '0');
    if (Number.isNaN(amount) || amount <= 0) {
      showError('amountError', 'Please enter a transfer amount greater than 0.');
      return;
    }
    if (state.wallet.loaded && amount > state.wallet.availableBalance) {
      showError('amountError', `Insufficient available balance (${fmtMoney(state.wallet.availableBalance)}).`);
      return;
    }

    const note = noteInput?.value.trim() || `Transfer to ${state.selectedRecipient?.name || 'Recipient'}`;
    const fmtAmt = fmtMoney(amount);
    const afterBal = Math.max(0, state.wallet.availableBalance - amount);

    document.getElementById('confirmAmount').textContent = fmtAmt;
    document.getElementById('confirmName').textContent = state.selectedRecipient?.name || '—';
    document.getElementById('confirmIban').textContent = formatIban(state.selectedRecipient?.iban);
    document.getElementById('confirmSource').textContent = state.wallet.loaded && state.wallet.accountNumber
      ? `Vigilia Wallet · •••• ${state.wallet.accountNumber.slice(-4)}`
      : 'Vigilia Wallet';
    document.getElementById('confirmNote').textContent = note;
    document.getElementById('confirmAfterBalance').textContent = state.wallet.loaded ? fmtMoney(afterBal) : '—';
    document.getElementById('confirmTotal').textContent = fmtAmt;

    const sendLabel = document.getElementById('btnSendLabel');
    if (sendLabel) sendLabel.textContent = `Send ${fmtAmt}`;

    showError('confirmError', '');
    goTo('stepConfirm');
  });

  document.getElementById('btnStep3Back')?.addEventListener('click', () => goTo('stepAmount'));

  document.getElementById('btnSend')?.addEventListener('click', async () => {
    if (state.submitting) return;
    const amount = Number.parseFloat(amountInput?.value || '0');
    if (!state.selectedRecipient?.iban || Number.isNaN(amount) || amount <= 0) return;

    const btn = document.getElementById('btnSend');
    const sendLabel = document.getElementById('btnSendLabel');
    state.submitting = true;
    if (btn) btn.disabled = true;
    if (sendLabel) sendLabel.textContent = 'Sending...';
    showError('confirmError', '');

    try {
      const customerId = state.wallet.customerId || user?.customerId || user?.id || user?.sub;
      const payload = {
        customerId,
        type: 'TRANSFER',
        amount,
        currency: state.wallet.currency || 'EUR',
        recipientIban: normalizeIban(state.selectedRecipient.iban),
        transferReference: noteInput?.value.trim() || `Transfer to ${state.selectedRecipient.name}`,
      };

      const res = await apiClient.post('/api/v1/transactions', payload, {
        headers: { 'Idempotency-Key': crypto.randomUUID() },
      });

      const txData = res?.data || res;
      await Promise.all([loadWallet(), loadRecentTransfers()]);
      renderOutcome(txData?.status || 'APPROVED', txData, amount);
      goTo('stepSuccess');
    } catch (err) {
      showError('confirmError', err.message || 'Transfer could not be completed. Please verify the recipient IBAN and try again.');
    } finally {
      state.submitting = false;
      if (btn) btn.disabled = false;
      if (sendLabel) sendLabel.textContent = `Send ${fmtMoney(amount)}`;
    }
  });

  const copyBtn = document.getElementById('btnCopyTxId');
  copyBtn?.addEventListener('click', async () => {
    const txId = document.getElementById('receiptTxId')?.textContent?.trim();
    if (!txId || txId === '—') return;
    try {
      await navigator.clipboard.writeText(txId);
      copyBtn.classList.add('copied');
      setTimeout(() => copyBtn.classList.remove('copied'), 1500);
    } catch {
      // Clipboard unavailable
    }
  });

  document.getElementById('btnSendAnother')?.addEventListener('click', () => {
    state.selectedRecipient = null;
    if (amountInput) amountInput.value = '';
    if (noteInput) noteInput.value = '';
    if (manualIbanEl) manualIbanEl.value = '';
    const manualNick = document.getElementById('manualNickname');
    if (manualNick) manualNick.value = '';
    const saveCheck = document.getElementById('manualSaveBeneficiary');
    if (saveCheck) saveCheck.checked = false;
    if (btnManualContinue) btnManualContinue.disabled = true;
    const countEl = document.getElementById('noteCharCount');
    if (countEl) countEl.textContent = '0 / 120';
    document.querySelectorAll('.sm-preset-pill').forEach(p => p.classList.remove('active'));
    updateRemainingPreview();
    goTo('stepRecipient');
  });
}

document.addEventListener('DOMContentLoaded', async () => {
  goTo('stepRecipient');
  wireEvents();
  await Promise.all([loadWallet(), loadBeneficiaries(), loadRecentTransfers()]);

  const params = new URLSearchParams(window.location.search);
  const prefillIban = normalizeIban(params.get('iban') || '');
  if (prefillIban) {
    const matched = state.beneficiaries.find(
      b => normalizeIban(b.recipientAccountNumber || b.accountNumber) === prefillIban
    );
    const name = params.get('name') || matched?.nickname || matched?.recipientName || `Account ${prefillIban.slice(-4)}`;
    selectRecipientAndContinue({
      name,
      iban: prefillIban,
      recipientCustomerId: matched?.recipientCustomerId || null,
      initials: computeInitials(name),
      avClass: 'sm-av-copper',
    });
  }
});
