# Embedded AI

Embedded AI lets OsWL run a local LLM out of the box — no cloud account, no API key, and **nothing leaves the machine**. It starts a bundled [llama.cpp](https://github.com/ggml-org/llama.cpp) `llama-server` as a sidecar process and exposes an **OpenAI-compatible** endpoint (`http://127.0.0.1:<port>/v1`), which is registered as the **LOCAL** AI provider. CVE triage summaries and license insights then run entirely on this machine.

---

## How It Works

* OsWL launches `llama-server` from a **model directory** (default `./embedded-ai`) and waits until it answers `/health`. If no `.gguf` is present yet, OsWL starts downloading the default Qwen3 model in the background shortly after boot (one-time, ~1.2 GB) — so by the time you visit Settings and click **Start**, the model is often already there or partway through downloading, instead of the download starting only once you click. Set `oswl.ai.embedded.auto-download-on-boot=false` to go back to download-on-click-Start only. On an **air-gapped** machine (`oswl.airgapped.enabled=true`), this background download never runs — place a `.gguf` file there yourself beforehand instead (see [Requirements & Directory Layout](#requirements--directory-layout)).
* The server binds **localhost only** (`127.0.0.1`) — it is never reachable from other machines.
* On a successful start, OsWL saves the endpoint as the **LOCAL** provider and activates it (any other active provider is deactivated, as only one provider is active at a time).
* Stopping Embedded AI also deactivates the LOCAL provider so AI calls do not fail against a dead endpoint.
* The server launches with reasoning disabled (`--reasoning-budget` 0) and no web UI (`--no-webui`). By default it also tries to offload model layers to the GPU (`-ngl 999`) — if the GPU launch fails (no GPU build, not enough VRAM), OsWL automatically retries the same model CPU-only before giving up on it.
* Concurrent AI calls are served in parallel instead of queuing one by one: the server starts with `--parallel 4 --cont-batching`, flash attention (`-fa`) and prefix-cache reuse (`--cache-reuse 256`) by default, and OsWL itself runs its independent AI calls concurrently (up to `oswl.ai.enrichment.max-parallel-calls`, default `3`).
* Batch prompts sent to the LOCAL provider use a simplified JSON schema variant (`oswl.ai.enrichment.local-simple-schema`, default `true`) — a small local model follows a simple output schema much more reliably than the full one meant for larger cloud models.
* CVE and license summaries are cached by a hash of the input context, so unchanged components are not re-asked on the next scan.
* Posture, security-trend, license-trend, and version-diff insights are generated in a single combined call (`insights.combined`) instead of four separate calls, reducing fixed overhead.

> Required permission: `SETTINGS_AI_MANAGE` or System Admin — same as the other AI settings.

---

## Requirements & Directory Layout

The model directory needs the server binary; a `.gguf` model is fetched automatically the
first time you click **Start** if none is present yet:

```
embedded-ai/
  llama-server(.exe)            — llama.cpp server binary (you provide this)
  qwen3-1.7b-q4_k_m.gguf        — default model (Apache 2.0) — auto-downloaded on first Start
```

With the directory empty, OsWL downloads Qwen3-1.7B (~1.2 GB) straight into it — starting
automatically shortly after boot, or immediately on clicking **Start** if it hasn't finished
yet — verifies the SHA256 checksum, and only then launches the sidecar. The card shows live
download progress, and the whole thing runs from just `java -jar app.jar`, no separate
script or build step needed. This is safe because Qwen3 is Apache 2.0 licensed (see
[THIRD_PARTY_LICENSES.md](../THIRD_PARTY_LICENSES.md#qwen3-17b-gguf)) — bundling/fetching it
on the user's behalf carries no extra redistribution obligation. The model is downloaded from
the upstream [Hugging Face repository](https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF) by
default; point `OSWL_EMBEDDED_DEFAULT_MODEL_URL` at a byte-identical self-hosted mirror if you
would rather not depend on a third-party host.

Treat the default-model URL, SHA256, and size settings as a matched set: if you override the
URL, you must also update the SHA256 and size to match, or every download will fail checksum
verification.

You can drop in any other `.gguf` model yourself — OsWL picks up every `.gguf` file placed
directly in this directory, not just the default Qwen3 one. Check the model's own license
before redistributing or sharing it further; only Qwen3 is bundled/auto-fetched by OsWL.

| Item | Where OsWL looks |
|---|---|
| Server binary | `<dir>/llama-server(.exe)`, then `<dir>/bin/`, then the system `PATH` ([llama.cpp releases](https://github.com/ggml-org/llama.cpp/releases)) — **not** auto-downloaded, place it yourself |
| Models | Every `.gguf` file directly inside the directory |

Configuration defaults (a folder saved in the UI takes precedence over `dir`):

| Config key | Env var | Default | Description |
|---|---|---|---|
| `oswl.ai.embedded.dir` | `OSWL_EMBEDDED_AI_DIR` | `embedded-ai` | Model directory (relative to the working directory) |
| `oswl.ai.embedded.port` | `OSWL_EMBEDDED_AI_PORT` | `11435` | localhost port for the sidecar |
| `oswl.ai.embedded.context-size` | `OSWL_EMBEDDED_AI_CONTEXT` | `8192` | Context window passed to `llama-server -c` — the **total** context, divided across the parallel slots |
| `oswl.ai.embedded.gpu-layers` | `OSWL_EMBEDDED_AI_GPU_LAYERS` | `-1` | GPU layers to offload (`-ngl`): `-1` = as many as the build supports, `0` = CPU only, a positive number pins an explicit layer count. A failed GPU start auto-retries the same model CPU-only |
| `oswl.ai.embedded.threads` | `OSWL_EMBEDDED_AI_THREADS` | `0` | Thread count (`-t`); `0` = let llama.cpp auto-detect |
| `oswl.ai.embedded.parallel-slots` | `OSWL_EMBEDDED_AI_PARALLEL` | `4` | `>1` adds `--parallel N --cont-batching` so concurrent AI calls aren't serialized on the server. Each slot gets `context-size / N` — a warning is logged when that drops below 2048 |
| `oswl.ai.embedded.flash-attn` | `OSWL_EMBEDDED_AI_FLASH_ATTN` | `true` | Flash attention (`-fa`) |
| `oswl.ai.embedded.cache-reuse` | `OSWL_EMBEDDED_AI_CACHE_REUSE` | `256` | `--cache-reuse N` for prefix-cache reuse across calls; `<=0` disables |
| `oswl.ai.embedded.extra-args` | `OSWL_EMBEDDED_AI_EXTRA_ARGS` | (empty) | Extra llama-server CLI args, appended verbatim — server config only, never taken from request input |
| `oswl.ai.embedded.startup-timeout-seconds` | `OSWL_EMBEDDED_AI_STARTUP_TIMEOUT_SEC` | `120` | How long each model candidate gets per launch attempt to become healthy |
| `oswl.ai.embedded.default-model-url` | `OSWL_EMBEDDED_DEFAULT_MODEL_URL` | Upstream Hugging Face `ggml-org/Qwen3-1.7B-GGUF` asset | Primary download source for the default Qwen3 model |
| `oswl.ai.embedded.default-model-sha256` | `OSWL_EMBEDDED_DEFAULT_MODEL_SHA256` | (see THIRD_PARTY_LICENSES.md) | Expected SHA256 — always change together with the URL |
| `oswl.ai.embedded.default-model-size-bytes` | `OSWL_EMBEDDED_DEFAULT_MODEL_SIZE_BYTES` | `1282439264` | Expected size, used to pre-fill the download progress bar — part of the same matched set as URL/SHA256 |
| `oswl.ai.embedded.fallback-model-url` | `OSWL_EMBEDDED_FALLBACK_MODEL_URL` | (empty) | Retried once if the primary URL fails; set this to the upstream URL when you override the primary with a self-hosted mirror |
| `oswl.ai.embedded.auto-download-on-boot` | `OSWL_EMBEDDED_AUTO_DOWNLOAD` | `true` | Prefetch the default model in the background on boot; never runs when `oswl.airgapped.enabled=true` |

---

## Starting and Stopping

Open **Settings → AI** (`/settings?tab=ai&section=provider`) and use the **Embedded AI (built-in local model)** card:

1. Check the status line — **Running** / **Stopped**, plus a note when the binary is missing.
2. Click **Start**. If the directory has no model yet, a progress bar shows the Qwen3
   download (~1.2 GB — can take a few minutes depending on connection speed) before the
   server launches; otherwise model loading alone can take up to a minute.
3. Once running, the card shows the **Active** model and the endpoint is live as the LOCAL provider.
4. Click **Stop** to shut the sidecar down. (This does not delete the downloaded model —
   the next Start reuses it instantly.)

The card also shows the folder in use, all detected `.gguf` files, and the last start error (if any) in red.

---

## Switching Models

You are not limited to the default model — any llama.cpp-compatible `.gguf` works:

1. **Download** a quantized GGUF model (e.g. from [Hugging Face](https://huggingface.co/models?library=gguf)).
2. **Place** the `.gguf` file in the model folder shown on the card.
3. **Pick it** in the **Model** dropdown (the list is refreshed from the folder) and click **Save**.
4. Click **Start** — the selected model is tried first. The model choice applies on the next start; switching models while running is not possible (the dropdown is disabled).

The **Auto (preference order)** option tries, in order: the model saved in the dropdown → `qwen3…` → the first remaining `.gguf` file (alphabetical).

> For CPU-only inference, small quantized models in the **1B–4B parameter** range (Q4_K_M or similar) are recommended. Larger models need more RAM and may fail the start timeout on slow machines. The context window is `OSWL_EMBEDDED_AI_CONTEXT` (default `8192`) and is split across the parallel slots — with the default 4 slots each call gets 2048 tokens of context, so raise the context or lower `OSWL_EMBEDDED_AI_PARALLEL` if you need longer per-call contexts.

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

A single start click can try several models. Candidates are attempted in order — the requested/saved model first, then the built-in preference order, then any remaining `.gguf` — and each candidate gets up to **120 seconds** per launch attempt (`oswl.ai.embedded.startup-timeout-seconds`) to become healthy. With GPU offload enabled (the default), a candidate that fails its GPU launch is first retried CPU-only; a model that still crashes or times out is skipped in favor of the next one.

When OsWL ends up running a model that is **not** the first choice, the card shows an amber **"Started with fallback model"** badge next to the model selector. This usually means the preferred model failed to load (too large, corrupted download) — the Active model line tells you what is actually running.

---

## Status and Logs

`GET /api/settings/ai/embedded` reports `running`, `external`, `binaryFound`, `activeModel`, `fallbackUsed`, `lastError`, `availableModels`, `modelsDir`, `baseUrl`, and (while a default-model download triggered by Start is in flight) `downloading`, `downloadedBytes`, `downloadTotalBytes`.

`external` is `true` when something already answers `/health` on the configured port that OsWL did not start itself (a manually launched `llama-server`, or one orphaned by a previous OsWL process/crash). `running` stays `true` in this case — the endpoint is genuinely usable as the LOCAL provider — but clicking **Stop** cannot kill a process OsWL doesn't own; it leaves it running and the status keeps reporting `external: true`.

`POST /api/settings/ai/embedded/start` returns immediately (`success: true`, `downloading: true`) when it kicks off the Qwen3 download instead of waiting for it — the settings page polls status for progress and the eventual `running`/`lastError` outcome. The download itself runs on the server independent of any browser session, so refreshing the page (or closing it) doesn't cancel it; reopening the page resumes showing progress.

`llama-server` writes its own stdout/stderr to **`<dir>/llama-server.log`**. When a start fails, the last lines of that log are included in `lastError` and shown in red on the card — check the full file for details.

---

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| "llama-server binary not found" | Put `llama-server(.exe)` in the folder shown on the card (or its `bin/` subfolder, or on `PATH`) — this is never auto-downloaded |
| No model yet, and Start doesn't seem to do anything | Check for internet access — the default-model download needs it once. On an air-gapped machine, place a `.gguf` file directly inside the model folder yourself instead |
| "Model download failed" / checksum mismatch | Network interrupted mid-download or a corrupted transfer — the partial file is deleted automatically; click **Start** again to retry |
| `failed to open GGUF file` in the log | The folder in settings does not match where the model actually is — check the **Folder** field and that the file name matches the dropdown entry |
| "did not become healthy within 120s" | Slow machine or oversized model — try a smaller quantization (e.g. a Q4_K_M `.gguf` in the 1B–2B parameter range), or raise `OSWL_EMBEDDED_AI_STARTUP_TIMEOUT_SEC`. A `(GPU)` failure is retried CPU-only automatically, so this only persists when the CPU retry fails too |
| `slotContext … is below 2048` warning in the log | Too many parallel slots for the context size — raise `OSWL_EMBEDDED_AI_CONTEXT` or lower `OSWL_EMBEDDED_AI_PARALLEL` |
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
