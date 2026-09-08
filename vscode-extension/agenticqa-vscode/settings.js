'use strict';
/**
 * Ensures Copilot Chat's built-in OpenTelemetry token export is enabled.
 * This is a VS Code feature (settings under `github.copilot.chat.otel`) — no
 * third-party software. We point it at a local JSONL file that the collector
 * tails. Token counts only; `captureContent` stays off so prompts/responses
 * are never written to disk.
 */
const vscode = require('vscode');
const { layout } = require('./core/paths');

const SECTION = 'github.copilot.chat.otel';

async function ensureOtelSettings({ dataHome, log }) {
  const cfg = vscode.workspace.getConfiguration(SECTION);
  const agenticqaCfg = vscode.workspace.getConfiguration('agenticqa');
  const manage = agenticqaCfg.get('manageOtelSettings', true);

  const outfile = layout(dataHome).otelFile;
  const desired = {
    enabled: true,
    exporterType: 'file',
    outfile,
    captureContent: false
  };

  const changed = [];
  if (manage) {
    for (const key of Object.keys(desired)) {
      const cur = cfg.get(key);
      if (cur === desired[key]) continue;
      // Respect explicit user choices for these, only fill in when unset:
      if ((key === 'outfile' || key === 'exporterType') && typeof cur === 'string' && cur.length > 0) continue;
      if (key === 'captureContent' && cur !== undefined) continue;
      await cfg.update(key, desired[key], vscode.ConfigurationTarget.Global);
      changed.push(key);
    }
  }

  if (changed.length) {
    log(`Copilot Chat OTel settings updated: ${changed.join(', ')}`);
  }

  return {
    managed: manage,
    enabled: cfg.get('enabled') === true,
    exporterType: String(cfg.get('exporterType') || ''),
    outfile: String(cfg.get('outfile') || ''),
    captureContent: cfg.get('captureContent') === true,
    changed
  };
}

module.exports = { ensureOtelSettings, SECTION };
