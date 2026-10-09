# Stage 1: Build dependencies in an isolated virtual environment
FROM python:3.12-slim-bookworm AS builder

WORKDIR /build

# Create dedicated virtual environment
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt

# Stage 2: Clean training development & execution image
FROM python:3.12-slim-bookworm

WORKDIR /workspace

# Create non-root user for safe local execution
RUN addgroup --system appgroup && adduser --system --group appuser

# Copy pre-built virtual environment from builder stage
COPY --from=builder /opt/venv /opt/venv

ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONPATH="/workspace:/workspace/src"

# Copy training workspace files and configure ownership
COPY --chown=appuser:appgroup . /workspace

USER appuser

# Default command validates Python runtime
CMD ["python", "--version"]
