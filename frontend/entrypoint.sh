#!/bin/sh
# Inject runtime environment variables into the static bundle
# We look for a placeholder like VITE_API_URL_PLACEHOLDER
echo "Injecting runtime variables..."
# find /usr/share/nginx/html -type f -name "*.js" -exec sed -i "s|VITE_API_URL_PLACEHOLDER|${VITE_API_URL}|g" {} +
exec "$@"
