# 내장 AI

OsWL은 llama.cpp를 `http://127.0.0.1:11435/v1`에서 실행해 LOCAL 제공자로 등록합니다. 추론 데이터는 서버 안에서 처리되며, 모델 설치에는 인터넷 또는 파일의 오프라인 전달이 필요합니다. **CPU 기본 모델은 Qwen3.5-2B Q4_K_M, 선택 모델은 Gemma 4 E2B Q4_K_M입니다.** 한 번에 하나만 실행합니다. 텍스트 전용이므로 GPU와 이미지·오디오 프로젝터는 필요하지 않습니다.

## 사양과 폴더 구조

| 모델 | 가중치 다운로드 | 서버 전체 예상 최소 | 권장 |
|---|---:|---|---|
| Qwen3.5 2B Q4_K_M | 1.28 GB | 2 vCPU / RAM 8 GB | 4 vCPU / RAM 8~16 GB |
| Gemma 4 E2B Q4_K_M | 3.11 GB | 4 vCPU / RAM 8 GB | 4 vCPU / RAM 16 GB |

이는 **성능 보증이 아닌 용량 산정안**입니다. OsWL·PostgreSQL 동시 실행, 4K~8K 컨텍스트, 소규모 스캔과 AI 생성 1건씩의 간헐적 처리가 기준입니다. 대형 압축파일·동시 스캔·DB 증가에는 추가 자원이 필요합니다. 파일 크기는 최대 메모리가 아닙니다. 두 모델에 여유 디스크 6 GB 이상을 확보하고 실행 파일·DB·로그·기존 모델은 별도로 계산하세요. 빌드 사양은 포함하지 않습니다.

Qwen을 기본으로 고른 이유는 CPU 공유 서버에 맞는 작은 가중치 크기입니다. 항상 더 정확하다는 뜻은 아닙니다. 실제 한국어·영어·일본어 입력으로 품질을 비교하세요. 버스터블의 vCPU 수는 지속 성능을 보장하지 않으므로 CPU 크레딧 소진 상태에서도 확인해야 합니다. 연속 분석은 고정 성능 CPU나 별도 CPU 워커를 고려하되 GPU를 필수로 요구하지 않습니다.

