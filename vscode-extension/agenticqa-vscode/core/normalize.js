'use strict';
/**
 * Parser for the Copilot Chat OpenTelemetry file-exporter journal.
 *
 * IMPORTANT: the file exporter writes JSON lines of the SDK's internal object
 * shapes — NOT the standard OTLP JSON wire format. The shapes that matter:
 *
 *   Log record (one object per line):
 *     {
 *       hrTime: [sec, nsec],
 *       resource: { _rawAttributes: [["service.name","copilot-chat"], ...] },
 *       attributes: { "event.name": "...", "gen_ai.usage.input_tokens": 253, ... },
 *       _body: "...",
 *       ...
 *     }
 *
 *   Metric batch:  { resource: {...}, scopeMetrics: [...] }   — ignored here;
 *     per-call token counts come from inference log events, which are exact.
 *
 *   Empty separator: {}
 *
 * Records we consume:
 *   event.name = "gen_ai.client.inference.operation.details"
 *     → one exact record per LLM API call (input/output/cache/reasoning tokens).
 *   event.name = "copilot_chat.session.start"
 *     → one record per chat session (session.id + gen_ai.agent.name).
 */

function toNumber(v) {
  if (v === null || v === undefined || v === '') return 0;
  if (typeof v === 'number') return Number.isFinite(v) ? v : 0;
  const n = Number(String(v).replace(/["']/g, '').trim());
  return Number.isFinite(n) ? n : 0;
}

/** `hrTime` is an [epochSeconds, nanoseconds] pair in the SDK dump. */
function hrTimeToMs(hrTime) {
  if (Array.isArray(hrTime) && hrTime.length >= 2) {
    return Math.floor(Number(hrTime[0]) * 1000 + Number(hrTime[1]) / 1e6);
  }
  if (typeof hrTime === 'number') return hrTime;
  return 0;
}

/** Merge log `attributes` with `resource._rawAttributes`; log attrs win on collisions. */
function mergeAttributes(obj) {
  const out = {};
  const ra = obj && obj.resource && obj.resource._rawAttributes;
  if (Array.isArray(ra)) {
    for (const pair of ra) {
      if (Array.isArray(pair) && pair.length >= 2) out[String(pair[0])] = pair[1];
    }
  }
  if (obj && obj.attributes && typeof obj.attributes === 'object' && !Array.isArray(obj.attributes)) {
    Object.assign(out, obj.attributes);
  }
  return out;
}

/** Read a single resource-level attribute (e.g. the window-scoped session.id). */
function resourceAttr(obj, key) {
  const ra = obj && obj.resource && obj.resource._rawAttributes;
  if (Array.isArray(ra)) {
    for (const pair of ra) {
      if (Array.isArray(pair) && pair.length >= 2 && String(pair[0]) === key) return pair[1];
    }
  }
  return undefined;
}

/**
 * Parse one JSON line of the journal into normalized records.
 * Returns [] for lines we intentionally ignore (metrics, tool calls, blanks).
 */
function parseLine(obj) {
  const records = [];
  if (!obj || typeof obj !== 'object') return records;
  const attrs = mergeAttributes(obj);
  const event = attrs['event.name'];
  const tsMs = hrTimeToMs(obj.hrTime) || Date.now();

  if (event === 'gen_ai.client.inference.operation.details') {
    const inputTokens = toNumber(attrs['gen_ai.usage.input_tokens']);
    const outputTokens = toNumber(attrs['gen_ai.usage.output_tokens']);
    const responseId = attrs['gen_ai.response.id'];
    const id = responseId
      ? 'resp:' + String(responseId)
      : 'call:' + tsMs + ':' + inputTokens + ':' + outputTokens;
    records.push({
      type: 'inference',
      id,
      tsMs,
      operation: String(attrs['gen_ai.operation.name'] || 'chat'),
      model: String(attrs['gen_ai.request.model'] || attrs['gen_ai.response.model'] || ''),
      inputTokens,
      outputTokens,
      cacheReadTokens: toNumber(attrs['gen_ai.usage.cache_read.input_tokens']),
      cacheCreationTokens: toNumber(attrs['gen_ai.usage.cache_creation.input_tokens']),
      reasoningTokens: toNumber(
        attrs['gen_ai.usage.reasoning.output_tokens'] !== undefined
          ? attrs['gen_ai.usage.reasoning.output_tokens']
          : attrs['gen_ai.usage.reasoning_tokens']
      )
    });
  } else if (event === 'copilot_chat.session.start') {
    records.push({
      type: 'session',
      id: 'session:' + String(attrs['session.id'] || 's-' + tsMs),
      tsMs,
      windowId: String(resourceAttr(obj, 'session.id') || ''),
      model: String(attrs['gen_ai.request.model'] || ''),
      agentName: String(attrs['gen_ai.agent.name'] || '')
    });
  } else if (event === 'copilot_chat.agent.turn') {
    // Turn-level aggregate usage. For agent sessions with BYOK providers
    // (e.g. DeepSeek) Copilot Chat does NOT emit per-call inference events;
    // these turns are the only token signal. Note: turn input includes the
    // full context re-sent on every turn (NOT billing-accurate deltas).
    const win = String(resourceAttr(obj, 'session.id') || '');
    const idx = attrs['turn.index'];
    records.push({
      type: 'turn',
      id: 'turn:' + win + ':' + (idx !== undefined ? toNumber(idx) : tsMs),
      tsMs,
      windowId: win,
      turnIndex: idx !== undefined ? toNumber(idx) : -1,
      inputTokens: toNumber(attrs['gen_ai.usage.input_tokens']),
      outputTokens: toNumber(attrs['gen_ai.usage.output_tokens']),
      toolCalls: toNumber(attrs['tool_call_count'])
    });
  }
  return records;
}

module.exports = { toNumber, hrTimeToMs, mergeAttributes, resourceAttr, parseLine };

