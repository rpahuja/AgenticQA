'use strict';
/**
 * AgenticQA — phase 1: token-usage telemetry for GitHub Copilot Chat.
 *
 * Activation sequence:
 *   1. Ensure Copilot Chat's built-in OTel token export writes to a local file.
 *   2. Start the collector (ingest + HTTP API + dashboard) on 127.0.0.1;
 *      leader/follower election across VS Code windows.
 *   3. Register the @agenticQA chat participant.
 *   4. Register commands: open dashboard / show capture status.
 *
 * Zero third-party dependencies: Node's built-in modules only, running on the
 * Node.js runtime that ships inside VS Code.
 */
const vscode = require('vscode');
const path = require('path');
const fs = require('fs');
const { resolveDataHome, layout } = require('./core/paths');
const { Ingestor } = require('./core/ingest');
const { Leader, probeHealth } = require('./core/leader');
const { ensureOtelSettings } = require('./settings');
const { registerParticipant } = require('./participant');

let output;
let leader = null;
let otelInfo = null;
let dataHome = '';

function log(msg) {
  try { output.appendLine(`[${new Date().toISOString()}] ${msg}`); } catch (_) { /* ignore */ }
}

async function activate(context) {
  output = vscode.window.createOutputChannel('AgenticQA');
  log('AgenticQA activating…');

  dataHome = resolveDataHome(vscode.workspace.getConfiguration('agenticqa').get('dataHome', ''));
  for (const dir of [layout(dataHome).otelDir, layout(dataHome).dataDir, layout(dataHome).logsDir]) {
    fs.mkdirSync(dir, { recursive: true });
  }
  log(`Data home: ${dataHome}`);

  // 1) Built-in Copilot Chat token export (setting, not a process).
  otelInfo = await ensureOtelSettings({ dataHome, log });
  log(`OTel export: enabled=${otelInfo.enabled}, exporter=${otelInfo.exporterType}, file=${otelInfo.outfile}`);

  // 2) Collector: leader/follower HTTP server + ingestor.
  const ingestor = new Ingestor(dataHome, log);
  leader = new Leader({
    ingestor,
    dashboardDir: path.join(context.extensionUri.fsPath, 'dashboard'),
    preferredPort: vscode.workspace.getConfiguration('agenticqa').get('serverPort', 8787),
    version: (context.extension.packageJSON && context.extension.packageJSON.version) || '0.0.0',
    log,
    getStatus: () => ({ otel: otelInfo })
  });
  const status = await leader.start();
  await context.globalState.update('agenticqa.serverPort', leader.port || 0);
  log(`Collector: ${status.mode} @ ${status.url || '(no port)'}`);

  // 3) @agenticQA participant.
  registerParticipant({ context, dataHome, getServerUrl: () => leader.url(), log });

  // 4) Commands.
  context.subscriptions.push(
    vscode.commands.registerCommand('agenticqa.openDashboard', async () => {
      const url = leader.url() || `http://127.0.0.1:${await context.globalState.get('agenticqa.serverPort', 8787)}`;
      const health = await probeHealth(url.replace(/\/$/, ''));
      if (health && health.agent === 'agenticqa') {
        vscode.env.openExternal(vscode.Uri.parse(url));
      } else {
        vscode.window.showWarningMessage(`AgenticQA collector is not running yet (${leader.mode}). Wait a few seconds and retry.`);
      }
    }),
    vscode.commands.registerCommand('agenticqa.showStatus', async () => {
      const items = buildStatusItems();
      const pick = await vscode.window.showQuickPick(
        items.map((i) => ({ label: i.label, description: i.description })),
        { title: 'AgenticQA — capture status' }
      );
      if (pick && pick.label.startsWith('Dashboard')) {
        vscode.env.openExternal(vscode.Uri.parse(leader.url() || 'http://127.0.0.1:8787'));
      }
    })
  );

  log('AgenticQA ready.');

  if (otelInfo.changed && otelInfo.changed.length) {
    const action = await vscode.window.showInformationMessage(
      'AgenticQA enabled automatic Copilot token capture. Reload the window if the dashboard stays empty.',
      'Reload Now'
    );
    if (action === 'Reload Now') {
      await vscode.commands.executeCommand('workbench.action.reloadWindow');
    }
  }
}

function buildStatusItems() {
  const s = leader && leader.ingestor ? leader.ingestor.status() : null;
  const items = [];
  items.push({ label: `Collector: ${leader.mode}`, description: leader.url() || 'no port' });
  if (s) {
    items.push({ label: 'LLM events ingested', description: String(s.eventsTotal) });
    items.push({ label: 'Sessions tracked', description: `${s.sessionsTracked} (${s.withAgenticqaSessions} with AgenticQA)` });
    items.push({ label: 'Capture file', description: `${s.otelFileExists ? 'exists (' + s.otelFileBytes + ' bytes)' : 'not created yet'} — ${s.otelFile}` });
  }
  if (otelInfo) {
    items.push({ label: 'Copilot token export', description: `enabled=${otelInfo.enabled} · ${otelInfo.exporterType} · ${otelInfo.outfile}` });
  }
  items.push({ label: `Dashboard: ${leader.url() || 'http://127.0.0.1:8787'}`, description: 'open in browser' });
  return items;
}

async function deactivate() {
  if (leader) await leader.stop();
  log('AgenticQA deactivated.');
}

module.exports = { activate, deactivate };
