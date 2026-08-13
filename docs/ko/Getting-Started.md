# 시작하기

이 가이드는 OsWL 설치, 설정 마법사 실행, 그리고 첫 번째 프로젝트 스캔 완료까지 안내합니다.

---

## 시스템 요구사항

| 구성 요소 | 요구사항 |
|---|---|
| **JDK** | 25 이상 |
| **빌드 도구** | Gradle Wrapper (포함 — `./gradlew`) |
| **데이터베이스** | H2 파일 모드 (로컬/개발) 또는 PostgreSQL 15 이상 (운영) |
| **OS** | Linux, macOS, Windows |
| **메모리** | 최소 512MB, 1GB 이상 권장 |

> Node.js나 npm은 필요 없습니다 — Tailwind CSS 독립 실행형 바이너리가 첫 번째 빌드 시 Gradle에 의해 자동으로 다운로드됩니다.

---

## 설치

### 1. 저장소 클론

```bash
git clone https://github.com/SalkCoding/Oswl.git
cd Oswl
```

### 2. 프로파일 선택

OsWL은 두 가지 Spring 프로파일을 제공합니다:

| 프로파일 | 데이터베이스 | 용도 |
|---|---|---|
| `local` *(기본값)* | H2 파일 (`./oswl-db.mv.db`) | 개발 및 평가 |
| `prod` | PostgreSQL | 운영 배포 |

### 3. 애플리케이션 시작

**로컬 (H2, 설정 없음):**

```bash
./gradlew bootRun
```

**운영 (PostgreSQL):**

```bash
export SPRING_PROFILES_ACTIVE=prod
export DB_URL=jdbc:postgresql://localhost:5432/oswl
export DB_USERNAME=oswl
export DB_PASSWORD=changeme
export OSWL_ENCRYPTION_KEY=$(openssl rand -base64 32)

./gradlew bootRun
```

> **`OSWL_ENCRYPTION_KEY`** — VCS 등 저장 비밀 보호용. `local`에서는 개발용 키가 자동 생성될 수 있습니다. **`prod`에서는 기동 전 필수**이며, 없으면 애플리케이션이 시작되지 않습니다. 키 분실 시 기존 저장 자격증명을 사용할 수 없습니다.

애플리케이션은 기본적으로 포트 **8080**에서 시작됩니다.

---

## 임베디드 AI 모델 (최초 실행)

OsWL은 임베디드 llama.cpp 사이드카를 통해 AI 기능을 완전한 온프레미스로 실행할 수 있습니다. 최초 기동 시 `embedded-ai/`에 `.gguf` 모델이 없으면, OsWL이 기본 **Qwen3-1.7B** 모델(~1.2GB)을 백그라운드에서 자동으로 다운로드합니다:

* 업스트림 Hugging Face 저장소(`ggml-org/Qwen3-1.7B-GGUF`)에서 받으며 SHA-256 무결성을 검증합니다. 서드파티 호스트 의존을 피하려면 `OSWL_EMBEDDED_DEFAULT_MODEL_URL`을 자체 호스팅 미러로 지정하세요.
* 다운로드만 수행합니다 — 사이드카를 시작하거나 활성 AI 프로바이더를 변경하지 않습니다. 진행률은 **설정 → AI**에서 확인할 수 있으며, 파일이 준비되면 거기서 **시작**을 누르세요.
* `OSWL_EMBEDDED_AUTO_DOWNLOAD=false`로 끌 수 있습니다. 에어갭 모드에서는 다운로드를 시도하지 않습니다(아래 참조).

