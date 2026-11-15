import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  // Load env variables for the current mode (development / production)
  const env = loadEnv(mode, process.cwd(), '');

  return {
    // ─── Source root: all HTML pages live directly in src/
    root: 'src',

    // ─── Build output goes to ../dist (sibling of src/, outside of it)
    build: {
      outDir: '../dist',
      emptyOutDir: true,

      // Multi-page app: each .html file is its own entry point
      rollupOptions: {
        input: {
          main:      'index.html',
          login:     'login.html',
          monitor:   'monitor-dashboard.html',
        },
      },

      // Asset file naming with content hashes (critical for K8s cache-busting)
      assetsDir: 'assets',

      // Minify in production, skip in dev preview
      minify: mode === 'production' ? 'oxc' : false,
    },

    // ─── Static assets served at root (logos, favicons, images)
    // relative to root ('src'), so 'assets' resolves to src/assets/
    publicDir: 'assets',

    // ─── Dev server config
    server: {
      port: 5173,
      strictPort: true,
      open: '/index.html',
      proxy: {
        // All /api/* calls → Spring Boot API Gateway (local dev)
        '/api': {
          target: env.VITE_API_BASE_URL || 'http://localhost:8080',
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/api/, '/api'),
        },
      },
    },

    // ─── Preview server (runs built dist/)
    preview: {
      port: 4173,
    },

    // ─── Make env vars available to HTML via import.meta.env
    // Only vars prefixed VITE_ are exposed to the browser
    define: {},
  };
});
