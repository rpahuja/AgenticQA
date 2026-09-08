'use strict';
/**
 * The @agenticQA chat participant.
 *
 * Two modes:
 *   1. ORCHESTRATOR (RAG): when agenticqa-orchestrator.jar is available, the
 *      participant runs the Java orchestrator against the repository the user
 *      chose (--repo in the prompt, the agenticqa.orchestrator.repoPath
 *      setting, or the workspace open in this window). The orchestrator
 *      auto-discovers the repo layout, retrieves the relevant existing tests
 *      (RAG), calls the model once and writes the generated test files into
 *      the repository. Token usage is printed by the orchestrator and
 *      reported to the collector as a classification mark.
 *   2. FALLBACK (phase 1 behaviour): without the jar, the participant simply
 *      routes the QA's prompt to the selected Copilot model, counts its own
 *      input/output tokens and reports a classification mark.
 *
 * Both modes report a "classification mark" so the collector can separate
 * With-AgenticQA conversations from all other chat usage.
 */
const vscode = require('vscode');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const { reportMark } = require('./reporter');

function registerParticipant({ context: extensionContext, dataHome, getServerUrl, log }) {
  const participant = vscode.chat.createChatParticipant('agenticqa', handler);
  extensionContext.subscriptions.push(participant);
  return participant;

  async function handler(request, context, stream, token) {
    const startedAt = Date.now();
    const requestId = 'aqa-' + crypto.randomBytes(8).toString('hex');
    const showMeter = vscode.workspace.getConfiguration('agenticqa').get('showTokenMeterInChat', true);
    const modelLabel = request.model ? (request.model.name || request.model.id) : 'selected model';

    // Orchestrator mode: run the Java RAG orchestrator against the target repo.
    const jarPath = resolveJarPath(extensionContext);
    if (jarPath) {
      await runOrchestrator({ request, stream, token, jarPath, startedAt, requestId, showMeter, dataHome, getServerUrl, log });
      return;
    }

    let text = '';
    let inputTokens = 0;
    let outputTokens = 0;
    let errorMessage = '';

    try {
      stream.progress(`AgenticQA orchestrator · ${modelLabel} …`);
      const model = await pickModel(request, token);
      if (!model) {
        errorMessage = 'No Copilot language model is available for this request.';
      } else {
        const messages = [vscode.LanguageModelChatMessage.User(request.prompt)];
        inputTokens = await countTokensSafe(model, messages, token);
        const response = await model.sendRequest(messages, {}, token);
        for await (const fragment of response.text) {
          text += fragment;
          stream.markdown(fragment);
        }
        outputTokens = await countTokensSafe(model, text, token);
      }
    } catch (err) {
      if (err && (err.name === 'CancellationError' || err instanceof vscode.CancellationError)) throw err;
      errorMessage = err && err.message ? String(err.message) : String(err);
    }

    if (errorMessage) {
      stream.markdown(`**AgenticQA:** the orchestrator could not process the request.\n\n> ${errorMessage}`);
    } else if (showMeter && inputTokens > 0) {
      const url = getServerUrl ? getServerUrl() : '';
      stream.markdown(
        `\n\n---\n⚡ *AgenticQA token meter — input ≈ ${formatNum(inputTokens)} · output ≈ ${formatNum(outputTokens)}*` +
        (url ? ` · [open dashboard](${url})` : '')
      );
    }

    // Classification mark — never blocks the response; file fallback included.
    reportMark({
      dataHome,
      serverUrl: getServerUrl ? getServerUrl() : '',
      mark: {
        requestId,
        model: modelLabel,
        startedAt,
        endedAt: Date.now(),
        inputTokens,
        outputTokens
      },
      log
    }).catch(() => {});
  }
}

/**
 * Where the orchestrator jar lives: the configured path, or the jar bundled
 * with the extension (orchestrator/agenticqa-orchestrator.jar).
 */
function resolveJarPath(extensionContext) {
  const cfg = vscode.workspace.getConfiguration('agenticqa');
  let jarPath = String(cfg.get('orchestrator.jarPath', '') || '').trim();
  if (!jarPath) {
    jarPath = path.join(extensionContext.extensionUri.fsPath, 'orchestrator', 'agenticqa-orchestrator.jar');
  }
  return jarPath && fs.existsSync(jarPath) ? jarPath : '';
}

/** Pull an optional "--repo <path>" out of the prompt and strip it. */
function extractRepo(prompt) {
  const m = String(prompt || '').match(/--repo\s+"([^"]+)"|--repo\s+(\S+)/);
  const repo = m ? String(m[1] || m[2] || '').trim() : '';
  const promptText = String(prompt || '')
    .replace(/--repo\s+("[^"]+"|\S+)/, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return { repo, prompt: promptText };
}

