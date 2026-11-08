/**
 * Vigilia Platform — Authentication & Session State Manager
 * ─────────────────────────────────────────────────────────
 * Synchronizes JWT access tokens, refresh tokens, and user metadata
 * across LocalStorage (persistent) and SessionStorage (temporary).
 */

const TOKEN_KEY = 'vigilia_token';
const REFRESH_KEY = 'vigilia_refresh_token';
const USER_KEY = 'vigilia_user';

export const VigiliaAuth = {
  TOKEN_KEY,
  REFRESH_KEY,
  USER_KEY,

  getToken() {
    return localStorage.getItem(TOKEN_KEY) || sessionStorage.getItem(TOKEN_KEY) || null;
  },

  getRefreshToken() {
    return localStorage.getItem(REFRESH_KEY) || sessionStorage.getItem(REFRESH_KEY) || null;
  },

  getUser() {
    try {
      const raw = localStorage.getItem(USER_KEY) || sessionStorage.getItem(USER_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      console.warn('[VigiliaAuth] Failed to parse user session:', e);
      return null;
    }
  },

  isAuthenticated() {
    return !!this.getToken();
  },

  saveSession(token, refreshToken, user, remember = true) {
    const primaryStorage = remember ? localStorage : sessionStorage;
    const secondaryStorage = remember ? sessionStorage : localStorage;

    secondaryStorage.removeItem(TOKEN_KEY);
    secondaryStorage.removeItem(REFRESH_KEY);
    secondaryStorage.removeItem(USER_KEY);

    if (token) primaryStorage.setItem(TOKEN_KEY, token);
    if (refreshToken) primaryStorage.setItem(REFRESH_KEY, refreshToken);
    if (user) primaryStorage.setItem(USER_KEY, typeof user === 'string' ? user : JSON.stringify(user));
  },

  clearSession() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_KEY);
    localStorage.removeItem(USER_KEY);
    sessionStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(REFRESH_KEY);
    sessionStorage.removeItem(USER_KEY);
  },

  logout() {
    this.clearSession();
    window.location.replace('/login.html');
  },

  requireAuth(allowedRoles) {
    const token = this.getToken();
    const user = this.getUser();

    if (!token || !user) {
      const currentPath = encodeURIComponent(window.location.pathname + window.location.search);
      window.location.replace(`/login.html?redirect=${currentPath}`);
      return false;
    }

    if (allowedRoles && Array.isArray(allowedRoles) && allowedRoles.length > 0) {
      const userRole = (user.role || '').toUpperCase();
      const hasRole = allowedRoles.some(r => r.toUpperCase() === userRole || `ROLE_${r.toUpperCase()}` === userRole);
      if (!hasRole) {
        console.warn(`[VigiliaAuth] Role '${userRole}' not authorized. Required:`, allowedRoles);
        window.location.replace('/login.html?unauthorized=1');
        return false;
      }
    }

    return true;
  }
};

// Global Back-Forward Cache (BFCache) Guard: re-evaluates session when navigating back
if (typeof window !== 'undefined') {
  window.addEventListener('pageshow', (event) => {
    // If navigating back to protected page without token
    const isProtected = window.location.pathname.includes('monitor-dashboard.html');
    if (isProtected && !VigiliaAuth.isAuthenticated()) {
      window.location.replace('/login.html');
    }
  });
}

// Also attach to window for global access
if (typeof window !== 'undefined') {
  window.VigiliaAuth = VigiliaAuth;
}

export default VigiliaAuth;
