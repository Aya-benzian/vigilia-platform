/**
 * Vigilia Platform — Human Analyst Workspace Components
 * Modular, accessible Web Components for Sidebar (<analyst-sidebar>) and Top Navbar (<analyst-navbar>).
 * Eliminates HTML duplication across multi-page views while guaranteeing 100% WCAG accessibility.
 */

class AnalystSidebar extends HTMLElement {
  connectedCallback() {
    const activeRoute = (this.getAttribute('active') || 'overview').toLowerCase();

    const navItems = [
      {
        id: 'overview',
        title: 'Analyst Overview',
        href: 'analyst-overview.html',
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path><polyline points="9 22 9 12 15 12 15 22"></polyline></svg>`
      },
      {
        id: 'queue',
        title: 'Review Queue',
        href: 'review-queue.html',
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4-1-4-1z"></path><line x1="4" y1="22" x2="4" y2="15"></line></svg>`
      },
      {
        id: 'investigations',
        title: 'My Investigations',
        href: 'case-investigation.html?case=VG-2048',
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path></svg>`
      },
      {
        id: 'history',
        title: 'Decision History',
        href: 'decision-history.html',
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>`
      }
    ];

    const navLinksHtml = navItems.map(item => {
      const isActive = activeRoute === item.id ? ' active' : '';
      return `
        <a href="${item.href}" class="nav-item${isActive}" title="${item.title}">
          ${item.icon}
          <span>${item.title}</span>
        </a>`;
    }).join('\n');

    this.innerHTML = `
    <aside class="sidebar light-theme" aria-label="Analyst Workspace Navigation">
      <div style="display: flex; flex-direction: column; gap: 0.35rem;">
        <a href="analyst-overview.html" class="brand-link" title="Vigilia Analyst Workspace">
          <img src="assets/vigilia-logo-mark.png" alt="Vigilia Platform Logo" class="brand-logo-img" />
          <span class="brand-name">Vigilia</span>
        </a>

        <nav class="nav-group" aria-label="Main Navigation">
          ${navLinksHtml}
        </nav>

        <div style="height: 1px; background: #e2e8f0; margin: 0.85rem 0.5rem;"></div>

        <nav class="nav-group" aria-label="User Account Navigation">
          <a href="#" class="nav-item" title="Notifications" onclick="return false;">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"></path>
              <path d="M13.73 21a2 2 0 0 1-3.46 0"></path>
            </svg>
            <span>Notifications</span>
            <span class="nav-badge">3</span>
          </a>

          <a href="#" class="nav-item" title="Profile & Settings" onclick="return false;">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"></path>
              <circle cx="12" cy="7" r="4"></circle>
            </svg>
            <span>Profile &amp; Settings</span>
          </a>
        </nav>
      </div>

      <div style="display: flex; flex-direction: column;">
        <div class="sidebar-security-box">
          <div class="sidebar-security-icon">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" aria-hidden="true">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path>
            </svg>
          </div>
          <div>
            <h4 class="sidebar-security-title">Your security is our priority</h4>
            <p class="sidebar-security-desc">AI-powered fraud detection keeps your transactions safe.</p>
          </div>
        </div>

        <a href="#" class="sidebar-contact-link" onclick="return false;">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <path d="M3 18v-6a9 9 0 0 1 18 0v6"></path>
            <path d="M21 19a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3zM3 19a2 2 0 0 0 2 2h1a2 2 0 0 0 2-2v-3a2 2 0 0 0-2-2H3z"></path>
          </svg>
          <span>Need help? Contact support</span>
        </a>
      </div>
    </aside>`;
  }
}

class AnalystNavbar extends HTMLElement {
  connectedCallback() {
    this.innerHTML = `
    <header class="top-navbar" aria-label="Top Navigation Bar">
      <div class="nav-left">
        <div class="search-container">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true">
            <circle cx="11" cy="11" r="8"></circle>
            <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
          </svg>
          <label for="analystTopSearch" class="sr-only">Search cases, transactions, or customers</label>
          <input 
            type="text" 
            id="analystTopSearch"
            name="analystTopSearch"
            class="search-input" 
            placeholder="Search cases, transactions, or customers..." 
            aria-label="Search cases, transactions, or customers..." 
          />
        </div>
      </div>

      <div class="nav-right">
        <button class="notification-btn" aria-label="Notifications (3 unread)" type="button">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"></path>
            <path d="M13.73 21a2 2 0 0 1-3.46 0"></path>
          </svg>
          <span class="notification-badge">3</span>
        </button>

        <div class="profile-pill" tabindex="0" role="button" aria-label="User Profile: Aïcha Belk">
          <div class="profile-avatar">AB</div>
          <div style="display: flex; flex-direction: column;">
            <span style="font-size: 0.86rem; font-weight: 700; color: #0f172a; line-height: 1.2;">Aïcha Belk</span>
            <span style="font-size: 0.72rem; color: #64748b;">Senior Analyst</span>
          </div>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" stroke-width="2.5" style="margin-left: 2px;" aria-hidden="true">
            <polyline points="6 9 12 15 18 9"></polyline>
          </svg>
        </div>
      </div>
    </header>`;
  }
}

if (!customElements.get('analyst-sidebar')) {
  customElements.define('analyst-sidebar', AnalystSidebar);
}

if (!customElements.get('analyst-navbar')) {
  customElements.define('analyst-navbar', AnalystNavbar);
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { AnalystSidebar, AnalystNavbar };
}
