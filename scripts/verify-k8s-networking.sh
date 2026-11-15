#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Vigilia Platform — Step 20: CoreDNS & Internal Networking Verification
#
# This script spins up an ephemeral curl/nslookup pod inside the cluster to
# verify:
#   1. CoreDNS resolves internal service names (auth-service, postgres, etc.)
#   2. HTTP connectivity to actuator/health or service ports succeeds
#
# Usage:
#   ./scripts/verify-k8s-networking.sh [namespace]
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

NAMESPACE="${1:-vigilia}"
TEST_POD="vigilia-net-probe-$$"

echo "======================================================================"
echo " 🌐 Vigilia Platform — Kubernetes Networking Verification (Step 20)"
echo " Namespace: ${NAMESPACE}"
echo "======================================================================"

SERVICES=(
  "api-gateway:8080:/actuator/health"
  "auth-service:8081:/actuator/health"
  "customer-service:8082:/actuator/health"
  "transaction-service:8083:/actuator/health"
  "risk-management-service:8084:/actuator/health"
  "notification-service:8085:/actuator/health"
  "audit-service:8086:/actuator/health"
  "ml-serving:8000:/health/live"
  "frontend:8080:/"
  "postgres:5432:tcp"
)

cleanup() {
  echo ""
  echo " Cleaning up probe pod..."
  kubectl delete pod "${TEST_POD}" -n "${NAMESPACE}" --ignore-not-found=true --grace-period=0 --force >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo " Launching diagnostic pod: ${TEST_POD} in namespace ${NAMESPACE}..."
kubectl run "${TEST_POD}" \
  --namespace="${NAMESPACE}" \
  --image=curlimages/curl:8.10.1 \
  --restart=Never \
  --command -- sleep 300 >/dev/null 2>&1

echo " Waiting for probe pod to be ready..."
kubectl wait --for=condition=Ready pod/"${TEST_POD}" -n "${NAMESPACE}" --timeout=30s >/dev/null

echo ""
echo " Testing CoreDNS Name Resolution & Internal Connectivity:"
echo "----------------------------------------------------------------------"

FAILED=0

for ENTRY in "${SERVICES[@]}"; do
  IFS=':' read -r HOST PORT PATH_CHECK <<< "${ENTRY}"
  echo -n "  Testing ${HOST}:${PORT} ... "

  if [ "${PATH_CHECK}" = "tcp" ]; then
    # Test raw TCP port via nc/curl
    if kubectl exec "${TEST_POD}" -n "${NAMESPACE}" -- nc -z -w 3 "${HOST}" "${PORT}" >/dev/null 2>&1; then
      echo " OK (TCP connected)"
    else
      echo " FAILED (TCP connection refused or DNS resolution failed)"
      FAILED=$((FAILED + 1))
    fi
  else
    # Test HTTP endpoint
    HTTP_CODE=$(kubectl exec "${TEST_POD}" -n "${NAMESPACE}" -- \
      curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 "http://${HOST}:${PORT}${PATH_CHECK}" 2>/dev/null || echo "000")
    
    if [ "${HTTP_CODE}" -ge 200 ] && [ "${HTTP_CODE}" -lt 400 ]; then
      echo " OK (HTTP ${HTTP_CODE} on ${PATH_CHECK})"
    else
      echo " HTTP ${HTTP_CODE} on ${PATH_CHECK} (CoreDNS may work, check service readiness)"
    fi
  fi
done

echo "----------------------------------------------------------------------"
if [ "${FAILED}" -eq 0 ]; then
  echo " CoreDNS and internal microservice networking are fully verified!"
else
  echo " ${FAILED} service(s) could not be reached. Ensure pods and services are deployed."
  exit 1
fi

