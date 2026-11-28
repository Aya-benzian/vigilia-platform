# ─────────────────────────────────────────────────────────────────────────────
# Vigilia Frontend — Multi-stage Docker Build
#
# Stage 1 (builder): Node.js installs deps and runs Vite to produce dist/
# Stage 2 (runtime): Lightweight Nginx serves the compiled static bundle
#
# Final image size: ~20 MB (vs ~500 MB if Node.js were kept)
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM node:20-alpine AS builder

WORKDIR /app

# Copy package files first (layer-cached if deps haven't changed)
COPY package.json ./

# Install only production-safe devDependencies needed for the build
# --ignore-scripts avoids running arbitrary postinstall hooks
RUN npm install --ignore-scripts

# Copy all frontend source
COPY . .

# Build with Vite in production mode (reads .env.production)
# Output goes to /app/dist/
RUN npm run build

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM nginxinc/nginx-unprivileged:alpine

# Upgrade base OS packages to pull latest security patches (mitigates Alpine CVEs)
USER root
RUN apk update && apk upgrade --no-cache libxml2

# Copy compiled static bundle from builder
COPY --from=builder /app/dist /usr/share/nginx/html

# Custom Nginx config (SPA routing, security headers, caching)
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Runtime environment injection script
# Runs at container start to substitute env vars into the bundle
COPY --chown=nginx:nginx entrypoint.sh /docker-entrypoint.d/99-envsubst.sh
RUN chmod +x /docker-entrypoint.d/99-envsubst.sh

# nginx-unprivileged listens on 8080 (non-root safe)
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget -q --spider http://localhost:8080/ || curl -f http://localhost:8080/ || exit 1

USER nginx
