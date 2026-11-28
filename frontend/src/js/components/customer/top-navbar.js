import { VigiliaAuth } from '../../auth.js';
import { notificationStore, escapeHtml, formatRelativeTime } from '../../notification-store.js';

/**
 * Vigilia Platform — Customer Portal Top Navbar (<customer-navbar>)
 * - Sidebar collapse / drawer toggle button
 * - Global search bar with ⌘K shortcut
 * - Live Notification bell with unread badge and backend-wired dropdown panel
 * - User profile menu with avatar, name, role badge, and dropdown actions
 */
class CustomerNavbar extends HTMLElement {
  connectedCallback() {
    const placeholder = this.getAttribute('search-placeholder') || 'Search transactions, recipients, or merchants...';

    let displayName = 'Customer';
    let displayRole = 'Customer';
    let initials = 'VG';

    if (typeof window !== 'undefined' && window.VigiliaAuth) {
      const user = window.VigiliaAuth.getUser();
      if (user) {
        if (user.fullName || user.name) {
          displayName = user.fullName || user.name;
          initials = displayName.split(' ').map(n => n[0]).join('').slice(0, 2).toUpperCase();
        } else if (user.email) {
          const namePart = user.email.split('@')[0];
          displayName = namePart.charAt(0).toUpperCase() + namePart.slice(1);
          initials = namePart.slice(0, 2).toUpperCase();
        } else if (user.username) {
          displayName = user.username.charAt(0).toUpperCase() + user.username.slice(1);
          initials = user.username.slice(0, 2).toUpperCase();
        }
      }
    }

    this.innerHTML = `
      <header class="top-navbar">
        <div class="nav-left">
          <button class="sidebar-toggle-btn" id="sidebarToggleBtn" aria-label="Toggle sidebar" title="Toggle sidebar">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="3" y1="12" x2="21" y2="12"></line>
              <line x1="3" y1="6" x2="21" y2="6"></line>
              <line x1="3" y1="18" x2="21" y2="18"></line>
            </svg>
          </button>

          <div class="search-container">
            <span class="search-icon">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
                <circle cx="11" cy="11" r="8"></circle>
                <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
              </svg>
            </span>
            <input 
              type="text" 
              class="search-input" 
              id="cstGlobalSearch"
              placeholder="${placeholder}" 
            />
            <span class="search-kbd-shortcut">⌘K</span>
          </div>
        </div>

        <div class="nav-right">
          <!-- Notification Dropdown -->
          <div class="notification-dropdown-wrapper" id="notificationDropdownWrapper">
            <button class="notification-btn" id="notificationBtn" aria-label="Notifications" type="button" aria-haspopup="true" aria-expanded="false">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"></path>
                <path d="M13.73 21a2 2 0 0 1-3.46 0"></path>
              </svg>
              <span class="notification-badge" id="notificationBadge" style="display:none;">0</span>
            </button>

            <!-- Notifications Dropdown Panel -->
            <div class="notification-dropdown-panel" id="notificationPanel">
              <div class="notification-panel-header">
                <div class="notif-title-row">
                  <span class="notif-panel-title">Notifications</span>
                  <span class="notif-unread-tag" id="notifUnreadTag">0 New</span>
                </div>
                <button type="button" class="notif-clear-btn" id="notifClearBtn">Mark all as read</button>
              </div>

              <div class="notification-items-list" id="notificationItemsList">
                <div class="notif-dropdown-empty">Loading notifications...</div>
              </div>

              <div class="notification-panel-footer">
                <a href="notifications.html" class="notif-view-all-link">Manage notifications →</a>
              </div>
            </div>
          </div>

          <!-- User Profile Menu -->
          <div class="user-profile-menu" id="profileDropdownTrigger">
            <div class="user-avatar-circle" id="cstUserAvatar">${initials}</div>
            <div class="user-info-text">
              <span class="user-display-name" id="cstUserName">${displayName}</span>
              <span class="user-display-role">${displayRole}</span>
            </div>
            <svg class="chevron-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="6 9 12 15 18 9"></polyline>
            </svg>

            <!-- Dropdown Menu -->
            <div class="user-dropdown-panel" id="userDropdownPanel">
              <a href="profile.html" class="dropdown-item">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path>
                  <circle cx="12" cy="7" r="4"></circle>
                </svg>
                <span>Profile Settings</span>
              </a>
              <a href="wallet.html" class="dropdown-item">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a8 8 0 0 1-16 0V6"></path>
                  <circle cx="18" cy="14" r="1"></circle>
                </svg>
                <span>Wallet Settings</span>
              </a>
              <div style="height:1px; background:var(--border-color); margin: 0.35rem 0;"></div>
              <button type="button" class="dropdown-item danger" id="cstLogoutBtn">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path>
                  <polyline points="16 17 21 12 16 7"></polyline>
                  <line x1="21" y1="12" x2="9" y2="12"></line>
                </svg>
                <span>Sign Out</span>
              </button>
            </div>
          </div>
        </div>
      </header>
    `;

    // 1. Sidebar toggle logic
    const toggleBtn = this.querySelector('#sidebarToggleBtn');
    if (toggleBtn) {
      toggleBtn.addEventListener('click', () => {
        const shell = document.querySelector('.app-shell');
        if (!shell) return;
        if (window.innerWidth <= 1024) {
          shell.classList.toggle('sidebar-mobile-open');
        } else {
          const isCollapsed = shell.classList.toggle('sidebar-collapsed');
          localStorage.setItem('vigilia_sidebar_collapsed', isCollapsed ? 'true' : 'false');
        }
      });
    }

    // 2. Notification dropdown toggle & live backend wiring
    const notifBtn = this.querySelector('#notificationBtn');
    const notifPanel = this.querySelector('#notificationPanel');
    const notifClearBtn = this.querySelector('#notifClearBtn');

    if (notifBtn && notifPanel) {
      notifBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        const isOpen = notifPanel.classList.toggle('open');
        notifBtn.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
        const userDropdown = this.querySelector('#userDropdownPanel');
        if (userDropdown) userDropdown.classList.remove('open');
        if (isOpen && VigiliaAuth.isAuthenticated()) {
          void this.refreshNotifications();
        }
      });

