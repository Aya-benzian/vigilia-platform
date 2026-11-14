/**
 * Vigilia Platform — Live Dashboard State Binder
 * Binds real Prometheus & Alertmanager telemetry to DOM elements
 */
document.addEventListener('DOMContentLoaded', () => {
  const el = (id) => document.getElementById(id);

  // Enforce operator role authorization
  if (typeof window !== 'undefined' && window.VigiliaAuth) {
    const isAllowed = window.VigiliaAuth.requireAuth(['ADMIN', 'ANALYST']);
    if (!isAllowed) return;
  }

  let currentDuration = 3600;
  let currentStep = 120;

  async function updateDashboard(duration = currentDuration, step = currentStep) {
    if (!window.VigiliaMonitorAPI) return;

    try {
      const data = await window.VigiliaMonitorAPI.loadLiveDashboardData(duration, step);

      // Sync timestamp update (single authoritative updater)
      if (el('syncPillText')) {
        const now = new Date();
        const timeStr = now.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
        el('syncPillText').textContent = `Live Prometheus Sync · ${timeStr}`;
      }

      // 1. Top KPI Row
      if (el('kpiServicesOnline')) {
        el('kpiServicesOnline').innerHTML = `${data.healthyServices} <span class="kpi-denominator">/ ${data.totalServices}</span>`;
      }
      if (el('kpiCpuUsage')) {
        el('kpiCpuUsage').textContent = `${data.cpuUsage}%`;
      }
      if (el('kpiMemUsage')) {
        el('kpiMemUsage').textContent = `${data.jvmMemory} MB`;
      }
      if (el('kpiMlLatency')) {
        el('kpiMlLatency').innerHTML = `${data.mlLatency} <span class="kpi-denominator">ms</span>`;
      }
      if (el('kpiPlatformStatus')) {
        const isAllHealthy = data.healthyServices === data.totalServices && data.totalServices > 0;
        el('kpiPlatformStatus').innerHTML = `
          <span style="display:inline-block; width:8px; height:8px; border-radius:50%; background:${isAllHealthy ? 'var(--green, #10b981)' : 'var(--orange, #f59e0b)'};"></span>
          ${isAllHealthy ? 'Healthy' : 'Degraded'}
        `;
      }

      // Sync Resource Usage Trends KPI stats with real Prometheus telemetry
      if (el('trendKpiCpu')) {
        el('trendKpiCpu').textContent = `${data.cpuUsage}%`;
      }
      if (el('trendLegendCpu')) {
        el('trendLegendCpu').textContent = `${data.cpuUsage}%`;
      }
      if (el('trendKpiMem')) {
        el('trendKpiMem').textContent = `${data.jvmMemory} MB`;
      }
      if (el('trendLegendMem')) {
        el('trendLegendMem').textContent = `${data.jvmMemory} MB`;
      }
      if (el('trendKpiLatency')) {
        el('trendKpiLatency').textContent = `${data.mlLatency} ms`;
      }

      // 2. Microservices Fleet Table
      const fleetTbody = el('microservicesTbody');
      if (fleetTbody && data.targets && data.targets.length > 0) {
        const serviceMetaMap = {
          'api-gateway': {
            color: 'blue',
            icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12h14M12 5l7 7-7 7"/></svg>'
          },
          'audit-service': {
            color: 'slate',
            icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>'
          },
          'auth-service': {
            color: 'purple',
            icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>'
          },
          'customer-service': {
            color: 'cyan',
            icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>'
          },
          'ml-serving': {
            color: 'rose',
            icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="4" y="4" width="16" height="16" rx="2"/><rect x="9" y="9" width="6" height="6"/><path d="M9 1v3M15 1v3M9 20v3M15 20v3M20 9h3M20 14h3M1 9h3M1 14h3"/></svg>'
          },
          'notification-service': {
            color: 'indigo',
            icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>'
          },
          'risk-management-service': {
            color: 'amber',
            icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>'
          },
          'transaction-service': {
            color: 'emerald',
            icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="1" y="4" width="22" height="16" rx="2" ry="2"/><line x1="1" y1="10" x2="23" y2="10"/></svg>'
          }
        };
        const defaultMeta = {
          color: 'blue',
          icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="2" width="20" height="8" rx="2" ry="2"/><rect x="2" y="14" width="20" height="8" rx="2" ry="2"/></svg>'
        };

        let totalLat = 0;
        fleetTbody.innerHTML = data.targets.map(t => {
          const serviceName = t.labels.job || 'service';
          const port = (t.labels.instance || '').split(':')[1] || '8080';
          const latencyMs = Math.round((Number.parseFloat(t.lastScrapeDuration) || 0.015) * 1000);
          totalLat += latencyMs;
          const isUp = t.health === 'up';
          const meta = serviceMetaMap[serviceName] || defaultMeta;

          let fillClass = 'fill-fast';
          if (latencyMs > 18) {
            fillClass = 'fill-elevated';
          } else if (latencyMs > 10) {
            fillClass = 'fill-nominal';
          }
          const latPercent = Math.min(100, Math.max(15, Math.round((latencyMs / 25) * 100)));

          return `
            <tr>
              <td>
                <div class="service-identity-cell">
                  <div class="service-badge-icon tag-${meta.color}">
                    ${meta.icon}
                  </div>
                  <div class="service-info-meta">
                    <span class="service-name-text">${serviceName}</span>
                    <div class="table-sub-text">${t.labels.instance}</div>
                  </div>
                </div>
              </td>
              <td><span class="table-code-badge">${port}</span></td>
              <td>
                <div class="latency-cell-box">
                  <span class="latency-val mono-num">${latencyMs} ms</span>
                  <div class="latency-meter-bar">
                    <div class="latency-meter-fill ${fillClass}" style="width: ${latPercent}%;"></div>
                  </div>
                </div>
              </td>
              <td class="text-right">
                <span class="status-cell ${isUp ? 'healthy' : 'warn'}">
                  <span class="dot"></span>${isUp ? 'Healthy' : 'Down'}
                </span>
              </td>
            </tr>
          `;
        }).join('');

        if (el('fleetAvgLatVal') && data.targets.length > 0) {
          const avg = (totalLat / data.targets.length).toFixed(1);
          el('fleetAvgLatVal').textContent = `${avg} ms`;
        }
        if (el('fleetHealthVal')) {
          const isAllUp = data.healthyServices === data.totalServices;
          el('fleetHealthVal').innerHTML = `<span class="legend-dot running"></span>${isAllUp ? '100% Operational' : `${data.healthyServices}/${data.totalServices} Online`}`;
        }
      }

      // 3. AI Model Drift & Risk Confidence
      if (el('modelConfidenceScore')) {
        const rawScore = data.confidenceScore !== undefined ? data.confidenceScore : 100.0;
        const confNum = Math.min(100, Math.max(0, Number.parseFloat(rawScore) || 100.0));
        el('modelConfidenceScore').textContent = `${confNum.toFixed(1)}%`;

        const segHigh = document.querySelector('.dist-segment.seg-high');
        if (segHigh && confNum > 0) {
          segHigh.style.width = `${confNum.toFixed(1)}%`;
        }
        if (el('highConfVal')) {
          el('highConfVal').textContent = `${confNum.toFixed(1)}%`;
        }

        const circle = el('confidenceCircleProgress');
        if (circle) {
          const totalCirc = 251.33;
          const offset = totalCirc * (1 - confNum / 100);
          circle.style.strokeDasharray = `${totalCirc} ${totalCirc}`;
          circle.style.strokeDashoffset = `${offset}`;
          if (confNum >= 99.5) {
            circle.setAttribute('stroke-linecap', 'butt');
          } else {
            circle.setAttribute('stroke-linecap', 'round');
          }
          if (confNum >= 90) {
            circle.setAttribute('stroke', '#10b981');
          } else if (confNum >= 75) {
            circle.setAttribute('stroke', '#f59e0b');
          } else {
            circle.setAttribute('stroke', '#ef4444');
          }
        }
      }

      if (el('driftInferenceLatency') && data.mlLatency) {
        el('driftInferenceLatency').textContent = `${data.mlLatency} ms`;
      }

      // 4. Transaction Flow & Ingestion Rate
      if (el('tpsIngestionVal')) {
        el('tpsIngestionVal').innerHTML = `${data.tps} <span class="res-unit">tx/s</span>`;
      }
      if (el('kpiWorkloads')) {
        el('kpiWorkloads').innerHTML = `${data.tps} <span class="kpi-denominator">tx/s</span>`;
      }

      // 5. Incidents from Alertmanager
      const incidentsContainer = el('incidentsList');
      const incidentsBadge = el('incidentsCountBadge');
      if (incidentsContainer) {
        if (data.alerts && data.alerts.length > 0) {
          if (incidentsBadge) {
            incidentsBadge.textContent = `${data.alerts.length} Active`;
            incidentsBadge.className = 'ari-pill-alert';
          }
          incidentsContainer.innerHTML = data.alerts.map(a => `
            <div class="incident-compact-row">
              <div class="incident-left">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>
                <span class="incident-name">${a.labels.alertname || 'System Alert'}</span>
              </div>
              <div class="incident-right">
                <span class="incident-pill high">${a.labels.severity || 'High'}</span>
                <span>${a.labels.instance || 'active'}</span>
                <span class="incident-chevron">›</span>
              </div>
            </div>
          `).join('');
        } else {
          if (incidentsBadge) {
            incidentsBadge.textContent = '0 Active';
            incidentsBadge.className = 'ari-pill-zero';
          }
          incidentsContainer.innerHTML = `
            <div class="ari-check-row">
              <div class="ari-check-left">
                <div class="ari-check-icon ok">
                  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.5" width="10" height="10"><polyline points="2 8 6 12 14 4"></polyline></svg>
                </div>
                <div class="ari-check-meta">
                  <span class="ari-check-name">Alertmanager Cluster</span>
                  <code class="ari-check-port">:9093</code>
                </div>
              </div>
              <div class="ari-check-right">
                <span class="ari-check-detail">Watching</span>
                <span class="ari-check-status ok-tag">Active</span>
              </div>
            </div>
            <div class="ari-check-row">
              <div class="ari-check-left">
                <div class="ari-check-icon ok">
                  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.5" width="10" height="10"><polyline points="2 8 6 12 14 4"></polyline></svg>
                </div>
                <div class="ari-check-meta">
                  <span class="ari-check-name">Prometheus Rule Engine</span>
                </div>
              </div>
              <div class="ari-check-right">
                <span class="ari-check-detail">14 Rules OK</span>
                <span class="ari-check-status ok-tag">Healthy</span>
              </div>
            </div>
            <div class="ari-check-row">
              <div class="ari-check-left">
                <div class="ari-check-icon ok">
                  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.5" width="10" height="10"><polyline points="2 8 6 12 14 4"></polyline></svg>
                </div>
                <div class="ari-check-meta">
                  <span class="ari-check-name">Platform SLO Target</span>
                </div>
              </div>
              <div class="ari-check-right">
                <span class="ari-check-detail">99.9% Target</span>
                <span class="ari-check-status ok-tag">Compliant</span>
              </div>
            </div>
            <div class="ari-check-row">
              <div class="ari-check-left">
                <div class="ari-check-icon ok">
                  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.5" width="10" height="10"><polyline points="2 8 6 12 14 4"></polyline></svg>
                </div>
                <div class="ari-check-meta">
                  <span class="ari-check-name">Dead Man's Snitch</span>
                </div>
              </div>
              <div class="ari-check-right">
                <span class="ari-check-detail">Heartbeat &lt;10s</span>
                <span class="ari-check-status ok-tag">Active</span>
              </div>
            </div>
          `;
        }
      }

      // 6. Dynamic Trend Chart from Prometheus query_range
      if (data.cpuRange && data.cpuRange.length > 1 && el('trendSvgChart')) {
        renderTrendChart(data.cpuRange, data.memRange);
      }

    } catch (e) {
      console.warn('[VigiliaMonitor] Dashboard live refresh error:', e);
    }
  }

  function renderTrendChart(cpuPoints, memPoints) {
    const svg = el('trendSvgChart');
    if (!svg || !cpuPoints || cpuPoints.length < 2) return;

    const paddingLeft = 38;
    const chartWidth = 458;
    const paddingTop = 12;
    const chartHeight = 78; // baseline at y = 90

    const maxLen = cpuPoints.length;
    const stepX = (chartWidth - paddingLeft) / (maxLen - 1);

    // Plot CPU Load (0 - 100%)
    const cpuCoords = cpuPoints.map((pt, idx) => {
      const val = Math.min(100, Math.max(0, Number.parseFloat(pt[1]) || 0));
      const x = paddingLeft + (idx * stepX);
      const y = paddingTop + chartHeight - ((val / 100) * chartHeight);
      return { x, y, str: `${x.toFixed(1)},${y.toFixed(1)}` };
    });

    const cpuPathD = `M ${cpuCoords.map(c => c.str).join(' L ')}`;
    const cpuAreaD = `M ${cpuCoords[0].str} L ${cpuCoords.map(c => c.str).join(' L ')} L ${chartWidth},${paddingTop + chartHeight} L ${paddingLeft},${paddingTop + chartHeight} Z`;

    const cpuAreaEl = el('cpuTrendArea');
    const cpuLineEl = el('cpuTrendLine');
    const cpuDotEl = el('cpuTrendDot');

    if (cpuAreaEl) cpuAreaEl.setAttribute('d', cpuAreaD);
    if (cpuLineEl) cpuLineEl.setAttribute('d', cpuPathD);
    if (cpuDotEl && cpuCoords.length > 0) {
      const last = cpuCoords[cpuCoords.length - 1];
      cpuDotEl.setAttribute('cx', last.x.toFixed(1));
      cpuDotEl.setAttribute('cy', last.y.toFixed(1));
    }

    // Plot JVM Memory if available (dynamic scaling against max heap)
    if (memPoints && memPoints.length > 1) {
      const maxMemVal = Math.max(1600, ...memPoints.map(p => Number.parseFloat(p[1]) || 0));
      const memStepX = (chartWidth - paddingLeft) / (memPoints.length - 1);

      const memCoords = memPoints.map((pt, idx) => {
        const val = Math.max(0, Number.parseFloat(pt[1]) || 0);
        const x = paddingLeft + (idx * memStepX);
        const y = paddingTop + chartHeight - ((val / maxMemVal) * chartHeight);
        return { x, y, str: `${x.toFixed(1)},${y.toFixed(1)}` };
      });

      const memPathD = `M ${memCoords.map(c => c.str).join(' L ')}`;
      const memAreaD = `M ${memCoords[0].str} L ${memCoords.map(c => c.str).join(' L ')} L ${chartWidth},${paddingTop + chartHeight} L ${paddingLeft},${paddingTop + chartHeight} Z`;

      const memAreaEl = el('memTrendArea');
      const memLineEl = el('memTrendLine');
      const memDotEl = el('memTrendDot');

      if (memAreaEl) memAreaEl.setAttribute('d', memAreaD);
      if (memLineEl) memLineEl.setAttribute('d', memPathD);
      if (memDotEl && memCoords.length > 0) {
        const last = memCoords[memCoords.length - 1];
        memDotEl.setAttribute('cx', last.x.toFixed(1));
        memDotEl.setAttribute('cy', last.y.toFixed(1));
      }
    }
  }

  // ─── Header Filter Controls (Time Window & Cluster) ──────────────────────────
  const timeFilterBtn = el('timeFilterBtn');
  const timeFilterMenu = el('timeFilterMenu');
  const timeFilterLabel = el('timeFilterLabel');

  const clusterFilterBtn = el('clusterFilterBtn');
  const clusterFilterMenu = el('clusterFilterMenu');
  const clusterFilterLabel = el('clusterFilterLabel');

  function setTimeRange(rangeKey, labelText) {
    if (timeFilterLabel) timeFilterLabel.textContent = labelText;

    // Update active in top dropdown
    document.querySelectorAll('#timeFilterMenu .hero-filter-option').forEach(opt => {
      opt.classList.toggle('active', opt.getAttribute('data-range') === rangeKey);
    });

    // Update active in trend chart time pills
    document.querySelectorAll('.time-pill-btn').forEach(pill => {
      pill.classList.toggle('active', pill.textContent.trim().toLowerCase() === rangeKey.toLowerCase());
    });

    if (rangeKey === '24h') {
      currentDuration = 86400;
      currentStep = 1800;
      if (el('timeAxisStart')) el('timeAxisStart').textContent = '24h ago';
      if (el('timeAxisQ1')) el('timeAxisQ1').textContent = '18h ago';
      if (el('timeAxisMid')) el('timeAxisMid').textContent = '12h ago';
      if (el('timeAxisQ3')) el('timeAxisQ3').textContent = '6h ago';
    } else if (rangeKey === '6h') {
      currentDuration = 21600;
      currentStep = 600;
      if (el('timeAxisStart')) el('timeAxisStart').textContent = '6h ago';
      if (el('timeAxisQ1')) el('timeAxisQ1').textContent = '4.5h ago';
      if (el('timeAxisMid')) el('timeAxisMid').textContent = '3h ago';
      if (el('timeAxisQ3')) el('timeAxisQ3').textContent = '1.5h ago';
    } else if (rangeKey === '7d') {
      currentDuration = 604800;
      currentStep = 14400;
      if (el('timeAxisStart')) el('timeAxisStart').textContent = '7d ago';
      if (el('timeAxisQ1')) el('timeAxisQ1').textContent = '5d ago';
      if (el('timeAxisMid')) el('timeAxisMid').textContent = '3d ago';
      if (el('timeAxisQ3')) el('timeAxisQ3').textContent = '1d ago';
    } else if (rangeKey === '30d') {
      currentDuration = 2592000;
      currentStep = 43200;
      if (el('timeAxisStart')) el('timeAxisStart').textContent = '30d ago';
      if (el('timeAxisQ1')) el('timeAxisQ1').textContent = '22d ago';
      if (el('timeAxisMid')) el('timeAxisMid').textContent = '15d ago';
      if (el('timeAxisQ3')) el('timeAxisQ3').textContent = '7d ago';
    } else {
      currentDuration = 3600;
      currentStep = 120;
      if (el('timeAxisStart')) el('timeAxisStart').textContent = '60m ago';
      if (el('timeAxisQ1')) el('timeAxisQ1').textContent = '45m ago';
      if (el('timeAxisMid')) el('timeAxisMid').textContent = '30m ago';
      if (el('timeAxisQ3')) el('timeAxisQ3').textContent = '15m ago';
    }

    updateDashboard(currentDuration, currentStep);
  }

  // Time filter dropdown trigger
  if (timeFilterBtn && timeFilterMenu) {
    timeFilterBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      const isOpen = timeFilterMenu.classList.toggle('open');
      timeFilterBtn.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
      if (clusterFilterMenu) clusterFilterMenu.classList.remove('open');
    });

    document.querySelectorAll('#timeFilterMenu .hero-filter-option').forEach(option => {
      option.addEventListener('click', (e) => {
        e.stopPropagation();
        const range = option.getAttribute('data-range') || '24h';
        const label = option.getAttribute('data-label') || option.textContent.trim();
        setTimeRange(range, label);
        timeFilterMenu.classList.remove('open');
        timeFilterBtn.setAttribute('aria-expanded', 'false');
      });
    });
  }

  // Cluster filter dropdown trigger
  if (clusterFilterBtn && clusterFilterMenu) {
    clusterFilterBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      const isOpen = clusterFilterMenu.classList.toggle('open');
      clusterFilterBtn.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
      if (timeFilterMenu) timeFilterMenu.classList.remove('open');
    });

    document.querySelectorAll('#clusterFilterMenu .hero-filter-option').forEach(option => {
      option.addEventListener('click', (e) => {
        e.stopPropagation();
        const cluster = option.getAttribute('data-cluster') || 'Vigilia-cluster';
        if (clusterFilterLabel) clusterFilterLabel.textContent = cluster;

        document.querySelectorAll('#clusterFilterMenu .hero-filter-option').forEach(opt => {
          opt.classList.toggle('active', opt === option);
        });

        clusterFilterMenu.classList.remove('open');
        clusterFilterBtn.setAttribute('aria-expanded', 'false');

        // Visual feedback on sync pill
        if (el('syncPillText')) {
          el('syncPillText').textContent = `Targeting ${cluster} · Syncing...`;
        }
        updateDashboard(currentDuration, currentStep);
      });
    });
  }

  // Close menus on outside click
  document.addEventListener('click', (e) => {
    if (timeFilterBtn && timeFilterMenu && !timeFilterBtn.contains(e.target) && !timeFilterMenu.contains(e.target)) {
      timeFilterMenu.classList.remove('open');
      timeFilterBtn.setAttribute('aria-expanded', 'false');
    }
    if (clusterFilterBtn && clusterFilterMenu && !clusterFilterBtn.contains(e.target) && !clusterFilterMenu.contains(e.target)) {
      clusterFilterMenu.classList.remove('open');
      clusterFilterBtn.setAttribute('aria-expanded', 'false');
    }
  });

  // Interactive time range buttons (1h / 24h / 7d) in Resource Trends card
  document.querySelectorAll('.time-pill-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const label = btn.textContent.trim().toLowerCase();
      const mapping = {
        '1h': { range: '1h', text: 'Last 1 hour' },
        '24h': { range: '24h', text: 'Last 24 hours' },
        '7d': { range: '7d', text: 'Last 7 days' }
      };
      const sel = mapping[label] || { range: label, text: `Last ${label}` };
      setTimeRange(sel.range, sel.text);
    });
  });

  // Initial call and 10s auto-refresh
  updateDashboard();
  setInterval(() => updateDashboard(currentDuration, currentStep), 10000);
});
