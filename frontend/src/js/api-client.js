/**
 * Vigilia Platform — Central API Client
 * ─────────────────────────────────────
 * All backend URLs are driven by VITE_ environment variables.
 *
 * In development:  Vite reads .env.development, dev server proxies /api → localhost:8080
 * In production:   .env.production sets relative paths (/api, /prometheus, /alertmanager)
 *                  Nginx in the Docker image routes these to the real K8s services.
 */

// ─── Runtime environment (injected by Vite at build time) ─────────────────────
export const ENV = {
  API_BASE:          import.meta.env.VITE_API_BASE_URL       || '/api',
  PROMETHEUS_BASE:   import.meta.env.VITE_PROMETHEUS_URL     || '/prometheus',
  ALERTMANAGER_BASE: import.meta.env.VITE_ALERTMANAGER_URL || '/alertmanager',
  APP_ENV:           import.meta.env.VITE_APP_ENV            || 'production',
  APP_VERSION:       import.meta.env.VITE_APP_VERSION        || '1.0.0',
};

// ─── Token helper (reads from VigiliaAuth if available, else storage) ─────────
function getAuthToken() {
  if (typeof window !== 'undefined' && window.VigiliaAuth) {
    return window.VigiliaAuth.getToken();
  }
  return localStorage.getItem('vigilia_token') || sessionStorage.getItem('vigilia_token');
}

// ─── URL Normalizer (prevents double /api/ or missing slashes) ─────────────────
export function buildApiUrl(path) {
  let base = ENV.API_BASE || '/api';
  while (base.endsWith('/')) {
    base = base.slice(0, -1);
  }
  const cleanPath = path.startsWith('/') ? path : `/${path}`;
  if (base.endsWith('/api') && cleanPath.startsWith('/api/')) {
    return `${base}${cleanPath.substring(4)}`;
  }
  return `${base}${cleanPath}`;
}

// ─── Core fetch wrapper ────────────────────────────────────────────────────────
async function request(url, options = {}) {
  const token = getAuthToken();

  const headers = {
    'Content-Type': 'application/json',
    'Accept': 'application/json',
    ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
    ...(options.headers || {}),
  };

  const response = await fetch(url, { ...options, headers });

  // Handle 401 Unauthorized — clear session and redirect to login
  if (response.status === 401) {
    if (typeof window !== 'undefined' && window.VigiliaAuth) {
      window.VigiliaAuth.clearSession();
    }
    const current = typeof window !== 'undefined' ? window.location.pathname : '';
    if (current && !current.includes('login.html')) {
      window.location.href = `/login.html?redirect=${encodeURIComponent(current)}`;
    }
    return null;
  }

  // Parse JSON response or return text
  const contentType = response.headers.get('content-type') || '';
  const isJson = contentType.includes('application/json');
  const body = isJson ? await response.json().catch(() => null) : await response.text().catch(() => null);

  if (!response.ok) {
    const errorMsg = (body && typeof body === 'object')
      ? (body.message || body.error || `HTTP ${response.status} ${response.statusText}`)
      : `HTTP ${response.status} ${response.statusText}`;
    const err = new Error(errorMsg);
    err.status = response.status;
    err.body = body;
    throw err;
  }

  return body;
}

