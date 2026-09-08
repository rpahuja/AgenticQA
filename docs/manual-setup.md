# AgenticQA — Manual Setup Guide (for QA)

Everything in this guide is done **by hand** — no scripts, no installers, no admin rights.
Follow the steps in order; they take about 10 minutes per machine.

## 1. Before you start

On the client machine you need:

- **VS Code 1.119 or newer**, signed in to **GitHub Copilot** with working Copilot Chat.
- The **AgenticQA extension folder** (`agenticqa-vscode`) — usually delivered as a `.zip`.
  Unzip it somewhere convenient (e.g. the Desktop) and keep the resulting `agenticqa-vscode`
  folder ready for step 4.

The setup makes three reversible changes to the machine:

1. creates a data folder `~/.agenticqa` (Windows: `C:\Users\<you>\.agenticqa`),
2. adds **four settings** to VS Code so Copilot Chat writes a local token journal
   (prompt/response content is **never** stored),
3. places the AgenticQA extension folder into VS Code's extensions directory.

## 2. Create the data folder

### Windows

1. Open File Explorer and navigate to your user profile: `C:\Users\<YourUserName>\`.
2. Create a new folder named `.agenticqa`. (Explorer may warn about the leading dot —
   keep the name.)
3. Inside `.agenticqa`, create three subfolders: `otel`, `data`, `logs`.

Result:

```
C:\Users\<YourUserName>\.agenticqa\
├── otel\
├── data\
└── logs\
```

### macOS / Linux

Create the same three folders under your home directory, e.g. in a terminal:

```
mkdir -p ~/.agenticqa/otel ~/.agenticqa/data ~/.agenticqa/logs
```

(On macOS, dot-folders are hidden in Finder — press `Cmd+Shift+.` to show them,
or just use the terminal command.)

## 3. Turn on the token journal (VS Code settings)

1. Open VS Code.
2. Press `Ctrl+Shift+P` (macOS: `Cmd+Shift+P`), type `Open User Settings (JSON)`,
   and select **Preferences: Open User Settings (JSON)**.
3. A `settings.json` file opens. Add the four settings below.

   - **If the file only contains `{}`** (new machine), replace it with:

     ```json
     {
         "github.copilot.chat.otel.enabled": true,
         "github.copilot.chat.otel.exporterType": "file",
         "github.copilot.chat.otel.outfile": "C:/Users/<YourUserName>/.agenticqa/otel/copilot-otel.jsonl",
         "github.copilot.chat.otel.captureContent": false
     }
     ```

   - **If the file already has settings**, paste the four lines right after the
     opening `{`. Every entry must be followed by a comma **except the very last one
     before the closing `}`**. If any of the four keys already exists, change its
     value instead of adding a duplicate.

4. In the `outfile` value, replace `<YourUserName>` with the real user folder name.
   **Use forward slashes `/` even on Windows**, and make sure the path matches the
   folder from step 2 exactly.

   | OS | `outfile` value |
   |---|---|
   | Windows | `C:/Users/<YourUserName>/.agenticqa/otel/copilot-otel.jsonl` |
   | macOS | `/Users/<YourUserName>/.agenticqa/otel/copilot-otel.jsonl` |
   | Linux | `/home/<YourUserName>/.agenticqa/otel/copilot-otel.jsonl` |

5. Save (`Ctrl+S` / `Cmd+S`). VS Code underlines JSON mistakes in red — the settings
   only apply if the file is valid JSON.

What the four keys do:

| Key | Value | Meaning |
|---|---|---|
| `github.copilot.chat.otel.enabled` | `true` | Switches on Copilot Chat's built-in telemetry export. |
| `github.copilot.chat.otel.exporterType` | `"file"` | Writes telemetry to a local file (no network backend). |
| `github.copilot.chat.otel.outfile` | journal path | The exact file Copilot Chat writes every LLM call into. |
| `github.copilot.chat.otel.captureContent` | `false` | Token counts and metadata only. **Never set to `true`** — that would write prompt/response text to disk. |

> **Tip:** keep a copy of the original `settings.json` (paste it into a scratch file)
> so you can restore your previous settings during uninstall.
>
> If `settings.json` shows a lock icon or won't save, your company policy manages it —
> contact IT (see FAQ below).

## 4. Install the AgenticQA extension

### Option A — folder copy (works everywhere, no admin rights)

1. Take the unzipped `agenticqa-vscode` folder and **rename it to**
   `agenticqa.agenticqa-vscode-0.1.0`.
   The last part (`0.1.0`) is the version and **must match the `"version"` field** in
   the folder's `package.json` — open that file and check. If it says a different
   version, use that number.
2. Copy or move the renamed folder into VS Code's extensions directory:
   - Windows: `C:\Users\<YourUserName>\.vscode\extensions\`
   - macOS / Linux: `~/.vscode/extensions/`
   Create the directory if it doesn't exist.
3. Check the result: the file `package.json` must sit **directly inside** the new folder:

   ```
   C:\Users\<YourUserName>\.vscode\extensions\agenticqa.agenticqa-vscode-0.1.0\package.json
   ```

4. If an older AgenticQA folder (different version) already exists in `extensions`,
   delete it before copying the new one.

### Option B — .vsix package (if your org requires a packaged extension)

If IT policy only allows packaged/marketplace extensions, ask whoever prepares the
delivery for a `.vsix` file, then install it from the Extensions view (`Ctrl+Shift+X`) →
**… menu → Install from VSIX…** and select the file. Skip the folder copy.

### After installing

Reload VS Code: `Ctrl+Shift+P` → **Developer: Reload Window** (or close and reopen
VS Code). The extension starts automatically.

## 5. Verify (manual checklist)

1. **Settings present** — reopen **Preferences: Open User Settings (JSON)**; the four
   keys from step 3 are there and there are no red errors.
2. **Extension loaded** — Extensions view (`Ctrl+Shift+X`) lists **AgenticQA** under
   Installed.
3. **Status command** — `Ctrl+Shift+P` → **AgenticQA: Show Capture Status**. It should
   show the collector as `leader`, capture `enabled`, and the same journal path you
   configured.
4. **Journal file** — open Copilot Chat and send a plain message (e.g. `hello`). Wait
   about 10 seconds, then check that
   `C:\Users\<you>\.agenticqa\otel\copilot-otel.jsonl` exists and grows in size
   (right-click → Properties). It contains token counts and metadata only — no prompt text.
5. **Classification** —
   - a plain message (`hello`) → counts as **Without AgenticQA**;
   - a message starting with `@agenticQA` (e.g. `@agenticQA create test cases for login`)
     → counts as **With AgenticQA**.
6. **Dashboard** — `Ctrl+Shift+P` → **AgenticQA: Open Token Usage Dashboard**
   (or browse to http://127.0.0.1:8787). You should see the current month's totals,
   a daily chart, a per-model table, an **Agent turns** table and a conversation
   list — and the numbers should increase after the two chats above.
7. **Log** — View → Output → pick **AgenticQA** from the dropdown: startup and ingestion
   lines, no errors.

## 6. Optional — Java RAG orchestrator (for the POC demo)

The `@agenticQA` participant can run a **Java RAG orchestrator** that generates test
files inside a target Maven/Cucumber repository. Setup for demo machines only:

1. Install a **JDK (11 or newer)** and **Maven**.
2. Have a DeepSeek API key ready: set the environment variable
   `DEEPSEEK_API_KEY`, or put it in `agenticqa.properties` in step 4
   (environment variables win over the file).
3. Build the Java components from the AgenticQA repository root:
   ```
   cd <agenticqa-repo>
   mvn package
   ```
4. Copy the built jar and the config folder into the extension folder
   **before** installing the extension:
   ```
   copy target\agenticqa-orchestrator.jar <agenticqa-repo>\vscode-extension\agenticqa-vscode\orchestrator\
   xcopy config <agenticqa-repo>\vscode-extension\agenticqa-vscode\orchestrator\config\ /E /I
   ```
   Put the API keys into `agenticqa.properties` (or use the per-provider
   environment variables such as `DEEPSEEK_API_KEY`). Providers, models and
   the per-task routing rules are configured in the same file.
5. Reload VS Code.
6. Tell the orchestrator which project to work on: either with `--repo <path>`
   in the prompt, or set `agenticqa.orchestrator.repoPath` in
   `config/agenticqa.properties` (use forward slashes in the path).

The root `README.md` of this repository covers the build, the CLI flags and
where the RAG code lives. Note: the RAG parts (`rag/`, `SurfaceExtractor`) are
deliberately TODO stubs — the build is green, and runs with RAG on fail with a
clear `TODO` message until they are implemented.

## 7. Uninstall (manual)

1. Delete `agenticqa.agenticqa-vscode-0.1.0` from the VS Code extensions folder.
2. Remove the four `github.copilot.chat.otel.*` keys from `settings.json` (restore the
   copy you made in step 3 if you made one).
3. Reload VS Code.
4. Optional: delete `C:\Users\<you>\.agenticqa` to remove all collected data.

## FAQ

### Does token tracking survive a system restart?

Yes. Capture is a VS Code **setting**; the journal keeps growing on disk across reboots.
When VS Code opens again, AgenticQA starts automatically, ingests the backlog and serves
the dashboard. When VS Code is closed there are no chat interactions, so nothing can be
missed.

### What if AgenticQA isn't running but VS Code is?

Tokens still accumulate in the journal (written by Copilot Chat itself). When AgenticQA
starts again it ingests everything it missed — offsets and dedup prevent double counting.

### What resets on the 1st of the month?

Only the current-month view. All aggregation is bucketed by calendar month, so the
dashboard starts from zero on the 1st. Nothing is deleted; previous months stay
browsable from the month picker and in `monthly_snapshots.json`.

### Does this need admin rights or extra software?

No. Everything lives under the user profile and localhost. The collector runs inside
VS Code's built-in Node.js runtime. The only traffic is loopback (`127.0.0.1:8787`).

### Is it safe behind the corporate VPN?

Yes. The dashboard and API listen on `127.0.0.1` only; no new outbound connections are
introduced. Prompts and responses are **never** stored (`captureContent: false`); only
token counts, model names and conversation ids.

### Are my prompts stored anywhere?

No. The capture journal contains token counts and metadata only.

### Which Copilot usage is counted?

Copilot **Chat** interactions: ask, edit and agent modes, any model — whether or not
they go through `@agenticQA`. Inline code completions (ghost text) and Next Edit
Suggestions are a different pipeline and are **not** counted in phase 1.
Agent sessions that use a bring-your-own-key provider (e.g. DeepSeek) do not
produce per-call token records in Copilot Chat's telemetry; Copilot Chat emits
one turn-level usage record per turn instead. Their usage appears separately in
the dashboard under **Agent turns**. The sum of turn input + output matches the
provider's billed tokens exactly (verified against the DeepSeek usage
dashboard); each turn's input includes the context re-sent on that turn, which
the provider bills as cache-hit input. Only the cache-hit vs cache-miss split
is an estimate (within ~1% of input totals) because providers cache prefix
blocks on a best-effort basis.
### How is "With AgenticQA" decided?

"With AgenticQA" is the token usage **self-reported by the `@agenticQA` participant**
(it counts its own requests' input/output tokens). "Without AgenticQA" = total captured
− With. Sessions in the dashboard are flagged "With" when a mark overlaps their time
window.

### I have several VS Code windows open — will it break?

No. One window becomes the collector **leader** (owns ingestion and the port); the
others are followers. If the leader window closes, a follower takes over within
~30 seconds.

### The org restricts extensions — will AgenticQA be blocked?

If your org policy blocks non-marketplace extensions, the same code can be packaged as
a `.vsix` and distributed through the approved channel (Option B above). Check with IT:
search Settings for `extensions.allowed` / `extensions.blocked`; a policy lock icon
indicates org control.

### Troubleshooting

| Symptom | Check / fix |
|---|---|
| Dashboard empty after a chat | Run **AgenticQA: Show Capture Status**; reload the window once; check the `outfile` in settings matches the real journal path on disk. |
| `copilot-otel.jsonl` never appears | 1) all four keys present, valid JSON, saved; 2) the `.agenticqa\otel\` folder exists; 3) `outfile` uses forward slashes; 4) reload the window and chat again. |
| Extension missing / `@agenticQA` not available | The folder name must be exactly `agenticqa.agenticqa-vscode-<version>` with `package.json` directly inside; then reload or restart VS Code. |
| Dashboard unreachable | Only the leader window serves it — open it via the command from any window. If the port is busy, set `agenticqa.serverPort` to a free port. |
| Parse errors in Output → AgenticQA | The journal format may differ slightly; tolerant parsing covers the known variants — report the line shape if new. |
| Settings show a lock icon | Managed by org policy — contact IT (also consider the `.vsix` route). |
