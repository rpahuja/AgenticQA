'use strict';
/* AgenticQA dashboard — vanilla JS, no external libraries (works offline behind VPN). */

const $ = (id) => document.getElementById(id);
const REFRESH_MS = 15000;

const fmt = (n) => {
  n = Number(n || 0);
  if (n >= 1e9) return (n / 1e9).toFixed(2) + 'B';
  if (n >= 1e6) return (n / 1e6).toFixed(2) + 'M';
  if (n >= 1e3) return (n / 1e3).toFixed(1) + 'k';
  return String(Math.round(n));
};

const pad = (n) => String(n).padStart(2, '0');
function currentMonth() {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}`;
}

async function loadJson(path) {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`${path} → HTTP ${res.status}`);
  return res.json();
}

async function refresh() {
  const m = $('month').value || currentMonth();
  try {
    const [summary, daily, models, convs, health, reconcile] = await Promise.all([
      loadJson('/api/usage/summary?month=' + m),
      loadJson('/api/usage/daily?month=' + m),
      loadJson('/api/usage/models?month=' + m),
      loadJson('/api/conversations?limit=50'),
      loadJson('/api/health'),
      loadJson('/api/reconcile?month=' + m)
    ]);
    renderSummary(summary);
    renderChart(daily.days || []);
    renderModels(models.models || []);
    renderTurns(summary, models.models || []);
    renderConversations(convs.conversations || []);
    renderHealth(health);
    renderReconcile(reconcile);
    $('refreshedAt').textContent = new Date().toLocaleTimeString();
  } catch (err) {
    setHealthBad(false, 'collector unreachable: ' + err.message);
    $('footMode').textContent = 'unreachable';
  }
}

function setHealthBad(bad, title) {
  $('health').className = 'dot ' + (bad ? 'bad' : 'ok');
  $('health').title = title || '';
}

function renderHealth(health) {
  const mode = (health && health.leader && health.leader.mode) || 'unknown';
  $('footMode').textContent = mode;
  const ok = !!(health && health.ok && mode === 'leader');
  setHealthBad(!ok, `collector ${mode}` + (health && health.otelFileExists ? '' : ' · waiting for first span'));
}

function subLine(c) {
  return `in ${fmt(c.inputTokens)} · out ${fmt(c.outputTokens)}` +
    (c.cacheReadTokens ? ` · cache ${fmt(c.cacheReadTokens)}` : '') +
    (c.reasoningTokens ? ` · reasoning ${fmt(c.reasoningTokens)}` : '');
}

function renderSummary(s) {
  $('cardTotal').textContent = fmt(s.total.totalTokens);
  $('cardTotalSub').textContent = subLine(s.total);
  $('cardWith').textContent = fmt(s.withAgenticqa.totalTokens);
  $('cardWithSub').textContent = subLine(s.withAgenticqa);
  $('cardWithout').textContent = fmt(s.withoutAgenticqa.totalTokens);
  $('cardWithoutSub').textContent = subLine(s.withoutAgenticqa);
  const calls = s.total.llmCalls;
  $('cardCalls').textContent = fmt(calls);
  const withShare = calls ? Math.round((s.withAgenticqa.llmCalls / calls) * 100) : 0;
  $('cardCallsSub').textContent = `${s.withAgenticqa.llmCalls} via @agenticQA (${withShare}%)`;
}

function renderChart(days) {
  if (!days.length) {
    $('chart').innerHTML = '<div class="empty">No data for this month yet.</div>';
    return;
  }
  const W = 940, H = 260, padL = 48, padB = 26, padT = 8, padR = 8;
  const innerW = W - padL - padR;
  const innerH = H - padT - padB;
  const maxV = Math.max(1, ...days.map((d) => Math.max(d.with.totalTokens || 0, d.without.totalTokens || 0)));
  const slot = innerW / days.length;
  const bw = Math.max(2, Math.min(16, slot * 0.34));

  let bars = '';
  let labels = '';
  days.forEach((d, i) => {
    const x = padL + i * slot + (slot - bw * 2 - 1) / 2;
    const hWith = ((d.with.totalTokens || 0) / maxV) * innerH;
    const hWithout = ((d.without.totalTokens || 0) / maxV) * innerH;
    const yWith = padT + innerH - hWith;
    const yWithout = padT + innerH - hWithout;
    bars +=
      `<rect x="${x.toFixed(1)}" y="${yWith.toFixed(1)}" width="${bw}" height="${Math.max(0, hWith).toFixed(1)}" class="bar-with" rx="1">` +
      `<title>${d.date} · With AgenticQA: ${fmt(d.with.totalTokens)} tokens (${d.with.llmCalls} calls)</title></rect>`;
    bars +=
      `<rect x="${(x + bw + 1).toFixed(1)}" y="${yWithout.toFixed(1)}" width="${bw}" height="${Math.max(0, hWithout).toFixed(1)}" class="bar-without" rx="1">` +
      `<title>${d.date} · Without AgenticQA: ${fmt(d.without.totalTokens)} tokens (${d.without.llmCalls} calls)</title></rect>`;
    if (i % 7 === 0 || i === days.length - 1) {
      labels += `<text x="${(x + bw).toFixed(1)}" y="${H - 8}" class="axis" text-anchor="middle">${d.date.slice(8)}</text>`;
    }
  });

  const grid = [0.25, 0.5, 0.75, 1]
    .map((f) => {
      const y = padT + innerH - f * innerH;
      return (
        `<line x1="${padL}" y1="${y}" x2="${W - padR}" y2="${y}" class="gridline"/>` +
        `<text x="${padL - 6}" y="${y + 3}" class="axis" text-anchor="end">${fmt(maxV * f)}</text>`
      );
    })
    .join('');

  $('chart').innerHTML =
    `<svg class="svg" viewBox="0 0 ${W} ${H}">` +
    `<line x1="${padL}" y1="${padT}" x2="${padL}" y2="${padT + innerH}" class="axisline"/>` +
    grid + bars + labels +
    '</svg>';
}

function renderModels(models) {
  if (!models.length) {
    $('modelRows').innerHTML = '<tr><td colspan="4" class="empty">No data yet for this month.</td></tr>';
    return;
  }
  $('modelRows').innerHTML = models
    .map(
      (m) => `<tr>
        <td>${escapeHtml(m.model)}</td>
        <td class="num">${fmt(m.captured.totalTokens)}</td>
        <td class="num with-cell">${fmt(m.with.totalTokens)}</td>
        <td class="num">${m.calls}</td>
      </tr>`
    )
    .join('');
}

function renderTurns(s, models) {
  const t = s.turns || {};
  $('turnSummary').innerHTML =
    `This month: <b>${fmt(t.llmCalls)}</b> agent turns · in <b>${fmt(t.inputTokens)}</b> · out <b>${fmt(t.outputTokens)}</b>` +
    ` · est. cache-hit input <b>${fmt(t.cacheReadTokens)}</b> (context re-sent each turn). ` +
    `Input+output totals match the provider's billed tokens; only the cache-hit/cache-miss split is estimated ` +
    `(DeepSeek caches best-effort prefix blocks, so the exact split is not reproducible from Copilot telemetry).`;
  const rows = (models || []).filter((m) => m.turns && m.turns.llmCalls > 0);
  if (!rows.length) {
    $('turnRows').innerHTML = '<tr><td colspan="5" class="empty">No agent-turn data yet for this month.</td></tr>';
    return;
  }
  $('turnRows').innerHTML = rows
    .map(
      (m) => `<tr>
        <td>${escapeHtml(m.model)}</td>
        <td class="num">${m.turns.llmCalls}</td>
        <td class="num">${fmt(m.turns.inputTokens)}</td>
        <td class="num">${fmt(m.turns.cacheReadTokens)}</td>
        <td class="num">${fmt(m.turns.outputTokens)}</td>
      </tr>`
    )
    .join('');
}

