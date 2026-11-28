# Stage 1: Build wheels
FROM python:3.12-slim-bookworm AS builder
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip && \
    pip wheel --no-cache-dir --wheel-dir /app/wheels -r requirements.txt

# Stage 2: Run
FROM python:3.12-slim-bookworm
WORKDIR /app

# Pull security updates for packages inherited from the base image.
RUN apt-get update && \
    apt-get upgrade -y --no-install-recommends && \
    rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN addgroup --system appgroup && adduser --system --group appuser

COPY --from=builder /app/wheels /wheels
RUN pip install --no-cache-dir /wheels/*.whl && \
    rm -rf /wheels

COPY . .
RUN chown -R appuser:appgroup /app

USER appuser
EXPOSE 8000

# Using python to check health since curl is not in slim images
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3   CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health/live')" || exit 1

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