// ─── Public API Client ─────────────────────────────────────────────────────────
export const apiClient = {
  ENV,
  buildApiUrl,

  /**
   * GET request to the Spring Boot API Gateway
   */
  async get(path, options = {}) {
    return request(buildApiUrl(path), { method: 'GET', ...options });
  },

  /**
   * POST request with JSON body
   */
  async post(path, body, options = {}) {
    return request(buildApiUrl(path), {
      method: 'POST',
      body: JSON.stringify(body),
      ...options,
    });
  },

  /**
   * PUT request with JSON body
   */
  async put(path, body, options = {}) {
    return request(buildApiUrl(path), {
      method: 'PUT',
      body: JSON.stringify(body),
      ...options,
    });
  },

  /**
   * DELETE request
   */
  async delete(path, options = {}) {
    return request(buildApiUrl(path), { method: 'DELETE', ...options });
  },

  // ─── Authentication Subsystem ────────────────────────────────────────────────
  auth: {
    /**
     * Authenticate with backend: POST /api/v1/auth/login
     */
    async login(email, password, remember = true) {
      const payload = {
        email: email.trim(),
        username: email.trim(),
        password: password
      };

      const res = await request(buildApiUrl('/v1/auth/login'), {
        method: 'POST',
        body: JSON.stringify(payload)
      });

      // Backend returns ApiResponse<AuthResponse>: { data: { accessToken, refreshToken, userId, email, role }, meta: {...} }
      const authData = (res && res.data) ? res.data : res;

      if (!authData || !authData.accessToken) {
        throw new Error('Authentication response did not contain access token.');
      }

      const user = {
        userId: authData.userId,
        email: authData.email,
        role: authData.role,
        roles: authData.roles || [`ROLE_${authData.role}`]
      };

      if (typeof window !== 'undefined' && window.VigiliaAuth) {
        window.VigiliaAuth.saveSession(authData.accessToken, authData.refreshToken, user, remember);
      }

      return { authData, user };
    },

    /**
     * Register customer: POST /api/v1/auth/register
     */
    async register(email, password, username, remember = true) {
      const cleanEmail = email.trim();
      const payload = {
        email: cleanEmail,
        username: username ? username.trim() : cleanEmail.split('@')[0],
        password: password,
        role: 'CUSTOMER'
      };

      const res = await request(buildApiUrl('/v1/auth/register'), {
        method: 'POST',
        body: JSON.stringify(payload)
      });

      const authData = (res && res.data) ? res.data : res;

      if (authData && authData.accessToken) {
        const user = {
          userId: authData.userId,
          email: authData.email,
          role: authData.role || 'CUSTOMER',
          roles: authData.roles || ['ROLE_CUSTOMER']
        };
        if (typeof window !== 'undefined' && window.VigiliaAuth) {
          window.VigiliaAuth.saveSession(authData.accessToken, authData.refreshToken, user, remember);
        }
        return { authData, user };
      }

      return authData;
    },

    /**
     * Fetch authenticated user profile: GET /api/v1/auth/me
     */
    async me() {
      const res = await request(buildApiUrl('/v1/auth/me'));
      return (res && res.data) ? res.data : res;
    },

    /**
     * Terminate session: POST /api/v1/auth/logout
     */
    async logout() {
      const refreshToken = typeof window !== 'undefined' && window.VigiliaAuth
        ? window.VigiliaAuth.getRefreshToken()
        : null;

      try {
        if (refreshToken) {
          await request(buildApiUrl('/v1/auth/logout'), {
            method: 'POST',
            body: JSON.stringify({ refreshToken })
          });
        }
      } catch (e) {
        console.warn('[VigiliaAuth] Remote logout notification failed:', e);
      } finally {
        if (typeof window !== 'undefined' && window.VigiliaAuth) {
          window.VigiliaAuth.clearSession();
        }
      }
    },

    isAuthenticated() {
      if (typeof window !== 'undefined' && window.VigiliaAuth) {
        return window.VigiliaAuth.isAuthenticated();
      }
      return !!getAuthToken();
    },

    getUser() {
      if (typeof window !== 'undefined' && window.VigiliaAuth) {
        return window.VigiliaAuth.getUser();
      }
      return null;
    }
  },

  // ─── System Health & Readiness Subsystem ─────────────────────────────────────
  health: {
    /**
     * Check backend connectivity (Spring Cloud Gateway JWKS endpoint)
     */
    async check() {
      const start = performance.now();
      try {
        const res = await fetch('/.well-known/jwks.json', {
          method: 'GET',
          headers: { 'Accept': 'application/json' },
          cache: 'no-cache'
        });
        const latencyMs = Math.round(performance.now() - start);
        return {
          online: res.ok || res.status === 200,
          status: res.ok ? 'HEALTHY' : `HTTP_${res.status}`,
          latencyMs
        };
      } catch (e) {
        return {
          online: false,
          status: 'UNREACHABLE',
          latencyMs: Math.round(performance.now() - start),
          error: e.message
        };
      }
    }
  }
};

// Expose to window for vanilla scripts
if (typeof window !== 'undefined') {
  window.VigiliaApiClient = apiClient;
}
