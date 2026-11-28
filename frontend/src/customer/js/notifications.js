import '../../js/components/customer/sidebar.js';
import '../../js/components/customer/top-navbar.js';
import { VigiliaAuth } from '../../js/auth.js';
import { notificationStore, escapeHtml, formatRelativeTime } from '../../js/notification-store.js';

const user = VigiliaAuth.getUser();
if (!user || (user.role && user.role !== 'CUSTOMER')) {
  window.location.replace('/login.html');
}

const pageState = {
  filter: 'ALL', // ALL | UNREAD | TRANSACTION | REVIEW | SECURITY
  search: '',
  page: 0,
  size: 20,
  totalPages: 1,
  items: [],
  unreadCount: 0,
  totalCount: 0,
  categoryCounts: { ALL: 0, UNREAD: 0, TRANSACTION: 0, REVIEW: 0, SECURITY: 0, SYSTEM: 0 },
  activeModalItem: null,
  preferences: null,
};

let toastTimer = null;
function showToast(msg) {
  const el = document.getElementById('notifToast');
  if (!el) return;
  el.textContent = msg;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    el.hidden = true;
  }, 2600);
}

function getIconHtml(severity, category) {
  const sev = (severity || '').toUpperCase();
  const cat = (category || '').toUpperCase();
  if (sev === 'CRITICAL') {
    return {
      cls: 'danger',
      svg: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" width="16" height="16"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>`,
    };
  }
  if (sev === 'WARNING' || cat === 'REVIEW') {
    return {
      cls: 'warn',
      svg: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" width="16" height="16"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>`,
    };
  }
  if (cat === 'SECURITY') {
    return {
      cls: 'info',
      svg: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" width="16" height="16"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>`,
    };
  }
  if (sev === 'SUCCESS') {
    return {
      cls: 'ok',
      svg: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" width="16" height="16"><polyline points="20 6 9 17 4 12"/></svg>`,
    };
  }
  return {
    cls: 'info',
    svg: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" width="16" height="16"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>`,
  };
}

function getDateBucket(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return 'Earlier';
  const now = new Date();
  const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const startYesterday = startToday - 86400000;
  const ts = d.getTime();
  if (ts >= startToday) return 'Today';
  if (ts >= startYesterday) return 'Yesterday';
  return 'Earlier';
}

