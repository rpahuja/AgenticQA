'use strict';
/**
 * Leader — owns the local HTTP server for the AgenticQA collector.
 *
 * Multiple VS Code windows can be open at once; only one should ingest the
 * journal and serve the dashboard (single-writer storage). Windows race to
 * bind 127.0.0.1:<preferredPort>:
 *   - winner becomes the leader and starts the ingestor,
 *   - losers probe the port; if it is another AgenticQA leader they stay
 *     followers and periodically re-check, taking over automatically if the
 *     leader's window is closed,
 *   - if a foreign process owns the port, the next free port is used.
 */
const http = require('http');
const { createApi } = require('./api');

const PROBE_TIMEOUT_MS = 800;
const SWEEP_MS = 30000;

function probeHealth(url, timeoutMs = PROBE_TIMEOUT_MS) {
  return new Promise((resolve) => {
    const req = http.get(url + '/api/health', { timeout: timeoutMs }, (res) => {
      let data = '';
      res.on('data', (c) => (data += c));
      res.on('end', () => {
        try { resolve(JSON.parse(data)); } catch (_) { resolve(null); }
      });
    });
    req.on('timeout', () => req.destroy());
    req.on('error', () => resolve(null));
  });
}

class Leader {
  constructor({ ingestor, dashboardDir, preferredPort, version, log, getStatus }) {
    this.ingestor = ingestor;
    this.dashboardDir = dashboardDir;
    this.preferredPort = Number(preferredPort) || 8787;
    this.version = version || '0.0.0';
    this.log = log || (() => {});
    this.api = createApi(ingestor, {
      dashboardDir,
      version: this.version,
      leaderStatus: () => this.status(),
      otelStatus: () => (getStatus ? getStatus().otel : null)
    });
    this.server = null;
    this.port = 0;
    this.mode = 'stopped';
    this._sweep = null;
  }

  async start() {
    let port = await this._tryBind(this.preferredPort);
    if (port) {
      this._becomeLeader(port);
      this._startSweep();
      return this.status();
    }

    const health = await probeHealth(`http://127.0.0.1:${this.preferredPort}`);
    if (health && health.agent === 'agenticqa') {
      this.mode = 'follower';
      this.port = this.preferredPort;
      this.log(`Collector: follower — existing leader at 127.0.0.1:${this.preferredPort}`);
    } else {
      for (let p = this.preferredPort + 1; p <= this.preferredPort + 20; p++) {
        port = await this._tryBind(p);
        if (port) { this._becomeLeader(port); break; }
      }
      if (!this.port) {
        this.mode = 'blocked';
        this.log('Collector: could not bind any port in range');
      }
    }
    this._startSweep();
    return this.status();
  }

  _startSweep() {
    // Note: do NOT unref() this interval — in follower mode there is no
    // listening server, so the sweep must keep the event loop alive (both for
    // the standalone harness and for reliable takeover).
    this._sweep = setInterval(() => this._sweepTick(), SWEEP_MS);
  }

  _becomeLeader(port) {
    this.mode = 'leader';
    this.port = port;
    if (!this.ingestor._running) this.ingestor.start();
    this.log(`Collector: leader at http://127.0.0.1:${port}`);
  }

  _tryBind(port) {
    return new Promise((resolve) => {
      const server = http.createServer((req, res) => this.api.handle(req, res));
      server.once('error', (err) => {
        if (err && err.code === 'EADDRINUSE') resolve(false);
        else {
          this.log('bind error: ' + err.message);
          resolve(false);
        }
      });
      server.listen(port, '127.0.0.1', () => {
        this.server = server;
        resolve(port);
      });
    });
  }

  async _sweepTick() {
    if (this.mode === 'leader') return;
    const health = await probeHealth(`http://127.0.0.1:${this.preferredPort}`);
    if (health && health.agent === 'agenticqa') {
      this.mode = 'follower';
      this.port = this.preferredPort;
      return;
    }
    const port = await this._tryBind(this.preferredPort);
    if (port) this._becomeLeader(port);
  }

  url() {
    return this.port ? `http://127.0.0.1:${this.port}` : '';
  }

  status() {
    return { mode: this.mode, port: this.port, url: this.url() };
  }

  async stop() {
    if (this._sweep) clearInterval(this._sweep);
    this._sweep = null;
    this.ingestor.stop();
    if (this.server) {
      await new Promise((resolve) => this.server.close(resolve));
    }
    this.server = null;
    this.mode = 'stopped';
    this.port = 0;
  }
}

module.exports = { Leader, probeHealth };
