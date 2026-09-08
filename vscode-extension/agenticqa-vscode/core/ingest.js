'use strict';
/**
 * Ingestor — ingests the Copilot Chat OTel journal and computes the
 * With/Without AgenticQA token split.
 *
 * Sources of truth (verified against the real exporter output):
 *   - "captured" totals: `gen_ai.client.inference.operation.details` events —
 *     one exact record per LLM API call (input/output/cache/reasoning tokens).
 *     The file exporter emits logs + metrics, not spans, and events carry no
 *     conversation id — but they ARE exact per call.
 *   - "with AgenticQA": classification marks reported by the @agenticQA
 *     participant. The participant counts its own requests' tokens precisely
 *     (input via countTokens, output via countTokens on the response).
 *   - "without AgenticQA" = captured − with, clamped at 0 (per field, per day,
 *     and per model).
 *
 * This residual model stays exact without needing conversation linkage in the
 * exporter stream: AgenticQA reports exactly what it spent; everything else is
 * baseline. `copilot_chat.session.start` events let us reconstruct session
 * ("conversation") boundaries for the dashboard's session list.
 *
 * Pure Node.js — no third-party dependencies.
 */
const fs = require('fs');
const { JsonStore, appendLineSync, ensureDir } = require('./store');
const { layout } = require('./paths');
const { parseLine, toNumber } = require('./normalize');

const POLL_MS = 2000;
const SEEN_FLUSH_MS = 30000;
const MARK_PAD_MS = 2000; // tolerance for matching marks to session windows