`llama-server(.exe)` 바이너리만 수동 단계입니다 — [llama.cpp releases](https://github.com/ggml-org/llama.cpp/releases)에서 받아 `embedded-ai/`(또는 `PATH`)에 두세요. 자세한 내용은 [임베디드 AI](Embedded-AI.md)를 참조하세요.

---

## 에어갭(오프라인) 환경에서 시작하기

아웃바운드 인터넷 접속이 없는 호스트의 경우:

1. 기동 전 `OSWL_AIRGAPPED_ENABLED=true`를 설정합니다. 취약점/위협 인텔 조회(OSV, deps.dev, EPSS, KEV)는 import된 오프라인 스냅샷에서 제공되며 아웃바운드 HTTP를 시도하지 않고, 임베디드 모델 자동 다운로드도 건너뜁니다.
2. 인터넷에 연결된 머신에서 스냅샷 번들을 빌드합니다:

   ```bash
   scripts/oswl-vdb/oswl-vdb.sh build --wanted wanted-list.jsonl --out bundle.zip
   ```

   (Windows: `scripts/oswl-vdb/oswl-vdb.ps1`.) 번들을 실제 의존성에 맞추려면, 먼저 연결된 OsWL 인스턴스에서 wanted-list를 내보내세요: `GET /api/admin/snapshot/wanted-list`.
3. `bundle.zip`을 에어갭 호스트로 옮겨 시스템 관리자 권한으로 `POST /api/admin/snapshot/import`(멀티파트 업로드)로 import하거나, `OSWL_AIRGAPPED_IMPORT_DIR`로 디렉터리를 허용 목록에 등록한 뒤 `POST /api/admin/snapshot/import-from-path`를 사용하세요. [관리 — 오프라인 스냅샷 번들](Administration.md)을 참조하세요.
4. 임베디드 AI를 쓰려면 직접 구한 `.gguf` 모델을 `embedded-ai/`에 넣은 뒤 **시작**을 누르세요.

---

## 설정 마법사

최초 실행 시(빈 데이터베이스), OsWL은 모든 요청을 `http://localhost:8080/setup`으로 리다이렉트합니다.

마법사에서 수집하는 정보:

| 필드 | 설명 |
|---|---|
| **관리자 이메일** | 시스템 관리자 계정의 로그인 자격증명으로 사용 |
| **비밀번호** | 최소 길이 정책을 충족해야 함 (기본값: 8자) |
| **표시 이름** | UI 및 감사 로그에 표시 |

제출 후 OsWL이 관리자 계정을 생성하고 로그인 페이지로 리다이렉트합니다.

> 로컬 모드에서 초기 상태로 재시작이 필요하면, 서버를 중지하고 `oswl-db.mv.db` (및 `oswl-db.trace.db`가 있으면 함께) 파일을 삭제한 후 재시작하세요.

---

## 첫 로그인

1. `http://localhost:8080/login`으로 이동합니다.
2. 설정 마법사에서 생성한 이메일과 비밀번호를 입력합니다.
3. **이중 인증(2FA)**이 활성화된 경우(관리자 설정 가능), 이메일로 발송된 6자리 OTP를 입력하라는 메시지가 표시됩니다.
   * `local` 모드에서는 OTP가 서버 로그에 표시됩니다: `*** OTP CODE: NNNNNN ***`
   * 개발용 단축키: 테스트 프로파일 사용 시 `000000`이 허용됩니다.
4. 임시 비밀번호로 첫 로그인 시, OsWL이 즉시 비밀번호 변경을 강제합니다.

---

## 테스트 데이터 시드 (로컬 전용)

로그인 후 다음을 호출하세요:

```
GET http://localhost:8080/data/test
```

이 엔드포인트(**`local` 프로파일에서만** 사용 가능):

* 기존의 모든 프로젝트, 스캔, 라이브러리, CVE를 삭제합니다.
* Maven과 npm 에코시스템의 여러 프로젝트, 다양한 심각도의 수십 개 CVE, 혼합된 라이선스 상태, 트렌드 시각화를 위한 다수의 히스토리 스캔이 포함된 풍부한 현실적 데이터셋으로 DB를 재구성합니다.

테스트 API 키도 다음에서 확인할 수 있습니다:

```
GET http://localhost:8080/data/test-api-key
```

---

## UI/접근성 테스트 하네스 (개발자 전용)

`./gradlew uiTest`는 실제 애플리케이션을 임의 포트로 기동한 뒤 Playwright로 헤드리스 Chromium을 구동해 화면을 조작하고, 방문하는 각 페이지에 axe-core 접근성 감사를 실행한다. curl 기반 스모크 테스트로는 원리적으로 확인할 수 없는 것들 — Alpine.js가 실제로 초기화되는지, 클릭이 기대한 DOM 갱신을 일으키는지, 실제 렌더된 페이지의 WCAG 명암비·랜드마크 위반 여부 — 를 잡아낸다. 브라우저를 내려받고(수백 MB, 최초 실행 후 `%LOCALAPPDATA%\ms-playwright` / `~/.cache/ms-playwright`에 캐시됨) 전체 Spring 컨텍스트를 기동해야 하므로 `./gradlew test`/`check`와 의도적으로 분리되어 있다 — 템플릿·JS·접근성 작업 시 명시적으로 실행할 것. 리포트는 `build/reports/axe/`에 남는다. 폐쇄망 빌드 환경에서는 브라우저 캐시를 미리 채워두고 `PLAYWRIGHT_BROWSERS_PATH` 환경 변수로 그 경로를 가리키게 하면 자동 다운로드에 의존하지 않을 수 있다.

---

## 접근 제어 (권장)

* [권한 레이어](Authorization-Layers.md) — 역할 템플릿 vs 프로젝트 멤버십
* [운영 배포 체크리스트](Production-Deployment-Checklist.md) — `prod`로 실서비스 전환 전에

## 다음 단계

* [첫 번째 VCS 저장소 연결](Quick-Import.md)
* [CLI로 스캔 제출](CLI-Integration.md)
* [보안 센터 탐색](Security-Center.md)