function renderConversations(convs) {
  if (!convs.length) {
    $('convRows').innerHTML = '<tr><td colspan="5" class="empty">No conversations yet.</td></tr>';
    return;
  }
  $('convRows').innerHTML = convs
    .map((c) => {
      const when = c.lastSeenMs ? new Date(c.lastSeenMs).toLocaleString() : '—';
      const badge = c.withAgenticqa
        ? '<span class="badge badge-with">WITH</span>'
        : '<span class="badge badge-without">WITHOUT</span>';
      const tokenCell = c.withAgenticqa
        ? `<td class="num" title="Session captured ${fmt(c.totals.totalTokens)} tokens across ${c.totals.llmCalls} Copilot calls. The @agenticQA runs inside this conversation reported ${fmt(c.reported.totalTokens)} tokens (${c.reported.llmCalls} calls).">${fmt(c.reported.totalTokens)}<span class="muted"> / ${fmt(c.totals.totalTokens)} session</span></td>`
        : `<td class="num">${fmt(c.totals.totalTokens)}</td>`;
      return `<tr>
        <td class="muted">${when}</td>
        <td>${escapeHtml(c.participant || 'copilot')}</td>
        <td class="muted">${escapeHtml((c.models || []).join(', ') || '—')}</td>
        ${tokenCell}
        <td>${badge}</td>
      </tr>`;
    })
    .join('');
}

function renderReconcile(r) {
  if (!r || !r.reported || !r.reported.requests) {
    $('reconcilePanel').hidden = true;
    return;
  }
  $('reconcilePanel').hidden = false;
  const withCap = r.withAgenticqa || {};
  const captured = r.captured || {};
  $('reconcileBody').innerHTML =
    `<p>@agenticQA requests this month: <b>${r.reported.requests}</b> — self-reported by the participant: <b>${fmt(r.reported.totalTokens)}</b> tokens ` +
    `(in ${fmt(r.reported.inputTokens)} / out ${fmt(r.reported.outputTokens)}).</p>` +
    `<p>Captured total (all Copilot Chat LLM calls): <b>${fmt(captured.totalTokens)}</b> tokens.` +
    ` With AgenticQA (reported): <b>${fmt(withCap.totalTokens)}</b>; Without AgenticQA = captured - with, shown in the cards above.</p>`;
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

$('month').value = currentMonth();
$('month').addEventListener('change', refresh);
refresh();
setInterval(refresh, REFRESH_MS);
