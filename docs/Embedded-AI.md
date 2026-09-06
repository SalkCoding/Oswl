# Embedded AI

OsWL runs llama.cpp at `http://127.0.0.1:11435/v1`, registered as the LOCAL provider. Inference data stays on this server; model installation needs network access or offline file transfer. **Qwen3.5-2B Q4_K_M is the CPU default; Gemma 4 E2B Q4_K_M is optional.** Only one model runs at a time. The integration is text-only; no image/audio projector or GPU is required.

## Requirements & Directory Layout

| Model | Weight download | Whole-server planning minimum | Recommended |
|---|---:|---|---|
| Qwen3.5 2B Q4_K_M | 1.28 GB | 2 vCPU / 8 GB RAM | 4 vCPU / 8–16 GB RAM |
| Gemma 4 E2B Q4_K_M | 3.11 GB | 4 vCPU / 8 GB RAM | 4 vCPU / 16 GB RAM |

These are **sizing estimates, not certified performance minima**: OsWL + PostgreSQL, small occasional scans, one AI generation, 4K–8K context. Large archives, concurrent scans and database growth require additional capacity. Weight size is not peak process memory. Reserve at least 6 GB disk for these two weights, plus runtime, DB, logs and older models. Application builds are outside this estimate.

Qwen is the default because it has a smaller weight footprint for a shared CPU server, not because it always produces better answers. Validate English/Korean/Japanese quality on your own findings. Burstable vCPU counts do not guarantee sustained full-core capacity: check depleted CPU credits as well as warm-model performance. Continuous analysis may need sustained CPU capacity or a separate CPU worker, without requiring GPU hardware.

Use a CPU runtime matching the server OS/architecture. Keep companion libraries from the same release together. Local validation uses llama.cpp **b10068 (571d0d540)**; this release or a compatible newer version must support both model architectures, `--chat-template-kwargs` and `-fa on`.

```text
embedded-ai/
  llama/
    llama-server(.exe)
    ... companion libraries ...
  model/
    Qwen/Qwen3.5-2B-Q4_K_M.gguf
    Gemma/gemma-4-E2B-it-Q4_K_M.gguf
  llama-server.log
```

| Model | Bytes | SHA-256 |
|---|---:|---|
| Qwen3.5-2B Q4_K_M | 1280835840 | `aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223` |
| Gemma 4 E2B Q4_K_M | 3106738272 | `740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8` |