function formatExactTime(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function updateKpisAndTabs() {
  const counts = pageState.categoryCounts || {};
  const unread = pageState.unreadCount || 0;

  const kpiUnread = document.getElementById('kpiUnreadCount');
  const kpiUnreadSub = document.getElementById('kpiUnreadSub');
  const kpiTx = document.getElementById('kpiTxCount');
  const kpiReview = document.getElementById('kpiReviewCount');
  const kpiSecurity = document.getElementById('kpiSecurityCount');

  if (kpiUnread) kpiUnread.textContent = String(unread);
  if (kpiUnreadSub) {
    kpiUnreadSub.textContent = unread === 0
      ? 'All caught up'
      : `${unread} alert${unread === 1 ? '' : 's'} require attention`;
  }
  if (kpiTx) kpiTx.textContent = String(counts.TRANSACTION ?? 0);
  if (kpiReview) kpiReview.textContent = String(counts.REVIEW ?? 0);
  if (kpiSecurity) kpiSecurity.textContent = String(counts.SECURITY ?? 0);

  ['ALL', 'UNREAD', 'TRANSACTION', 'REVIEW', 'SECURITY'].forEach((key) => {
    const el = document.getElementById(`tabCount${key}`);
    if (el) el.textContent = String(counts[key] ?? 0);
  });

  const markAllBtn = document.getElementById('markAllReadBtn');
  if (markAllBtn) markAllBtn.disabled = unread === 0;

  const clearReadBtn = document.getElementById('clearReadBtn');
  const readCount = Math.max(0, (pageState.totalCount || 0) - unread);
  if (clearReadBtn) clearReadBtn.disabled = readCount === 0;
}

function renderFeed() {
  const container = document.getElementById('notifFeedContainer');
  if (!container) return;

  if (!pageState.items.length) {
    const isFiltered = pageState.filter !== 'ALL' || pageState.search.trim() !== '';
    container.innerHTML = `
      <div class="pg-card notif-empty-state">
        <div class="notif-empty-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" width="24" height="24">
            <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/>
            <path d="M13.73 21a2 2 0 0 1-3.46 0"/>
          </svg>
        </div>
        <h3>${isFiltered ? 'No matching notifications' : 'You are all caught up'}</h3>
        <p>${isFiltered
          ? 'Try clearing your search query or switching to another filter tab.'
          : 'New transaction approvals, security alerts, and transfer reviews will appear here in real time.'}</p>
        ${isFiltered ? '<button type="button" class="btn btn-outline btn-sm" id="resetFiltersBtn">Reset filters</button>' : ''}
      </div>
    `;
    document.getElementById('resetFiltersBtn')?.addEventListener('click', () => {
      pageState.filter = 'ALL';
      pageState.search = '';
      pageState.page = 0;
      const searchEl = document.getElementById('notifSearchInput');
      if (searchEl) searchEl.value = '';
      document.querySelectorAll('.notif-tab').forEach((t) => {
        t.classList.toggle('active', t.dataset.filter === 'ALL');
      });
      loadNotifications();
    });
    return;
  }

  const buckets = new Map([
    ['Today', []],
    ['Yesterday', []],
    ['Earlier', []],
  ]);

  for (const item of pageState.items) {
    buckets.get(getDateBucket(item.createdAt)).push(item);
  }

  let html = '';
  for (const [label, groupItems] of buckets.entries()) {
    if (!groupItems.length) continue;
    html += `<div class="notif-date-group">`;
    html += `<div class="notif-date-label">${label}</div>`;
    html += `<div class="pg-card notif-group">`;
    html += groupItems.map((n) => renderNotificationRow(n)).join('');
    html += `</div></div>`;
  }

  container.innerHTML = html;

  container.querySelectorAll('.notif-item').forEach((row) => {
    const id = row.dataset.id;
    row.addEventListener('click', (e) => {
      if (e.target.closest('.notif-action-btn')) return;
      const item = pageState.items.find((x) => x.id === id);
      if (item) openDetailModal(item);
    });
  });

  container.querySelectorAll('.notif-toggle-read-btn').forEach((btn) => {
    btn.addEventListener('click', async (e) => {
      e.stopPropagation();
      const id = btn.dataset.id;
      const isRead = btn.dataset.read === 'true';
      try {
        if (isRead) {
          await notificationStore.markAsUnread(id);
          showToast('Marked as unread');
        } else {
          await notificationStore.markAsRead(id);
          showToast('Marked as read');
        }
        await loadNotifications();
      } catch (err) {
        showToast(err.message || 'Unable to update notification');
      }
    });
  });

  container.querySelectorAll('.notif-dismiss-btn').forEach((btn) => {
    btn.addEventListener('click', async (e) => {
      e.stopPropagation();
      const id = btn.dataset.id;
      try {
        await notificationStore.dismiss(id);
        showToast('Notification dismissed');
        await loadNotifications();
      } catch (err) {
        showToast(err.message || 'Unable to dismiss notification');
      }
    });
  });
}

function renderNotificationRow(n) {
  const icon = getIconHtml(n.severity, n.category);
  const catLower = (n.category || 'system').toLowerCase();
  const catLabel = catLower.charAt(0).toUpperCase() + catLower.slice(1);
  const meta = n.metadata || {};
  const amountChip = meta.amount != null
    ? `<span class="notif-amount-chip">${escapeHtml(String(meta.amount))} ${escapeHtml(meta.currency || 'EUR')}</span>`
    : '';

  return `
    <div class="notif-item ${n.read ? '' : 'unread'}" data-id="${escapeHtml(n.id)}">
      <div class="notif-icon ${icon.cls}">${icon.svg}</div>
      <div class="notif-content">
        <div class="notif-title-line">
          <strong class="notif-title">${escapeHtml(n.title)}</strong>
          <span class="notif-cat-pill ${escapeHtml(catLower)}">${escapeHtml(catLabel)}</span>
          ${n.read ? '' : '<span class="notif-unread-pill">New</span>'}
        </div>
        <p class="notif-body">${escapeHtml(n.message)}</p>
        <div class="notif-meta-row">
          <span class="notif-time" title="${escapeHtml(formatExactTime(n.createdAt))}">${escapeHtml(formatRelativeTime(n.createdAt))}</span>
          ${amountChip}
        </div>
      </div>
      <div class="notif-actions">
        <button type="button"
                class="notif-action-btn notif-toggle-read-btn"
                data-id="${escapeHtml(n.id)}"
                data-read="${n.read ? 'true' : 'false'}"
                title="${n.read ? 'Mark as unread' : 'Mark as read'}"
                aria-label="${n.read ? 'Mark as unread' : 'Mark as read'}">
          ${n.read
            ? '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="15" height="15"><circle cx="12" cy="12" r="9"/></svg>'
            : '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" width="15" height="15"><polyline points="20 6 9 17 4 12"/></svg>'}
        </button>
        <button type="button"
                class="notif-action-btn dismiss notif-dismiss-btn"
                data-id="${escapeHtml(n.id)}"
                title="Dismiss"
                aria-label="Dismiss notification">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="15" height="15"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
        </button>
      </div>
    </div>
  `;
}

function renderPagination() {
  const pagEl = document.getElementById('notifPagination');
  const infoEl = document.getElementById('pageInfoText');
  const prevBtn = document.getElementById('prevPageBtn');
  const nextBtn = document.getElementById('nextPageBtn');
  if (!pagEl) return;

  if (pageState.totalPages <= 1) {
    pagEl.hidden = true;
    return;
  }
  pagEl.hidden = false;
  if (infoEl) infoEl.textContent = `Page ${pageState.page + 1} of ${pageState.totalPages}`;
  if (prevBtn) prevBtn.disabled = pageState.page <= 0;
  if (nextBtn) nextBtn.disabled = pageState.page + 1 >= pageState.totalPages;
}

async function loadNotifications() {
  try {
    const isUnreadTab = pageState.filter === 'UNREAD';
    const category = isUnreadTab ? 'ALL' : pageState.filter;
    const status = isUnreadTab ? 'UNREAD' : 'ALL';
    const updateGlobal = pageState.filter === 'ALL' && !pageState.search.trim() && pageState.page === 0;

    const res = await notificationStore.fetchList({
      category,
      status,
      search: pageState.search,
      page: pageState.page,
      size: pageState.size,
      updateGlobal,
    });

    if (res) {
      pageState.items = Array.isArray(res.items) ? res.items : [];
      pageState.unreadCount = res.unreadCount ?? 0;
      pageState.totalCount = res.totalCount ?? pageState.items.length;
      pageState.totalPages = res.totalPages ?? 1;
      if (res.categoryCounts) pageState.categoryCounts = res.categoryCounts;
    }

    updateKpisAndTabs();
    renderFeed();
    renderPagination();
  } catch (err) {
    const container = document.getElementById('notifFeedContainer');
    if (container) {
      container.innerHTML = `
        <div class="pg-card notif-empty-state">
          <h3>Unable to load notifications</h3>
          <p>${escapeHtml(err.message || 'Please check your connection and try again.')}</p>
          <button type="button" class="btn btn-outline btn-sm" id="retryNotifsBtn">Retry</button>
        </div>
      `;
      document.getElementById('retryNotifsBtn')?.addEventListener('click', loadNotifications);
    }
  }
}

async function openDetailModal(item) {
  const modal = document.getElementById('notifDetailModal');
  if (!modal) return;
  pageState.activeModalItem = item;

  if (!item.read) {
    try {
      await notificationStore.markAsRead(item.id);
      item.read = true;
      await loadNotifications();
    } catch (_) {}
  }

  const catLower = (item.category || 'system').toLowerCase();
  const badgesEl = document.getElementById('modalNotifBadges');
  if (badgesEl) {
    badgesEl.innerHTML = `
      <span class="notif-cat-pill ${escapeHtml(catLower)}">${escapeHtml(catLower)}</span>
      <span class="notif-cat-pill system">${escapeHtml(item.channel || 'IN_APP')}</span>
    `;
  }

  document.getElementById('modalNotifTitle').textContent = item.title || 'Notification';
  document.getElementById('modalNotifTime').textContent = formatExactTime(item.createdAt);
  document.getElementById('modalNotifBody').textContent = item.message || '';

  const metaEl = document.getElementById('modalNotifMeta');
  const meta = item.metadata || {};
  const entries = Object.entries(meta).filter(([, v]) => v !== null && v !== undefined && v !== '');
  if (item.transactionId) {
    entries.unshift(['Transaction ID', item.transactionId]);
  }
  if (entries.length && metaEl) {
    metaEl.hidden = false;
    metaEl.innerHTML = entries.map(([k, v]) => `
      <div class="notif-meta-cell">
        <span>${escapeHtml(k)}</span>
        <strong class="font-mono">${escapeHtml(String(v))}</strong>
      </div>
    `).join('');
  } else if (metaEl) {
    metaEl.hidden = true;
  }

  const viewTxBtn = document.getElementById('modalViewTxBtn');
  if (viewTxBtn) {
    viewTxBtn.hidden = !item.transactionId;
    if (item.transactionId) {
      viewTxBtn.href = `transactions.html?search=${encodeURIComponent(item.transactionId)}`;
    }
  }

  const toggleBtn = document.getElementById('modalToggleReadBtn');
  if (toggleBtn) {
    toggleBtn.textContent = item.read ? 'Mark as unread' : 'Mark as read';
  }

  modal.hidden = false;
}

function closeDetailModal() {
  const modal = document.getElementById('notifDetailModal');
  if (modal) modal.hidden = true;
  pageState.activeModalItem = null;
}

async function loadPreferences() {
  const badge = document.getElementById('prefsStatusBadge');
  try {
    const prefs = await notificationStore.getPreferences();
    pageState.preferences = prefs;
    document.querySelectorAll('input[data-pref]').forEach((input) => {
      const key = input.dataset.pref;
      if (key in prefs) {
        input.checked = Boolean(prefs[key]);
      }
    });
    if (badge) {
      badge.textContent = 'Synced';
      badge.classList.remove('saving');
    }
  } catch (err) {
    if (badge) badge.textContent = 'Offline';
  }
}

function bindPreferenceToggles() {
  document.querySelectorAll('input[data-pref]').forEach((input) => {
    input.addEventListener('change', async () => {
      const key = input.dataset.pref;
      const newVal = input.checked;
      const prevVal = !newVal;
      const badge = document.getElementById('prefsStatusBadge');
      if (badge) {
        badge.textContent = 'Saving...';
        badge.classList.add('saving');
      }

      const payload = { ...(pageState.preferences || {}), [key]: newVal };
      try {
        const updated = await notificationStore.updatePreferences(payload);
        pageState.preferences = updated || payload;
        if (badge) {
          badge.textContent = 'Saved';
          badge.classList.remove('saving');
        }
        showToast('Notification preferences saved');
      } catch (err) {
        input.checked = prevVal;
        if (badge) {
          badge.textContent = 'Error';
          badge.classList.remove('saving');
        }
        showToast(err.message || 'Failed to save preferences');
      }
    });
  });
}

document.addEventListener('DOMContentLoaded', async () => {
  // Filter tabs
  document.querySelectorAll('.notif-tab').forEach((tab) => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.notif-tab').forEach((t) => t.classList.remove('active'));
      tab.classList.add('active');
      pageState.filter = tab.dataset.filter || 'ALL';
      pageState.page = 0;
      loadNotifications();
    });
  });

  // Search input (debounced)
  let searchTimer = null;
  document.getElementById('notifSearchInput')?.addEventListener('input', (e) => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      pageState.search = e.target.value || '';
      pageState.page = 0;
      loadNotifications();
    }, 220);
  });

  // Refresh button
  document.getElementById('notifRefreshBtn')?.addEventListener('click', () => {
    loadNotifications();
    showToast('Notifications refreshed');
  });

  // Mark all as read
  document.getElementById('markAllReadBtn')?.addEventListener('click', async () => {
    try {
      await notificationStore.markAllAsRead();
      showToast('All notifications marked as read');
      await loadNotifications();
    } catch (err) {
      showToast(err.message || 'Unable to mark all as read');
    }
  });

  // Clear read
  document.getElementById('clearReadBtn')?.addEventListener('click', async () => {
    try {
      await notificationStore.clearRead();
      showToast('Read notifications cleared');
      await loadNotifications();
    } catch (err) {
      showToast(err.message || 'Unable to clear read notifications');
    }
  });

  // Pagination
  document.getElementById('prevPageBtn')?.addEventListener('click', () => {
    if (pageState.page > 0) {
      pageState.page -= 1;
      loadNotifications();
    }
  });
  document.getElementById('nextPageBtn')?.addEventListener('click', () => {
    if (pageState.page + 1 < pageState.totalPages) {
      pageState.page += 1;
      loadNotifications();
    }
  });

  // Modal controls
  document.getElementById('closeModalBtn')?.addEventListener('click', closeDetailModal);
  document.getElementById('notifDetailModal')?.addEventListener('click', (e) => {
    if (e.target.id === 'notifDetailModal') closeDetailModal();
  });
  document.getElementById('modalToggleReadBtn')?.addEventListener('click', async () => {
    const item = pageState.activeModalItem;
    if (!item) return;
    try {
      if (item.read) {
        await notificationStore.markAsUnread(item.id);
        item.read = false;
        showToast('Marked as unread');
      } else {
        await notificationStore.markAsRead(item.id);
        item.read = true;
        showToast('Marked as read');
      }
      closeDetailModal();
      await loadNotifications();
    } catch (err) {
      showToast(err.message || 'Unable to update notification');
    }
  });
  document.getElementById('modalDismissBtn')?.addEventListener('click', async () => {
    const item = pageState.activeModalItem;
    if (!item) return;
    try {
      await notificationStore.dismiss(item.id);
      closeDetailModal();
      showToast('Notification dismissed');
      await loadNotifications();
    } catch (err) {
      showToast(err.message || 'Unable to dismiss notification');
    }
  });

  // Sync if navbar bell triggers an update
  window.addEventListener(notificationStore.EVENT_NAME, () => {
    // Only reload list if triggered externally (avoid loop)
  });

  window.addEventListener('vigilia:notification-selected', (e) => {
    const id = e.detail?.id;
    const item = pageState.items.find((x) => x.id === id);
    if (item) openDetailModal(item);
  });

  bindPreferenceToggles();
  await Promise.all([loadNotifications(), loadPreferences()]);

  // Open modal if ?id=... is in URL
  const urlId = new URLSearchParams(window.location.search).get('id');
  if (urlId) {
    const target = pageState.items.find((x) => x.id === urlId);
    if (target) openDetailModal(target);
  }
});
