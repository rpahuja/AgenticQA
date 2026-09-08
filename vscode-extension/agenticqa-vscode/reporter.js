'use strict';
/**
 * Reports @agenticQA request metadata ("classification marks") to the collector:
 *   1. POST to the local collector's /api/agenticqa/mark (leader window), or
 *   2. fall back to appending to the marks journal on disk — the ingestor tails
 *      that file, so no data is lost when the server isn't reachable.
 */
const http = require('http');
const { URL } = require('url');
const { appendLineSync } = require('./core/store');
const { layout } = require('./core/paths');

function postJson(url, payload, timeoutMs = 1500) {
  return new Promise((resolve) => {
    const data = JSON.stringify(payload);
    let parsed;
    try { parsed = new URL(url); } catch (_) { return resolve(false); }
    const req = http.request(
      {
        host: parsed.hostname,
        port: parsed.port,
        path: parsed.pathname,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(data)
        },
        timeout: timeoutMs
      },
      (res) => {
        res.resume();
        res.on('end', () => resolve(true));
      }
    );
    req.on('timeout', () => req.destroy());
    req.on('error', () => resolve(false));
    req.end(data);
  });
}

async function reportMark({ dataHome, serverUrl, mark, log }) {
  const payload = Object.assign({ ts: Date.now() }, mark);
  try {
    if (serverUrl) {
      const delivered = await postJson(serverUrl + '/api/agenticqa/mark', payload);
      if (delivered) return { delivered: 'http' };
    }
  } catch (e) {
    /* fall through to file */
  }
  try {
    appendLineSync(layout(dataHome).marksFile, JSON.stringify(payload));
    return { delivered: 'file' };
  } catch (e) {
    if (log) log('mark fallback write failed: ' + e.message);
    return { delivered: 'lost' };
  }
}

module.exports = { reportMark, postJson };