function localDay(ms) {
  const d = new Date(ms);
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${m}-${day}`;
}
function localMonth(ms) { return localDay(ms).slice(0, 7); }
function currentMonth() { return localMonth(Date.now()); }

function zeroCounts() { return { in: 0, out: 0, cr: 0, cc: 0, re: 0, calls: 0 }; }
function addCounts(target, src) {
  target.in += src.in || 0;
  target.out += src.out || 0;
  target.cr += src.cr || 0;
  target.cc += src.cc || 0;
  target.re += src.re || 0;
  target.calls += src.calls || 0;
  return target;
}
/** without = captured − with, clamped at 0 per field. */
function subClamped(captured, with_) {
  return {
    in: Math.max(0, (captured.in || 0) - (with_.in || 0)),
    out: Math.max(0, (captured.out || 0) - (with_.out || 0)),
    cr: Math.max(0, (captured.cr || 0) - (with_.cr || 0)),
    cc: Math.max(0, (captured.cc || 0) - (with_.cc || 0)),
    re: Math.max(0, (captured.re || 0) - (with_.re || 0)),
    calls: Math.max(0, (captured.calls || 0) - (with_.calls || 0))
  };
}
function publicCounts(c) {
  c = c || zeroCounts();
  return {
    inputTokens: c.in || 0,
    outputTokens: c.out || 0,
    cacheReadTokens: c.cr || 0,
    cacheCreationTokens: c.cc || 0,
    reasoningTokens: c.re || 0,
    totalTokens: (c.in || 0) + (c.out || 0),
    llmCalls: c.calls || 0
  };
}

class Ingestor {
  constructor(dataHome, log) {
    this.dataHome = dataHome;
    this.log = log || (() => {});
    this.L = layout(dataHome);
    for (const dir of [this.L.otelDir, this.L.dataDir, this.L.logsDir]) ensureDir(dir);

    this.store = new JsonStore(this.L.dataDir);
    const rawState = this.store.read('state.json', {});
    if (!rawState || rawState.schemaVersion !== 3) {
      // Migration from earlier collector versions: re-read the journal from
      // the top and rebuild the seen set from the events journal. v3 adds
      // turn-level usage (copilot_chat.agent.turn) as a separate bucket.
      this.state = { offsets: {}, partial: {}, schemaVersion: 3 };
      this._needsSeenRebuild = true;
      this._seenMigrate = true;
    } else {
      this.state = rawState;
      this._needsSeenRebuild = false;
      this._seenMigrate = false;
    }
    // window-scoped session.id -> model (agent.turn events carry no model).
    if (!this.state.windowModels || typeof this.state.windowModels !== 'object') {
      this.state.windowModels = {};
    }

    const seenData = this.store.read('seen.json', { ids: [] });
    this.seen = new Set(this._seenMigrate ? [] : (Array.isArray(seenData.ids) ? seenData.ids : []));
    if (!this.seen.size) this._needsSeenRebuild = true;

    // daily.json: day -> { captured: {model: counts}, with: {model: counts},
    //                      turns: {model: counts} }
    this.daily = this.store.read('daily.json', {});
    // conversations.json: sessionId -> session (session.start-derived)
    this.sessions = this.store.read('conversations.json', {});
    this.snapshots = this.store.read('monthly_snapshots.json', {});
    this.marks = [];
    this._markIds = new Set();
    // "with" buckets are derived from the marks journal; rebuild them on every
    // startup so restarts/crashes can never double-count or lose a mark.
    for (const day of Object.keys(this.daily)) {
      this.daily[day].with = {};
      this.daily[day].captured = this.daily[day].captured || {};
      this.daily[day].turns = this.daily[day].turns || {};
    }
    this.turnEventsTotal = 0;
    this.eventsTotal = this._countJournalLines();
    this._loadMarksFile();

    this.ignoredLines = 0;
    this.unparsedLines = 0;
    this.lastIngestMs = 0;
    this._dirty = false;
    this._timer = null;
    this._running = false;
    this._lastSeenFlush = 0;
  }

  // ---------------------------------------------------------------- lifecycle

  start() {
    if (this._running) return;
    this._running = true;
    this.log(`Ingestor started (dataHome=${this.dataHome})`);
    this.pollOnce().catch((e) => this.log('poll error: ' + e.message));
    this._timer = setInterval(() => this.pollOnce().catch((e) => this.log('poll error: ' + e.message)), POLL_MS);
  }

  stop() {
    if (!this._running) return;
    this._running = false;
    if (this._timer) clearInterval(this._timer);
    this._timer = null;
    this._flush(true);
  }

  async pollOnce() {
    for (const src of this._sources()) {
      await this._readSource(src);
    }
    this._maybeSnapshot();
    if (this._dirty) this._flush(false);
  }

  // ------------------------------------------------------------------ reading

  _sources() {
    return [
      { type: 'otel', file: this.L.otelFile },
      { type: 'marks', file: this.L.marksFile }
    ];
  }

  _readSource(src) {
    return new Promise((resolve) => {
      fs.open(src.file, 'r', (err, fd) => {
        if (err) { resolve(); return; }
        fs.fstat(fd, (err2, st) => {
          if (err2) { try { fs.closeSync(fd); } catch (_) {} resolve(); return; }
          let offset = this.state.offsets && this.state.offsets[src.file] ? Number(this.state.offsets[src.file]) : 0;
          if (st.size < offset) offset = 0; // file rotated/truncated — restart from the top
          const len = st.size - offset;
          if (len <= 0) { try { fs.closeSync(fd); } catch (_) {} resolve(); return; }

          const buf = Buffer.alloc(len);
          fs.read(fd, buf, 0, len, offset, (err3, bytesRead) => {
            try { fs.closeSync(fd); } catch (_) {}
            if (err3 || bytesRead <= 0) { resolve(); return; }
            let chunk = buf.toString('utf8', 0, bytesRead);
            const partial = (this.state.partial && this.state.partial[src.file]) || '';
            if (partial) chunk = partial + chunk;

            const endsWithNewline = chunk.endsWith('\n');
            const lines = chunk.split('\n');
            const last = lines.length - 1;
            for (let i = 0; i < last; i++) {
              const line = lines[i];
              if (!line.trim()) continue;
              try {
                this._handleLine(src.type, line);
              } catch (e) {
                this.unparsedLines++;
                this.log(`parse error (${src.type}): ${e.message}`);
              }
            }
            const leftover = endsWithNewline ? '' : (lines[last] || '');
            if (leftover) this.state.partial[src.file] = leftover;
            else delete this.state.partial[src.file];

            this.state.offsets[src.file] = offset + bytesRead;
            this._dirty = true;
            resolve();
          });
        });
      });
    });
  }

  _handleLine(type, line) {
    const obj = JSON.parse(line);
    if (type === 'marks') {
      const mark = this._normalizeMark(obj);
      if (mark) this._applyMark(mark, false);
      return;
    }
    const records = parseLine(obj);
    if (!records.length) {
      if (obj && typeof obj === 'object' && Object.keys(obj).length) this.ignoredLines++;
      return;
    }
    for (const rec of records) {
      if (rec.type === 'inference') this._handleInference(rec);
      else if (rec.type === 'session') this._handleSession(rec);
      else if (rec.type === 'turn') this._handleTurn(rec);
    }
  }

  // ---------------------------------------------------------------- events

  _handleInference(rec) {
    if (!rec || !rec.id) return;
    if (this.seen.has(rec.id)) return;

    const day = localDay(rec.tsMs);
    const model = rec.model || 'unknown';
    const c = {
      in: rec.inputTokens, out: rec.outputTokens,
      cr: rec.cacheReadTokens, cc: rec.cacheCreationTokens, re: rec.reasoningTokens,
      calls: 1
    };
    this._addBucket(day, 'captured', model, c);

    const session = this._sessionFor(rec.tsMs);
    if (session) {
      addCounts(session.captured, c);
      session.lastEventMs = Math.max(session.lastEventMs || 0, rec.tsMs);
    }

    appendLineSync(this.L.eventsFile, JSON.stringify(rec));
    this.seen.add(rec.id);
    this.eventsTotal += 1;
    this.lastIngestMs = Date.now();
    this._dirty = true;
  }

  _handleSession(rec) {
    let session = this.sessions[rec.id];
    if (!session) {
      session = {
        sessionId: rec.id,
        startMs: rec.tsMs,
        lastEventMs: rec.tsMs,
        windowId: rec.windowId || '',
        agentName: rec.agentName || '',
        model: rec.model || '',
        captured: zeroCounts(),
        turns: zeroCounts(),
        withAgenticqa: 0,
        source: ''
      };
      this.sessions[rec.id] = session;
    } else {
      if (!session.agentName && rec.agentName) session.agentName = rec.agentName;
      if (!session.model && rec.model) session.model = rec.model;
      if (!session.windowId && rec.windowId) session.windowId = rec.windowId;
      if (!session.turns) session.turns = zeroCounts();
    }
    // Turn events carry no model; remember the window's model for attribution.
    if (rec.windowId && rec.model) this.state.windowModels[rec.windowId] = rec.model;
    // Marks loaded earlier (or from disk) may belong to this session.
    this._applyMarksToSession(session);
    this._dirty = true;
  }

  _applyMarksToSession(session) {
    if (session.withAgenticqa) return false;
    const windowEnd = (session.lastEventMs || session.startMs) + MARK_PAD_MS;
    for (const mark of this.marks) {
      if (mark.endedAt >= session.startMs - MARK_PAD_MS && mark.startedAt <= windowEnd) {
        session.withAgenticqa = 1;
        session.source = 'mark:' + mark.requestId;
        return true;
      }
    }
    return false;
  }

  _sessionFor(tsMs) {
    let best = null;
    for (const s of Object.values(this.sessions)) {
      if (s.startMs <= tsMs && (!best || s.startMs > best.startMs)) best = s;
    }
    return best;
  }

  /**
   * Turn-level usage events carry only the window-scoped session.id; prefer a
   * session from the same window, fall back to the latest session by time.
   */
  _handleTurn(rec) {
    if (!rec || !rec.id) return;
    if (this.seen.has(rec.id)) return;

    const day = localDay(rec.tsMs);
    let model = (rec.windowId && this.state.windowModels[rec.windowId]) || '';
    const session = this._sessionForTurn(rec);
    if (!model && session && session.model) model = session.model;
    if (!model) model = 'unknown';

    // Estimated cache-hit split: a turn re-sends the previous turn's context,
    // which DeepSeek bills as cache-hit input (best-effort prefix matching).
    // Totals stay exact; the hit/miss split is an estimate only.
    const prev = this.state.turnPrevInput || {};
    const prevInput = prev[rec.windowId] ? toNumber(prev[rec.windowId]) : 0;
    const estHit = rec.turnIndex > 0 && prevInput > 0 ? Math.min(rec.inputTokens, prevInput) : 0;
    if (!this.state.turnPrevInput) this.state.turnPrevInput = {};
    this.state.turnPrevInput[rec.windowId] = rec.inputTokens;

    const c = {
      in: rec.inputTokens, out: rec.outputTokens,
      cr: estHit, cc: 0, re: 0,
      calls: 1
    };
    this._addBucket(day, 'turns', model, c);

    if (session) {
      session.turns = session.turns || zeroCounts();
      addCounts(session.turns, c);
      session.lastEventMs = Math.max(session.lastEventMs || 0, rec.tsMs);
    }

    appendLineSync(this.L.eventsFile, JSON.stringify(rec));
    this.seen.add(rec.id);
    this.eventsTotal += 1;
    this.turnEventsTotal += 1;
    this.lastIngestMs = Date.now();
    this._dirty = true;
  }

  _sessionForTurn(rec) {
    let best = null;
    let bestWin = null;
    for (const s of Object.values(this.sessions)) {
      if (s.startMs > rec.tsMs) continue;
      if (rec.windowId && s.windowId === rec.windowId && (!bestWin || s.startMs > bestWin.startMs)) {
        bestWin = s;
      }
      if (!best || s.startMs > best.startMs) best = s;
    }
    return bestWin || best;
  }

  _addBucket(day, bucket, model, c) {
    this.daily[day] = this.daily[day] || { captured: {}, with: {}, turns: {} };
    this.daily[day][bucket] = this.daily[day][bucket] || {};
    this.daily[day][bucket][model] = this.daily[day][bucket][model] || zeroCounts();
    addCounts(this.daily[day][bucket][model], c);
  }

  // ------------------------------------------------------------------- marks

  _normalizeMark(obj) {
    if (!obj || !obj.requestId) return null;
    const fallback = obj.ts !== undefined ? toNumber(obj.ts) : Date.now();
    const startedAt = (obj.startedAt !== undefined ? toNumber(obj.startedAt) : fallback) || Date.now();
    const endedAt = (obj.endedAt !== undefined ? toNumber(obj.endedAt) : fallback) || startedAt;
    return {
      requestId: String(obj.requestId),
      model: String(obj.model || ''),
      startedAt,
      endedAt,
      inputTokens: toNumber(obj.inputTokens),
      outputTokens: toNumber(obj.outputTokens)
    };
  }

  _loadMarksFile() {
    try {
      const content = fs.readFileSync(this.L.marksFile, 'utf8');
      for (const line of content.split('\n')) {
        if (!line.trim()) continue;
        try {
          const mark = this._normalizeMark(JSON.parse(line));
          if (mark) this._applyMark(mark, false);
        } catch (_) { /* skip unparsable line */ }
      }
    } catch (_) { /* file does not exist yet */ }
  }

  /** Public entry point for the HTTP endpoint. Appends to the marks journal. */
  applyMarkFromApi(obj) {
    const mark = this._normalizeMark(obj || {});
    if (!mark) return { ok: false, matched: 0 };
    return Object.assign({ ok: true }, this._applyMark(mark, true));
  }

  _applyMark(mark, appendToFile) {
    if (this._markIds.has(mark.requestId)) return { matched: 0 };
    this._markIds.add(mark.requestId);
    this.marks.push(mark);
    if (appendToFile) appendLineSync(this.L.marksFile, JSON.stringify(Object.assign({ ts: Date.now() }, mark)));

    // "With AgenticQA" = the participant's own reported token counts.
    const day = localDay(mark.startedAt);
    this._addBucket(day, 'with', mark.model || 'unknown', {
      in: mark.inputTokens || 0,
      out: mark.outputTokens || 0,
      cr: 0, cc: 0, re: 0,
      calls: 1
    });

    let matched = 0;
    for (const s of Object.values(this.sessions)) {
      if (s.withAgenticqa) continue;
      const windowEnd = (s.lastEventMs || s.startMs) + MARK_PAD_MS;
      if (mark.endedAt >= s.startMs - MARK_PAD_MS && mark.startedAt <= windowEnd) {
        s.withAgenticqa = 1;
        s.source = 'mark:' + mark.requestId;
        matched++;
      }
    }
    this._dirty = true;
    return { matched };
  }

  // ------------------------------------------------------------ aggregation

  _sumBucketMap(map) {
    const t = zeroCounts();
    for (const c of Object.values(map || {})) addCounts(t, c);
    return t;
  }

  _sumBucketMonth(month, bucket) {
    const t = zeroCounts();
    for (const day of Object.keys(this.daily)) {
      if (!day.startsWith(month)) continue;
      addCounts(t, this._sumBucketMap(this.daily[day][bucket]));
    }
    return t;
  }

  getDailyMonth(month) {
    const out = [];
    for (const day of Object.keys(this.daily).sort()) {
      if (!day.startsWith(month)) continue;
      const entry = this.daily[day];
      const captured = this._sumBucketMap(entry.captured);
      const with_ = this._sumBucketMap(entry.with);
      const turns = this._sumBucketMap(entry.turns);
      out.push({
        date: day,
        captured: publicCounts(captured),
        with: publicCounts(with_),
        without: publicCounts(subClamped(captured, with_)),
        turns: publicCounts(turns)
      });
    }
    return out;
  }

  summarizeMonth(month) {
    const captured = zeroCounts();
    const with_ = zeroCounts();
    const without = zeroCounts();
    const turns = zeroCounts();
    for (const day of this.getDailyMonth(month)) {
      addCounts(captured, {
        in: day.captured.inputTokens, out: day.captured.outputTokens,
        cr: day.captured.cacheReadTokens, cc: day.captured.cacheCreationTokens,
        re: day.captured.reasoningTokens, calls: day.captured.llmCalls
      });
      addCounts(with_, {
        in: day.with.inputTokens, out: day.with.outputTokens,
        cr: day.with.cacheReadTokens, cc: day.with.cacheCreationTokens,
        re: day.with.reasoningTokens, calls: day.with.llmCalls
      });
      addCounts(without, {
        in: day.without.inputTokens, out: day.without.outputTokens,
        cr: day.without.cacheReadTokens, cc: day.without.cacheCreationTokens,
        re: day.without.reasoningTokens, calls: day.without.llmCalls
      });
      addCounts(turns, {
        in: day.turns.inputTokens, out: day.turns.outputTokens,
        cr: day.turns.cacheReadTokens, cc: 0, re: 0, calls: day.turns.llmCalls
      });
    }
    return {
      month,
      captured: publicCounts(captured),
      withAgenticqa: publicCounts(with_),
      withoutAgenticqa: publicCounts(without),
      total: publicCounts(captured),
      turns: publicCounts(turns)
    };
  }

  getModelsMonth(month) {
    const models = {};
    for (const day of Object.keys(this.daily)) {
      if (!day.startsWith(month)) continue;
      const entry = this.daily[day];
      for (const [model, c] of Object.entries(entry.captured || {})) {
        models[model] = models[model] || { model, captured: zeroCounts(), with: zeroCounts(), turns: zeroCounts() };
        addCounts(models[model].captured, c);
      }
      for (const [model, c] of Object.entries(entry.with || {})) {
        models[model] = models[model] || { model, captured: zeroCounts(), with: zeroCounts(), turns: zeroCounts() };
        addCounts(models[model].with, c);
      }
      for (const [model, c] of Object.entries(entry.turns || {})) {
        models[model] = models[model] || { model, captured: zeroCounts(), with: zeroCounts(), turns: zeroCounts() };
        addCounts(models[model].turns, c);
      }
    }
    return Object.values(models)
      .map((m) => ({
        model: m.model,
        captured: publicCounts(m.captured),
        with: publicCounts(m.with),
        turns: publicCounts(m.turns),
        calls: m.captured.calls
      }))
      .sort((a, b) => b.captured.totalTokens - a.captured.totalTokens);
  }

  getConversations(limit = 50) {
    return Object.values(this.sessions)
      .map((s) => {
        // Reported = the @agenticQA marks that landed inside this session's
        // window. The session's captured totals include ALL Copilot traffic in
        // it, so a "WITH" row must show the reported tokens, not the captured
        // ones, or a long chat session would look like one giant @agenticQA run.
        const reported = zeroCounts();
        if (s.withAgenticqa) {
          const windowEnd = (s.lastEventMs || s.startMs) + MARK_PAD_MS;
          for (const mark of this.marks) {
            if (mark.endedAt >= s.startMs - MARK_PAD_MS && mark.startedAt <= windowEnd) {
              addCounts(reported, { in: mark.inputTokens || 0, out: mark.outputTokens || 0, cr: 0, cc: 0, re: 0, calls: 1 });
            }
          }
        }
        return {
          id: s.sessionId,
          participant: s.agentName || '',
          models: s.model ? [s.model] : [],
          withAgenticqa: !!s.withAgenticqa,
          source: s.source || '',
          firstSeenMs: s.startMs || 0,
          lastSeenMs: s.lastEventMs || s.startMs || 0,
          totals: publicCounts(s.captured || zeroCounts()),
          turnTotals: publicCounts(s.turns || zeroCounts()),
          reported: publicCounts(reported)
        };
      })
      .sort((a, b) => b.lastSeenMs - a.lastSeenMs)
      .slice(0, limit);
  }

  getMonths() {
    const set = new Set();
    for (const day of Object.keys(this.daily)) set.add(day.slice(0, 7));
    for (const m of Object.keys(this.snapshots)) set.add(m);
    set.add(currentMonth());
    return Array.from(set).sort().reverse();
  }

  reconcileMonth(month) {
    let requests = 0;
    let reportedInput = 0;
    let reportedOutput = 0;
    for (const m of this.marks) {
      if (localMonth(m.startedAt) === month) {
        requests++;
        reportedInput += m.inputTokens || 0;
        reportedOutput += m.outputTokens || 0;
      }
    }
    const captured = this._sumBucketMonth(month, 'captured');
    const with_ = this._sumBucketMonth(month, 'with');
    return {
      month,
      reported: {
        requests,
        inputTokens: reportedInput,
        outputTokens: reportedOutput,
        totalTokens: reportedInput + reportedOutput
      },
      captured: publicCounts(captured),
      withAgenticqa: publicCounts(with_)
    };
  }

  /** Snapshot every past month that has data and no snapshot yet. */
  _maybeSnapshot() {
    const cur = currentMonth();
    for (const day of Object.keys(this.daily)) {
      const m = day.slice(0, 7);
      if (m === cur || this.snapshots[m]) continue;
      const captured = this._sumBucketMonth(m, 'captured');
      const with_ = this._sumBucketMonth(m, 'with');
      const turns = this._sumBucketMonth(m, 'turns');
      this.snapshots[m] = {
        month: m,
        captured: publicCounts(captured),
        with: publicCounts(with_),
        without: publicCounts(subClamped(captured, with_)),
        turns: publicCounts(turns),
        capturedAt: Date.now()
      };
      this._dirty = true;
    }
  }

  status() {
    let otelExists = false;
    let otelBytes = 0;
    try {
      const st = fs.statSync(this.L.otelFile);
      otelExists = true;
      otelBytes = st.size;
    } catch (_) { /* not created yet */ }
    return {
      dataHome: this.dataHome,
      otelFile: this.L.otelFile,
      otelFileExists: otelExists,
      otelFileBytes: otelBytes,
      offsets: Object.assign({}, this.state.offsets || {}),
      eventsTotal: this.eventsTotal,
      unparsedLines: this.unparsedLines,
      ignoredLines: this.ignoredLines,
      sessionsTracked: Object.keys(this.sessions).length,
      withAgenticqaSessions: Object.values(this.sessions).filter((s) => s.withAgenticqa).length,
      marksCount: this.marks.length,
      turnEventsTotal: this.turnEventsTotal,
      lastIngestMs: this.lastIngestMs,
      lastFlushMs: this.state.lastFlushMs || 0
    };
  }

  // ---------------------------------------------------------------- storage

  _flush(full) {
    try {
      this.store.write('daily.json', this.daily);
      this.store.write('conversations.json', this.sessions);
      this.store.write('monthly_snapshots.json', this.snapshots);
      this.state.lastFlushMs = Date.now();
      this.store.write('state.json', this.state);
      const now = Date.now();
      if (full || now - this._lastSeenFlush > SEEN_FLUSH_MS) {
        this._lastSeenFlush = now;
        this.store.write('seen.json', { ids: Array.from(this.seen) });
      }
      this._dirty = false;
    } catch (e) {
      this.log('flush error: ' + e.message);
    }
  }

  _countJournalLines() {
    let n = 0;
    this.turnEventsTotal = 0;
    try {
      const content = fs.readFileSync(this.L.eventsFile, 'utf8');
      for (const line of content.split('\n')) {
        if (!line.trim()) continue;
        n++;
        if (this._needsSeenRebuild) {
          try {
            const rec = JSON.parse(line);
            if (rec && rec.id) this.seen.add(String(rec.id));
            if (rec && rec.type === 'turn') this.turnEventsTotal++;
          } catch (_) { /* skip */ }
        } else {
          try {
            const rec = JSON.parse(line);
            if (rec && rec.type === 'turn') this.turnEventsTotal++;
          } catch (_) { /* skip */ }
        }
      }
    } catch (_) { /* journal does not exist yet */ }
    this._needsSeenRebuild = false;
    return n;
  }
}

module.exports = { Ingestor, publicCounts, zeroCounts, addCounts, subClamped, localDay, localMonth, currentMonth };

