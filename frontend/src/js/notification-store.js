import { apiClient } from './api-client.js';

const EVENT_NAME = 'vigilia:notifications-updated';

const state = {
  items: [],
  unreadCount: 0,
  totalCount: 0,
  criticalCount: 0,
  categoryCounts: {
    ALL: 0,
    UNREAD: 0,
    TRANSACTION: 0,
    REVIEW: 0,
    SECURITY: 0,
    SYSTEM: 0,
  },
  loaded: false,
  lastFetchedAt: 0,
};

function unwrap(res) {
  return res && res.data !== undefined ? res.data : res;
}

function emitUpdate() {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(
    new CustomEvent(EVENT_NAME, {
      detail: {
        items: state.items,
        unreadCount: state.unreadCount,
        totalCount: state.totalCount,
        criticalCount: state.criticalCount,
        categoryCounts: state.categoryCounts,
        loaded: state.loaded,
      },
    })
  );
}

export function escapeHtml(str) {
  return String(str ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

export function formatRelativeTime(iso) {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const diffSec = Math.max(0, Math.floor((Date.now() - date.getTime()) / 1000));
  if (diffSec < 60) return 'Just now';
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return `${diffHr}h ago`;
  const diffDay = Math.floor(diffHr / 24);
  if (diffDay === 1) return 'Yesterday';
  if (diffDay < 7) return `${diffDay}d ago`;
  return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

export const notificationStore = {
  EVENT_NAME,

  getState() {
    return { ...state };
  },

  async fetchUnreadCount() {
    try {
      const res = unwrap(await apiClient.get('/v1/notifications/unread-count'));
      if (res && typeof res.unreadCount === 'number') {
        state.unreadCount = res.unreadCount;
        state.criticalCount = res.criticalCount || 0;
        state.loaded = true;
        emitUpdate();
      }
      return res;
    } catch (err) {
      return null;
    }
  },

  async fetchList({ category = 'ALL', status = 'ALL', search = '', page = 0, size = 20, updateGlobal = true } = {}) {
    const params = new URLSearchParams();
    if (category && category !== 'ALL') params.set('category', category);
    if (status && status !== 'ALL') params.set('status', status);
    if (search && search.trim()) params.set('search', search.trim());
    params.set('page', String(page));
    params.set('size', String(size));

    const res = unwrap(await apiClient.get(`/v1/notifications?${params.toString()}`));
    if (res && updateGlobal) {
      state.items = Array.isArray(res.items) ? res.items : [];
      state.unreadCount = typeof res.unreadCount === 'number' ? res.unreadCount : 0;
      state.totalCount = typeof res.totalCount === 'number' ? res.totalCount : state.items.length;
      if (res.categoryCounts) state.categoryCounts = res.categoryCounts;
      state.loaded = true;
      state.lastFetchedAt = Date.now();
      emitUpdate();
    }
    return res;
  },

  async markAsRead(id) {
    const updated = unwrap(await apiClient.patch(`/v1/notifications/${encodeURIComponent(id)}/read`, {}));
    const target = state.items.find((n) => n.id === id);
    if (target && !target.read) {
      target.read = true;
      target.readAt = updated?.readAt || new Date().toISOString();
      state.unreadCount = Math.max(0, state.unreadCount - 1);
      if (state.categoryCounts) {
        state.categoryCounts.UNREAD = state.unreadCount;
      }
    }
    emitUpdate();
    return updated;
  },

  async markAsUnread(id) {
    const updated = unwrap(await apiClient.patch(`/v1/notifications/${encodeURIComponent(id)}/unread`, {}));
    const target = state.items.find((n) => n.id === id);
    if (target && target.read) {
      target.read = false;
      target.readAt = null;
      state.unreadCount += 1;
      if (state.categoryCounts) {
        state.categoryCounts.UNREAD = state.unreadCount;
      }
    }
    emitUpdate();
    return updated;
  },

  async markAllAsRead() {
    const res = unwrap(await apiClient.post('/v1/notifications/read-all', {}));
    const now = new Date().toISOString();
    state.items.forEach((n) => {
      n.read = true;
      n.readAt = n.readAt || now;
    });
    state.unreadCount = 0;
    state.criticalCount = 0;
    if (state.categoryCounts) {
      state.categoryCounts.UNREAD = 0;
    }
    emitUpdate();
    return res;
  },

  async dismiss(id) {
    await apiClient.delete(`/v1/notifications/${encodeURIComponent(id)}`);
    const idx = state.items.findIndex((n) => n.id === id);
    if (idx !== -1) {
      const removed = state.items[idx];
      state.items.splice(idx, 1);
      state.totalCount = Math.max(0, state.totalCount - 1);
      if (!removed.read) {
        state.unreadCount = Math.max(0, state.unreadCount - 1);
      }
    }
    emitUpdate();
  },

  async clearRead() {
    const res = unwrap(await apiClient.delete('/v1/notifications/clear-read'));
    state.items = state.items.filter((n) => !n.read);
    state.totalCount = state.items.length;
    emitUpdate();
    return res;
  },

  async getPreferences() {
    const [notifRes, custRes] = await Promise.allSettled([
      apiClient.get('/v1/notifications/preferences'),
      apiClient.get('/v1/customers/me/preferences'),
    ]);
    const notifPrefs = notifRes.status === 'fulfilled' ? unwrap(notifRes.value) : {};
    const custPrefs = custRes.status === 'fulfilled' ? unwrap(custRes.value) : {};
    return {
      transactionUpdates: notifPrefs?.transactionUpdates ?? true,
      securityAlerts: notifPrefs?.securityAlerts ?? true,
      transferReviews: notifPrefs?.transferReviews ?? true,
      emailNotifications: notifPrefs?.emailNotifications ?? custPrefs?.emailNotifications ?? true,
      inAppNotifications: notifPrefs?.inAppNotifications ?? custPrefs?.inAppNotifications ?? true,
      language: custPrefs?.language || 'en',
    };
  },

  async updatePreferences(prefs) {
    const [notifRes] = await Promise.allSettled([
      apiClient.put('/v1/notifications/preferences', prefs),
      apiClient.put('/v1/customers/me/preferences', {
        emailNotifications: prefs.emailNotifications,
        inAppNotifications: prefs.inAppNotifications,
      }),
    ]);
    if (notifRes.status === 'rejected') {
      throw notifRes.reason;
    }
    return unwrap(notifRes.value);
  },
};

