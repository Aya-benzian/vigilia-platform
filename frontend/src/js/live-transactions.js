/**
 * Live Transactions — Interactive Controller
 * Vigilia Platform · live-transactions.js
 * Consumes real backend data via window.VigiliaApiClient & window.VigiliaMonitorAPI
 */

// ─── State ────────────────────────────────────────────────────────────────────

let autoRefresh    = true;
let refreshTimer   = null;
let activeChannel  = 'all';
let currentPage    = 0;        // 0-based page index (API page param)
let allRows        = [];       // cached DOM rows from last fetch (for filter)

// ─── Helpers ──────────────────────────────────────────────────────────────────

function $(id) {
  return document.getElementById(id);
}

function formatTime(date = new Date()) {
  const hh = String(date.getHours()).padStart(2, '0');
  const mm = String(date.getMinutes()).padStart(2, '0');
  const ss = String(date.getSeconds()).padStart(2, '0');
  return `${hh}:${mm}:${ss}`;
}

function fmtNumber(n) {
  return Number(n).toLocaleString('en-US');
}

function fmtPct(n) {
  return parseFloat(n).toFixed(1) + '%';
}

function fmtMoney(val, currency) {
  const sym = currency === 'EUR' ? '€' : '$';
  return sym + parseFloat(val).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

// ─── Sync Ticker ─────────────────────────────────────────────────────────────

function startSyncTicker() {
  const el = $('ltxSyncTime');
  if (!el) return;
  function tick() {
    el.textContent = `Updated: ${formatTime()}`;
  }
  tick();
  setInterval(tick, 1000);
}

// ─── Visual Badges & Helpers ───────────────────────────────────────────────

function getChannelIcon(type) {
  if (type === 'TRANSFER') {
    return `<span class="ltx-ch-ico atm"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12"><polyline points="17 1 21 5 17 9"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><polyline points="7 23 3 19 7 15"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/></svg></span>`;
  }
  // PURCHASE → web/card icon
  return `<span class="ltx-ch-ico web"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg></span>`;
}

function getScoreBadge(score) {
  if (score == null) {
    return `<span class="ltx-score-badge low"><span class="ltx-score-dot"></span>—</span>`;
  }
  const val = parseFloat(score);
  let level = 'low';
  if (val >= 0.80) level = 'high';
  else if (val >= 0.60) level = 'mid';
  return `<span class="ltx-score-badge ${level}"><span class="ltx-score-dot"></span>${val.toFixed(2)}</span>`;
}

function getDecisionPill(decision) {
  if (decision === 'Approve') {
    return `<span class="ltx-dec-pill approve"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" width="10" height="10"><polyline points="20 6 9 17 4 12"/></svg>Approve</span>`;
  }
  if (decision === 'Review') {
    return `<span class="ltx-dec-pill review"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" width="10" height="10"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>Review</span>`;
  }
  return `<span class="ltx-dec-pill block"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" width="10" height="10"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>Block</span>`;
}

// ─── Row Builder (from real API data) ────────────────────────────────────────

function buildRowFromApi(tx) {
  const txid           = tx.id.substring(0, 8).toUpperCase();
  const time           = new Date(tx.timestamp).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  const channelName    = tx.type === 'PURCHASE' ? 'Card Payment' : 'Bank Transfer';
  const customerDisplay = 'USR-' + tx.id.substring(0, 4);
  const amountStr      = fmtMoney(tx.amount, tx.currency);
  const score          = tx.riskScore != null ? tx.riskScore : null;
  const decisionLabel  = tx.status === 'APPROVED' ? 'Approve' : tx.status === 'REJECTED' ? 'Block' : 'Review';

  const chIcon    = getChannelIcon(tx.type);
  const scoreBadge = getScoreBadge(score);
  const decPill   = getDecisionPill(decisionLabel);

  const tr = document.createElement('tr');
  tr.className = 'ltx-row';
  tr.dataset.channel  = tx.type;
  tr.dataset.txid     = txid.toLowerCase();
  tr.dataset.customer = customerDisplay.toLowerCase();
  tr.dataset.amount   = amountStr;

  tr.innerHTML = `
    <td class="col-txid"><a href="#${txid}" class="ltx-txid font-mono">#${txid}</a></td>
    <td class="col-time"><span class="ltx-time font-mono">${time}</span></td>
    <td class="col-channel">
      <div class="ltx-ch-cell">
        ${chIcon}
        <span class="ltx-ch-text">${channelName}</span>
      </div>
    </td>
    <td class="col-amount text-right"><span class="ltx-amount font-mono">${amountStr}</span></td>
    <td class="col-customer">
      <div class="ltx-customer-cell">
        <span class="ltx-cust-avatar">${customerDisplay.substring(4, 8).toUpperCase()}</span>
        <span class="ltx-customer">${customerDisplay}</span>
      </div>
    </td>
    <td class="col-score text-center">${scoreBadge}</td>
    <td class="col-decision text-center">${decPill}</td>
  `;

  return tr;
}

// ─── Load: Transaction Feed ───────────────────────────────────────────────────

async function loadTransactionFeed(page = 0) {
  try {
    const client = window.VigiliaApiClient;
    if (!client) throw new Error('VigiliaApiClient not available');

    const data = await client.get(`/api/v1/transactions?page=${page}&size=20`);
    // Expect { content: [...], meta: { totalElements, ... } } or ApiResponse wrapper
    const payload  = data.data || data;
    const content  = payload.content || [];
    const meta     = payload.meta || payload.metadata || {};
    const total    = meta.totalElements ?? meta.total ?? null;

    const tbody = $('ltxTbody');
    if (!tbody) return;

    // Clear existing rows and rebuild
    tbody.innerHTML = '';
    allRows = [];

    if (content.length === 0) {
      tbody.innerHTML = `
        <tr class="ltx-empty-state-row">
          <td colspan="7" style="text-align: center; padding: 48px 16px; color: #64748b;">
            <div style="display: flex; flex-direction: column; align-items: center; gap: 8px;">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" width="32" height="32" style="opacity: 0.5;">
                <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline>
              </svg>
              <strong style="font-size: 14px; font-weight: 600; color: #1e293b;">No transactions recorded yet</strong>
              <span style="font-size: 12px; color: #94a3b8;">Real-time ingestion stream is active &middot; Incoming transactions will appear here</span>
            </div>
          </td>
        </tr>
      `;
    } else {
      content.forEach(tx => {
        const tr = buildRowFromApi(tx);
        tbody.appendChild(tr);
        allRows.push(tr);
      });
    }

    // Update footer count
    const showingEl = $('ltxShowingCount');
    if (showingEl) showingEl.textContent = content.length;
    if (total !== null) {
      const footerEl = $('ltxFooterCount');
      if (footerEl) footerEl.textContent = fmtNumber(total);
    }

    applyFilters();
  } catch (err) {
    console.warn('[live-transactions] loadTransactionFeed failed, keeping static data:', err);
  }
}

// ─── Load: Risk Stats ─────────────────────────────────────────────────────────

async function loadRiskStats() {
  try {
    const client = window.VigiliaApiClient;
    if (!client) throw new Error('VigiliaApiClient not available');

    const res  = await client.get('/api/v1/risk/stats');
    const s    = res.data || res;

    const totalEvaluated     = s.totalEvaluated     ?? 0;
    const approvedCount      = s.approvedCount      ?? 0;
    const pendingReviewCount = s.pendingReviewCount  ?? 0;
    const rejectedCount      = s.rejectedCount       ?? 0;
    const approvedPct        = s.approvedPct         ?? 0;
    const pendingReviewPct   = s.pendingReviewPct    ?? 0;
    const rejectedPct        = s.rejectedPct         ?? 0;

    // ── Donut center ──────────────────────────────────────────────────────────
    const donutNum = document.querySelector('.ltx-donut-num');
    if (donutNum) donutNum.textContent = fmtNumber(totalEvaluated);

    // ── Donut segments ────────────────────────────────────────────────────────
    // circumference = 2π × r50 ≈ 314.16
    const C = 314.16;
    const donutCircles = document.querySelectorAll('.ltx-donut-svg circle[stroke-dasharray]');
    // Order: approved (green), pending (amber), rejected (red)
    const pcts = totalEvaluated > 0
      ? [approvedPct / 100, pendingReviewPct / 100, rejectedPct / 100]
      : [0, 0, 0];
    let offset = 0;
    donutCircles.forEach((circle, i) => {
      const fill = C * pcts[i];
      circle.setAttribute('stroke-dasharray', `${fill.toFixed(2)} ${C}`);
      circle.setAttribute('stroke-dashoffset', `-${offset.toFixed(2)}`);
      offset += fill;
    });

    // ── Donut legend: .ltx-legend-pct and .ltx-legend-count ─────────────────
    const legendPcts   = document.querySelectorAll('.ltx-legend-pct');
    const legendCounts = document.querySelectorAll('.ltx-legend-count');
    const legendData = [
      { pct: approvedPct,      count: approvedCount,      label: 'tx' },
      { pct: pendingReviewPct, count: pendingReviewCount,  label: 'tx' },
      { pct: rejectedPct,      count: rejectedCount,       label: 'tx' },
    ];
    legendData.forEach((d, i) => {
      if (legendPcts[i])   legendPcts[i].textContent   = fmtPct(d.pct);
      if (legendCounts[i]) legendCounts[i].textContent = `${fmtNumber(d.count)} tx`;
    });

    // ── Avg Latency (first .ai-ins-val) ──────────────────────────────────────
    const aiInsVals = document.querySelectorAll('.ai-ins-val');
    if (aiInsVals[0] && s.avgInferenceMs != null) {
      aiInsVals[0].textContent = `${Math.round(s.avgInferenceMs)}ms`;
    }

    // ── Risk Score Distribution bars ──────────────────────────────────────────
    // Buckets in DOM order: 0-25 (Safe), 26-50 (Low Risk), 51-75 (Review), 76-100 (Blocked)
    const distBadges = document.querySelectorAll('.ltx-dist-badge');
    const distCounts = document.querySelectorAll('.ltx-dist-count');
    const distFills  = document.querySelectorAll('.ltx-dist-bar-fill');
    const buckets = [
      { pct: s.bucket0Pct ?? 0, count: s.bucket0Count ?? 0 },
      { pct: s.bucket1Pct ?? 0, count: s.bucket1Count ?? 0 },
      { pct: s.bucket2Pct ?? 0, count: s.bucket2Count ?? 0 },
      { pct: s.bucket3Pct ?? 0, count: s.bucket3Count ?? 0 },
    ];
    buckets.forEach((b, i) => {
      if (distBadges[i]) distBadges[i].textContent = fmtPct(b.pct);
      if (distCounts[i]) distCounts[i].textContent = `${fmtNumber(b.count)} tx`;
      if (distFills[i])  distFills[i].style.height  = `${Math.min(100, b.pct)}%`;
    });

    // ── Model version tag ─────────────────────────────────────────────────────
    const distTag = document.querySelector('.ltx-dist-tag');
    if (distTag && s.modelVersion) {
      distTag.innerHTML = `<span class="chip-dot emerald"></span>Model ${s.modelVersion} Live`;
    }

    // ── Health Summary Strip (Confidence & Evaluated) ─────────────────────────
    const safeEl = $('confidenceSafeVal');
    const flaggedEl = $('confidenceFlaggedVal');
    const evalMeta = $('ltxTotalEvaluated') || document.querySelector('.dist-sum-meta');
    if (evalMeta) {
      evalMeta.textContent = `${fmtNumber(totalEvaluated)} Evaluated`;
    }
    if (totalEvaluated > 0) {
      const safePct = approvedPct;
      const flaggedPct = pendingReviewPct + rejectedPct;
      if (safeEl) safeEl.textContent = `${safePct.toFixed(1)}% Safe`;
      if (flaggedEl) flaggedEl.textContent = `${flaggedPct.toFixed(1)}% Flagged`;
    } else {
      if (safeEl) safeEl.textContent = '0.0% Safe';
      if (flaggedEl) flaggedEl.textContent = '0.0% Flagged';
    }

    // ── Policy Enforcement Log ────────────────────────────────────────────────
    const polCounts = document.querySelectorAll('.ltx-pol-count');
    const polBadges = document.querySelectorAll('.ltx-pol-badge');
    const totalRules = s.totalRulesEvaluated ?? totalEvaluated;
    const polData = [
      { count: s.autoApproveCount   ?? 0 },
      { count: s.manualReviewCount  ?? 0 },
      { count: s.hardBlockCount     ?? 0 },
      { count: s.velocityCount      ?? 0 },
      { count: s.geoAnomalyCount    ?? 0 },
    ];
    polData.forEach((p, i) => {
      if (polCounts[i]) polCounts[i].textContent = fmtNumber(p.count);
      if (polBadges[i]) {
        const pct = totalRules > 0 ? (p.count / totalRules * 100) : 0;
        polBadges[i].textContent = fmtPct(pct);
      }
    });

    // Policy summary banner
    const polSumRuleCount = document.querySelector('.pol-sum-rule-count');
    if (polSumRuleCount) polSumRuleCount.textContent = `${fmtNumber(totalRules)} Rules Evaluated`;

  } catch (err) {
    console.warn('[live-transactions] loadRiskStats failed, keeping static data:', err);
  }
}

// ─── Load: Transaction Stats (Channel Performance) ────────────────────────────

async function loadTxStats() {
  try {
    const client = window.VigiliaApiClient;
    if (!client) throw new Error('VigiliaApiClient not available');

    const res = await client.get('/api/v1/transactions/stats');
    const s   = res.data || res;

    // DOM order: [0] = Payments bar, [1] = Transfers bar
    const barFills = document.querySelectorAll('.ltx-ch-bar-fill');
    const chRates  = document.querySelectorAll('.ltx-ch-rate');
    const chTrends = document.querySelectorAll('.ltx-ch-trend');

    // Payments (PURCHASE) — index 0
    const purchasePct    = s.purchasePct    ?? 0;
    const purchaseVol    = s.purchaseVolume ?? 0;
    const purchasePass   = s.purchasePassRate ?? 0;
    const purchaseAvg    = s.purchaseAvgAmount ?? 0;
    const purchaseCt     = s.purchaseCount  ?? 0;

    if (barFills[0]) barFills[0].style.width = `${purchasePct}%`;
    if (chRates[0])  chRates[0].textContent  = `${fmtPct(purchasePct)} Share`;
    // telemetry row for Payments
    _updateChTelemetry(0, purchaseVol, purchasePass, purchaseAvg);

    // Transfers — index 1
    const transferPct    = s.transferPct    ?? 0;
    const transferVol    = s.transferVolume ?? 0;
    const transferPass   = s.transferPassRate ?? 0;
    const transferAvg    = s.transferAvgAmount ?? 0;
    const transferCt     = s.transferCount  ?? 0;

    if (barFills[1]) barFills[1].style.width = `${transferPct}%`;
    if (chRates[1])  chRates[1].textContent  = `${fmtPct(transferPct)} Share`;
    _updateChTelemetry(1, transferVol, transferPass, transferAvg);

    // Channel badges (e.g. "66% Share")
    const chBadges = document.querySelectorAll('.ltx-ch-badge');
    if (chBadges[0]) chBadges[0].textContent = `${fmtPct(purchasePct)} Share`;
    if (chBadges[1]) chBadges[1].textContent = `${fmtPct(transferPct)} Share`;

    // Total tx/s pill — use totalTransactions as rough proxy if available
    const totalTx = s.totalTransactions ?? (purchaseCt + transferCt);
    const chTotalPill = document.querySelector('.ltx-ch-total-pill');
    if (chTotalPill) {
      // Keep the live dot, update text
      const dot = chTotalPill.querySelector('.ch-live-dot');
      chTotalPill.textContent = `${fmtNumber(totalTx)} Total Tx`;
      if (dot) chTotalPill.prepend(dot);
    }

  } catch (err) {
    console.warn('[live-transactions] loadTxStats failed, keeping static data:', err);
  }
}

/**
 * Update the Vol / Pass / Avg telemetry row for a channel.
 * @param {number} idx   - 0 for Payments, 1 for Transfers
 * @param {number} vol   - volume in USD/EUR (raw number)
 * @param {number} pass  - pass rate percentage (0-100)
 * @param {number} avg   - average transaction amount
 */
function _updateChTelemetry(idx, vol, pass, avg) {
  const modules = document.querySelectorAll('.ltx-ch-module');
  const mod = modules[idx];
  if (!mod) return;

  const telems = mod.querySelectorAll('.ch-tel-item strong');
  // Expected order in HTML: Vol, Pass, Avg
  if (telems[0]) {
    const volK = vol >= 1000 ? `$${(vol / 1000).toFixed(1)}K` : `$${parseFloat(vol).toFixed(0)}`;
    telems[0].textContent = volK;
  }
  if (telems[1]) {
    telems[1].textContent = `${parseFloat(pass).toFixed(1)}%`;
  }
  if (telems[2]) {
    telems[2].textContent = avg >= 1000
      ? `$${(avg / 1000).toFixed(1)}K`
      : `$${parseFloat(avg).toFixed(2)}`;
  }
}

// ─── Load: Throughput Timeline ────────────────────────────────────────────────

async function loadThroughputTimeline(durationSeconds = 3600, step = 60) {
  const poly = $('ltxTimelineArea');
  const polyline = $('ltxTimelineLine');
  const pointsGroup = $('ltxTimelinePoints');
  const tagEl = document.querySelector('.ltx-timeline-tag .font-mono');
  const kpiSpeed = document.querySelector('.ltx-tkpi-val.blue');
  const kpiPeak = document.querySelectorAll('.ltx-tkpi-val')[1];
  const kpiAvg = document.querySelectorAll('.ltx-tkpi-val')[2];
  const xLabels = document.querySelectorAll('.ltx-chart-xaxis span');

  // Always update X-axis timestamps based on current time window
  const now = Date.now();
  const startTime = now - durationSeconds * 1000;
  if (xLabels && xLabels.length) {
    const interval = (durationSeconds * 1000) / (xLabels.length - 1 || 1);
    xLabels.forEach((el, i) => {
      const d = new Date(startTime + i * interval);
      el.textContent = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    });
  }

  try {
    const monitorApi = window.VigiliaMonitorAPI;
    if (!monitorApi || typeof monitorApi.queryPrometheusRange !== 'function') {
      throw new Error('VigiliaMonitorAPI.queryPrometheusRange not available');
    }

    const result = await monitorApi.queryPrometheusRange(
      'sum(rate(http_server_requests_seconds_count{job="transaction-service"}[1m]))',
      durationSeconds,
      step
    );

    // result is expected to be an array of [timestamp, value] pairs
    const points = Array.isArray(result) ? result : (result?.data || result?.values || []);

    if (!points || points.length === 0) {
      if (poly) poly.setAttribute('points', '0,112 420,112');
      if (polyline) polyline.setAttribute('points', '0,112 420,112');
      if (pointsGroup) pointsGroup.innerHTML = '';
      if (tagEl) tagEl.textContent = '0.00 tx/s';
      if (kpiSpeed) kpiSpeed.innerHTML = '0.00 <small>tx/s</small>';
      if (kpiPeak) kpiPeak.innerHTML = '&mdash; <small>tx/s</small>';
      if (kpiAvg) kpiAvg.innerHTML = '&mdash; <small>tx/s</small>';
      return;
    }

    const values = points.map(p => parseFloat(Array.isArray(p) ? p[1] : p.value) || 0);
    const maxVal = Math.max(...values, 0);

    // SVG viewBox: 0 0 420 120, baseline at 112
    const SVG_W = 420;
    const SVG_H = 112;
    const PAD_TOP = 15;
    const drawH = SVG_H - PAD_TOP;

    const n = values.length;
    const coords = values.map((v, i) => {
      const x = n > 1 ? (i / (n - 1)) * SVG_W : SVG_W / 2;
      const y = maxVal > 0 ? (PAD_TOP + drawH - (v / maxVal) * drawH) : SVG_H;
      return { x: x.toFixed(1), y: y.toFixed(1), v };
    });

    const linePoints = coords.map(c => `${c.x},${c.y}`).join(' ');
    const polygonPoints = `0,${SVG_H} ` + linePoints + ` ${SVG_W},${SVG_H}`;

    if (poly) poly.setAttribute('points', polygonPoints);
    if (polyline) polyline.setAttribute('points', linePoints);

    if (pointsGroup) {
      pointsGroup.innerHTML = '';
      const sampleStep = Math.max(1, Math.floor(n / 10));
      coords.forEach((c, i) => {
        if (i % sampleStep !== 0 && i !== n - 1) return;
        const circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
        circle.setAttribute('cx', c.x);
        circle.setAttribute('cy', c.y);
        circle.setAttribute('r', i === n - 1 ? '4' : '2.5');
        circle.setAttribute('fill', '#2563eb');
        if (i === n - 1) {
          circle.setAttribute('stroke', '#ffffff');
          circle.setAttribute('stroke-width', '2');
        }
        pointsGroup.appendChild(circle);
      });
    }

    // Current value tag
    const last = values[values.length - 1] || 0;
    if (tagEl) tagEl.textContent = `${last.toFixed(2)} tx/s`;
    if (kpiSpeed) kpiSpeed.innerHTML = `${last.toFixed(2)} <small>tx/s</small>`;
    if (kpiPeak) kpiPeak.innerHTML = `${maxVal.toFixed(2)} <small>tx/s</small>`;
    const avg = values.reduce((a, b) => a + b, 0) / (values.length || 1);
    if (kpiAvg) kpiAvg.innerHTML = `${avg.toFixed(2)} <small>tx/s</small>`;

  } catch (err) {
    console.warn('[live-transactions] loadThroughputTimeline failed:', err);
    if (poly) poly.setAttribute('points', '0,112 420,112');
    if (polyline) polyline.setAttribute('points', '0,112 420,112');
    if (pointsGroup) pointsGroup.innerHTML = '';
  }
}

// ─── Filtering ───────────────────────────────────────────────────────────────

function applyFilters() {
  const query = ($('ltxSearch')?.value || '').toLowerCase().trim();
  const tbody = $('ltxTbody');
  if (!tbody) return;

  tbody.querySelectorAll('tr.ltx-row').forEach(tr => {
    const channelAttr = (tr.dataset.channel || '').toLowerCase();
    const matchChannel = activeChannel === 'all' || channelAttr === activeChannel.toLowerCase();
    const matchSearch  = !query ||
      (tr.dataset.txid      || '').toLowerCase().includes(query) ||
      (tr.dataset.customer  || '').toLowerCase().includes(query) ||
      (tr.innerText         || '').toLowerCase().includes(query);

    tr.style.display = (matchChannel && matchSearch) ? '' : 'none';
  });
}

// ─── Channel Filter Buttons ───────────────────────────────────────────────────

function initChannelFilter() {
  const container = $('ltxChannelFilter');
  if (!container) return;

  container.querySelectorAll('.ltx-filter-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      container.querySelectorAll('.ltx-filter-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      activeChannel = btn.dataset.channel || 'all';
      applyFilters();
    });
  });
}