/**
 * Run the Java orchestrator against the repository the user chose and stream
 * its output back into the chat.
 */
async function runOrchestrator({ request, stream, token, jarPath, startedAt, requestId, showMeter, dataHome, getServerUrl, log }) {
  // Project selection: --repo in the prompt, OR the orchestrator's own
  // agenticqa.properties (agenticqa.orchestrator.repoPath). The chat side
  // stays dumb - when --repo is absent it simply passes nothing.
  let { repo, prompt } = extractRepo(request.prompt);

  if (repo && !fs.existsSync(repo)) {
    stream.markdown(`**AgenticQA orchestrator:** repository not found: \`${repo}\``);
    return;
  }

  stream.progress(repo
    ? `AgenticQA orchestrator (Java + RAG) · repo: ${repo} …`
    : 'AgenticQA orchestrator (Java + RAG) · project from agenticqa.properties …');

  const args = ['-jar', jarPath];
  if (repo) args.push('--repo', repo);
  const child = spawn('java', args, repo ? { cwd: repo, windowsHide: true } : { windowsHide: true });
  child.stdin.write(prompt);
  child.stdin.end();

  let out = '';
  let errText = '';
  token.onCancellationRequested(() => {
    try { child.kill(); } catch (_) { /* noop */ }
  });

  await new Promise((resolve) => {
    child.stdout.on('data', (chunk) => { out += chunk.toString(); });
    child.stderr.on('data', (chunk) => { errText += chunk.toString(); });
    child.on('error', (err) => {
      const msg = err && err.code === 'ENOENT'
        ? 'Java was not found on this machine. Install a JDK (11 or newer) to use the orchestrator.'
        : String((err && err.message) || err);
      stream.markdown(`**AgenticQA orchestrator failed:** ${msg}`);
      resolve();
    });
    child.on('close', () => resolve());
  });

  // Parse the orchestrator output protocol.
  const wroteFiles = [];
  let inputTokens = 0;
  let outputTokens = 0;
  for (const line of out.split(/\r?\n/)) {
    if (line.startsWith('AGENTICQA_WROTE ')) {
      wroteFiles.push(line.slice('AGENTICQA_WROTE '.length).trim());
    }
    const m = line.match(/^AGENTICQA_TOKENS prompt=(\d+) completion=(\d+) total=(\d+)/);
    if (m) {
      inputTokens = Number(m[1]);
      outputTokens = Number(m[2]);
    }
  }
  const body = out
    .split(/\r?\n/)
    .filter((l) => l.trim() && !l.startsWith('AGENTICQA_'))
    .join('\n')
    .trim();

  if (body) stream.markdown('```\n' + body + '\n```');
  if (wroteFiles.length) {
    stream.markdown('**Generated files:**\n' + wroteFiles.map((f) => `- \`${f}\``).join('\n'));
  }
  if (errText.trim()) {
    stream.markdown(`\n**Orchestrator stderr:**\n\`\`\`\n${errText.trim()}\n\`\`\``);
  }
  if (showMeter && inputTokens > 0) {
    const url = getServerUrl ? getServerUrl() : '';
    stream.markdown(
      `\n\n---\n⚡ *AgenticQA token meter — input ≈ ${formatNum(inputTokens)} · output ≈ ${formatNum(outputTokens)}*` +
      (url ? ` · [open dashboard](${url})` : '')
    );
  }

  if (inputTokens > 0 || outputTokens > 0) {
    reportMark({
      dataHome,
      serverUrl: getServerUrl ? getServerUrl() : '',
      mark: {
        requestId,
        model: 'agenticqa-orchestrator (Java, RAG)',
        startedAt,
        endedAt: Date.now(),
        inputTokens,
        outputTokens
      },
      log
    }).catch(() => {});
  }
}

async function pickModel(request, token) {
  const models = await vscode.lm.selectChatModels({});
  if (!models.length) return null;
  const wantedId = request.model && request.model.id;
  return (
    models.find((m) => wantedId && m.id === wantedId) ||
    models.find((m) => request.model && m.vendor === request.model.vendor && m.family === request.model.family) ||
    models.find((m) => m.vendor === 'copilot') ||
    models[0]
  );
}

async function countTokensSafe(model, textOrMessages, token) {
  try {
    if (Array.isArray(textOrMessages)) {
      let total = 0;
      for (const m of textOrMessages) total += await model.countTokens(m, token);
      return total;
    }
    return await model.countTokens(textOrMessages, token);
  } catch (_) {
    return 0;
  }
}

function formatNum(n) {
  return Number(n || 0).toLocaleString('en-US');
}

module.exports = { registerParticipant };
