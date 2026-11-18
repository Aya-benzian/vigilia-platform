import logoUrl from '../../assets/vigilia-logo-mark.png';

/**
 * Vigilia Platform — Dark Navy Workspace Sidebar (<vigilia-sidebar>)
 * Enterprise Observability & Operations Navigation
 */
class VigiliaSidebar extends HTMLElement {
  connectedCallback() {
    const activeRoute = (this.getAttribute('active') || 'command-center').toLowerCase();
    const workspaceName = this.getAttribute('workspace') || 'MONITOR WORKSPACE';

    const navItems = [
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
        id: 'kubernetes-explorer',
        title: 'Kubernetes Explorer',
        href: '#',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="3"></circle>
            <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
          </svg>`
      },
      {
        id: 'live-transactions',
        title: 'Live Transactions',
        href: '#',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <rect x="2" y="5" width="20" height="14" rx="2"></rect>
            <line x1="2" y1="10" x2="22" y2="10"></line>
          </svg>`
      },
      {
        id: 'system-health',
        title: 'System Health',
        href: '#',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path>
            <polyline points="9 12 11 14 15 10"></polyline>
          </svg>`
      },
      {
        id: 'gpu-workloads',
        title: 'GPU & AI Workloads',
        href: '#',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <rect x="4" y="4" width="16" height="16" rx="2"></rect>
            <rect x="9" y="9" width="6" height="6"></rect>
            <line x1="9" y1="1" x2="9" y2="4"></line>
            <line x1="15" y1="1" x2="15" y2="4"></line>
            <line x1="9" y1="20" x2="9" y2="23"></line>
            <line x1="15" y1="20" x2="15" y2="23"></line>
            <line x1="20" y1="9" x2="23" y2="9"></line>
            <line x1="20" y1="15" x2="23" y2="15"></line>
            <line x1="1" y1="9" x2="4" y2="9"></line>
            <line x1="1" y1="15" x2="4" y2="15"></line>
          </svg>`
      },
      {
        id: 'resource-analytics',
        title: 'Resource Analytics',
        href: '#',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <line x1="18" y1="20" x2="18" y2="10"></line>
            <line x1="12" y1="20" x2="12" y2="4"></line>
            <line x1="6" y1="20" x2="6" y2="14"></line>
          </svg>`
      },
      {
        id: 'service-health',
        title: 'Service & API Health',
        href: '#',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10"></circle>
            <circle cx="12" cy="12" r="4"></circle>
            <line x1="4.93" y1="4.93" x2="9.17" y2="9.17"></line>
            <line x1="14.83" y1="14.83" x2="19.07" y2="19.07"></line>
            <line x1="14.83" y1="9.17" x2="19.07" y2="4.93"></line>
            <line x1="4.93" y1="19.07" x2="9.17" y2="14.83"></line>
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
        id: 'incident-timeline',
        title: 'Incident Timeline',
        href: '#',
        icon: `
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10"></circle>
            <polyline points="12 6 12 12 16 14"></polyline>
          </svg>`
      },
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
        title: 'Monitoring Configuration',
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
            ${renderNav(navItems)}
          </nav>
        </div>

        <!-- Bottom Platform Health Pulse Widget -->
        <div class="sidebar-health-widget">
          <div class="health-status-header">
            <span class="health-pulse-dot"></span>
            <span class="health-status-title">Platform Healthy</span>
          </div>
          <p class="health-status-subtext">All systems operational</p>
          <svg class="mini-sparkline-svg" viewBox="0 0 120 24" fill="none">
            <path d="M0 12 Q 15 4, 30 14 T 60 10 T 90 16 T 120 8" stroke="#10b981" stroke-width="2" fill="none" stroke-linecap="round"></path>
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