      if (notifClearBtn) {
        notifClearBtn.addEventListener('click', async (e) => {
          e.stopPropagation();
          notifClearBtn.disabled = true;
          try {
            await notificationStore.markAllAsRead();
          } catch (err) {
            console.warn('[CustomerNavbar] Failed to mark all read:', err);
          } finally {
            notifClearBtn.disabled = false;
          }
        });
      }

      document.addEventListener('click', (e) => {
        if (!notifBtn.contains(e.target) && !notifPanel.contains(e.target)) {
          notifPanel.classList.remove('open');
          notifBtn.setAttribute('aria-expanded', 'false');
        }
      });
    }

    this._onNotifUpdate = (e) => this.renderNotifications(e.detail);
    window.addEventListener(notificationStore.EVENT_NAME, this._onNotifUpdate);

    if (VigiliaAuth.isAuthenticated()) {
      void this.refreshNotifications();
      this._pollTimer = setInterval(() => {
        if (document.visibilityState === 'visible') {
          void notificationStore.fetchUnreadCount();
        }
      }, 30000);
    }

    // 3. User profile dropdown toggle
    const profileTrigger = this.querySelector('#profileDropdownTrigger');
    const userPanel = this.querySelector('#userDropdownPanel');
    const logoutBtn = this.querySelector('#cstLogoutBtn');

    if (profileTrigger && userPanel) {
      profileTrigger.addEventListener('click', (e) => {
        if (e.target.closest('#userDropdownPanel')) return;
        userPanel.classList.toggle('open');
        if (notifPanel) notifPanel.classList.remove('open');
      });

      document.addEventListener('click', (e) => {
        if (!profileTrigger.contains(e.target)) {
          userPanel.classList.remove('open');
        }
      });
    }

    if (logoutBtn) {
      logoutBtn.addEventListener('click', (e) => {
        e.preventDefault();
        VigiliaAuth.logout();
      });
    }

    // 4. Global Keyboard Shortcut: ⌘K / Ctrl+K focus search
    document.addEventListener('keydown', (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        const searchInput = this.querySelector('#cstGlobalSearch');
        if (searchInput) searchInput.focus();
      }
    });
  }

  disconnectedCallback() {
    if (this._onNotifUpdate) {
      window.removeEventListener(notificationStore.EVENT_NAME, this._onNotifUpdate);
    }
    if (this._pollTimer) {
      clearInterval(this._pollTimer);
    }
  }

  async refreshNotifications() {
    try {
      await notificationStore.fetchList({ size: 6 });
    } catch (err) {
      const listEl = this.querySelector('#notificationItemsList');
      if (listEl && !notificationStore.getState().loaded) {
        listEl.innerHTML = `<div class="notif-dropdown-empty">Unable to load notifications</div>`;
      }
    }
  }

  renderNotifications(detail) {
    const state = detail || notificationStore.getState();
    const unread = Number(state.unreadCount || 0);
    const items = Array.isArray(state.items) ? state.items.slice(0, 6) : [];

    const notifBadge = this.querySelector('#notificationBadge');
    const notifUnreadTag = this.querySelector('#notifUnreadTag');
    const notifClearBtn = this.querySelector('#notifClearBtn');
    const listEl = this.querySelector('#notificationItemsList');

    if (notifBadge) {
      if (unread > 0) {
        notifBadge.textContent = unread > 99 ? '99+' : String(unread);
        notifBadge.style.display = 'flex';
      } else {
        notifBadge.style.display = 'none';
      }
    }

    if (notifUnreadTag) {
      if (unread > 0) {
        notifUnreadTag.textContent = `${unread} New`;
        notifUnreadTag.classList.remove('caught-up');
      } else {
        notifUnreadTag.textContent = 'All caught up';
        notifUnreadTag.classList.add('caught-up');
      }
    }

    if (notifClearBtn) {
      notifClearBtn.style.display = unread > 0 ? 'inline-block' : 'none';
    }

    if (!listEl) return;

    if (items.length === 0) {
      listEl.innerHTML = `
        <div class="notif-dropdown-empty">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"></path>
            <path d="M13.73 21a2 2 0 0 1-3.46 0"></path>
          </svg>
          <span>No notifications yet</span>
        </div>
      `;
      return;
    }

    listEl.innerHTML = items.map((n) => {
      const iconMeta = this.getSeverityIcon(n.severity, n.category);
      const unreadClass = n.read ? '' : 'unread';
      return `
        <button type="button" class="notif-item notif-dropdown-item ${unreadClass}" data-id="${escapeHtml(n.id)}" data-tx="${escapeHtml(n.transactionId || '')}">
          <div class="notif-icon-box ${iconMeta.cls}">${iconMeta.svg}</div>
          <div class="notif-content">
            <p class="notif-text"><strong>${escapeHtml(n.title)}:</strong> ${escapeHtml(n.message)}</p>
            <span class="notif-time">${escapeHtml(formatRelativeTime(n.createdAt))}</span>
          </div>
          ${n.read ? '' : '<span class="notif-unread-dot" aria-hidden="true"></span>'}
        </button>
      `;
    }).join('');

    listEl.querySelectorAll('.notif-dropdown-item').forEach((btn) => {
      btn.addEventListener('click', async () => {
        const id = btn.dataset.id;
        try {
          if (btn.classList.contains('unread')) {
            await notificationStore.markAsRead(id);
          }
        } catch (err) {
          console.warn('[CustomerNavbar] Mark read failed:', err);
        }
        const panel = this.querySelector('#notificationPanel');
        if (panel) panel.classList.remove('open');
        if (!window.location.pathname.endsWith('notifications.html')) {
          window.location.href = `notifications.html?id=${encodeURIComponent(id)}`;
        } else {
          window.dispatchEvent(new CustomEvent('vigilia:notification-selected', { detail: { id } }));
        }
      });
    });
  }

  getSeverityIcon(severity, category) {
    const sev = (severity || '').toUpperCase();
    const cat = (category || '').toUpperCase();
    if (sev === 'CRITICAL') {
      return {
        cls: 'danger',
        svg: `<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.4"><circle cx="12" cy="12" r="10"></circle><line x1="15" y1="9" x2="9" y2="15"></line><line x1="9" y1="9" x2="15" y2="15"></line></svg>`,
      };
    }
    if (sev === 'WARNING' || cat === 'REVIEW') {
      return {
        cls: 'warn',
        svg: `<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.3"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>`,
      };
    }
    if (cat === 'SECURITY') {
      return {
        cls: 'info',
        svg: `<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>`,
      };
    }
    if (sev === 'SUCCESS') {
      return {
        cls: 'ok',
        svg: `<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"></polyline></svg>`,
      };
    }
    return {
      cls: 'info',
      svg: `<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="16" x2="12" y2="12"></line><line x1="12" y1="8" x2="12.01" y2="8"></line></svg>`,
    };
  }

  updateUser(name, initials) {
    const avatarEl = this.querySelector('#cstUserAvatar');
    const nameEl = this.querySelector('#cstUserName');
    if (avatarEl && initials) avatarEl.textContent = initials;
    if (nameEl && name) nameEl.textContent = name;
  }
}

if (!customElements.get('customer-navbar')) {
  customElements.define('customer-navbar', CustomerNavbar);
}

export default CustomerNavbar;
