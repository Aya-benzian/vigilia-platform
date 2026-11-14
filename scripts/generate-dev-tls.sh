#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Vigilia Platform — Step 22: Generate TLS Certificates & Kubernetes Secret
#
# Generates a local/dev SAN certificate for:
#   - vigilia.local
#   - *.vigilia.local
#   - localhost
#   - 127.0.0.1
#
# And automatically loads or outputs the Kubernetes TLS Secret.
#
# Usage:
#   ./scripts/generate-dev-tls.sh [namespace]
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

NAMESPACE="${1:-vigilia}"
SECRET_NAME="vigilia-tls-secret"
OUTPUT_DIR="${TMPDIR:-/tmp}/vigilia-tls"

mkdir -p "${OUTPUT_DIR}"
KEY_FILE="${OUTPUT_DIR}/tls.key"
CERT_FILE="${OUTPUT_DIR}/tls.crt"
CONF_FILE="${OUTPUT_DIR}/openssl.cnf"

echo "======================================================================"
echo "  Vigilia Platform — TLS Certificate Generation (Step 22)"
echo " Output Directory: ${OUTPUT_DIR}"
echo " Target Namespace: ${NAMESPACE}"
echo "======================================================================"

cat > "${CONF_FILE}" <<EOF
[req]
default_bits       = 2048
prompt             = no
default_md         = sha256
distinguished_name = dn
req_extensions     = req_ext

[dn]
C  = US
ST = California
L  = San Francisco
O  = Vigilia Platform
OU = DevSecOps
CN = vigilia.local

[req_ext]
subjectAltName = @alt_names

[alt_names]
DNS.1 = vigilia.local
DNS.2 = *.vigilia.local
DNS.3 = localhost
IP.1  = 127.0.0.1
EOF

echo " Generating 2048-bit RSA private key..."
openssl genrsa -out "${KEY_FILE}" 2048 >/dev/null 2>&1

echo " Generating self-signed X.509 certificate (valid 365 days)..."
openssl req -new -x509 \
  -key "${KEY_FILE}" \
  -out "${CERT_FILE}" \
  -days 365 \
  -config "${CONF_FILE}" >/dev/null 2>&1

echo " Generated certificate successfully:"
openssl x509 -in "${CERT_FILE}" -noout -subject -dates -ext subjectAltName

echo ""
echo " Creating Kubernetes TLS Secret manifest..."
if command -v kubectl >/dev/null 2>&1 && kubectl cluster-info >/dev/null 2>&1; then
  kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f - 2>/dev/null || true
  
  kubectl create secret tls "${SECRET_NAME}" \
    --cert="${CERT_FILE}" \
    --key="${KEY_FILE}" \
    --namespace="${NAMESPACE}" \
    --dry-run=client -o yaml | kubectl apply -f -
  
  echo " Kubernetes TLS secret '${SECRET_NAME}' applied to cluster in namespace '${NAMESPACE}'!"
else
  # Output yaml for manual or offline usage
  kubectl create secret tls "${SECRET_NAME}" \
    --cert="${CERT_FILE}" \
    --key="${KEY_FILE}" \
    --namespace="${NAMESPACE}" \
    --dry-run=client -o yaml > "${OUTPUT_DIR}/secret-tls.yaml" 2>/dev/null || true
  
  echo "  Cluster is currently offline or unreachable."
  echo " TLS Secret manifest saved to: ${OUTPUT_DIR}/secret-tls.yaml"
  echo "   (Apply when cluster is up: kubectl apply -f ${OUTPUT_DIR}/secret-tls.yaml)"
fi

echo ""
echo " To trust this certificate locally in /etc/hosts, add:"
echo "   127.0.0.1  vigilia.local"
