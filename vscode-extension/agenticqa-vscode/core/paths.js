'use strict';
/**
 * AgenticQA paths — everything lives under one per-user data home
 * (default: ~/.agenticqa, i.e. %USERPROFILE%\.agenticqa on Windows).
 * No admin rights needed; all writes stay inside the user profile.
 */
const os = require('os');
const path = require('path');

function defaultDataHome() {
  return process.env.AGENTICQA_HOME || path.join(os.homedir(), '.agenticqa');
}

/**
 * Resolve the effective data home:
 * 1. explicit configuration (VS Code setting `agenticqa.dataHome`)
 * 2. AGENTICQA_HOME environment variable
 * 3. ~/.agenticqa
 */
function resolveDataHome(configured) {
  if (configured && typeof configured === 'string' && configured.trim().length > 0) {
    return configured.trim();
  }
  return defaultDataHome();
}

function layout(dataHome) {
  return {
    dataHome,
    otelDir: path.join(dataHome, 'otel'),
    dataDir: path.join(dataHome, 'data'),
    logsDir: path.join(dataHome, 'logs'),
    // Copilot Chat's built-in OTel file exporter writes every LLM span here.
    otelFile: path.join(dataHome, 'otel', 'copilot-otel.jsonl'),
    // Classification marks reported by the @agenticQA participant (fallback + reconciliation).
    marksFile: path.join(dataHome, 'otel', 'agenticqa-marks.jsonl'),
    // Collector state.
    stateFile: path.join(dataHome, 'data', 'state.json'),
    seenFile: path.join(dataHome, 'data', 'seen.json'),
    dailyFile: path.join(dataHome, 'data', 'daily.json'),
    conversationsFile: path.join(dataHome, 'data', 'conversations.json'),
    snapshotsFile: path.join(dataHome, 'data', 'monthly_snapshots.json'),
    // Normalized inference-event journal (append-only, audit trail).
    eventsFile: path.join(dataHome, 'data', 'events.jsonl')
  };
}

module.exports = { defaultDataHome, resolveDataHome, layout };
