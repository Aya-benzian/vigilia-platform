/**
 * Vigilia Platform — Real-Time Monitor Data Fetcher
 * Connects directly to live backend endpoints:
 *  - Prometheus API (:9090) for microservices health, CPU, JVM memory, and scrape latency
 *  - Alertmanager API (:9093) for live active incidents
 *  - Risk Management Service (/api/v1/risk/stats) for fraud decision distribution
 *  - Spring Boot Actuator / API Gateway (:8080) for system readiness
 */
(function(window) {
  // Use relative proxied paths by default for seamless K8s and Docker compatibility
  const PROMETHEUS_BASE = (window.VigiliaApiClient && window.VigiliaApiClient.ENV && window.VigiliaApiClient.ENV.PROMETHEUS_BASE) || '/prometheus';
  const ALERTMANAGER_BASE = (window.VigiliaApiClient && window.VigiliaApiClient.ENV && window.VigiliaApiClient.ENV.ALERTMANAGER_BASE) || '/alertmanager';
  const GATEWAY_BASE = (window.VigiliaApiClient && window.VigiliaApiClient.ENV && window.VigiliaApiClient.ENV.API_BASE) || '/api';

  function getAuthHeaders() {
    const token = (typeof window !== 'undefined' && window.VigiliaAuth)
      ? window.VigiliaAuth.getToken()
      : (localStorage.getItem('vigilia_token') || sessionStorage.getItem('vigilia_token'));
    const headers = { 'Accept': 'application/json' };
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    return headers;
  }

  const MonitorAPI = {
    async queryPrometheus(query) {
      try {
        const res = await fetch(`${PROMETHEUS_BASE}/api/v1/query?query=${encodeURIComponent(query)}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        return data.data && data.data.result ? data.data.result : [];
      } catch (e) {
        console.warn('[VigiliaMonitor] Prometheus query failed:', query, e);
        return [];
      }
    },

    async queryPrometheusRange(query, durationSeconds = 3600, step = 60) {
      try {
        const end = Math.floor(Date.now() / 1000);
        const start = end - durationSeconds;
        const url = `${PROMETHEUS_BASE}/api/v1/query_range?query=${encodeURIComponent(query)}&start=${start}&end=${end}&step=${step}`;
        const res = await fetch(url);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        return data.data && data.data.result && data.data.result[0] ? data.data.result[0].values : [];
      } catch (e) {
        console.warn('[VigiliaMonitor] Prometheus range query failed:', query, e);
        return [];
      }
    },

    async fetchTargets() {
      try {
        const res = await fetch(`${PROMETHEUS_BASE}/api/v1/targets`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        return data.data && data.data.activeTargets ? data.data.activeTargets : [];
      } catch (e) {
        console.warn('[VigiliaMonitor] Targets query failed:', e);
        return [];
      }
    },

    async fetchAlerts() {
      try {
        const res = await fetch(`${ALERTMANAGER_BASE}/api/v2/alerts`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return await res.json();
      } catch (e) {
        console.warn('[VigiliaMonitor] Alertmanager fetch failed:', e);
        return [];
      }
    },

    /**
     * Fetches live fraud decision distribution and model stats from risk-management-service.
     * Endpoint: GET /api/v1/risk/stats
     * Returns: totalEvaluated, approvedPct, pendingReviewPct, rejectedPct,
     *          approvedCount, pendingReviewCount, rejectedCount,
     *          avgInferenceMs, manualReviewCount, modelVersion
     */
    async fetchRiskStats() {
      try {
        const res = await fetch(`${GATEWAY_BASE}/v1/risk/stats`, {
          headers: getAuthHeaders()
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const envelope = await res.json();
        return envelope.data || envelope;
      } catch (e) {
        console.warn('[VigiliaMonitor] Risk stats fetch failed:', e);
        return null;
      }
    },

    /**
     * Fetches live transactional outbox status from transaction-service.
     * Endpoint: GET /api/v1/transactions/outbox/status
     */
    async fetchOutboxStatus() {
      try {
        const res = await fetch(`${GATEWAY_BASE}/v1/transactions/outbox/status`, {
          headers: getAuthHeaders()
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const envelope = await res.json();
        return envelope.data || envelope;
      } catch (e) {
        console.warn('[VigiliaMonitor] Outbox status fetch failed:', e);
        return null;
      }
    },

    /**
     * Fetches live transaction channel metrics from transaction-service.
     * Endpoint: GET /api/v1/transactions/stats
     */
    async fetchTransactionStats() {
      try {
        const res = await fetch(`${GATEWAY_BASE}/v1/transactions/stats`, {
          headers: getAuthHeaders()
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const envelope = await res.json();
        return envelope.data || envelope;
      } catch (e) {
        console.warn('[VigiliaMonitor] Transaction stats fetch failed:', e);
        return null;
      }
    },

    /**
     * Aggregates all real-time platform metrics from Prometheus, Alertmanager & microservices
     */
    async loadLiveDashboardData(durationSeconds = 3600, rangeStep = 120) {
      const results = await Promise.allSettled([
        this.fetchTargets(),
        this.queryPrometheus('avg(process_cpu_usage) * 100'),
        this.queryPrometheus('sum(jvm_memory_used_bytes) / 1024 / 1024'),
        this.queryPrometheus('scrape_duration_seconds{job="ml-serving"} * 1000'),
        this.queryPrometheus('sum(up)'),
        this.fetchAlerts(),
        this.queryPrometheusRange('avg(process_cpu_usage) * 100', durationSeconds, rangeStep),
        this.queryPrometheusRange('sum(jvm_memory_used_bytes) / 1024 / 1024', durationSeconds, rangeStep),
        this.queryPrometheus('avg(fintech_ml_prediction_uncertainty)'),
        this.queryPrometheus('sum(rate(http_server_requests_seconds_count[5m]))'),
        this.fetchRiskStats(),
        this.fetchOutboxStatus(),
        this.fetchTransactionStats()
      ]);

      const targets = results[0].status === 'fulfilled' ? results[0].value : [];
      const cpuResult = results[1].status === 'fulfilled' ? results[1].value : [];
      const memResult = results[2].status === 'fulfilled' ? results[2].value : [];
      const mlLatencyResult = results[3].status === 'fulfilled' ? results[3].value : [];
      const healthyUpResult = results[4].status === 'fulfilled' ? results[4].value : [];
      const alerts = results[5].status === 'fulfilled' ? results[5].value : [];
      const cpuRange = results[6].status === 'fulfilled' ? results[6].value : [];
      const memRange = results[7].status === 'fulfilled' ? results[7].value : [];
      const uncertaintyResult = results[8].status === 'fulfilled' ? results[8].value : [];
      const tpsResult = results[9].status === 'fulfilled' ? results[9].value : [];
      const riskStats = results[10].status === 'fulfilled' ? results[10].value : null;
      const outboxStatus = results[11].status === 'fulfilled' ? results[11].value : null;
      const txStats = results[12].status === 'fulfilled' ? results[12].value : null;

      // Parse Metrics from live Prometheus data
      const totalServices = targets.length || 8;
      const healthyServices = targets.filter(t => t.health === 'up').length;
      const cpuUsage = cpuResult.length > 0 ? Number.parseFloat(cpuResult[0].value[1]).toFixed(1) : '0.0';
      const jvmMemory = memResult.length > 0 ? Math.round(Number.parseFloat(memResult[0].value[1])) : 0;
      const mlLatency = mlLatencyResult.length > 0 ? Math.round(Number.parseFloat(mlLatencyResult[0].value[1])) : 0;

      // FinTech & Model Drift Telemetry
      const uncertainty = uncertaintyResult.length > 0 ? Number.parseFloat(uncertaintyResult[0].value[1]) : 0;
      const confidenceScore = ((1 - Math.min(1, uncertainty)) * 100).toFixed(1);
      const rawTps = tpsResult.length > 0 ? Number.parseFloat(tpsResult[0].value[1]) : 0;
      const tps = rawTps > 0 ? (rawTps < 1 ? rawTps.toFixed(2) : Math.round(rawTps)) : 0;

      return {
        targets,
        totalServices,
        healthyServices,
        cpuUsage,
        jvmMemory,
        mlLatency,
        alerts,
        cpuRange,
        memRange,
        confidenceScore,
        tps,
        riskStats,
        outboxStatus,
        txStats,
        isLive: targets.length > 0
      };
    }
  };

  window.VigiliaMonitorAPI = MonitorAPI;
})(window);

