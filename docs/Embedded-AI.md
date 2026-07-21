# Embedded AI

Embedded AI lets OsWL run a local LLM out of the box — no cloud account, no API key, and **nothing leaves the machine**. It starts a bundled [llama.cpp](https://github.com/ggml-org/llama.cpp) `llama-server` as a sidecar process and exposes an **OpenAI-compatible** endpoint (`http://127.0.0.1:<port>/v1`), which is registered as the **LOCAL** AI provider. CVE triage summaries and license insights then run entirely on your own CPU.

---

## How It Works

* OsWL launches `llama-server` from a **model directory** (default `./embedded-ai`) and waits until it answers `/health`.
* The server binds **localhost only** (`127.0.0.1`) — it is never reachable from other machines.
* On a successful start, OsWL saves the endpoint as the **LOCAL** provider and activates it (any other active provider is deactivated, as only one provider is active at a time).
* Stopping Embedded AI also deactivates the LOCAL provider so AI calls do not fail against a dead endpoint.
* The server runs CPU-only inference with reasoning disabled (`--reasoning-budget 0`) and no web UI (`--no-webui`).

> Required permission: `SETTINGS_AI_MANAGE` or System Admin — same as the other AI settings.

---

## Requirements & Directory Layout

The model directory must contain the server binary and at least one `.gguf` model:

```
embedded-ai/
  llama-server(.exe)            — llama.cpp server binary
  qwen3-1.7b-q4_k_m.gguf        — preferred bundled model
  gemma-3-1b-it-Q4_K_M.gguf     — low-spec bundled fallback
```

| Item | Where OsWL looks |
|---|---|
| Server binary | `<dir>/llama-server(.exe)`, then `<dir>/bin/`, then the system `PATH` ([llama.cpp releases](https://github.com/ggml-org/llama.cpp/releases)) |
| Models | Every `.gguf` file directly inside the directory |

Configuration defaults (a folder saved in the UI takes precedence over `dir`):

| Config key | Env var | Default | Description |
|---|---|---|---|
| `oswl.ai.embedded.dir` | `OSWL_EMBEDDED_AI_DIR` | `embedded-ai` | Model directory (relative to the working directory) |
| `oswl.ai.embedded.port` | `OSWL_EMBEDDED_AI_PORT` | `11435` | localhost port for the sidecar |
| `oswl.ai.embedded.context-size` | `OSWL_EMBEDDED_AI_CONTEXT` | `4096` | Context window passed to `llama-server -c` |

---

## Starting and Stopping

Open **Settings → AI** and use the **Embedded AI (built-in local model)** card:

1. Check the status line — **Running** / **Stopped**, plus warnings when the binary or a model is missing.
2. Click **Start**. Model loading can take a minute; the button shows progress.
3. Once running, the card shows the **Active** model and the endpoint is live as the LOCAL provider.
4. Click **Stop** to shut the sidecar down.

The card also shows the folder in use, all detected `.gguf` files, and the last start error (if any) in red.

---

## Switching Models

You are not limited to the bundled models — any llama.cpp-compatible `.gguf` works:

1. **Download** a quantized GGUF model (e.g. from [Hugging Face](https://huggingface.co/models?library=gguf)).
2. **Place** the `.gguf` file in the model folder shown on the card.
3. **Pick it** in the **Model** dropdown (the list is refreshed from the folder) and click **Save**.
4. Click **Start** — the selected model is tried first. The model choice applies on the next start; switching models while running is not possible (the dropdown is disabled).

The **Auto (preference order)** option tries, in order: the model saved in the dropdown → `qwen3…` → `gemma-3-1b…` → `gemma3…` → the first remaining `.gguf` file (alphabetical).

> For CPU-only inference, small quantized models in the **1B–4B parameter** range (Q4_K_M or similar) are recommended. Larger models need more RAM and may fail the start timeout on slow machines. The context window is fixed by `OSWL_EMBEDDED_AI_CONTEXT` (default `4096`).

---

## Changing the Model Folder

Two ways to point OsWL at a different directory:

| Method | Scope |
|---|---|
| **Folder** input + **Save** on the Embedded AI card | Persisted in the database (`ai_preferences.embedded_dir` / `embedded_model`); wins over the default |
| `OSWL_EMBEDDED_AI_DIR` env / `oswl.ai.embedded.dir` yaml | Default used when no folder is saved in the UI |

Rules enforced by `PUT /api/settings/ai/embedded/config`:

* The folder must **already exist** — otherwise the save is rejected (`400`, "Folder not found or not a directory").
* Changing the folder **while the sidecar is running** stops it first (a running `llama-server` holds file locks on the old directory) and deactivates the LOCAL provider. Start it again from the new folder.
* Clearing the folder input (blank) removes the override and falls back to the configured default.

---

## Automatic Fallback

A single start click can try several models. Candidates are attempted in order — the requested/saved model first, then the built-in preference order, then any remaining `.gguf` — and each candidate gets up to **90 seconds** to become healthy. A model that crashes or times out is skipped in favor of the next one.

When OsWL ends up running a model that is **not** the first choice, the card shows an amber **"Started with fallback model"** badge next to the model selector. This usually means the preferred model failed to load (too large, corrupted download) — the Active model line tells you what is actually running.

---

## Status and Logs

`GET /api/settings/ai/embedded` reports `running`, `external`, `binaryFound`, `activeModel`, `fallbackUsed`, `lastError`, `availableModels`, `modelsDir`, and `baseUrl`.

`external` is `true` when something already answers `/health` on the configured port that OsWL did not start itself (a manually launched `llama-server`, or one orphaned by a previous OsWL process/crash). `running` stays `true` in this case — the endpoint is genuinely usable as the LOCAL provider — but clicking **Stop** cannot kill a process OsWL doesn't own; it leaves it running and the status keeps reporting `external: true`.

`llama-server` writes its own stdout/stderr to **`<dir>/llama-server.log`**. When a start fails, the last lines of that log are included in `lastError` and shown in red on the card — check the full file for details.

---

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| "llama-server binary not found" | Put `llama-server(.exe)` in the folder shown on the card (or its `bin/` subfolder, or on `PATH`) |
| "No .gguf model found" | Place at least one `.gguf` file directly inside the model folder |
| `failed to open GGUF file` in the log | The folder in settings does not match where the model actually is — check the **Folder** field and that the file name matches the dropdown entry |
| "did not become healthy within 90s" | Slow machine or oversized model — try a smaller quantization (e.g. the bundled `gemma-3-1b-it-Q4_K_M.gguf`) |
| Port already in use | Another process (or a manually started `llama-server`) occupies the port — stop it or set `OSWL_EMBEDDED_AI_PORT`. A healthy server already listening on the port counts as "running" and is flagged `external` in the status response |
| Clicking **Stop** doesn't turn the card off | The running server is `external` (not started by this OsWL instance) — stop the process yourself (or restart the machine/container it runs in), OsWL cannot terminate it |
| "Folder not found or not a directory" on Save | Create the directory first; the save only accepts existing folders |

---

## Security & Privacy

* The sidecar binds to `127.0.0.1` only and requires **no API key** — nothing is exposed to the network.
* The llama.cpp **CORS warning** in `llama-server.log` is expected and harmless: the server only listens on localhost.
* No telemetry: prompts, code snippets, and CVE data sent to the model never leave the machine.

---

## REST API summary

See [API Reference — AI](API-Reference.md#ai). Interactive schemas: Swagger UI (`local` profile).
