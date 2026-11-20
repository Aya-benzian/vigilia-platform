import logoUrl from '../../assets/vigilia-logo-mark.png';

/**
 * Vigilia Platform — Dark Navy Workspace Sidebar (<vigilia-sidebar>)
 * Enterprise Observability & Operations Navigation
 */
class VigiliaSidebar extends HTMLElement {
  connectedCallback() {
    const activeRoute = (this.getAttribute('active') || 'command-center').toLowerCase();
    const workspaceName = this.getAttribute('workspace') || 'MONITOR WORKSPACE';

    // ── MUST-HAVE ────────────────────────────────────────────────────────────
    const mustHaveItems = [
      {
        id: 'command-center',
        title: 'Command Center',
        href: '/monitor-dashboard.html',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path>
            <polyline points="9 22 9 12 15 12 15 22"></polyline>
          </svg>`
      },
      {
        id: 'live-transactions',
        title: 'Live Transactions',
        href: '/live-transactions.html',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline>
          </svg>`
      },
      {
        id: 'ai-model-intelligence',
        title: 'AI Model Intelligence',
        href: '#',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 2a4 4 0 0 1 4 4v1h1a3 3 0 0 1 3 3v2a3 3 0 0 1-3 3h-1v1a4 4 0 0 1-8 0v-1H7a3 3 0 0 1-3-3v-2a3 3 0 0 1 3-3h1V6a4 4 0 0 1 4-4z"></path>
            <circle cx="9" cy="10" r="1" fill="currentColor"></circle>
            <circle cx="15" cy="10" r="1" fill="currentColor"></circle>
            <path d="M9 14s1 1 3 1 3-1 3-1"></path>
          </svg>`
      },
      {
        id: 'alerts',
        title: 'Alerts & Incidents',
        href: '#',
        badge: '3',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"></path>
            <path d="M13.73 21a2 2 0 0 1-3.46 0"></path>
          </svg>`
      },
      {
        id: 'service-health',
        title: 'Service & API Health',
        href: '#',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline>
          </svg>`
      },
    ];

    // ── HIGH VALUE ───────────────────────────────────────────────────────────
    const highValueItems = [
      {
        id: 'fraud-analytics',
        title: 'Fraud Analytics',
        href: '#',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <line x1="18" y1="20" x2="18" y2="10"></line>
            <line x1="12" y1="20" x2="12" y2="4"></line>
            <line x1="6" y1="20" x2="6" y2="14"></line>
            <path d="M3 3l7.07 16.97 2.51-7.39 7.39-2.51L3 3z"></path>
          </svg>`
      },
      {
        id: 'logs-explorer',
        title: 'Logs Explorer',
        href: '#',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="4 17 10 11 4 5"></polyline>
            <line x1="12" y1="19" x2="20" y2="19"></line>
          </svg>`
      },
      {
        id: 'data-pipeline',
        title: 'Data Pipeline',
        href: '#',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="6" cy="6" r="3"></circle>
            <circle cx="6" cy="18" r="3"></circle>
            <line x1="20" y1="4" x2="8.12" y2="15.88"></line>
            <circle cx="18" cy="18" r="3"></circle>
            <line x1="14.47" y1="14.48" x2="20" y2="20"></line>
            <line x1="8.12" y1="8.12" x2="12" y2="12"></line>
          </svg>`
      },
    ];

    // ── NICE-TO-HAVE ─────────────────────────────────────────────────────────
    const niceToHaveItems = [
      {
        id: 'reports',
        title: 'Reports & Exports',
        href: '#',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
            <polyline points="14 2 14 8 20 8"></polyline>
            <line x1="16" y1="13" x2="8" y2="13"></line>
            <line x1="16" y1="17" x2="8" y2="17"></line>
          </svg>`
      },
      {
        id: 'monitoring-config',
        title: 'Monitoring Config',
        href: '#',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <line x1="4" y1="21" x2="4" y2="14"></line>
            <line x1="4" y1="10" x2="4" y2="3"></line>
            <line x1="12" y1="21" x2="12" y2="12"></line>
            <line x1="12" y1="8" x2="12" y2="3"></line>
            <line x1="20" y1="21" x2="20" y2="16"></line>
            <line x1="20" y1="12" x2="20" y2="3"></line>
            <line x1="1" y1="14" x2="7" y2="14"></line>
            <line x1="9" y1="8" x2="15" y2="8"></line>
            <line x1="17" y1="16" x2="23" y2="16"></line>
          </svg>`
      },
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

    const renderSection = (items) => `
      <div class="sidebar-nav-section">
        ${renderNav(items)}
      </div>
    `;

    this.innerHTML = `
      <aside class="sidebar light-theme">
        <div class="sidebar-top-section">
          <!-- Vigilia Brand in Sidebar -->
          <div class="sidebar-brand">
            <a href="/" class="brand-link">
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

          <!-- Main Ops & Monitor Navigation -->
          <nav class="sidebar-nav-group">
            ${renderSection(mustHaveItems)}
            ${renderSection(highValueItems)}
            ${renderSection(niceToHaveItems)}
          </nav>
        </div>

        <!-- Bottom Platform Health Pulse Widget -->
        <div class="sidebar-health-widget">
          <div class="health-status-header">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink:0;">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
              <polyline points="9 12 11 14 15 10"/>
            </svg>
            <span class="health-status-title">Platform Healthy</span>
          </div>
          <p class="health-status-subtext">All systems operational</p>
          <svg class="mini-sparkline-svg" viewBox="0 0 120 24" fill="none">
            <path d="M0 14 Q 15 6, 30 16 T 60 12 T 90 18 T 120 10" stroke="#10b981" stroke-width="2" fill="none" stroke-linecap="round"></path>
          </svg>
        </div>
      </aside>
      <div class="sidebar-backdrop" id="sidebarBackdrop"></div>
    `;

    // Initialize desktop collapsed state from localStorage
    const appShell = document.querySelector('.app-shell');
    if (appShell && window.innerWidth > 1024) {
      if (localStorage.getItem('vigilia_sidebar_collapsed') === 'true') {
        appShell.classList.add('sidebar-collapsed');
      }
    }

    // Collapse / Close button click
    const collapseBtn = this.querySelector('#sidebarCollapseBtn');
    if (collapseBtn) {
      collapseBtn.addEventListener('click', () => {
        const shell = document.querySelector('.app-shell');
        if (!shell) return;
        if (window.innerWidth <= 1024) {
          shell.classList.remove('sidebar-mobile-open');
        } else {
          shell.classList.add('sidebar-collapsed');
          localStorage.setItem('vigilia_sidebar_collapsed', 'true');
        }
      });
    }

    // Backdrop click to close on mobile
    const backdrop = this.querySelector('#sidebarBackdrop');
    if (backdrop) {
      backdrop.addEventListener('click', () => {
        document.querySelector('.app-shell')?.classList.remove('sidebar-mobile-open');
      });
    }

    // Auto-close on link click when on mobile
    this.querySelectorAll('.nav-item').forEach(link => {
      link.addEventListener('click', () => {
        if (window.innerWidth <= 1024) {
          document.querySelector('.app-shell')?.classList.remove('sidebar-mobile-open');
        }
      });
    });

    // Escape key closes mobile sidebar
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        document.querySelector('.app-shell')?.classList.remove('sidebar-mobile-open');
      }
    });
  }
}

customElements.define('vigilia-sidebar', VigiliaSidebar);