// ─── Search ───────────────────────────────────────────────────────────────────

function initSearch() {
  const input = $('ltxSearch');
  if (!input) return;
  input.addEventListener('input', applyFilters);
}

// ─── Auto-refresh Toggle ──────────────────────────────────────────────────────

function startAutoRefresh() {
  if (refreshTimer) clearInterval(refreshTimer);
  refreshTimer = setInterval(() => {
    loadTransactionFeed(currentPage);
  }, 10000);
}

function stopAutoRefresh() {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = null;
  }
}

function initAutoRefreshToggle() {
  const toggle = $('ltxAutoRefresh');
  if (!toggle) return;

  // Start ON by default to match autoRefresh = true
  if (autoRefresh) startAutoRefresh();

  toggle.addEventListener('change', () => {
    autoRefresh = toggle.checked;
    if (autoRefresh) {
      startAutoRefresh();
    } else {
      stopAutoRefresh();
    }
  });
}

// ─── Export CSV ───────────────────────────────────────────────────────────────

function initExport() {
  const btn = $('ltxExportBtn');
  if (!btn) return;
  btn.addEventListener('click', () => {
    const rows  = [...document.querySelectorAll('#ltxTbody tr.ltx-row')];
    const lines = ['TX_ID,TIME,CHANNEL,AMOUNT,CUSTOMER,AI_SCORE,DECISION'];
    rows.forEach(tr => {
      const cells = tr.querySelectorAll('td');
      const txid  = cells[0]?.innerText.trim() || '';
      const time  = cells[1]?.innerText.trim() || '';
      const ch    = cells[2]?.innerText.trim() || '';
      const amt   = cells[3]?.innerText.trim() || '';
      const cust  = cells[4]?.innerText.trim() || '';
      const score = cells[5]?.innerText.trim() || '';
      const dec   = cells[6]?.innerText.trim() || '';
      lines.push(`"${txid}","${time}","${ch}","${amt}","${cust}","${score}","${dec}"`);
    });

    const blob = new Blob([lines.join('\n')], { type: 'text/csv' });
    const url  = URL.createObjectURL(blob);
    const a    = document.createElement('a');
    a.href     = url;
    a.download = `vigilia-transactions-${Date.now()}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  });
}

// ─── Pagination ───────────────────────────────────────────────────────────────

function initPagination() {
  document.querySelectorAll('.ltx-p-btn').forEach(btn => {
    btn.addEventListener('click', function () {
      // prev/next are decorative for now
      if (this.classList.contains('prev') || this.classList.contains('next')) return;

      document.querySelectorAll('.ltx-p-btn').forEach(b => b.classList.remove('active'));
      this.classList.add('active');

      // Map button label to 0-based page index
      const label = parseInt(this.textContent.trim(), 10);
      if (!isNaN(label)) {
        currentPage = label - 1;
        loadTransactionFeed(currentPage);
      }
    });
  });
}

// ─── Timeline Range Buttons ───────────────────────────────────────────────────

function initTimelineRange() {
  document.querySelectorAll('.ltx-range-btn').forEach(btn => {
    btn.addEventListener('click', function () {
      document.querySelectorAll('.ltx-range-btn').forEach(b => b.classList.remove('active'));
      this.classList.add('active');
      const text = this.textContent.trim();
      let duration = 3600;
      let step = 60;
      if (text === '24h') { duration = 86400; step = 1800; }
      else if (text === '7d') { duration = 604800; step = 14400; }
      loadThroughputTimeline(duration, step);
    });
  });
}

// ─── Boot ────────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
  // UI interactions
  startSyncTicker();
  initChannelFilter();
  initSearch();
  initAutoRefreshToggle();
  initExport();
  initPagination();
  initTimelineRange();

  // Load real data (in parallel; each has its own silent fallback)
  loadTransactionFeed(0);
  loadRiskStats();
  loadTxStats();
  loadThroughputTimeline();
});
