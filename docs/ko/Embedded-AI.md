# 내장 AI (Embedded AI)

내장 AI는 별도의 클라우드 계정이나 API 키 없이 OsWL이 로컬 LLM을 바로 사용할 수 있게 해주는 기능입니다. **데이터는 절대 기기 밖으로 나가지 않습니다.** 함께 제공되는 [llama.cpp](https://github.com/ggml-org/llama.cpp) `llama-server`를 사이드카 프로세스로 실행해 **OpenAI 호환** 엔드포인트(`http://127.0.0.1:<port>/v1`)를 띄우고, 이를 **LOCAL** AI 프로바이더로 등록합니다. CVE 트리아지 요약과 라이선스 인사이트가 모두 이 컴퓨터 안에서만 처리됩니다.

---

## 동작 방식

* OsWL이 **모델 디렉터리**(기본값 `./embedded-ai`)에서 `llama-server`를 실행하고 `/health`에 응답할 때까지 기다립니다. `.gguf` 파일이 아직 없다면 부팅 직후 백그라운드에서 기본 Qwen3 모델 다운로드가 자동으로 시작됩니다(최초 1회, 약 1.2GB) — 그래서 Settings에서 **시작**을 누를 때는 이미 다 받아져 있거나 다운로드가 진행 중인 경우가 많습니다. `oswl.ai.embedded.auto-download-on-boot=false`로 설정하면 예전처럼 시작 버튼을 눌러야만 다운로드가 시작됩니다. **폐쇄망**(`oswl.airgapped.enabled=true`) 환경에서는 이 백그라운드 다운로드가 절대 실행되지 않으므로 미리 직접 `.gguf` 파일을 넣어두세요([요구 사항 및 디렉터리 구조](#요구-사항-및-디렉터리-구조) 참고).
* 서버는 **localhost(`127.0.0.1`)에만 바인딩**되므로 다른 기기에서 접근할 수 없습니다.
* 시작에 성공하면 OsWL이 이 엔드포인트를 **LOCAL** 프로바이더로 저장하고 활성화합니다(활성 프로바이더는 하나뿐이므로 기존 프로바이더는 비활성화됩니다).
* 내장 AI를 중지하면 LOCAL 프로바이더도 함께 비활성화되어, 죽은 엔드포인트로 AI 호출이 나가는 일이 없습니다.
* 서버는 reasoning 비활성화(`--reasoning-budget 0`), 웹 UI 비활성화(`--no-webui`) 옵션으로 실행됩니다. 기본적으로 GPU 레이어 오프로드를 시도하며(`-ngl 999`), GPU 시작이 실패하면(GPU 빌드 부재, VRAM 부족 등) 같은 모델을 CPU 전용으로 1회 자동 재시도한 뒤에야 다음 후보로 넘어갑니다.
* 병렬 AI 콜을 지원합니다. 서버는 기본적으로 `--parallel 4 --cont-batching`, 플래시 어텐션(`-fa`), 프리픽스 캐시 재사용(`--cache-reuse 256`) 옵션으로 시작하고, OsWL 자체적으로 독립적인 AI 콜을 병렬로 실행합니다(최대 `oswl.ai.enrichment.max-parallel-calls`, 기본 `3`).
* LOCAL 프로바이더로 복사되는 배치 프롬프트는 더 단순한 JSON 스키마 변형(`oswl.ai.enrichment.local-simple-schema`, 기본 `true`)을 사용합니다. 소형 로컬 모델이 클라우드 대형 모델용 전체 스키마보다 단순한 출력 스키마를 훨씬 잘 따릅니다.
* CVE/라이선스 요약은 입력 컨텍스트 해시를 기준으로 캐싱되며, 변경되지 않은 항목은 다음 스캔에서 다시 AI를 호출하지 않습니다.
* 보안 자세(posture), 보안 트렌드, 라이선스 트렌드, 버전 차이 인사이트는 기존 4개의 개별 호출을 `insights.combined` 하나로 합쳐 고정 오버헤드를 줄입니다.

> 필요 권한: 다른 AI 설정과 동일하게 `SETTINGS_AI_MANAGE` 또는 시스템 관리자.

---

## 요구 사항 및 디렉터리 구조

모델 디렉터리에는 서버 바이너리가 있어야 하며, `.gguf` 모델이 없으면 **시작** 버튼을 처음
누를 때(또는 부팅 시 백그라운드로) 자동으로 받아옵니다:

```
embedded-ai/
  llama-server(.exe)            — llama.cpp 서버 바이너리 (직접 준비)
  qwen3-1.7b-q4_k_m.gguf        — 기본 모델 (Apache 2.0) — 최초 시작 시 자동 다운로드
```

디렉터리가 비어 있으면 Qwen3-1.7B(~1.2GB)를 바로 그 위치로 다운로드합니다 — 부팅 직후 자동으로
시작되거나, 아직 안 끝났다면 **시작**을 누르는 즉시 — SHA256 체크섬을 검증한 뒤에야 사이드카를
실행합니다. 카드에 실시간 다운로드 진행률이 표시되며, `java -jar app.jar`만으로 전 과정이
끝납니다. 별도 스크립트나 빌드 단계가 필요 없습니다. 이렇게 할 수 있는 이유는 Qwen3가 Apache 2.0
라이선스이기 때문입니다([THIRD_PARTY_LICENSES.md](../../THIRD_PARTY_LICENSES.md#qwen3-17b-gguf)
참고) — 사용자를 대신해 받아오는 데 별도 재배포 의무가 없습니다. 모델은 기본적으로 OsWL 자체
[GitHub Release 자산](https://github.com/SalkCoding/Oswl/releases/tag/models-v1)에서
다운로드됩니다(업스트림 파일과 바이트 단위로 동일한 사본) — 사내망에서 `huggingface.co`는 막혀
있고 `github.com`은 허용되는 환경이 흔하기 때문입니다. 이 자산에 접근할 수 없으면 업스트림
[Hugging Face 저장소](https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF)로 1회 재시도합니다.
다른 미러를 쓰려면 `OSWL_EMBEDDED_DEFAULT_MODEL_URL`로 1차 소스를 재정의하세요.

기본 모델 URL, SHA256, 크기 설정은 한 세트로 묶인 값입니다. URL만 바꾸고 SHA256/크기를 맞추지
않으면 모든 다운로드가 체크섬 검증 실패로 거부됩니다.

다른 `.gguf` 모델도 직접 넣어 쓸 수 있습니다 — OsWL은 기본 Qwen3뿐만 아니라 이 디렉터리에 직접
넣은 모든 `.gguf` 파일을 인식합니다. 재배포하거나 공유하기 전에 해당 모델 자체의 라이선스를
먼저 확인하세요 — OsWL이 번들/자동 다운로드하는 것은 Qwen3뿐입니다.

| 항목 | OsWL이 찾는 위치 |
|---|---|
| 서버 바이너리 | `<dir>/llama-server(.exe)` → `<dir>/bin/` → 시스템 `PATH` 순 ([llama.cpp 릴리스](https://github.com/ggml-org/llama.cpp/releases)) — **자동 다운로드 안 됨**, 직접 준비 |
| 모델 | 디렉터리 바로 아래의 모든 `.gguf` 파일 |

설정 기본값(UI에서 저장한 폴더가 `dir`보다 우선):

| 설정 키 | 환경 변수 | 기본값 | 설명 |
|---|---|---|---|
| `oswl.ai.embedded.dir` | `OSWL_EMBEDDED_AI_DIR` | `embedded-ai` | 모델 디렉터리 (작업 디렉터리 기준 상대 경로) |
| `oswl.ai.embedded.port` | `OSWL_EMBEDDED_AI_PORT` | `11435` | 사이드카가 사용할 localhost 포트 |
| `oswl.ai.embedded.context-size` | `OSWL_EMBEDDED_AI_CONTEXT` | `8192` | `llama-server -c`에 전달되는 전체 컨텍스트 크기. 병렬 슬롯 수로 나눠 각 슬롯에 할당됩니다 |
| `oswl.ai.embedded.gpu-layers` | `OSWL_EMBEDDED_AI_GPU_LAYERS` | `-1` | GPU 레이어 오프로드(`-ngl`): `-1`은 빌드가 지원하는 한 최대한, `0`은 CPU 전용, 양수는 명시적 레이어 수. GPU 시작 실패 시 CPU 전용으로 자동 재시도 |
| `oswl.ai.embedded.threads` | `OSWL_EMBEDDED_AI_THREADS` | `0` | 스레드 수(`-t`); `0`은 llama.cpp 자동 감지 |
| `oswl.ai.embedded.parallel-slots` | `OSWL_EMBEDDED_AI_PARALLEL` | `4` | `>1`이면 `--parallel N --cont-batching`을 추가해 서버에서 AI 콜이 직렬화되지 않게 합니다. 각 슬롯은 `context-size / N`을 받으며, 2048 미만이면 경고 로그가 남습니다 |
| `oswl.ai.embedded.flash-attn` | `OSWL_EMBEDDED_AI_FLASH_ATTN` | `true` | 플래시 어텐션(`-fa`) |
| `oswl.ai.embedded.cache-reuse` | `OSWL_EMBEDDED_AI_CACHE_REUSE` | `256` | 프리픽스 캐시 재사용(`--cache-reuse N`); `<=0`이면 비활성화 |
| `oswl.ai.embedded.extra-args` | `OSWL_EMBEDDED_AI_EXTRA_ARGS` | (비어 있음) | 추가 llama-server CLI 인자를 공백으로 구분해 그대로 덧붙임 — 서버 설정 전용이며 요청 입력에서 받지 않음 |
| `oswl.ai.embedded.startup-timeout-seconds` | `OSWL_EMBEDDED_AI_STARTUP_TIMEOUT_SEC` | `120` | 모델 후보 한 개가 healthy 상태가 될 때까지 기다리는 최대 시간(초) |
| `oswl.ai.embedded.default-model-url` | `OSWL_EMBEDDED_DEFAULT_MODEL_URL` | OsWL GitHub Release `models-v1` 자산 | 기본 Qwen3 모델의 1차 다운로드 소스 |
| `oswl.ai.embedded.default-model-sha256` | `OSWL_EMBEDDED_DEFAULT_MODEL_SHA256` | (THIRD_PARTY_LICENSES.md 참고) | 기대 SHA256 — URL과 항상 함께 변경 |
| `oswl.ai.embedded.default-model-size-bytes` | `OSWL_EMBEDDED_DEFAULT_MODEL_SIZE_BYTES` | `1282439264` | 기대 파일 크기(바이트). 진행률 바를 미리 채우는 데 사용되며, URL/SHA256과 같은 세트입니다 |
| `oswl.ai.embedded.fallback-model-url` | `OSWL_EMBEDDED_FALLBACK_MODEL_URL` | 업스트림 Hugging Face `ggml-org/Qwen3-1.7B-GGUF` 자산 | 1차 URL 실패 시 1회 재시도. 1차와 바이트 단위로 동일해야 함(같은 SHA256) |
| `oswl.ai.embedded.auto-download-on-boot` | `OSWL_EMBEDDED_AUTO_DOWNLOAD` | `true` | 부팅 시 백그라운드로 기본 모델을 미리 받음; `oswl.airgapped.enabled=true`면 절대 실행 안 함 |

---

## 시작 및 중지

**설정 → AI**의 **내장 AI (기본 제공 로컬 모델)** 카드에서 관리합니다:

1. 상태 표시(**실행 중** / **중지됨**)와 바이너리 누락 경고를 확인합니다.
2. **시작**을 클릭합니다. 디렉터리에 모델이 없으면 Qwen3 다운로드 진행률(~1.2GB, 연결 속도에
   따라 몇 분 소요)이 먼저 표시된 뒤 서버가 실행됩니다. 모델이 이미 있으면 로딩만 최대 2분
   정도 걸릴 수 있습니다(GPU 로딩 시 `startup-timeout-seconds`까지 허용).
3. 실행되면 카드에 **실행 모델**이 표시되고, 엔드포인트가 LOCAL 프로바이더로 동작합니다.
4. **중지**를 클릭하면 사이드카가 종료됩니다(다운로드된 모델 파일은 삭제되지 않으므로 다음
   시작 시 바로 재사용됩니다).

카드에는 현재 사용 중인 폴더, 감지된 `.gguf` 파일 목록, 마지막 시작 오류(있으면 빨간색)도 함께 표시됩니다.

---

## 모델 교체

기본 Qwen3 외에도 llama.cpp와 호환되는 `.gguf`라면 무엇이든 사용할 수 있습니다:

1. 양자화된 GGUF 모델을 **다운로드**합니다 (예: [Hugging Face](https://huggingface.co/models?library=gguf)).
2. `.gguf` 파일을 카드에 표시된 모델 폴더에 **넣습니다**.
3. **모델** 드롭다운에서 파일을 **선택**하고(목록은 폴더 기준으로 갱신됨) **저장**을 클릭합니다.
4. **시작**을 클릭하면 선택한 모델이 가장 먼저 시도됩니다. 모델 선택은 다음 시작부터 적용되며, 실행 중에는 드롭다운이 비활성화되어 바꿀 수 없습니다.

**자동 (기본 순서)** 옵션은 저장된 모델 → `qwen3…` → 나머지 `.gguf` 중 첫 번째 파일(가나다순) 순으로 시도합니다.

> CPU 전용 추론에는 **1B–4B 파라미터** 규모의 작은 양자화 모델(Q4_K_M 등)을 권장합니다. 더 큰 모델은 RAM을 많이 쓰고 느린 기기에서는 시작 제한 시간을 넘길 수 있습니다. 컨텍스트 크기는 `OSWL_EMBEDDED_AI_CONTEXT`(기본 `8192`)이며 병렬 슬롯 수로 나뉩니다 — 기본 4 슬롯이면 호출당 2048 토큰이므로, 호출당 더 긴 컨텍스트가 필요하면 컨텍스트를 늘리거나 `OSWL_EMBEDDED_AI_PARALLEL`을 낮추세요.

---

## 모델 폴더 변경

OsWL이 다른 디렉터리를 바라보게 하는 방법은 두 가지입니다:

| 방법 | 적용 범위 |
|---|---|
| 내장 AI 카드의 **폴더** 입력 + **저장** | DB에 저장(`ai_preferences.embedded_dir` / `embedded_model`); 기본값보다 우선 |
| `OSWL_EMBEDDED_AI_DIR` 환경 변수 / `oswl.ai.embedded.dir` yaml | UI에 저장된 폴더가 없을 때 사용되는 기본값 |

`PUT /api/settings/ai/embedded/config`에 적용되는 규칙:

* 폴더는 **미리 존재**해야 합니다 — 없으면 저장이 거부됩니다(`400`, "폴더가 없거나 디렉터리가 아닙니다").
* 사이드카 **실행 중에 폴더를 바꾸면** 먼저 사이드카가 중지되고(실행 중인 `llama-server`가 기존 디렉터리의 파일 잠금을 잡고 있기 때문) LOCAL 프로바이더가 비활성화됩니다. 새 폴더에서 다시 시작하세요.
* 폴더 입력을 비우면(공백) 오버라이드가 제거되어 설정 기본값으로 돌아갑니다.

---

## 자동 폴백

시작 버튼 한 번으로 여러 모델을 순서대로 시도할 수 있습니다. 요청/저장된 모델 → 내장 선호 순서 → 나머지 `.gguf` 순으로 후보를 시도하며, 각 후보는 최대 **120초** 안에 healthy 상태가 되어야 합니다. GPU 오프로드가 켜진 상태(기본값)에서 후보의 GPU 시작이 실패하면, OsWL은 먼저 같은 모델을 CPU 전용으로 1회 재시도한 뒤 다음 후보로 넘어갑니다. 크래시나 시간 초과된 모델도 건너뛰고 다음으로 넘어갑니다.

첫 번째 후보가 아닌 모델로 실행된 경우, 카드의 모델 선택 옆에 주황색 **"대체 모델로 실행 중"** 배지가 표시됩니다. 보통 선호 모델 로딩에 실패했다는 뜻이며(모델이 너무 크거나 다운로드가 깨진 경우), **실행 모델** 항목에서 실제로 돌고 있는 모델을 확인할 수 있습니다.

---

## 상태 및 로그

`GET /api/settings/ai/embedded`는 `running`, `external`, `binaryFound`, `activeModel`, `fallbackUsed`, `lastError`, `availableModels`, `modelsDir`, `baseUrl`을 반환하며, 시작 버튼으로 트리거된 기본 모델 다운로드가 진행 중일 때는 `downloading`, `downloadedBytes`, `downloadTotalBytes`도 함께 반환합니다.

`external`은 설정된 포트에서 OsWL이 직접 시작하지 않은 무언가(수동으로 띄운 `llama-server`, 또는 이전 OsWL 프로세스·크래시로 남겨진 고아 프로세스)가 이미 `/health`에 응답 중일 때 `true`가 됩니다. 이 경우에도 `running`은 `true`로 유지됩니다 — 엔드포인트 자체는 LOCAL 프로바이더로 정상 사용 가능하기 때문입니다 — 하지만 **중지** 버튼을 눌러도 OsWL이 소유하지 않은 프로세스는 종료할 수 없어 계속 실행 상태로 남고 상태 응답도 `external: true`를 유지합니다.

`POST /api/settings/ai/embedded/start`는 Qwen3 다운로드를 기다리는 대신 트리거만 하고 즉시 응답합니다(`success: true`, `downloading: true`) — 설정 페이지가 상태를 폴링하며 진행률과 최종 `running`/`lastError` 결과를 표시합니다. 다운로드 자체는 브라우저 세션과 무관하게 서버에서 진행되므로, 페이지를 새로고침하거나 닫아도 취소되지 않고 다시 열으면 진행 상황이 이어서 표시됩니다.

`llama-server`의 stdout/stderr는 **`<dir>/llama-server.log`**에 기록됩니다. 시작에 실패하면 이 로그의 마지막 몇 줄이 `lastError`에 포함되어 카드에 빨간색으로 표시되며, 자세한 내용은 파일 전체를 확인하세요.

---

## 문제 해결

| 증상 | 원인 및 해결 |
|---|---|
| "llama-server 바이너리를 찾을 수 없습니다" | 카드에 표시된 폴더(또는 그 `bin/` 하위, `PATH`)에 `llama-server(.exe)`를 넣으세요 — 이건 자동으로 받아지지 않습니다 |
| 모델이 없는데 시작을 눌러도 반응이 없어 보임 | 인터넷 연결을 확인하세요 — 기본 모델 다운로드에 최초 1회 인터넷이 필요합니다. 폐쇄망 환경이면 `.gguf` 파일을 직접 모델 폴더에 넣으세요 |
| "모델 다운로드 실패" / 체크섬 불일치 | 다운로드 도중 네트워크가 끊기거나 손상됐습니다 — 손상된 파일은 자동 삭제되니 **시작**을 다시 누르면 재시도됩니다 |
| 로그에 `failed to open GGUF file` | 설정의 폴더와 모델이 실제 있는 위치가 다릅니다 — **폴더** 입력값과 파일 이름이 드롭다운 항목과 일치하는지 확인 |
| "did not become healthy within 120s" | 기기가 느리거나 모델이 너무 큽니다 — 더 작은 양자화 모델(예: 1B~2B급 Q4_K_M `.gguf`)로 시도하거나, `OSWL_EMBEDDED_AI_STARTUP_TIMEOUT_SEC`을 늘리세요. `(GPU)` 실패는 CPU 전용으로 자동 재시도되므로, 이 메시지가 지속되면 CPU 재시도도 실패한 것입니다 |
| 로그에 `slotContext … is below 2048` 경고 | 병렬 슬롯이 컨텍스트 크기에 비해 너무 많습니다 — `OSWL_EMBEDDED_AI_CONTEXT`를 늘리거나 `OSWL_EMBEDDED_AI_PARALLEL`을 낮추세요 |
| 포트가 이미 사용 중 | 다른 프로세스(또는 수동으로 띄운 `llama-server`)가 포트를 점유 중입니다 — 종료하거나 `OSWL_EMBEDDED_AI_PORT`를 변경하세요. 해당 포트에서 이미 healthy한 서버가 응답하면 "실행 중"으로 간주되며 상태 응답에 `external`로 표시됩니다 |
| **중지**를 눌러도 카드가 꺼지지 않음 | 실행 중인 서버가 `external`(이 OsWL 인스턴스가 시작하지 않음) 상태입니다 — 해당 프로세스를 직접 종료하세요(또는 실행 중인 머신/컨테이너를 재시작). OsWL은 이 프로세스를 종료할 수 없습니다 |
| 저장 시 "폴더가 없거나 디렉터리가 아닙니다" | 디렉터리를 먼저 만드세요 — 저장은 존재하는 폴더만 허용됩니다 |

---

## 보안 및 프라이버시

* 사이드카는 `127.0.0.1`에만 바인딩되고 **API 키가 필요 없습니다** — 네트워크에 아무것도 노출되지 않습니다.
* `llama-server.log`에 보이는 llama.cpp의 **CORS 경고**는 localhost만 수신하기 때문에 예상된 것이며 무해합니다.
* 텔레메트리가 없습니다. 모델로 전송되는 프롬프트, 코드 조각, CVE 데이터는 기기를 벗어나지 않습니다.

---

## REST API 요약

[API 레퍼런스 — AI](API-Reference.md#ai) 참고. 인터랙티브 스키마는 Swagger UI(`local` 프로파일)에서 확인할 수 있습니다.
