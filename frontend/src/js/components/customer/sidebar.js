import logoUrl from '../../../assets/vigilia-logo-mark.png';

/**
 * Vigilia Platform — Customer Workspace Sidebar (<customer-sidebar>)
 * Uses the exact same design system & styling as the Monitor Sidebar.
 */
class CustomerSidebar extends HTMLElement {
  connectedCallback() {
    const activeRoute = (this.getAttribute('active') || 'overview').toLowerCase();
    const workspaceName = this.getAttribute('workspace') || 'CUSTOMER WORKSPACE';

    const customerNavItems = [
      {
        id: 'overview',
        title: 'Overview',
        href: 'overview.html',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path>
            <polyline points="9 22 9 12 15 12 15 22"></polyline>
          </svg>`
      },
      {
        id: 'wallet',
        title: 'My Wallet',
        href: 'wallet.html',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a8 8 0 0 1-16 0V6"></path>
            <circle cx="18" cy="14" r="1"></circle>
          </svg>`
      },
      {
        id: 'transactions',
        title: 'Transactions',
        href: 'transactions.html',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="m16 3 4 4-4 4"></path>
            <path d="M20 7H4"></path>
            <path d="m8 21-4-4 4-4"></path>
            <path d="M4 17h16"></path>
          </svg>`
      },
      {
        id: 'send-money',
        title: 'Send Money',
        href: 'send-money.html',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <line x1="22" y1="2" x2="11" y2="13"></line>
            <polygon points="22 2 15 22 11 13 2 9 22 2"></polygon>
          </svg>`
      },
      {
        id: 'beneficiaries',
        title: 'Beneficiaries',
        href: 'beneficiaries.html',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"></path>
            <circle cx="9" cy="7" r="4"></circle>
            <path d="M22 21v-2a4 4 0 0 0-3-3.87"></path>
            <path d="M16 3.13a4 4 0 0 1 0 7.75"></path>
          </svg>`
      },
      {
        id: 'notifications',
        title: 'Notifications',
        href: 'notifications.html',
        badge: '2',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"></path>
            <path d="M13.73 21a2 2 0 0 1-3.46 0"></path>
          </svg>`
      },
      {
        id: 'profile',
        title: 'Profile & Settings',
        href: 'profile.html',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"></path>
            <circle cx="12" cy="7" r="4"></circle>
          </svg>`
      }
    ];

    const renderNav = (items) => items.map(item => {
      const isActive = item.id === activeRoute;
      const activeClass = isActive ? 'nav-item active' : 'nav-item';
      const badgeHtml = item.badge ? `<span class="nav-item-badge">${item.badge}</span>` : '';
      return `
        <a href="${item.href}" class="${activeClass}" title="${item.title}" data-page="${item.title}">
          <span class="nav-item-icon-wrap">${item.icon}</span>
          <span class="nav-item-title">${item.title}</span>
          ${badgeHtml}
        </a>
      `;
    }).join('');

    this.innerHTML = `
      <aside class="sidebar light-theme">
        <div class="sidebar-top-section">
          <!-- Vigilia Brand in Sidebar -->
          <div class="sidebar-brand">
            <a href="overview.html" class="brand-link">
              <img src="${logoUrl}" alt="Vigilia Logo" class="brand-logo-img" />
              <span class="brand-name">Vigilia</span>
            </a>
            <button class="sidebar-collapse-btn" id="sidebarCollapseBtn" aria-label="Collapse sidebar" title="Close sidebar">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="15 18 9 12 15 6"></polyline>
              </svg>
            </button>
          </div>

          <!-- Active Workspace Category Tag -->
          <div class="sidebar-workspace-label">${workspaceName}</div>

          <!-- Main Customer Navigation -->
          <nav class="sidebar-nav-group">
            <div class="sidebar-nav-section">
              ${renderNav(customerNavItems)}
            </div>
          </nav>
        </div>

        <!-- Bottom Platform Security Pulse Widget -->
        <div class="sidebar-health-widget">
          <div class="health-status-header">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink:0;">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
              <polyline points="9 12 11 14 15 10"/>
            </svg>
            <span class="health-status-title">Account Protected</span>
          </div>
          <p class="health-status-subtext">AI Fraud Shield Active</p>
          <svg class="mini-sparkline-svg" viewBox="0 0 120 24" fill="none">
            <path d="M0 14 Q 15 6, 30 16 T 60 12 T 90 18 T 120 10" stroke="#10b981" stroke-width="2" fill="none" stroke-linecap="round"></path>
          </svg>
        </div>
      </aside>
      <div class="sidebar-backdrop" id="sidebarBackdrop"></div>
    `;

    // Initialize desktop collapsed state from localStorage
    const appShell = document.querySelector('.cst-app-layout') || document.querySelector('.app-shell');
    if (appShell && window.innerWidth > 1024) {
      if (localStorage.getItem('vigilia_sidebar_collapsed') === 'true') {
        appShell.classList.add('sidebar-collapsed');
      }
    }

    // Collapse / Close button click
    const collapseBtn = this.querySelector('#sidebarCollapseBtn');
    if (collapseBtn) {
      collapseBtn.addEventListener('click', () => {
        const shell = document.querySelector('.cst-app-layout') || document.querySelector('.app-shell');
        if (!shell) return;
        if (window.innerWidth <= 1024) {
          shell.classList.remove('sidebar-mobile-open');
        } else {
          shell.classList.toggle('sidebar-collapsed');
          const isCollapsed = shell.classList.contains('sidebar-collapsed');
          localStorage.setItem('vigilia_sidebar_collapsed', isCollapsed ? 'true' : 'false');
        }
      });
    }

    // Backdrop click to close on mobile
    const backdrop = this.querySelector('#sidebarBackdrop');
    if (backdrop) {
      backdrop.addEventListener('click', () => {
        const shell = document.querySelector('.cst-app-layout') || document.querySelector('.app-shell');
        shell?.classList.remove('sidebar-mobile-open');
      });
    }

    // Auto-close on link click when on mobile
    this.querySelectorAll('.nav-item').forEach(link => {
      link.addEventListener('click', () => {
        if (window.innerWidth <= 1024) {
          const shell = document.querySelector('.cst-app-layout') || document.querySelector('.app-shell');
          shell?.classList.remove('sidebar-mobile-open');
        }
      });
    });

    // Escape key closes mobile sidebar
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        const shell = document.querySelector('.cst-app-layout') || document.querySelector('.app-shell');
        shell?.classList.remove('sidebar-mobile-open');
      }
    });
  }
}

if (!customElements.get('customer-sidebar')) {
  customElements.define('customer-sidebar', CustomerSidebar);
}

export default CustomerSidebar;
