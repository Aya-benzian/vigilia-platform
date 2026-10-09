# Stage 1: Build
FROM node:20-alpine AS builder
WORKDIR /app
COPY package.json .
# RUN npm install (mocked for now)
COPY . .
RUN npm run build

# Stage 2: Serve
# Using the official unprivileged NGINX image
FROM nginxinc/nginx-unprivileged:alpine

# Copy built assets
COPY --from=builder /app/dist /usr/share/nginx/html

# Copy the runtime environment injection script
COPY --chown=nginx:nginx entrypoint.sh /docker-entrypoint.d/99-envsubst.sh
RUN chmod +x /docker-entrypoint.d/99-envsubst.sh

# nginx-unprivileged uses port 8080 by default (non-root cannot bind to 80)
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget -q --spider http://localhost:8080/ || curl -f http://localhost:8080/ || exit 1
