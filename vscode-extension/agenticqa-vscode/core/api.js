'use strict';
/**
 * HTTP API + dashboard static serving for the AgenticQA collector.
 * Bound to 127.0.0.1 only — nothing is exposed to the network/VPN.
 * Pure Node.js `http` module, no framework.
 */
const fs = require('fs');
const path = require('path');
const { toNumber } = require('./normalize');

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.json': 'application/json; charset=utf-8',
  '.ico': 'image/x-icon',
  '.png': 'image/png'
};

function currentMonth() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}
function validMonth(m) {
  return typeof m === 'string' && /^\d{4}-\d{2}$/.test(m);
}

function createApi(ingestor, meta) {
  return { handle };

  function handle(req, res) {
    try {
      route(req, res);
    } catch (err) {
      sendJson(res, 500, { ok: false, error: String((err && err.message) || err) });
    }
  }

  function route(req, res) {
    const u = new URL(req.url, 'http://127.0.0.1');
    const p = u.pathname;

    if (req.method === 'POST' && p === '/api/agenticqa/mark') {
      return readBody(req, res, (body) => {
        let obj;
        try { obj = JSON.parse(body); } catch (_) { return sendJson(res, 400, { ok: false, error: 'invalid json' }); }
        sendJson(res, 200, ingestor.applyMarkFromApi(obj || {}));
      });
    }

    if (req.method === 'GET' && p === '/api/health') {
      return sendJson(res, 200, Object.assign(
        { ok: true, agent: 'agenticqa', version: meta.version || '', leader: meta.leaderStatus ? meta.leaderStatus() : {} },
        ingestor.status(),
        { otel: meta.otelStatus ? meta.otelStatus() : null }
      ));
    }

    if (req.method === 'GET' && p === '/api/usage/summary') {
      return sendJson(res, 200, ingestor.summarizeMonth(monthParam(u)));
    }
    if (req.method === 'GET' && p === '/api/usage/daily') {
      return sendJson(res, 200, { month: monthParam(u), days: ingestor.getDailyMonth(monthParam(u)) });
    }
    if (req.method === 'GET' && p === '/api/usage/models') {
      return sendJson(res, 200, { month: monthParam(u), models: ingestor.getModelsMonth(monthParam(u)) });
    }
    if (req.method === 'GET' && p === '/api/conversations') {
      return sendJson(res, 200, { conversations: ingestor.getConversations(limitParam(u, 50)) });
    }
    if (req.method === 'GET' && p === '/api/months') {
      return sendJson(res, 200, { months: ingestor.getMonths() });
    }
    if (req.method === 'GET' && p === '/api/reconcile') {
      return sendJson(res, 200, ingestor.reconcileMonth(monthParam(u)));
    }

    // Dashboard static files.
    if (req.method === 'GET') {
      if (p === '/') return serveFile(res, meta.dashboardDir, 'index.html');
      if (p === '/app.js' || p === '/styles.css' || p === '/favicon.ico') {
        return serveFile(res, meta.dashboardDir, p.slice(1));
      }
    }

    sendJson(res, 404, { ok: false, error: 'not found' });
  }

  function monthParam(u) {
    const m = u.searchParams.get('month');
    return validMonth(m) ? m : currentMonth();
  }

  function limitParam(u, fallback) {
    const n = toNumber(u.searchParams.get('limit')) || fallback;
    return Math.max(1, Math.min(500, Math.floor(n)));
  }

  function serveFile(res, dir, rel) {
    const safe = path.normalize(rel).replace(/^(\.\.[/\\])+/, '');
    const file = path.join(dir, safe);
    if (!file.startsWith(path.resolve(dir))) {
      return sendJson(res, 403, { ok: false, error: 'forbidden' });
    }
    fs.readFile(file, (err, data) => {
      if (err) return sendJson(res, 404, { ok: false, error: 'file not found' });
      const ext = path.extname(file).toLowerCase();
      res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
      res.end(data);
    });
  }
}

function readBody(req, res, cb) {
  let data = '';
  req.on('data', (c) => {
    data += c;
    if (data.length > 1e6) req.destroy(); // 1 MB cap
  });
  req.on('end', () => cb(data));
}

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store'
  });
  res.end(body);
}

module.exports = { createApi, currentMonth };