- [Qwen model card](https://huggingface.co/Qwen/Qwen3.5-2B)
- [Qwen GGUF, pinned revision](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/tree/f6d5376be1edb4d416d56da11e5397a961aca8ae)
- [Gemma model card](https://huggingface.co/google/gemma-4-E2B-it)
- [Gemma GGUF, pinned revision](https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/tree/0314792d7f1f7e229411f620751375812bb9faf2)
- [llama.cpp releases](https://github.com/ggml-org/llama.cpp/releases)
- [License notices](../THIRD_PARTY_LICENSES.md#embedded-ai-runtime-and-models)

The configured directory is the root, not a family folder. OsWL scans `model/` and one family subfolder. Legacy root-level GGUF and root/bin runtime locations remain supported. Use unique filenames: the managed tree wins over same-named legacy weights. Projectors (`mmproj*`), draft weights (`mtp-*`), importance matrices and files resolving outside the root are excluded.

Existing models on other installations are not automatically deleted or replaced. Move retired files outside the discovered folders. If a saved model disappears, the UI returns to the installed default preference.

Local smoke checks with the project's localized prompts produced English, Korean and Japanese answers from both models. Qwen also changed a supplied fix version and misreported counts in some samples. These checks establish runtime compatibility, not factual accuracy: verify AI recommendations against the original scan findings. The default is a resource choice, not a quality ranking.

## Starting and Stopping

1. Put the executable and libraries in `llama/`.
2. If no chat model exists, startup prefetch downloads **only Qwen3.5 2B** into `model/Qwen/`. Start also triggers download if necessary. Disable prefetch with `OSWL_EMBEDDED_AUTO_DOWNLOAD=false`.
3. Gemma is manual: download the pinned file above, verify both SHA-256 and byte count, then place it in `model/Gemma/`.
4. Open **Settings → AI → Provider → Embedded AI**. The dropdown lists installed files with model names and sizing guidance.
5. To switch: **Stop → select model → Save → Start**. Check Active; if a candidate fails, a fallback can start and the UI indicates this.
6. Under **Scope / Context**, choose English, Korean or Japanese and save. Embedded ownership and endpoint are preserved. Existing summaries only change after regeneration; language selection does not translate stored results.

Existing AI-settings permissions apply. Start registers the actual model as LOCAL and deactivates another active provider. Stop deactivates LOCAL. Selecting another provider does not stop the embedded process; stop it separately to release memory.

## Switching Models

The default preference is Qwen3.5-2B Q4_K_M, then other Qwen3 weights, then Gemma 4 E2B, then remaining compatible GGUF files. Explicitly selected and valid saved models take priority. Stop before switching. Additional compatible chat models can be installed manually, subject to their own requirements and licenses.

## Changing the Model Folder

Save the root containing `llama/` and `model/`. A changed root stops the managed running server. Moving files while a server is running can fail due to file locks; stop first. On Linux, the executable also needs execute permission.

## CPU defaults

| Environment variable | Default |
|---|---|
| `OSWL_EMBEDDED_AI_DIR` | `embedded-ai` |
| `OSWL_EMBEDDED_AI_PORT` | `11435` |
| `OSWL_EMBEDDED_AI_GPU_LAYERS` | `0` |
| `OSWL_EMBEDDED_AI_THREADS` | `1` |
| `OSWL_EMBEDDED_AI_PARALLEL` | `1` |
| `OSWL_EMBEDDED_AI_CONTEXT` | `8192` |
| `OSWL_EMBEDDED_AI_FLASH_ATTN` | `true` |
| `OSWL_EMBEDDED_AI_CACHE_REUSE` | `256` |
| `OSWL_EMBEDDED_AI_STARTUP_TIMEOUT_SEC` | `120` |
| `OSWL_EMBEDDED_AI_EXTRA_ARGS` | empty |
| `OSWL_EMBEDDED_AUTO_DOWNLOAD` | `true` |

CPU-only is explicit (`-ngl 0`), as is one generation slot. Non-thinking uses `LLAMA_ARG_CHAT_TEMPLATE_KWARGS={"enable_thinking":false}` plus a zero reasoning budget; budget alone can leave new models with empty answers. Consider two threads on a 4-vCPU server only after checking web/scan latency. Threads are not a CPU quota; apply OS/container limits if required.

The context is total capacity divided across slots. `OSWL_AI_MAX_PARALLEL_CALLS` remains 3 and limits tasks **within each scan**, not globally. With one server slot, requests may queue past the current 90-second non-streaming read timeout. Small hosts should set this to 1 and avoid overlapping scans. It is not a global admission queue.

## Integrity, offline use and GitHub distribution

The default URL is pinned to the upstream revision above. Old OsWL GitHub `models-v1` contains Qwen3 1.7B and must not be reused for the new default. This change does not publish or assume a new GitHub model asset.

`OSWL_EMBEDDED_DEFAULT_MODEL_URL`, `OSWL_EMBEDDED_DEFAULT_MODEL_SHA256` and `OSWL_EMBEDDED_DEFAULT_MODEL_SIZE_BYTES` must describe the same file. Both hash and size are checked before installing a partial download. `OSWL_EMBEDDED_FALLBACK_MODEL_URL` defaults to empty; configure only a verified byte-identical mirror. A mirror is installed under the default Qwen filename; different models belong under their real filenames.

Air-gapped mode never downloads models: supply the matching runtime, verified weights and license notices offline. Docker does not bundle them: mount this root and supply a matching **Linux** runtime, with write access if downloads are enabled. Windows executables do not run inside Linux containers.

`embedded-ai/` is excluded from Git and Docker context; weights/runtime are not in the published JAR. Qwen3.5 and Gemma 4 are Apache 2.0; llama.cpp is MIT. Preserve full licenses, attribution, upstream notices when supplied, and the Unsloth GGUF Q4_K_M quantization notice. Runtime redistribution also needs the notices of its companion libraries.

## Troubleshooting

- Missing runtime: check llama/, OS/architecture, libraries and execute permission.
- Empty answers: check compatible runtime and explicit non-thinking template parameters.
- Start failure: inspect llama-server.log for model support or RAM exhaustion.
- Wrong active model: stop, save the installed selection, start and check fallback status.
- Timeout under load: reduce concurrent scans/calls and inspect CPU credits.
- Download mismatch: check URL, size and SHA-256 as one set.