서버 OS·아키텍처에 맞는 CPU 실행 파일과 같은 배포본의 라이브러리를 사용합니다. 로컬 검증 기준은 llama.cpp **b10068 (571d0d540)**입니다. 두 모델 구조와 `--chat-template-kwargs`, `-fa on`을 지원하는 호환 버전이 필요합니다.

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
- [License notices](../../THIRD_PARTY_LICENSES.md#embedded-ai-runtime-and-models)

설정 폴더는 모델 계열 폴더가 아닌 최상위 경로입니다. `model/`과 바로 아래 계열 폴더를 탐색합니다. 기존 최상위 GGUF와 최상위/bin 실행 파일도 인식합니다. 같은 파일 이름이면 새 model 폴더가 우선합니다. `mmproj*`, `mtp-*`, `imatrix*`와 최상위 경로 밖을 가리키는 파일은 선택에서 제외합니다.

다른 설치의 기존 모델은 자동 삭제·교체하지 않습니다. 목록에서 빼려면 탐색 폴더 밖으로 옮기세요. 저장한 모델이 없어졌다면 설치된 기본 우선순위로 돌아갑니다.

프로젝트의 다국어 프롬프트로 두 모델의 영어·한국어·일본어 응답을 확인했습니다. 다만 일부 Qwen 응답은 입력한 수정 버전이나 건수를 잘못 생성했습니다. 이 확인은 실행 호환성 검증이며 정확도 보장이 아닙니다. AI 권고는 원본 스캔 결과와 대조해야 하며, 기본 모델 선정은 자원 기준이지 품질 순위가 아닙니다.

## 설치·모델·언어 선택

1. 실행 파일과 라이브러리를 `llama/`에 넣습니다.
2. 모델이 하나도 없으면 부팅 시 **Qwen3.5 2B만** `model/Qwen/`에 미리 다운로드합니다. 시작 버튼도 필요 시 다운로드합니다. 사전 다운로드는 `OSWL_EMBEDDED_AUTO_DOWNLOAD=false`로 끕니다.
3. Gemma는 위 고정 리비전의 파일을 직접 받아 SHA-256·바이트 수를 검증한 뒤 `model/Gemma/`에 넣습니다.
4. **설정 → AI → 제공자 → 내장 AI**에서 설치된 모델과 사양 안내를 확인합니다.
5. **중지 → 모델 선택 → 저장 → 시작** 순서로 변경합니다. 시작 실패 시 대체 모델이 실행될 수 있으므로 실행 중인 모델과 대체 표시를 확인하세요.
6. **분석 범위/컨텍스트**에서 한국어·영어·일본어를 선택해 저장합니다. 내장 AI 식별과 주소는 유지됩니다. 저장된 기존 요약은 재생성 전까지 번역되지 않습니다.

기존 AI 설정 관리 권한을 사용합니다. 시작하면 실제 모델이 LOCAL로 등록되고 다른 활성 제공자는 비활성화됩니다. 중지는 LOCAL도 비활성화합니다. 다른 제공자 선택만으로 내장 프로세스가 중지되지는 않으므로 메모리를 반환하려면 따로 중지하세요.

기본 우선순위는 Qwen3.5-2B Q4_K_M → 다른 Qwen3 → Gemma 4 E2B → 나머지 호환 GGUF입니다. 명시적 선택과 유효한 저장값이 더 우선합니다. 다른 호환 모델을 추가할 때는 해당 모델의 사양과 라이선스를 확인하세요.

폴더를 바꿀 때는 llama/와 model/을 담는 최상위 경로를 저장합니다. 경로 변경 시 관리 중인 서버는 중지됩니다. 파일 이동도 잠금을 피하기 위해 중지 후 수행하세요. Linux 실행 파일에는 실행 권한이 필요합니다.

## CPU 기본값

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

CPU 전용 `-ngl 0`과 생성 슬롯 1개를 명시합니다. `LLAMA_ARG_CHAT_TEMPLATE_KWARGS={"enable_thinking":false}`와 reasoning budget 0을 함께 사용합니다. 예산만 0으로 설정하면 새 모델은 빈 답변을 반환할 수 있습니다. 4 vCPU에서는 웹·스캔 지연 확인 후 스레드 2개를 검토하세요. 스레드 수는 CPU 사용률 상한이 아니므로 필요하면 OS·컨테이너 제한을 적용합니다.

전체 컨텍스트는 슬롯 간 분할됩니다. `OSWL_AI_MAX_PARALLEL_CALLS` 기본값 3은 **스캔별** 제한입니다. 서버는 한 슬롯으로 생성해도 대기 요청이 비스트리밍 읽기 제한 90초를 초과할 수 있습니다. 소형 서버는 이 값을 1로 설정하고 스캔 중첩을 피하세요. 서버 전체 작업 접수 제한 기능은 아닙니다.

## 무결성·폐쇄망·GitHub 배포

기본 다운로드는 위 업스트림 고정 리비전을 사용합니다. 기존 GitHub `models-v1` 자산은 Qwen3 1.7B이므로 새 기본 모델 주소로 재사용하면 안 됩니다. 이 변경으로 새 GitHub 모델 자산을 게시하거나 존재한다고 가정하지 않습니다.

`OSWL_EMBEDDED_DEFAULT_MODEL_URL`, `OSWL_EMBEDDED_DEFAULT_MODEL_SHA256`, `OSWL_EMBEDDED_DEFAULT_MODEL_SIZE_BYTES`는 같은 파일을 가리켜야 합니다. 부분 다운로드의 해시와 크기를 모두 검증한 뒤 설치합니다. `OSWL_EMBEDDED_FALLBACK_MODEL_URL`은 기본적으로 비어 있으며 바이트 단위로 동일한 미러만 지정하세요. 미러도 기본 Qwen 이름으로 저장하므로 다른 모델은 실제 이름으로 직접 설치합니다.

폐쇄망에서는 다운로드하지 않습니다. 실행 파일·검증한 가중치·라이선스 고지를 함께 전달하세요. Docker에도 포함되지 않으므로 최상위 폴더를 마운트하고 **Linux용** 실행 파일을 준비합니다. 자동 다운로드에는 쓰기 권한이 필요합니다. Windows 실행 파일은 Linux 컨테이너에서 쓸 수 없습니다.

`embedded-ai/`는 Git·Docker 빌드 컨텍스트에서 제외되며 배포 JAR에 모델·실행 파일이 포함되지 않습니다. Qwen3.5·Gemma 4는 Apache 2.0, llama.cpp는 MIT입니다. 라이선스 전문·출처·제공되는 업스트림 고지·Unsloth GGUF Q4_K_M 양자화 사실을 유지하세요. 실행 파일 재배포 시 동봉 라이브러리의 고지도 보존해야 합니다.

## 문제 해결

- 실행 파일 없음: llama/ 위치·OS·아키텍처·라이브러리·실행 권한을 확인하세요.
- 빈 답변: 호환 버전과 명시적인 비추론 템플릿 옵션을 확인하세요.
- 시작 실패: llama-server.log에서 모델 지원·RAM 부족을 확인하세요.
- 다른 모델 실행: 중지·선택·저장·시작 후 대체 표시를 확인하세요.
- 시간 초과: 동시 스캔·호출 수와 CPU 크레딧을 확인하세요.
- 다운로드 실패: URL·크기·SHA-256을 함께 확인하세요.
